package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.history.Change
import uk.me.cormack.lighting7.history.HistoryEntry
import uk.me.cormack.lighting7.history.HistoryService
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.plugins.HistorySourceDto
import uk.me.cormack.lighting7.plugins.HistoryStateOutMessage
import java.time.Instant
import uk.me.cormack.lighting7.plugins.historySourceOf
import uk.me.cormack.lighting7.plugins.toMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.RecordFolders
import io.ktor.server.application.ApplicationCall
import uk.me.cormack.lighting7.history.ProgrammerHistory
import uk.me.cormack.lighting7.history.HistoryMove
import uk.me.cormack.lighting7.history.HistoryMoveResult
import uk.me.cormack.lighting7.history.HistoryRefusal
import uk.me.cormack.lighting7.history.refusal
import uk.me.cormack.lighting7.history.RecordRestorer
import uk.me.cormack.lighting7.history.moveHistory
import uk.me.cormack.lighting7.history.WriteOrigin
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.mcp.RemoteScriptsDisabledException
import uk.me.cormack.lighting7.mcp.scriptsAllowed

/** Nothing to undo or redo. */
internal const val CODE_HISTORY_EMPTY = "HISTORY_EMPTY"

/** An `entryId` that names no step of this history. */
internal const val CODE_HISTORY_UNKNOWN = "HISTORY_UNKNOWN"

/** The entry's records have changed since (D3): the answer's `later` lists what changed them. */
internal const val CODE_HISTORY_CONFLICT = "HISTORY_CONFLICT"

/** The undo moves the stage while the show runs (D6): resend with `confirm`. */
internal const val CODE_HISTORY_CONFIRM = "HISTORY_CONFIRM"

/** The records cannot be put back as they were: a name taken since, a clash with the venue, a record changed outside the history. */
internal const val CODE_HISTORY_UNDO_BLOCKED = "HISTORY_UNDO_BLOCKED"

/**
 * Undo and redo (undo plan §3.5, §3.7, D13): REST only. With no `entryId` the key's choice for the
 * caller (D1, D2): the newer of the live programmer's top step and the caller's newest show entry, or
 * the step alone with `programmerOnly`. An `entryId` names a step (`p-…`) or a show entry (`e-…`); a
 * step is undone with every step after it, which is going back to before it. `fadeMs` is the
 * programmer fade the client holds.
 *
 * A show entry is put back on any project (D12: under `/projects/{id}`, so the write guard applies,
 * and an operator's show undo onto a venue is refused here, since the guard passes the path as the
 * programmer's). A step is the live programmer's, so on the current project only. Both roles.
 */
internal fun Route.routeApiRestProjectHistory(state: State) {
    get<HistoryResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val source = historySourceOf(call.authenticatedUserOrNull)
            val limit = (resource.limit ?: DEFAULT_HISTORY_LIMIT).coerceIn(1, MAX_HISTORY_LIMIT)
            call.respond(historyPage(state, project.id.value, source, limit, resource.before))
        }
    }
    post<HistoryUndoResource> { resource ->
        withProject(state, resource.projectId) { project -> call.historyMove(state, project, call.receive(), undo = true) }
    }
    post<HistoryRedoResource> { resource ->
        withProject(state, resource.projectId) { project -> call.historyMove(state, project, call.receive(), undo = false) }
    }
}

