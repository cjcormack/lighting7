package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.plugins.HistoryStateOutMessage
import uk.me.cormack.lighting7.plugins.historySourceOf
import uk.me.cormack.lighting7.plugins.toMessage
import uk.me.cormack.lighting7.state.State

/** Nothing to undo or redo. */
internal const val CODE_HISTORY_EMPTY = "HISTORY_EMPTY"

/** An `entryId` that names no step of this history. */
internal const val CODE_HISTORY_UNKNOWN = "HISTORY_UNKNOWN"

/**
 * Undo and redo (undo plan §3.5, §3.7, D13): REST only. The programmer half (session 1): with no
 * `entryId` the newest programmer step — the one the key takes, since the history holds no show entry
 * yet; with one, every step down to and including it, which is going back to before it. `fadeMs` is the
 * programmer fade the client holds. Current project only (a step is the live programmer's), both roles.
 *
 * `backToHere` and `confirm` are the show half's (session 4) and are read but change nothing here.
 */
internal fun Route.routeApiRestProjectHistory(state: State) {
    post<HistoryUndoResource> { resource ->
        withCurrentProject(state, resource.projectId, "Only the current project's programmer can be undone") {
            val request = call.receive<HistoryRequest>()
            val history = state.show.programmerHistory
            val undone = history.undo(request.entryId, request.fadeMs.coerceFade(), historySourceOf(call.authenticatedUserOrNull))
            if (undone == null) return@withCurrentProject call.respondNothing(request.entryId, "undo")
            call.respond(HistoryUndoResponse(undone = undone.map { HistoryStepRef(it.id, it.label) }, history = history.frame().toMessage()))
        }
    }
    post<HistoryRedoResource> { resource ->
        withCurrentProject(state, resource.projectId, "Only the current project's programmer can be redone") {
            val request = call.receive<HistoryRequest>()
            val history = state.show.programmerHistory
            val redone = history.redo(request.entryId, request.fadeMs.coerceFade(), historySourceOf(call.authenticatedUserOrNull))
            if (redone == null) return@withCurrentProject call.respondNothing(request.entryId, "redo")
            call.respond(HistoryRedoResponse(redone = redone.map { HistoryStepRef(it.id, it.label) }, history = history.frame().toMessage()))
        }
    }
}

private fun Long?.coerceFade(): Long = (this ?: 0).coerceIn(0, MAX_HISTORY_FADE_MS)

private const val MAX_HISTORY_FADE_MS = 60_000L

private suspend fun io.ktor.server.application.ApplicationCall.respondNothing(entryId: String?, what: String) =
    if (entryId == null) {
        respond(HttpStatusCode.Conflict, ErrorResponse("Nothing to $what", CODE_HISTORY_EMPTY))
    } else {
        respond(HttpStatusCode.NotFound, ErrorResponse("No step $entryId to $what", CODE_HISTORY_UNKNOWN))
    }

@Resource("/{projectId}/history/undo")
internal data class HistoryUndoResource(val projectId: String)

@Resource("/{projectId}/history/redo")
internal data class HistoryRedoResource(val projectId: String)

@Serializable
internal data class HistoryRequest(
    val entryId: String? = null,
    val backToHere: Boolean = false,
    val programmerOnly: Boolean = false,
    val confirm: Boolean = false,
    val fadeMs: Long? = null,
)

@Serializable
internal data class HistoryStepRef(val id: String, val label: String)

@Serializable
internal data class HistoryUndoResponse(val undone: List<HistoryStepRef>, val history: HistoryStateOutMessage)

@Serializable
internal data class HistoryRedoResponse(val redone: List<HistoryStepRef>, val history: HistoryStateOutMessage)
