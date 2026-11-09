package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.BindingRefused
import uk.me.cormack.lighting7.midi.ControlKind
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.ControlSurfaceRegistry
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.LegacyRefs
import uk.me.cormack.lighting7.midi.SurfaceGrammar
import uk.me.cormack.lighting7.midi.deriveStripBindings
import uk.me.cormack.lighting7.midi.inputFor
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.state.State

/**
 * Control-surface binding CRUD.
 *
 *   - `GET    /api/rest/projects/{projectId}/surface-bindings`
 *   - `POST   /api/rest/projects/{projectId}/surface-bindings`
 *   - `GET    /api/rest/projects/{projectId}/surface-bindings/{bindingId}`
 *   - `PATCH  /api/rest/projects/{projectId}/surface-bindings/{bindingId}`
 *   - `DELETE /api/rest/projects/{projectId}/surface-bindings/{bindingId}`
 *   - `POST   /api/rest/projects/{projectId}/surface-bindings/{bindingId}/expand`
 *
 * All mutations go through [ControlSurfaceBindingService], which keeps an in-memory cache
 * in sync with the DB and broadcasts [ControlSurfaceBindingService.BindingChange] events.
 *
 * A write carries a `binding`, or — from the screens session 3 replaces — a `target` in the old
 * union's words, which [SurfaceGrammar] translates with this project's ints; a row answers both
 * (`binding`, and `target` read back the same way). The bridge goes with those screens.
 */