private suspend fun ApplicationCall.historyMove(state: State, project: DaoProject, request: HistoryRequest, undo: Boolean) {
    val user = authenticatedUserOrNull
    val caller = historySourceOf(user)
    val projectId = project.id.value
    val move = HistoryMove(undo, request.entryId, request.programmerOnly, request.backToHere, request.confirm, request.fadeMs ?: 0)
    val result = moveHistory(
        state, projectId, caller, move,
        window = WriteOrigin.current()?.window,
        operator = user != null && user.role != UserRole.ADMIN,
        scriptsAllowed = scriptsAllowed(state),
    )
    val steps = when (result) {
        is HistoryMoveResult.Steps -> result.steps.map { HistoryStepRef(it.id, it.label) }
        is HistoryMoveResult.Show -> (result.outcome as? RecordRestorer.Outcome.Done)?.entries?.map { HistoryStepRef(it.id, it.label) }
        else -> null
    }
    if (steps != null) return respondMoved(HistoryMoveResponse(steps, historyFrameFor(state, projectId, caller)), undo)
    val refusal = result.refusal(undo, request.entryId) ?: return
    when (refusal.kind) {
        HistoryRefusal.Kind.SCRIPTS_REFUSED -> throw RemoteScriptsDisabledException()
        HistoryRefusal.Kind.CONFLICT, HistoryRefusal.Kind.CONFIRM, HistoryRefusal.Kind.BLOCKED -> respond(
            HttpStatusCode.Conflict,
            HistoryRefusalDto(
                error = refusal.message,
                code = refusal.code!!,
                later = refusal.later.takeIf { refusal.kind == HistoryRefusal.Kind.CONFLICT }
                    ?.map { HistoryLaterDto(it.id, it.label, HistorySourceDto(it.source.kind.name, it.source.name, it.window)) },
                moves = refusal.moves.takeIf { refusal.kind == HistoryRefusal.Kind.CONFIRM },
                record = refusal.record,
                reason = refusal.reason,
            ),
        )
        HistoryRefusal.Kind.UNKNOWN -> respond(HttpStatusCode.NotFound, ErrorResponse(refusal.message, refusal.code))
        HistoryRefusal.Kind.VENUE_ADMIN_ONLY -> respond(HttpStatusCode.Forbidden, ErrorResponse(refusal.message, refusal.code))
        HistoryRefusal.Kind.EMPTY, HistoryRefusal.Kind.NOT_LIVE -> respond(HttpStatusCode.Conflict, ErrorResponse(refusal.message, refusal.code))
    }
}

private suspend fun ApplicationCall.respondMoved(answer: HistoryMoveResponse, undo: Boolean) =
    if (undo) respond(HistoryUndoResponse(answer.steps, answer.history)) else respond(HistoryRedoResponse(answer.steps, answer.history))

private class HistoryMoveResponse(val steps: List<HistoryStepRef>, val history: HistoryStateOutMessage)

/**
 * The caller's `history.state` for [projectId]: what its ⌘Z and ⇧⌘Z take now, the live programmer's
 * steps when the project is live and none otherwise.
 */
private fun historyFrameFor(state: State, projectId: Int, caller: HistorySource): HistoryStateOutMessage {
    val frame = state.programmerHistoryFrame.value.takeIf { state.projectManager.currentProjectOrNull?.id?.value == projectId }
        ?: ProgrammerHistory.Frame(projectId, 0, emptyList(), emptyList())
    return frame.toMessage(state.history, caller.key, projectId)
}

@Resource("/{projectId}/history")
internal data class HistoryResource(val projectId: String, val limit: Int? = null, val before: String? = null)

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

/** A later entry a conflicting undo collides with, as the confirm lists it. */
@Serializable
internal data class HistoryLaterDto(val id: String, val label: String, val source: HistorySourceDto)

/** An undo or redo refused with more to say than [ErrorResponse]: what collides, what moves, what is blocked. */
@Serializable
internal data class HistoryRefusalDto(
    val error: String,
    val code: String,
    val later: List<HistoryLaterDto>? = null,
    val moves: List<String>? = null,
    val record: String? = null,
    val reason: String? = null,
)

/**
 * The History sheet's read (undo plan §3.7): [projectId]'s show entries and — on the live project — the
 * programmer's steps, newest first, with the project's clear points among them as rules. [before] is a
 * row id: the page starts after it.
 */
