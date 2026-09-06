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
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.ControlSurfaceRegistry
import uk.me.cormack.lighting7.midi.deriveStripTargets
import uk.me.cormack.lighting7.midi.discriminator
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
 */
internal fun Route.routeApiRestProjectSurfaceBindings(state: State) {
    val service = state.controlSurfaceBindingService

    get<ProjectSurfaceBindingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val list = service.list(project.id.value).map { it.toDto() }
            call.respond(list)
        }
    }

    post<ProjectSurfaceBindingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreateSurfaceBindingRequest>()

            val validationError =
                validateRequestShape(request.deviceTypeKey, request.controlId, request.bank, request.target)
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
                    target = request.target,
                    takeoverPolicy = takeoverPolicy,
                    sortOrder = request.sortOrder ?: 0,
                )
                call.respond(HttpStatusCode.Created, resolved.toDto())
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid binding target"))
            }
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
                target = request.target ?: existing.target,
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
                    target = request.target,
                    sortOrder = request.sortOrder,
                    bankUpdate = bankUpdate,
                    takeoverPolicyUpdate = takeoverPolicyUpdate,
                )
                if (updated == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
                } else {
                    call.respond(updated.toDto())
                }
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid binding target"))
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
            val target = existing.target
            if (target !is BindingTarget.Strip) {
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
            val derived = deriveStripTargets(
                strip = strip,
                target = target.target,
                encoderBankProperty = state.encoderBankState.propertyFor(existing.deviceTypeKey),
            )
            try {
                val created = service.replace(
                    projectId = projectId,
                    deleteIds = listOf(existing.id),
                    creates = derived.map { (controlId, derivedTarget) ->
                        ControlSurfaceBindingService.NewBinding(
                            deviceTypeKey = existing.deviceTypeKey,
                            controlId = controlId,
                            bank = existing.bank,
                            target = derivedTarget,
                            takeoverPolicy = existing.takeoverPolicy,
                            sortOrder = existing.sortOrder,
                        )
                    },
                )
                call.respond(HttpStatusCode.Created, created.map { it.toDto() })
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Binding slot already taken"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid binding target"))
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

/** A `Strip` target was bound to an ordinary control rather than to a strip id. */
internal const val CODE_BINDING_STRIP_NEEDS_STRIP = "BINDING_STRIP_NEEDS_STRIP"

/** A strip slot was given something other than a `Strip` target. */
internal const val CODE_BINDING_CONTROL_NOT_STRIP = "BINDING_CONTROL_NOT_STRIP"

/** A refused write: a human-readable [message] and, where one exists, a machine-readable [code]. */
private data class ShapeError(val message: String, val code: String? = null)

/**
 * The slot a write asks for has to exist and has to suit the target. Applied to **both** POST and
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
    target: BindingTarget?,
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
    if (target != null) {
        if (target is BindingTarget.Strip && !isStripSlot) {
            return ShapeError(
                "'$controlId' is a control, not a strip — a strip binding covers a whole strip",
                CODE_BINDING_STRIP_NEEDS_STRIP,
            )
        }
        if (target !is BindingTarget.Strip && isStripSlot) {
            return ShapeError(
                "'$controlId' is a strip and takes a strip target; bind its controls individually instead",
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

@Serializable
data class CreateSurfaceBindingRequest(
    val deviceTypeKey: String,
    val controlId: String,
    val bank: String? = null,
    val target: BindingTarget,
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
    val target: BindingTarget? = null,
    val takeoverPolicy: String? = null,
    val takeoverPolicyPresent: Boolean = false,
    val sortOrder: Int? = null,
)

@Serializable
data class SurfaceBindingDto(
    val id: Int,
    val projectId: Int,
    val deviceTypeKey: String,
    val controlId: String,
    val bank: String?,
    val target: BindingTarget,
    val targetType: String,
    val takeoverPolicy: String?,
    val sortOrder: Int,
    /**
     * Dead-reference diagnostics. Non-Ok variants indicate the binding's target no longer
     * resolves against the current project (fixture renamed, stack deleted, bank removed
     * from profile, etc.) and the router will drop inbound events.
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
)

private fun ControlSurfaceBindingService.ResolvedBinding.toDto(): SurfaceBindingDto = SurfaceBindingDto(
    id = id,
    projectId = projectId,
    deviceTypeKey = deviceTypeKey,
    controlId = controlId,
    bank = bank,
    target = target,
    targetType = target.discriminator(),
    takeoverPolicy = takeoverPolicy?.name,
    sortOrder = sortOrder,
    health = health,
)