internal fun Route.routeApiRestProjectSurfaceBindings(state: State) {
    val service = state.controlSurfaceBindingService

    get<ProjectSurfaceBindingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val refs = LegacyRefs.of(state.database, project.id.value)
            val list = service.list(project.id.value).map { it.toDto(refs) }
            call.respond(list)
        }
    }

    post<ProjectSurfaceBindingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreateSurfaceBindingRequest>()
            val refs = LegacyRefs.of(state.database, project.id.value)
            val written = when (val read = readBinding(request.binding, request.target, request.deviceTypeKey, request.controlId, refs)) {
                is BindingRead.Bad -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(read.message))
                    return@withProject
                }
                BindingRead.Absent -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("A binding is required"))
                    return@withProject
                }
                is BindingRead.Read -> read
            }

            val validationError =
                validateRequestShape(request.deviceTypeKey, request.controlId, request.bank, written.binding)
            if (validationError != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(validationError.message, code = validationError.code),
                )
                return@withProject
            }

            val takeoverPolicy = request.takeoverPolicy?.let { BindingTakeoverPolicy.parseOrNull(it) }
            if (request.takeoverPolicy != null && takeoverPolicy == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown takeoverPolicy: ${request.takeoverPolicy}"))
                return@withProject
            }

            try {
                val resolved = service.create(
                    projectId = project.id.value,
                    deviceTypeKey = request.deviceTypeKey,
                    controlId = request.controlId,
                    bank = request.bank,
                    binding = written.binding,
                    takeoverPolicy = takeoverPolicy,
                    sortOrder = request.sortOrder ?: 0,
                    part = request.part ?: written.part,
                )
                call.respond(HttpStatusCode.Created, resolved.toDto(refs))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, refusalResponse(e))
            }
        }
    }

    get<ProjectSurfaceBindingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val binding = service.get(project.id.value, resource.bindingId)
            if (binding != null) call.respond(binding.toDto(LegacyRefs.of(state.database, project.id.value)))
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
        }
    }

    patch<ProjectSurfaceBindingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<UpdateSurfaceBindingRequest>()

            val takeoverPolicyUpdate = when {
                !request.takeoverPolicyPresent -> ControlSurfaceBindingService.FieldUpdate.NoChange
                request.takeoverPolicy == null -> ControlSurfaceBindingService.FieldUpdate.Set<BindingTakeoverPolicy?>(null)
                else -> {
                    val parsed = BindingTakeoverPolicy.parseOrNull(request.takeoverPolicy)
                    if (parsed == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Unknown takeoverPolicy: ${request.takeoverPolicy}"),
                        )
                        return@withProject
                    }
                    ControlSurfaceBindingService.FieldUpdate.Set<BindingTakeoverPolicy?>(parsed)
                }
            }
            val bankUpdate: ControlSurfaceBindingService.FieldUpdate<String?> = when {
                !request.bankPresent -> ControlSurfaceBindingService.FieldUpdate.NoChange
                else -> ControlSurfaceBindingService.FieldUpdate.Set<String?>(request.bank)
            }

            // The shape has to be checked against what the row *becomes*, so fields the request
            // leaves out are read from the existing binding.
            val existing = service.get(project.id.value, resource.bindingId)
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
                return@withProject
            }
            val refs = LegacyRefs.of(state.database, project.id.value)
            val deviceTypeKey = request.deviceTypeKey ?: existing.deviceTypeKey
            val controlId = request.controlId ?: existing.controlId
            val written = when (val read = readBinding(request.binding, request.target, deviceTypeKey, controlId, refs)) {
                is BindingRead.Bad -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(read.message))
                    return@withProject
                }
                BindingRead.Absent -> null
                is BindingRead.Read -> read
            }
            val validationError = validateRequestShape(
                deviceTypeKey = deviceTypeKey,
                controlId = controlId,
                bank = if (request.bankPresent) request.bank else existing.bank,
                binding = written?.binding ?: existing.binding,
            )
            if (validationError != null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(validationError.message, code = validationError.code),
                )
                return@withProject
            }

            try {
                val updated = service.update(
                    projectId = project.id.value,
                    bindingId = resource.bindingId,
                    deviceTypeKey = request.deviceTypeKey,
                    controlId = request.controlId,
                    binding = written?.binding,
                    sortOrder = request.sortOrder,
                    bankUpdate = bankUpdate,
                    takeoverPolicyUpdate = takeoverPolicyUpdate,
                    part = request.part ?: written?.part,
                )
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
                } else {
                    call.respond(updated.toDto(refs))
                }
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, refusalResponse(e))
            }
        }
    }

    post<ProjectSurfaceBindingExpandResource> { resource ->
        withProject(state, resource.parent.parent.projectId) { project ->
            val projectId = project.id.value
            val existing = service.get(projectId, resource.parent.bindingId)
            if (existing == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
                return@withProject
            }
            if (existing.binding.does !is Does.Strip) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Binding ${existing.id} is not a strip binding",
                        code = CODE_BINDING_CONTROL_NOT_STRIP,
                    ),
                )
                return@withProject
            }
            val profile = ControlSurfaceRegistry.typeFor(existing.deviceTypeKey)
            val strip = profile?.strips?.firstOrNull { it.id == existing.controlId }
            if (strip == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("No strip '${existing.controlId}' on device '${existing.deviceTypeKey}'"),
                )
                return@withProject
            }

            // Derived at the encoder bank the device is on right now, so the four rows the
            // operator ends up with are the four bindings they could see a moment ago.
            val derived = deriveStripBindings(
                strip = strip,
                on = existing.binding.on,
                encoderBank = state.encoderBankState.selectionFor(existing.deviceTypeKey),
            )
            try {
                val created = service.replace(
                    projectId = projectId,
                    deleteIds = listOf(existing.id),
                    creates = derived.map { (controlId, derived) ->
                        ControlSurfaceBindingService.NewBinding(
                            deviceTypeKey = existing.deviceTypeKey,
                            controlId = controlId,
                            bank = existing.bank,
                            binding = derived,
                            takeoverPolicy = existing.takeoverPolicy,
                            sortOrder = existing.sortOrder,
                        )
                    },
                )
                val refs = LegacyRefs.of(state.database, projectId)
                call.respond(HttpStatusCode.Created, created.map { it.toDto(refs) })
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, refusalResponse(e))
            }
        }
    }

    delete<ProjectSurfaceBindingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val ok = service.delete(project.id.value, resource.bindingId)
            if (ok) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
        }
    }
}

