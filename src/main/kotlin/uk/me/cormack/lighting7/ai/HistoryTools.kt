package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.history.HistoryEntry
import uk.me.cormack.lighting7.history.HistoryMove
import uk.me.cormack.lighting7.history.HistoryMoveResult
import uk.me.cormack.lighting7.history.HistoryRefusal
import uk.me.cormack.lighting7.history.refusal
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.history.RecordRestorer
import uk.me.cormack.lighting7.history.moveHistory
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.plugins.historySourceOf
import uk.me.cormack.lighting7.routes.CODE_HISTORY_EMPTY
import uk.me.cormack.lighting7.routes.CODE_HISTORY_UNKNOWN
import uk.me.cormack.lighting7.state.State
import java.time.Instant

/** An entry of another source's, which `undo_changes` never takes (undo plan §3.5, D7). */
internal const val CODE_HISTORY_NOT_YOURS = "HISTORY_NOT_YOURS"

internal val listHistoryTool = ToolDef(
    name = "list_history",
    description = "List the changes you have made to a project's stored show through this connection, newest first: each " +
        "with the id undo_changes takes, its label, when, whether it is undone, and the records it added, removed or " +
        "changed. One tool call that stores something is one change. The operator sees these, beside everyone else's, in " +
        "the desk's History sheet. Programmer changes are not listed: there is one programmer, and its steps are the " +
        "operator's to undo.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "How many changes to list, 1–$MAX_LIST (default $DEFAULT_LIST).")
            }
            putJsonObject("projectId") {
                put("type", "integer")
                put("description", PROJECT_ID_DESCRIPTION)
            }
        }
    },
)

internal val undoChangesTool = ToolDef(
    name = "undo_changes",
    description = "Take back changes you made to a project's stored show (list_history lists them): the one entryId names, or " +
        "your newest count changes, newest first, stopping at the first that cannot be undone. Only your own changes: " +
        "another's is refused (HISTORY_NOT_YOURS). A change something has changed since is refused (HISTORY_CONFLICT, " +
        "naming the later changes) — the operator can undo it with those from History. An undo that would move the " +
        "running show (a Look a live cue uses, the patch, a park) is refused with what it moves (HISTORY_CONFIRM): tell " +
        "the operator, and call again with confirm: true only once they agree; confirm covers the first undo of the call " +
        "alone, so with count a later one that moves the show stops there, with what it moves. Records come back exactly, ids kept.",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("entryId") {
                put("type", "string")
                put("description", "The change to undo, an id from list_history. Give this or count.")
            }
            putJsonObject("count") {
                put("type", "integer")
                put("description", "Undo your newest count changes not yet undone, 1–$MAX_COUNT. Default 1 when entryId is absent.")
            }
            putJsonObject("confirm") {
                put("type", "boolean")
                put("description", "Undo the first change of this call even though it moves the running show — the moves a refusal named. Only after the operator has agreed. Default false.")
            }
            putJsonObject("projectId") {
                put("type", "integer")
                put("description", PROJECT_ID_DESCRIPTION)
            }
        }
    },
)

/**
 * `list_history` and `undo_changes` (undo plan D7, §3.7, §4.9): Claude's own show entries, by the calling
 * grant's source, on the current project or the one `projectId` names. Show entries only — the
 * programmer is one, and its steps are not Claude's to take back. Held to the remote script gate as the
 * undo route is: MCP is always remote.
 */
internal class HistoryTools(private val state: State) {

