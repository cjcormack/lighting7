package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.models.sceneryStateObject
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.SceneryService
import java.time.Instant

/**
 * The `scenery.*` family (stage-view plan session 8): one outbound frame, no inbound. Scenery is
 * moved by the records that own it — a cue's GO, a stack starting and stopping, a Look pressed — so a
 * socket only ever *reports* it.
 */
@Serializable
sealed class SceneryOutMessage : OutMessage()

/**
 * One element's live scenery: the states it is going to ([state]) and leaving ([from]), the move's
 * start and its length. Each state object holds only the states the element takes — `visible`, a
 * drawn drape's `open`, a flown piece's `trimM`.
 *
 * [elapsedMs] is how far into the move the desk was **when this frame was sent**, beside the
 * absolute [startedAt]: a client animates from its own receive time plus the elapsed, as it does a
 * cue's fade from `cueRunStateChanged`, so a tablet with a skewed clock does not replay a finished
 * move. Neither is clamped: a client reads `elapsedMs >= durationMs` as landed.
 */
@Serializable
data class SceneryEntryDto(
    val elementUuid: String,
    val state: JsonObject,
    val from: JsonObject,
    val startedAt: String,
    val elapsedMs: Long,
    val durationMs: Long,
)

/**
 * Every element a scenery change names, as the desk resolves it now: the connect snapshot and the
 * broadcast on every change. `StateFlow`-backed, so the subscription *is* the snapshot. An element
 * absent from [elements] shows its base. [projectId] is the project resolved, null before the show
 * is up.
 */
@Serializable
@SerialName("scenery.state")
data class SceneryStateOutMessage(
    val projectId: Int? = null,
    val elements: List<SceneryEntryDto> = emptyList(),
) : SceneryOutMessage()

internal fun SceneryService.Frame.toMessage(nowMs: Long = System.currentTimeMillis()) = SceneryStateOutMessage(
    projectId = projectId,
    elements = entries.map { it.toDto(nowMs) },
)

internal fun SceneryService.Entry.toDto(nowMs: Long) = SceneryEntryDto(
    elementUuid = elementUuid.toString(),
    state = sceneryStateObject(state),
    from = sceneryStateObject(from),
    startedAt = Instant.ofEpochMilli(startedAtMs).toIsoUtc(),
    elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0),
    durationMs = durationMs,
)

/**
 * Registered in the **show band** of [configureSockets]: the scenery is the current project's. The
 * frame is built at send time, so `elapsedMs` is fresh in the connect snapshot as in a broadcast.
 */
fun setupScenerySubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.sceneryService.frame) { scope.send(it.toMessage()) }
}
