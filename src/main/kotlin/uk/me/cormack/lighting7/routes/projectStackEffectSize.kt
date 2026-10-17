package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State

/**
 * A cue stack's effect size over REST (effects plan D10, §3.7): a runtime weight, 0–1, on the
 * effects that stack's cues run, 100 % at load.
 *
 * - `GET /projects/{id}/cue-stacks/{sid}/effect-size` — answers `{size}`.
 * - `PUT /projects/{id}/cue-stacks/{sid}/effect-size` `{size}` — sets it; 400
 *   `STACK_EFFECT_SIZE_INVALID` outside 0–1. Answers the size as set.
 *
 * The current project's only, like the grand master, and a runtime gesture for both roles
 * (`projectWriteGuard.kt`). Nothing is stored: a project switch drops it and a reload carries it.
 * `stackEffectSize.state` streams it and `stackEffectSize.set` sets it at frame rate
 * (`plugins/StackEffectSizeSocket.kt`); a MIDI `stackEffectSize` binding sets it too.
 */
internal fun Route.routeApiRestProjectStackEffectSize(state: State) {
    get<StackEffectSizeResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Only the current project's stacks have an effect size") { project ->
            if (!stackInProject(state, project, resource.parent.stackId)) {
                return@withCurrentProject call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
            call.respond(StackEffectSizeDto(state.show.cueStackManager.effectSizes.of(resource.parent.stackId)))
        }
    }

    put<StackEffectSizeResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Only the current project's stacks have an effect size") { project ->
            val request = call.receive<StackEffectSizeDto>()
            if (!request.size.isFinite() || request.size !in 0.0..1.0) {
                return@withCurrentProject call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("size must be a number from 0 to 1", CODE_STACK_EFFECT_SIZE_INVALID),
                )
            }
            if (!stackInProject(state, project, resource.parent.stackId)) {
                return@withCurrentProject call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
            call.respond(StackEffectSizeDto(state.show.cueStackManager.setEffectSize(resource.parent.stackId, request.size)))
        }
    }
}

/** Whether [stackId] is one of [project]'s stacks. */
internal fun stackInProject(state: State, project: DaoProject, stackId: Int): Boolean = transaction(state.database) {
    DaoCueStack.findById(stackId)?.project?.id == project.id
}

internal const val CODE_STACK_EFFECT_SIZE_INVALID = "STACK_EFFECT_SIZE_INVALID"

/** A stack's effect size, 0–1. */
@Serializable
data class StackEffectSizeDto(val size: Double)

@Resource("/effect-size")
internal data class StackEffectSizeResource(val parent: ProjectCueStackResource)
