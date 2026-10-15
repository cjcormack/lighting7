package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.CommandRole
import uk.me.cormack.lighting7.mcp.requireCommandsAccess
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.CommandRunDto
import uk.me.cormack.lighting7.plugins.toDto
import uk.me.cormack.lighting7.state.RigSafetyService
import uk.me.cormack.lighting7.state.State

/**
 * Bulk fixture commands and *Make rig safe* over REST (venue-visit plan S6, D9, D18, D19). The current
 * project's only, both roles, and on the public listener held to [requireCommandsAccess] like a single
 * command. A run answers at once with its plan; how it goes streams as `rigSafety.state`.
 *
 * - `POST /projects/{id}/commands` `{role, targets?}` — run one role's command (`LAMP_ON`, `LAMP_OFF`,
 *   `RESET`) over [targets], or the whole rig when they are absent. A strike is staggered and waits out
 *   each lamp's restrike time. 400 `COMMAND_ROLE_UNKNOWN` / `COMMAND_NEEDS_SELECTION` (an empty list);
 *   409 `COMMAND_NO_UNITS`, `COMMAND_RUN_BUSY`, `COMMAND_BLIND`, `VENUE_HOLD`.
 * - `POST /projects/{id}/commands/cancel` — stop the run in flight; 409 `COMMAND_NO_RUN`.
 * - `POST /projects/{id}/rig-safe` `{targets?}` — *Make rig safe*: every lamp off (the selection's when
 *   named), the holds waited out, *Make rig safe*'s blackout, then *Ready to switch off*.
 * - `POST /projects/{id}/rig-safe/undo` — strike the lamps it turned off, staggered, then release the
 *   blackout; 409 `RIG_SAFE_NOTHING_TO_UNDO`.
 * - `POST /projects/{id}/rig-safe/release` — release the blackout alone, keeping what *Undo* would strike.
 *
 * Each is a runtime gesture on a venue (`projectWriteGuard.kt`).
 */
internal fun Route.routeApiRestProjectRigSafety(state: State) {
    post<ProjectCommandsResource> { resource ->
        call.requireCommandsAccess(state)
        withCurrentProject(state, resource.projectId, "Only the current project's fixtures can run a command") { _ ->
            val request = call.receive<BulkCommandRequest>()
            val role = CommandRole.entries.firstOrNull { it.name == request.role && it != CommandRole.OTHER }
                ?: return@withCurrentProject call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Name a role: LAMP_ON, LAMP_OFF or RESET", RigSafetyService.CODE_ROLE_UNKNOWN),
                )
            if (refusedTargets(request.targets)) return@withCurrentProject
            answer(state.rigSafety.runRole(role, request.targets?.map { it.target }))
        }
    }

    post<ProjectCommandsCancelResource> { resource ->
        call.requireCommandsAccess(state)
        withCurrentProject(state, resource.parent.projectId, "Only the current project's run can be cancelled") { _ ->
            if (state.rigSafety.cancel()) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.Conflict, ErrorResponse("No bulk command is running", CODE_NO_RUN))
        }
    }

    post<RigSafeResource> { resource ->
        call.requireCommandsAccess(state)
        withCurrentProject(state, resource.projectId, "Only the current project's rig can be made safe") { _ ->
            val request = call.receive<MakeRigSafeRequest>()
            if (refusedTargets(request.targets)) return@withCurrentProject
            answer(state.rigSafety.makeSafe(request.targets?.map { it.target }))
        }
    }

    post<RigSafeUndoResource> { resource ->
        call.requireCommandsAccess(state)
        withCurrentProject(state, resource.parent.projectId, "Only the current project's rig can be undone") { _ ->
            answer(state.rigSafety.undo())
        }
    }

    post<RigSafeReleaseResource> { resource ->
        call.requireCommandsAccess(state)
        withCurrentProject(state, resource.parent.projectId, "Only the current project's blackout can be released") { _ ->
            answer(state.rigSafety.releaseBlackout())
        }
    }
}

/**
 * Answer 400 and return true for an empty list or one naming an unknown kind of target. Absent
 * targets (null) are the whole rig, and pass.
 */
private suspend fun RoutingContext.refusedTargets(targets: List<CueTargetDto>?): Boolean {
    if (targets == null) return false
    val message = when {
        targets.isEmpty() -> "Select fixtures or groups, or leave targets out for the whole rig"
        else -> targets.firstOrNull { TargetRef.ofOrNull(it.type, it.key) == null }?.let { "Unknown target type '${it.type}'" }
    } ?: return false
    call.respond(HttpStatusCode.BadRequest, ErrorResponse(message, CODE_NEEDS_SELECTION))
    return true
}

private suspend fun RoutingContext.answer(outcome: RigSafetyService.Outcome) {
    when (outcome) {
        is RigSafetyService.Outcome.Started -> call.respond(RigSafetyResponse(run = outcome.run.toDto()))
        RigSafetyService.Outcome.Released -> call.respond(RigSafetyResponse(run = null))
        is RigSafetyService.Outcome.Refused -> call.respond(
            if (outcome.conflict) HttpStatusCode.Conflict else HttpStatusCode.BadRequest,
            ErrorResponse(outcome.message, outcome.code),
        )
    }
}

internal const val CODE_NEEDS_SELECTION = "COMMAND_NEEDS_SELECTION"
internal const val CODE_NO_RUN = "COMMAND_NO_RUN"

@Serializable
data class BulkCommandRequest(val role: String, val targets: List<CueTargetDto>? = null)

@Serializable
data class MakeRigSafeRequest(val targets: List<CueTargetDto>? = null)

/** The run a request started — absent for *Release*, or an *Undo* that had nothing to strike. */
@Serializable
data class RigSafetyResponse(val run: CommandRunDto? = null)

@Resource("/{projectId}/commands")
internal data class ProjectCommandsResource(val projectId: String)

@Resource("/cancel")
internal data class ProjectCommandsCancelResource(val parent: ProjectCommandsResource)

@Resource("/{projectId}/rig-safe")
internal data class RigSafeResource(val projectId: String)

@Resource("/undo")
internal data class RigSafeUndoResource(val parent: RigSafeResource)

@Resource("/release")
internal data class RigSafeReleaseResource(val parent: RigSafeResource)
