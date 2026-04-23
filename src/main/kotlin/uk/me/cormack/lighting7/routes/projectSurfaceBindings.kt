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
import uk.me.cormack.lighting7.fx.AssignmentHealth
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.ControlSurfaceRegistry
import uk.me.cormack.lighting7.midi.discriminator
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.state.State

/**
 * Control-surface binding CRUD.
 *
 *   - `GET    /api/rest/project/{projectId}/surfaceBindings`
 *   - `POST   /api/rest/project/{projectId}/surfaceBindings`
 *   - `GET    /api/rest/project/{projectId}/surfaceBindings/{bindingId}`
 *   - `PATCH  /api/rest/project/{projectId}/surfaceBindings/{bindingId}`
 *   - `DELETE /api/rest/project/{projectId}/surfaceBindings/{bindingId}`
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

            val validationError = validateRequestShape(request.deviceTypeKey, request.controlId, request.bank)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
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

    delete<ProjectSurfaceBindingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val ok = service.delete(project.id.value, resource.bindingId)
            if (ok) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Binding not found"))
        }
    }
}

private fun validateRequestShape(
    deviceTypeKey: String,
    controlId: String,
    bank: String?,
): String? {
    val profile = ControlSurfaceRegistry.allTypes.firstOrNull { it.typeKey == deviceTypeKey }
        ?: return "Unknown deviceTypeKey: $deviceTypeKey"
    if (profile.controls.none { it.controlId == controlId }) {
        return "Unknown controlId '$controlId' for device '$deviceTypeKey'"
    }
    if (bank != null && profile.banks.none { it.id == bank }) {
        return "Unknown bank '$bank' for device '$deviceTypeKey'"
    }
    return null
}

// ─── Resources ─────────────────────────────────────────────────────────────

@Resource("/{projectId}/surfaceBindings")
data class ProjectSurfaceBindingsResource(val projectId: String)

@Resource("/{bindingId}")
data class ProjectSurfaceBindingResource(val parent: ProjectSurfaceBindingsResource, val bindingId: Int)

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
