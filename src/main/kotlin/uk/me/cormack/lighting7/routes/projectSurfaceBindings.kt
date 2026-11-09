package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.BindingRefused
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.ControlSurfaceRegistry
import uk.me.cormack.lighting7.midi.Does
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
 *   - `PUT    /api/rest/projects/{projectId}/surface-bindings/slot` — the editors' one write
 *   - `DELETE /api/rest/projects/{projectId}/surface-bindings/slot`
 *
 * All mutations go through [ControlSurfaceBindingService], which keeps an in-memory cache
 * in sync with the DB and broadcasts [ControlSurfaceBindingService.BindingChange] events.
 */
internal fun Route.routeApiRestProjectSurfaceBindings(state: State) {
    val service = state.controlSurfaceBindingService

    get<ProjectSurfaceBindingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            call.respond(service.list(project.id.value).map { it.toDto() })
        }
    }

    post<ProjectSurfaceBindingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreateSurfaceBindingRequest>()
            validateRequestShape(request.deviceTypeKey, request.controlId, request.bank, request.binding)?.let {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message, code = it.code))
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
                    binding = request.binding,
                    takeoverPolicy = takeoverPolicy,
                    sortOrder = request.sortOrder ?: 0,
                    part = request.part,
                )
                call.respond(HttpStatusCode.Created, resolved.toDto())
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, refusalResponse(e))
            }
        }
    }

    // The one write the editors use: whatever the slot holds becomes this binding.
    put<ProjectSurfaceBindingSlotResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<SurfaceBindingSlotRequest>()
            validateRequestShape(request.deviceTypeKey, request.controlId, request.layer, request.binding)?.let {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message, code = it.code))
                return@withProject
            }
            val takeoverPolicy = request.takeoverPolicy?.let { BindingTakeoverPolicy.parseOrNull(it) }
            if (request.takeoverPolicy != null && takeoverPolicy == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown takeoverPolicy: ${request.takeoverPolicy}"))
                return@withProject
            }
            val projectId = project.id.value
            try {
                val existing = service.rowAt(projectId, request.deviceTypeKey, request.controlId, request.part, request.layer)
                val written = if (existing == null) {
                    service.create(
                        projectId = projectId,
                        deviceTypeKey = request.deviceTypeKey,
                        controlId = request.controlId,
                        bank = request.layer,
                        binding = request.binding,
                        takeoverPolicy = takeoverPolicy,
                        part = request.part,
                    )
                } else {
                    service.update(
                        projectId = projectId,
                        bindingId = existing.id,
                        binding = request.binding,
                        takeoverPolicyUpdate = ControlSurfaceBindingService.FieldUpdate.Set(takeoverPolicy),
                        part = request.part,
                    )
                }
                if (written == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
                else call.respond(written.toDto())
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, refusalResponse(e))
            }
        }
    }

    delete<ProjectSurfaceBindingSlotResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<SurfaceBindingSlotRef>()
            val existing = service.rowAt(project.id.value, request.deviceTypeKey, request.controlId, request.part, request.layer)
            if (existing != null && service.delete(project.id.value, existing.id)) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Nothing is bound there"))
        }
    }

    get<ProjectSurfaceBindingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val binding = service.get(project.id.value, resource.bindingId)
            if (binding != null) call.respond(binding.toDto())
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
            val validationError = validateRequestShape(
                deviceTypeKey = request.deviceTypeKey ?: existing.deviceTypeKey,
                controlId = request.controlId ?: existing.controlId,
                bank = if (request.bankPresent) request.bank else existing.bank,
                binding = request.binding ?: existing.binding,
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
                    binding = request.binding,
                    sortOrder = request.sortOrder,
                    bankUpdate = bankUpdate,
                    takeoverPolicyUpdate = takeoverPolicyUpdate,
                    part = request.part,
                )
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
                } else {
                    call.respond(updated.toDto())
                }
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

/** `PUT` / `DELETE .../surface-bindings/slot` — one control part on one layer, named in the body. */
@Resource("/slot")
data class ProjectSurfaceBindingSlotResource(val parent: ProjectSurfaceBindingsResource)

// ─── DTOs ──────────────────────────────────────────────────────────────────

/** A new binding. [part] null places it — an encoder's push for a function only a button takes. */
@Serializable
data class CreateSurfaceBindingRequest(
    val deviceTypeKey: String,
    val controlId: String,
    val bank: String? = null,
    val binding: Binding,
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
    val part: ControlPart? = null,
    val takeoverPolicy: String? = null,
    val takeoverPolicyPresent: Boolean = false,
    val sortOrder: Int? = null,
)

/** One binding row. */
@Serializable
data class SurfaceBindingDto(
    val id: Int,
    val projectId: Int,
    val deviceTypeKey: String,
    val controlId: String,
    val part: ControlPart,
    val bank: String?,
    val binding: Binding,
    val takeoverPolicy: String?,
    val sortOrder: Int,
    /**
     * Dead-reference diagnostics. Non-Ok variants indicate the binding no longer resolves
     * against the current project (fixture renamed, stack deleted, layer removed from the
     * profile, etc.) and the router will drop inbound events.
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
)

/**
 * The slot write: [binding] at `(deviceTypeKey, controlId, part, layer)`, [layer] null for both
 * layers, with [takeoverPolicy] as given (null is the control's default).
 */
@Serializable
data class SurfaceBindingSlotRequest(
    val deviceTypeKey: String,
    val controlId: String,
    val part: ControlPart = ControlPart.MAIN,
    val layer: String? = null,
    val binding: Binding,
    val takeoverPolicy: String? = null,
)

/** A slot to clear. */
@Serializable
data class SurfaceBindingSlotRef(
    val deviceTypeKey: String,
    val controlId: String,
    val part: ControlPart = ControlPart.MAIN,
    val layer: String? = null,
)

internal fun ControlSurfaceBindingService.ResolvedBinding.toDto() = SurfaceBindingDto(
    id = id,
    projectId = projectId,
    deviceTypeKey = deviceTypeKey,
    controlId = controlId,
    part = part,
    bank = bank,
    binding = binding,
    takeoverPolicy = takeoverPolicy?.name,
    sortOrder = sortOrder,
    health = health,
)