/**
 * The 400 body for a write the service refused.
 *
 * [BindingRefused] carries a machine-readable code, which is how a rule can live on
 * [ControlSurfaceBindingService] — the one door every write comes through — *and* still answer
 * something the frontend can branch on. Session 2 got both by duplicating the strip rules into
 * [validateRequestShape]; the rules added since do it this way instead.
 */
private fun refusalResponse(e: IllegalArgumentException) = ErrorResponse(
    e.message ?: "Invalid binding",
    code = (e as? BindingRefused)?.code,
)

/** A request's binding: given in the new grammar, translated from the old union, absent, or unreadable. */
internal sealed interface BindingRead {
    data class Read(val binding: Binding, val part: ControlPart?) : BindingRead
    data object Absent : BindingRead
    data class Bad(val message: String) : BindingRead
}

/**
 * The binding a write carries: [binding] as it is (its part left to the service), or [target], the
 * old union the screens session 3 replaces still send, through [SurfaceGrammar] with [refs] — on an
 * encoder, a button variant is its push, as the migration places one.
 */
internal fun readBinding(
    binding: Binding?,
    target: JsonObject?,
    deviceTypeKey: String,
    controlId: String,
    refs: SurfaceGrammar.Ints,
): BindingRead {
    if (binding != null && target != null) return BindingRead.Bad("Send a binding or a target, not both")
    if (binding != null) return BindingRead.Read(binding, null)
    if (target == null) return BindingRead.Absent
    val translated = SurfaceGrammar.binding(target, refs)
        ?: return BindingRead.Bad("The target ${SurfaceGrammar.legacyType(target)} does not name anything this show holds")
    val descriptor = ControlSurfaceRegistry.typeFor(deviceTypeKey)?.control(controlId)
    return BindingRead.Read(translated, SurfaceGrammar.partFor(descriptor, SurfaceGrammar.legacyType(target)))
}

/** A strip binding was bound to an ordinary control rather than to a strip id. */
internal const val CODE_BINDING_STRIP_NEEDS_STRIP = "BINDING_STRIP_NEEDS_STRIP"

/** A strip slot was given something other than a strip binding. */
internal const val CODE_BINDING_CONTROL_NOT_STRIP = "BINDING_CONTROL_NOT_STRIP"

/** A refused write: a human-readable [message] and, where one exists, a machine-readable [code]. */
private data class ShapeError(val message: String, val code: String? = null)

/**
 * The slot a write asks for has to exist and has to suit the binding. Applied to **both** POST and
 * PATCH: a PATCH is how the surface view saves a drag onto an already-bound control, so leaving it
 * unvalidated would let exactly the same bad row in through the other door.
 *
 * A strip id and a control id share one namespace (the registry refuses a collision), and the two
 * are not interchangeable: a strip binding covers four controls by derivation, so it is only
 * meaningful on a strip, and a strip slot is only meaningful holding one.
 */
private fun validateRequestShape(
    deviceTypeKey: String,
    controlId: String,
    bank: String?,
    binding: Binding?,
): ShapeError? {
    val profile = ControlSurfaceRegistry.typeFor(deviceTypeKey)
        ?: return ShapeError("Unknown deviceTypeKey: $deviceTypeKey")
    val isStripSlot = profile.isStripId(controlId)
    if (!isStripSlot && profile.controls.none { it.controlId == controlId }) {
        return ShapeError("Unknown controlId '$controlId' for device '$deviceTypeKey'")
    }
    if (bank != null && profile.banks.none { it.id == bank }) {
        return ShapeError("Unknown bank '$bank' for device '$deviceTypeKey'")
    }
    if (binding != null) {
        val isStrip = binding.does is Does.Strip
        if (isStrip && !isStripSlot) {
            return ShapeError(
                "'$controlId' is a control, not a strip — a strip binding covers a whole strip",
                CODE_BINDING_STRIP_NEEDS_STRIP,
            )
        }
        if (!isStrip && isStripSlot) {
            return ShapeError(
                "'$controlId' is a strip and takes a strip binding; bind its controls individually instead",
                CODE_BINDING_CONTROL_NOT_STRIP,
            )
        }
    }
    return null
}