    fun listHistory(input: JsonObject, target: ToolProject, user: AuthenticatedUser?): ToolExecutionResult {
        val source = sourceOf(user) ?: return notOverMcp()
        val limitRaw = input["limit"]
        val limit = if (limitRaw == null) DEFAULT_LIST else (limitRaw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (limit == null || limit !in 1..MAX_LIST) return failure("limit must be an integer from 1 to $MAX_LIST")
        val projectId = target.project.id.value
        val mine = state.history.entries(projectId).filter { it.source.key == source.key }
        val listed = mine.take(limit)
        return success(
            "${listed.size} of your ${mine.size} change(s)",
            buildJsonObject {
                put("projectId", projectId)
                putJsonArray("changes") { listed.forEach { addJsonObject { putEntry(projectId, it) } } }
                put("more", mine.size > listed.size)
            },
        )
    }

    suspend fun undoChanges(input: JsonObject, target: ToolProject, user: AuthenticatedUser?): ToolExecutionResult {
        val source = sourceOf(user) ?: return notOverMcp()
        val entryRaw = input["entryId"]
        val entryId = (entryRaw as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        if (entryRaw != null && entryId == null) return failure("entryId must be a change's id from list_history, a non-empty string")
        val countRaw = input["count"]
        val count = (countRaw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (countRaw != null && (count == null || count !in 1..MAX_COUNT)) return failure("count must be an integer 1–$MAX_COUNT")
        val confirmRaw = input["confirm"]
        val confirm = (confirmRaw as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (confirmRaw != null && confirm == null) return failure("confirm must be true or false")
        if (entryId != null && count != null) return failure("give entryId or count, not both")
        val projectId = target.project.id.value

        val ids = if (entryId != null) {
            val entry = state.history.entriesById(listOf(entryId)).firstOrNull { it.projectId == projectId }
                ?: return coded("No change $entryId on this project (list_history lists yours)", CODE_HISTORY_UNKNOWN)
            if (entry.source.key != source.key) {
                return coded("“${entry.label}” is ${entry.source.name}'s change, not yours: the operator can undo it from History", CODE_HISTORY_NOT_YOURS)
            }
            if (entry.undone) return coded("“${entry.label}” is already undone", CODE_HISTORY_EMPTY)
            listOf(entryId)
        } else {
            null
        }

        val undone = mutableListOf<HistoryEntry>()
        repeat(ids?.size ?: count ?: 1) { i ->
            val next = ids?.get(i) ?: state.history.topFor(projectId, source.key)?.id
            if (next == null) {
                return if (undone.isEmpty()) coded("You have no change to undo on this project", CODE_HISTORY_EMPTY) else done(undone)
            }
            // The confirm answers the moves a refusal named, which were the first undo's alone.
            val result = moveHistory(
                state, projectId, source, HistoryMove(undo = true, entryId = next, confirm = confirm == true && i == 0),
                operator = user?.role != UserRole.ADMIN,
                scriptsAllowed = state.remotePolicy.allowScripts,
            )
            val outcome = (result as? HistoryMoveResult.Show)?.outcome
            if (outcome is RecordRestorer.Outcome.Done) {
                undone += outcome.entries
            } else {
                return refused(result, next, undone)
            }
        }
        return done(undone)
    }

    private fun sourceOf(user: AuthenticatedUser?): HistorySource? =
        user?.let { historySourceOf(it) }?.takeIf { it.kind == HistorySource.Kind.MCP }

    private fun JsonObjectBuilder.putEntry(projectId: Int, entry: HistoryEntry) {
        put("id", entry.id)
        put("label", entry.label)
        put("at", Instant.ofEpochMilli(entry.lastAt).toIsoUtc())
        put("undone", entry.undone)
        val counts = entry.counts()
        putJsonObject("counts") {
            put("added", counts.added)
            put("removed", counts.removed)
            put("changed", counts.changed)
        }
        putJsonArray("records") {
            entry.records.take(MAX_RECORDS).forEach { change ->
                addJsonObject {
                    put("folder", change.key.tableName)
                    put("change", change.kind.name)
                    put("label", state.history.describe(projectId, change))
                }
            }
        }
        if (entry.records.size > MAX_RECORDS) put("moreRecords", entry.records.size - MAX_RECORDS)
    }

    private fun done(undone: List<HistoryEntry>) = success(
        "Undid ${undone.size} change(s): ${undone.joinToString { "“${it.label}”" }}",
        buildJsonObject {
            putJsonArray("undone") { undone.forEach { addJsonObject { put("id", it.id); put("label", it.label) } } }
        },
    )

    /** A refusal in the route's words and codes, with what to do about it and what was undone before it. */
    private fun refused(result: HistoryMoveResult, entryId: String, undone: List<HistoryEntry>): ToolExecutionResult {
        val refusal = result.refusal(undo = true, entryId) ?: return done(undone)
        val before = if (undone.isEmpty()) "" else "Undid ${undone.joinToString { "“${it.label}”" }}, then stopped: "
        val advice = when (refusal.kind) {
            HistoryRefusal.Kind.CONFLICT -> if (refusal.later.isEmpty()) {
                "."
            } else {
                " (${refusal.later.joinToString { "“${it.label}”, ${it.source.name}" }}). The operator can undo it with those from History."
            }
            HistoryRefusal.Kind.CONFIRM -> ". Ask the operator, and call again with confirm: true if they agree."
            else -> ""
        }
        val message = before + refusal.message + advice
        return ToolExecutionResult(
            success = false,
            description = message,
            result = buildJsonObject {
                put("error", message)
                refusal.code?.let { put("code", it) }
                if (refusal.kind == HistoryRefusal.Kind.CONFLICT) {
                    putJsonArray("later") {
                        refusal.later.forEach { addJsonObject { put("id", it.id); put("label", it.label); put("source", it.source.name) } }
                    }
                }
                if (refusal.kind == HistoryRefusal.Kind.CONFIRM) putJsonArray("moves") { refusal.moves.forEach { add(it) } }
                refusal.record?.let { put("record", it) }
                refusal.reason?.let { put("reason", it) }
                if (undone.isNotEmpty()) putJsonArray("undone") { undone.forEach { addJsonObject { put("id", it.id); put("label", it.label) } } }
            }.toString(),
        )
    }

    private fun notOverMcp() = failure("list_history and undo_changes are for a Claude connection's own changes")

    private fun success(description: String, result: JsonObject) =
        ToolExecutionResult(success = true, description = description, result = result.toString())

    private fun failure(message: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject { put("error", message) }.toString(),
    )

    private fun coded(message: String, code: String) = ToolExecutionResult(
        success = false,
        description = message,
        result = buildJsonObject {
            put("error", message)
            put("code", code)
        }.toString(),
    )
}

private const val DEFAULT_LIST = 20
private const val MAX_LIST = 100
private const val MAX_COUNT = 20
private const val MAX_RECORDS = 10
