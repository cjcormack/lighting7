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
    get<HistoryResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val source = historySourceOf(state, call.authenticatedUserOrNull)
            val limit = (resource.limit ?: DEFAULT_HISTORY_LIMIT).coerceIn(1, MAX_HISTORY_LIMIT)
            call.respond(historyPage(state, project.id.value, source, limit, resource.before))
        }
    }
    post<HistoryUndoResource> { resource ->
        withCurrentProject(state, resource.projectId, "Only the current project's programmer can be undone") {
            val request = call.receive<HistoryRequest>()
            val history = state.show.programmerHistory
            val undone = history.undo(request.entryId, request.fadeMs.coerceFade(), historySourceOf(state, call.authenticatedUserOrNull))
            if (undone == null) return@withCurrentProject call.respondNothing(request.entryId, "undo")
            call.respond(HistoryUndoResponse(undone = undone.map { HistoryStepRef(it.id, it.label) }, history = history.frame().toMessage()))
        }
    }
    post<HistoryRedoResource> { resource ->
        withCurrentProject(state, resource.projectId, "Only the current project's programmer can be redone") {
            val request = call.receive<HistoryRequest>()
            val history = state.show.programmerHistory
            val redone = history.redo(request.entryId, request.fadeMs.coerceFade(), historySourceOf(state, call.authenticatedUserOrNull))
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

/**
 * The History sheet's read (undo plan §3.7): [projectId]'s show entries and — on the live project — the
 * programmer's steps, newest first, with the project's clear points among them as rules. [before] is a
 * row id: the page starts after it. Read-only in session 3; nothing is undoable from it yet.
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
        HistoryRecordDto(change.key.tableName, change.key.uuid.toString(), change.kind.name, history.describe(projectId, change))
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
 * moves a live cue is session 4's confirm.
 */
private fun movesOf(changes: Collection<Change>): List<String> =
    if (changes.any { it.key.tableName in OUTPUT_FOLDERS }) listOf(CHANGES_OUTPUT) else emptyList()

private val OUTPUT_FOLDERS = setOf(
    "fixturePatches", "fixtureGroups", "universeConfigs", "showHides", "showOverrides", "showColours",
    "showGroupOrders", "parkedChannels", "showParkReleases",
)

private const val CHANGES_OUTPUT = "changes output"
private const val KIND_SHOW = "SHOW"
private const val KIND_PROGRAMMER = "PROGRAMMER"
private const val KIND_CLEAR = "CLEAR"
private const val DEFAULT_HISTORY_LIMIT = 100
private const val MAX_HISTORY_LIMIT = 500
private const val MAX_RECORDS_LISTED = 50

@Serializable
internal data class HistoryCountsDto(val added: Int, val removed: Int, val changed: Int)

/** One record an entry changed: its folder and uuid, `ADDED | REMOVED | CHANGED`, and its name. */
@Serializable
internal data class HistoryRecordDto(val folder: String, val uuid: String, val change: String, val label: String)

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
