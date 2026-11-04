package uk.me.cormack.lighting7.history

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.mcp.CODE_REMOTE_SCRIPTS_DISABLED
import uk.me.cormack.lighting7.mcp.RemoteScriptsDisabledException
import uk.me.cormack.lighting7.plugins.showTakes
import uk.me.cormack.lighting7.routes.CODE_HISTORY_CONFIRM
import uk.me.cormack.lighting7.routes.CODE_HISTORY_CONFLICT
import uk.me.cormack.lighting7.routes.CODE_HISTORY_EMPTY
import uk.me.cormack.lighting7.routes.CODE_HISTORY_UNDO_BLOCKED
import uk.me.cormack.lighting7.routes.CODE_HISTORY_UNKNOWN
import uk.me.cormack.lighting7.routes.CODE_VENUE_ADMIN_ONLY
import uk.me.cormack.lighting7.state.State

/**
 * One undo or redo as a door asks for it (undo plan §3.5, D1, D2). With no [entryId] it is the key's
 * choice for the caller: the newer of the live programmer's top step and the caller's newest show
 * entry, or the step alone with [programmerOnly]. An [entryId] names a step (`p-…`) or a show entry
 * (`e-…`). [fadeMs] is the programmer fade a step is put back at.
 */
internal data class HistoryMove(
    val undo: Boolean,
    val entryId: String? = null,
    val programmerOnly: Boolean = false,
    val backToHere: Boolean = false,
    val confirm: Boolean = false,
    val fadeMs: Long = 0,
)

/** What [moveHistory] did, for the door to answer in its own words. */
internal sealed interface HistoryMoveResult {
    /** The programmer moved: the steps undone newest first, or redone in the order redone. */
    data class Steps(val steps: List<ProgrammerHistory.StepInfo>) : HistoryMoveResult

    /** A show entry was asked for: the restorer's answer, [RecordRestorer.Outcome.Done] when it moved. */
    data class Show(val outcome: RecordRestorer.Outcome) : HistoryMoveResult

    /** The key found nothing to take. */
    data object Empty : HistoryMoveResult

    /** The step [entryId] names is not in the live programmer's history. */
    data class UnknownStep(val entryId: String) : HistoryMoveResult

    /** A step asked for on a project that is not the current one: only its programmer has steps. */
    data object NotLive : HistoryMoveResult

    /** An operator's show undo or redo on a venue (D12), which only an admin may change. */
    data object VenueAdminOnly : HistoryMoveResult
}

/**
 * Undo or redo on [projectId] for [caller], from any door: the REST routes, a surface's buttons, Claude's
 * `undo_changes`. [operator] is whether the caller is a signed-in operator, refused a show entry on a
 * venue; [scriptsAllowed] is the remote script gate a script or FX definition put back is held to.
 */
internal suspend fun moveHistory(
    state: State,
    projectId: Int,
    caller: HistorySource,
    move: HistoryMove,
    window: String? = null,
    operator: Boolean = false,
    scriptsAllowed: Boolean = true,
): HistoryMoveResult {
    val live = state.projectManager.currentProjectOrNull?.id?.value == projectId
    val undo = move.undo
    val showEntry = when {
        move.programmerOnly -> false
        move.entryId != null -> !move.entryId.startsWith(STEP_PREFIX)
        else -> {
            val step = if (live) state.show.programmerHistory.frame().let { if (undo) it.undo else it.redo }.firstOrNull() else null
            val entry = if (undo) state.history.topFor(projectId, caller.key) else state.history.redoFor(projectId, caller.key)
            showTakes(step, entry, redo = !undo)
        }
    }
    if (!showEntry) {
        if (!live) {
            // The key on a project with no show entry of the caller's has nothing to take; a step is asked for by name.
            return if (move.entryId == null && !move.programmerOnly) HistoryMoveResult.Empty else HistoryMoveResult.NotLive
        }
        val history = state.show.programmerHistory
        val fadeMs = move.fadeMs.coerceIn(0, MAX_HISTORY_FADE_MS)
        val steps = if (undo) history.undo(move.entryId, fadeMs, caller) else history.redo(move.entryId, fadeMs, caller)
        return when {
            steps != null -> HistoryMoveResult.Steps(steps)
            move.entryId == null -> HistoryMoveResult.Empty
            else -> HistoryMoveResult.UnknownStep(move.entryId)
        }
    }
    val kind = transaction(state.database) { DaoProject.findById(projectId)?.kind }
    if (kind == ProjectKind.VENUE && operator) return HistoryMoveResult.VenueAdminOnly
    val restorer = RecordRestorer(state)
    return HistoryMoveResult.Show(
        if (undo) {
            restorer.undo(projectId, move.entryId, caller, window, move.backToHere, move.confirm, scriptsAllowed)
        } else {
            restorer.redo(projectId, move.entryId, caller, window, move.confirm, scriptsAllowed)
        },
    )
}

