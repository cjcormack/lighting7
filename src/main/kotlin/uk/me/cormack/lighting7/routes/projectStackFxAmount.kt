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
 * A cue stack's FX amount over REST (effects plan D10, §3.7): a runtime weight, 0–1, on the
 * effects that stack's cues run, 100 % at load.
 *
 * - `GET /projects/{id}/cue-stacks/{sid}/fx-amount` — answers `{amount}`.
 * - `PUT /projects/{id}/cue-stacks/{sid}/fx-amount` `{amount}` — sets it; 400
 *   `STACK_FX_AMOUNT_INVALID` outside 0–1. Answers the amount as set.
 *
 * The current project's only, like the grand master, and a runtime gesture for both roles
 * (`projectWriteGuard.kt`). Nothing is stored: a project switch drops it and a reload carries it.
 * `stackFxAmount.state` streams it and `stackFxAmount.set` sets it at frame rate
 * (`plugins/StackFxAmountSocket.kt`); a MIDI `stackFxAmount` binding sets it too.
 */
internal fun Route.routeApiRestProjectStackFxAmount(state: State) {
    get<StackFxAmountResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Only the current project's stacks have an FX amount") { project ->
            if (!stackInProject(state, project, resource.parent.stackId)) {
                return@withCurrentProject call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
            call.respond(StackFxAmountDto(state.show.cueStackManager.fxAmounts.of(resource.parent.stackId)))
        }
    }

    put<StackFxAmountResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId, "Only the current project's stacks have an FX amount") { project ->
            val request = call.receive<StackFxAmountDto>()
            if (!request.amount.isFinite() || request.amount !in 0.0..1.0) {
                return@withCurrentProject call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("amount must be a number from 0 to 1", CODE_STACK_FX_AMOUNT_INVALID),
                )
            }
            if (!stackInProject(state, project, resource.parent.stackId)) {
                return@withCurrentProject call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            }
            call.respond(StackFxAmountDto(state.show.cueStackManager.setFxAmount(resource.parent.stackId, request.amount)))
        }
    }
}

/** Whether [stackId] is one of [project]'s stacks. */
internal fun stackInProject(state: State, project: DaoProject, stackId: Int): Boolean = transaction(state.database) {
    DaoCueStack.findById(stackId)?.project?.id == project.id
}

internal const val CODE_STACK_FX_AMOUNT_INVALID = "STACK_FX_AMOUNT_INVALID"

/** A stack's FX amount, 0–1. */
@Serializable
data class StackFxAmountDto(val amount: Double)

@Resource("/fx-amount")
internal data class StackFxAmountResource(val parent: ProjectCueStackResource)
