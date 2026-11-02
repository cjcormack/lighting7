package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.combine
import uk.me.cormack.lighting7.history.HistoryEntry
import uk.me.cormack.lighting7.history.HistoryService
import uk.me.cormack.lighting7.history.ProgrammerHistory
import uk.me.cormack.lighting7.models.toIsoUtc
import java.time.Instant

/**
 * `history.state` (undo plan §3.7, D13): what ⌘Z and ⇧⌘Z would take, outbound only — undo and redo are
 * REST. Per socket, for its source (D1): the newer of the programmer's top step, which is anyone's, and
 * the source's own newest show entry on the programmer's project. `revision` is the programmer's and
 * `showRevision` the show history's; the History sheet reads its page again when either moves.
 */

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class HistoryOutMessage : OutMessage()

/** Who a step belongs to, as the History sheet names it. */
@Serializable
data class HistorySourceDto(val kind: String, val name: String, val window: String? = null)

/** One step, as the programmer's steps menu lists it. */
@Serializable
data class HistoryStepDto(val id: String, val label: String, val at: String, val source: HistorySourceDto)

/** What the key would take: the step's id, its label and which history it is in. */
@Serializable
data class HistoryTopDto(val id: String, val label: String, val kind: String)

/** The programmer's steps, newest first, and those undone, next to redo first. */
@Serializable
data class ProgrammerHistoryDto(val undo: List<HistoryStepDto>, val redo: List<HistoryStepDto>)

/** The live show's history: what ⌘Z and ⇧⌘Z take, the programmer's steps, and a revision that moves with any change. */
@Serializable
@SerialName("history.state")
data class HistoryStateOutMessage(
    val projectId: Int?,
    val revision: Long,
    /** The show history's revision, on every project: it only ever moves. */
    val showRevision: Long = 0,
    val undo: HistoryTopDto? = null,
    val redo: HistoryTopDto? = null,
    val programmer: ProgrammerHistoryDto,
) : HistoryOutMessage()

internal fun ProgrammerHistory.StepInfo.toDto() = HistoryStepDto(
    id = id,
    label = label,
    at = Instant.ofEpochMilli(at).toIsoUtc(),
    source = HistorySourceDto(source.kind.name, source.name, window),
)

/**
 * The frame for [sourceKey] on [projectId] (the live project, else the programmer's): the programmer's
 * steps, and as the key's tops the newer of the programmer's and [history]'s for that source. With no
 * [history] the tops are the programmer's alone.
 */
internal fun ProgrammerHistory.Frame.toMessage(
    history: HistoryService? = null,
    sourceKey: String? = null,
    projectId: Int? = this.projectId,
): HistoryStateOutMessage {
    val showUndo = if (history != null && sourceKey != null && projectId != null) history.topFor(projectId, sourceKey) else null
    val showRedo = if (history != null && sourceKey != null && projectId != null) history.redoFor(projectId, sourceKey) else null
    val stepUndo = undo.firstOrNull()
    val stepRedo = redo.firstOrNull()
    return HistoryStateOutMessage(
        projectId = projectId,
        revision = revision,
        showRevision = history?.revision?.value ?: 0,
        undo = newer(stepUndo, showUndo),
        redo = newer(stepRedo, showRedo),
        programmer = ProgrammerHistoryDto(undo.map { it.toDto() }, redo.map { it.toDto() }),
    )
}

private fun newer(step: ProgrammerHistory.StepInfo?, entry: HistoryEntry?): HistoryTopDto? = when {
    entry != null && (step == null || entry.lastAt >= step.at) -> HistoryTopDto(entry.id, entry.label, SHOW_KIND)
    step != null -> HistoryTopDto(step.id, step.label, PROGRAMMER_KIND)
    else -> null
}

private const val PROGRAMMER_KIND = "PROGRAMMER"
private const val SHOW_KIND = "SHOW"

// ─── Subscriptions ──────────────────────────────────────────────────────

/**
 * `StateFlow`-backed, so the subscription is the connect snapshot; it outlives a project switch. The
 * socket's source is its account's, read once: a socket never changes who it is signed in as.
 */
fun setupHistorySubscriptions(scope: SocketScope) {
    val state = scope.state
    val sourceKey = historySourceOf(state, scope.user).key
    scope.subscribe(combine(state.programmerHistoryFrame, state.history.revision) { frame, _ -> frame }) { frame ->
        // The programmer's project, so the steps and the show entry are one project's even mid-switch.
        val project = frame.projectId ?: state.projectManager.currentProjectOrNull?.id?.value
        scope.send(frame.toMessage(state.history, sourceKey, project))
    }
}