private const val STEP_PREFIX = "p-"

private const val MAX_HISTORY_FADE_MS = 60_000L

/**
 * Why an undo or redo did not happen, in the words and codes every door answers it with (undo plan
 * §3.7): the route as HTTP ([kind] picks the status), Claude's `undo_changes` as a tool error, a surface
 * as a log line. [later], [moves], [record] and [reason] are a conflict's, a confirm's and a block's.
 */
internal data class HistoryRefusal(
    val kind: Kind,
    val message: String,
    val code: String?,
    val later: List<HistoryEntry> = emptyList(),
    val moves: List<String> = emptyList(),
    val record: String? = null,
    val reason: String? = null,
) {
    enum class Kind { EMPTY, UNKNOWN, NOT_LIVE, VENUE_ADMIN_ONLY, CONFLICT, CONFIRM, BLOCKED, SCRIPTS_REFUSED }
}

/** This result's refusal, or null when something moved. [entryId] is the one the move asked for. */
internal fun HistoryMoveResult.refusal(undo: Boolean, entryId: String?): HistoryRefusal? {
    val what = if (undo) "undo" else "redo"
    return when (this) {
        is HistoryMoveResult.Steps -> null
        HistoryMoveResult.Empty -> HistoryRefusal(HistoryRefusal.Kind.EMPTY, "Nothing to $what", CODE_HISTORY_EMPTY)
        HistoryMoveResult.NotLive -> HistoryRefusal(HistoryRefusal.Kind.NOT_LIVE, "Only the current project's programmer can be ${what}ne", null)
        is HistoryMoveResult.UnknownStep ->
            HistoryRefusal(HistoryRefusal.Kind.UNKNOWN, "No step ${this.entryId} to $what", CODE_HISTORY_UNKNOWN)
        HistoryMoveResult.VenueAdminOnly ->
            HistoryRefusal(HistoryRefusal.Kind.VENUE_ADMIN_ONLY, "Only an admin can change a venue", CODE_VENUE_ADMIN_ONLY)
        is HistoryMoveResult.Show -> when (val outcome = outcome) {
            is RecordRestorer.Outcome.Done -> null
            is RecordRestorer.Outcome.Empty -> HistoryRefusal(
                HistoryRefusal.Kind.EMPTY,
                when {
                    !outcome.already -> "Nothing to $what"
                    undo -> "That change is already undone"
                    else -> "That change is not undone"
                },
                CODE_HISTORY_EMPTY,
            )
            RecordRestorer.Outcome.Unknown -> HistoryRefusal(HistoryRefusal.Kind.UNKNOWN, "No change $entryId to $what", CODE_HISTORY_UNKNOWN)
            is RecordRestorer.Outcome.Conflict -> HistoryRefusal(
                HistoryRefusal.Kind.CONFLICT,
                if (outcome.later.isEmpty()) {
                    "“${outcome.entry.label}” has been changed since, outside the history"
                } else {
                    "“${outcome.entry.label}” has been changed since by ${outcome.later.size} later ${if (outcome.later.size == 1) "change" else "changes"}"
                },
                CODE_HISTORY_CONFLICT,
                later = outcome.later,
            )
            is RecordRestorer.Outcome.Confirm -> HistoryRefusal(
                HistoryRefusal.Kind.CONFIRM,
                "This $what moves the stage: ${outcome.moves.joinToString("; ")}",
                CODE_HISTORY_CONFIRM,
                moves = outcome.moves,
            )
            is RecordRestorer.Outcome.Blocked -> HistoryRefusal(
                HistoryRefusal.Kind.BLOCKED,
                "${outcome.record} cannot be put back: ${outcome.reason}",
                CODE_HISTORY_UNDO_BLOCKED,
                record = outcome.record,
                reason = outcome.reason,
            )
            RecordRestorer.Outcome.ScriptsRefused -> HistoryRefusal(
                HistoryRefusal.Kind.SCRIPTS_REFUSED,
                RemoteScriptsDisabledException().message.orEmpty(),
                CODE_REMOTE_SCRIPTS_DISABLED,
            )
        }
    }
}