// ─── Resources ─────────────────────────────────────────────────────────────

@Resource("/{projectId}/surface-bindings")
data class ProjectSurfaceBindingsResource(val projectId: String)

@Resource("/{bindingId}")
data class ProjectSurfaceBindingResource(val parent: ProjectSurfaceBindingsResource, val bindingId: Int)

/**
 * `POST .../surface-bindings/{bindingId}/expand` — the inspector's *Fader only…*: replace a strip
 * binding with the individual bindings it was deriving, so the operator can then change one of
 * them. One transaction; answers the created rows.
 */
@Resource("/expand")
data class ProjectSurfaceBindingExpandResource(val parent: ProjectSurfaceBindingResource)

// ─── DTOs ──────────────────────────────────────────────────────────────────

/**
 * A new binding: [binding], or [target] in the old union's words. [part] null places it — an
 * encoder's push for a function only a button takes.
 */
@Serializable
data class CreateSurfaceBindingRequest(
    val deviceTypeKey: String,
    val controlId: String,
    val bank: String? = null,
    val binding: Binding? = null,
    val target: JsonObject? = null,
    val part: ControlPart? = null,
    val takeoverPolicy: String? = null,
    val sortOrder: Int? = null,
)

/**
 * Partial-update payload. Because JSON-omission vs JSON-null is not directly recoverable
 * via kotlinx.serialization without custom serializers, we expose `*Present` booleans that
 * clients set to signal "I want to update this field to the provided value, possibly null".
 */
@Serializable
data class UpdateSurfaceBindingRequest(
    val deviceTypeKey: String? = null,
    val controlId: String? = null,
    val bank: String? = null,
    val bankPresent: Boolean = false,
    val binding: Binding? = null,
    val target: JsonObject? = null,
    val part: ControlPart? = null,
    val takeoverPolicy: String? = null,
    val takeoverPolicyPresent: Boolean = false,
    val sortOrder: Int? = null,
)

/**
 * One binding row. [binding] is the binding; [target] and [targetType] say it in the old union's
 * words for the screens session 3 replaces.
 */
@Serializable
data class SurfaceBindingDto(
    val id: Int,
    val projectId: Int,
    val deviceTypeKey: String,
    val controlId: String,
    val part: ControlPart,
    val bank: String?,
    val binding: Binding,
    val target: JsonObject,
    val targetType: String,
    val takeoverPolicy: String?,
    val sortOrder: Int,
    /**
     * Dead-reference diagnostics. Non-Ok variants indicate the binding no longer resolves
     * against the current project (fixture renamed, stack deleted, layer removed from the
     * profile, etc.) and the router will drop inbound events.
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
)

internal fun ControlSurfaceBindingService.ResolvedBinding.toDto(refs: SurfaceGrammar.Ids): SurfaceBindingDto {
    val button = ControlSurfaceRegistry.typeFor(deviceTypeKey)?.control(controlId)?.inputFor(part) == ControlKind.BUTTON
    val target = SurfaceGrammar.legacyTarget(binding, deviceTypeKey, button, refs)
    return SurfaceBindingDto(
        id = id,
        projectId = projectId,
        deviceTypeKey = deviceTypeKey,
        controlId = controlId,
        part = part,
        bank = bank,
        binding = binding,
        target = target,
        targetType = SurfaceGrammar.legacyType(target),
        takeoverPolicy = takeoverPolicy?.name,
        sortOrder = sortOrder,
        health = health,
    )
}