internal fun historyPage(state: State, projectId: Int, caller: HistorySource, limit: Int, before: String?): HistoryPageDto {
    val history = state.history
    // Every row is ordered by its time and id; only the page's rows are built, and an entry's records are
    // named only for those.
    class Row(val at: Long, val id: String, val build: () -> HistoryRowDto)
    val rows = mutableListOf<Row>()
    for (entry in history.entries(projectId)) rows += Row(entry.lastAt, entry.id) { showRow(history, projectId, entry, caller) }
    for (clear in history.clearPoints(projectId)) {
        rows += Row(clear.at, clear.id) {
            HistoryRowDto(id = clear.id, kind = KIND_CLEAR, at = Instant.ofEpochMilli(clear.at).toIsoUtc(), label = clear.reason)
        }
    }
    val live = state.projectManager.currentProjectOrNull?.id?.value == projectId
    if (live) {
        val frame = state.showOrNull?.programmerHistory?.frame()
        frame?.undo?.forEach { step -> rows += Row(step.at, step.id) { step.toRow(caller, undone = false) } }
        frame?.redo?.forEach { step -> rows += Row(step.at, step.id) { step.toRow(caller, undone = true) } }
    }
    val sorted = rows.sortedWith(compareByDescending<Row> { it.at }.thenByDescending { it.id })
    val from = before?.let { id -> sorted.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
    val page = sorted.drop(from).take(limit)
    return HistoryPageDto(
        projectId = projectId,
        rows = page.map { it.build() },
        more = from + page.size < sorted.size,
        live = live,
        revision = history.revision.value,
    )
}

private fun showRow(history: HistoryService, projectId: Int, entry: HistoryEntry, caller: HistorySource) = HistoryRowDto(
    id = entry.id,
    kind = KIND_SHOW,
    at = Instant.ofEpochMilli(entry.lastAt).toIsoUtc(),
    label = entry.label,
    source = HistorySourceDto(entry.source.kind.name, entry.source.name, entry.window),
    mine = entry.source.key == caller.key,
    counts = entry.counts().let { HistoryCountsDto(it.added, it.removed, it.changed) },
    records = entry.records.take(MAX_RECORDS_LISTED).map { change ->
        HistoryRecordDto(change.key.tableName, change.key.uuid.toString(), change.kind.name, history.describe(projectId, change), change.rowId)
    },
    moreRecords = (entry.records.size - MAX_RECORDS_LISTED).coerceAtLeast(0),
    requestIds = entry.requestIds.toList(),
    undone = entry.undone,
    redoable = entry.undone,
    moves = movesOf(entry.records),
)

private fun uk.me.cormack.lighting7.history.ProgrammerHistory.StepInfo.toRow(caller: HistorySource, undone: Boolean) = HistoryRowDto(
    id = id,
    kind = KIND_PROGRAMMER,
    at = Instant.ofEpochMilli(at).toIsoUtc(),
    label = label,
    source = HistorySourceDto(source.kind.name, source.name, window),
    mine = source.key == caller.key,
    undone = undone,
    redoable = undone,
)

/**
 * What undoing an entry would move on stage, by the kinds of record it holds (§5): a patch, group,
 * universe, hide or facet reloads the fixtures, and a park is output. Whether a Look or palette edit
 * moves a live cue is asked when the undo runs ([RecordRestorer]'s confirm), not here.
 */
private fun movesOf(changes: Collection<Change>): List<String> =
    if (changes.any { it.key.tableName in RecordFolders.OUTPUT }) listOf(CHANGES_OUTPUT) else emptyList()

private const val CHANGES_OUTPUT = "changes output"
private const val KIND_SHOW = "SHOW"
private const val KIND_PROGRAMMER = "PROGRAMMER"
private const val KIND_CLEAR = "CLEAR"
private const val DEFAULT_HISTORY_LIMIT = 100
private const val MAX_HISTORY_LIMIT = 500
private const val MAX_RECORDS_LISTED = 50

@Serializable
internal data class HistoryCountsDto(val added: Int, val removed: Int, val changed: Int)

/** One record an entry changed: its folder and uuid, `ADDED | REMOVED | CHANGED`, its name and its row id, which the sheet links by. */
@Serializable
internal data class HistoryRecordDto(val folder: String, val uuid: String, val change: String, val label: String, val rowId: Int)

/** One row of the History sheet: a show entry, a programmer step, or a clear point (`CLEAR`, a rule). */
@Serializable
internal data class HistoryRowDto(
    val id: String,
    val kind: String,
    val at: String,
    val label: String,
    val source: HistorySourceDto? = null,
    /** Whether the row is the caller's own source's — the sheet's *Mine*. */
    val mine: Boolean = false,
    val counts: HistoryCountsDto? = null,
    val records: List<HistoryRecordDto> = emptyList(),
    /** Records past the first fifty, not listed. */
    val moreRecords: Int = 0,
    /** The requests whose writes a show entry holds: a REST write's `X-History-Entry` is one of them. */
    val requestIds: List<String> = emptyList(),
    val undone: Boolean = false,
    val redoable: Boolean = false,
    val moves: List<String> = emptyList(),
)

@Serializable
internal data class HistoryPageDto(
    val projectId: Int,
    val rows: List<HistoryRowDto>,
    /** Whether older rows follow; the next page is `before` the last row's id. */
    val more: Boolean,
    /** Whether the project is the live one, whose programmer steps are listed. */
    val live: Boolean,
    val revision: Long,
)
