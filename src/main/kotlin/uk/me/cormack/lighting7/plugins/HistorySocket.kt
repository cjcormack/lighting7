package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.history.ProgrammerHistory
import uk.me.cormack.lighting7.models.toIsoUtc
import java.time.Instant

/**
 * `history.state` (undo plan §3.7, D13): what ⌘Z and ⇧⌘Z would take, outbound only — undo and redo are
 * REST. While the history holds programmer steps alone (session 1) every socket reads the same frame;
 * the show half, per socket and per source, is session 3's.
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

internal fun ProgrammerHistory.Frame.toMessage() = HistoryStateOutMessage(
    projectId = projectId,
    revision = revision,
    undo = undo.firstOrNull()?.let { HistoryTopDto(it.id, it.label, PROGRAMMER_KIND) },
    redo = redo.firstOrNull()?.let { HistoryTopDto(it.id, it.label, PROGRAMMER_KIND) },
    programmer = ProgrammerHistoryDto(undo.map { it.toDto() }, redo.map { it.toDto() }),
)

private const val PROGRAMMER_KIND = "PROGRAMMER"

// ─── Subscriptions ──────────────────────────────────────────────────────

/** `StateFlow`-backed, so the subscription is the connect snapshot; it outlives a project switch. */
fun setupHistorySubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.programmerHistoryFrame) { scope.send(it.toMessage()) }
}
