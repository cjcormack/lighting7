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
 * moved by the records that own it — a cue's GO, a stack starting and stopping, a Look pressed — and
 * by the programmer's own scenery, which is written on the programmer's socket
 * (`programmer.setScenery`, scenery-programmer plan D1), so this family only ever *reports* it.
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
 *
 * [source] names what holds the piece there (scenery-programmer plan D4). Additive: a client that
 * does not read it draws exactly as before.
 */
@Serializable
data class SceneryEntryDto(
    val elementUuid: String,
    val state: JsonObject,
    val from: JsonObject,
    val startedAt: String,
    val elapsedMs: Long,
    val durationMs: Long,
    /** No default, so the desk's `encodeDefaults = false` Json always sends it — `base` included. */
    val source: ScenerySourceDto,
)

/**
 * What holds an element where it is — the source of its highest-tier state: `base`, `set` (a live
 * stack's set: [stackId], the stack's [name]), `cue` ([stackId], [cueId], the cue's [label]),
 * `cueLook` (a Look a live cue layers: [stackId], [lookId], its [name]), `programmerLook` (pressed
 * or on a pad: [lookId], [name]) or `programmer` (the operator's own hands). Absent fields are the
 * kind's nothing.
 */
@Serializable
data class ScenerySourceDto(
    val kind: String,
    val stackId: Int? = null,
    val cueId: Int? = null,
    val label: String? = null,
    val lookId: Int? = null,
    val name: String? = null,
)

/**
 * One element as it would be on leaving Blind (scenery-programmer plan D12): the state the
 * programmer stages, the state it moves from (live, or an earlier staged move as drawn), and the move
 * — [elapsedMs] at send, as [SceneryEntryDto.elapsedMs] is. Listed only where it differs from live.
 */
@Serializable
data class SceneryStagedEntryDto(
    val elementUuid: String,
    val state: JsonObject,
    val from: JsonObject,
    val elapsedMs: Long,
    val durationMs: Long,
)

/**
 * Every element a scenery change names or the programmer holds, as the desk resolves it now: the
 * connect snapshot and the broadcast on every change. `StateFlow`-backed, so the subscription *is*
 * the snapshot. An element absent from [elements] shows its base. [projectId] is the project
 * resolved, null before the show is up.
 *
 * [staged] is present only while the programmer is blind **and** holds something that would move a
 * piece (D12) — the Stage view's Output + Programmer and Programmer sources draw it, Output draws
 * [elements]. Absent rather than empty otherwise, and both it and [SceneryEntryDto.source] are
 * optional on the client, so an older client that reads neither parses the frame exactly as before
 * (scenery-programmer plan P3).
 */
@Serializable
@SerialName("scenery.state")
data class SceneryStateOutMessage(
    val projectId: Int? = null,
    val elements: List<SceneryEntryDto> = emptyList(),
    val staged: List<SceneryStagedEntryDto>? = null,
) : SceneryOutMessage()

internal fun SceneryService.Frame.toMessage(nowMs: Long = System.currentTimeMillis()) = SceneryStateOutMessage(
    projectId = projectId,
    elements = entries.map { it.toDto(nowMs) },
    staged = staged?.map { it.toStagedDto(nowMs) },
)

internal fun SceneryService.Entry.toDto(nowMs: Long) = SceneryEntryDto(
    elementUuid = elementUuid.toString(),
    state = sceneryStateObject(state),
    from = sceneryStateObject(from),
    startedAt = Instant.ofEpochMilli(startedAtMs).toIsoUtc(),
    elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0),
    durationMs = durationMs,
    source = ScenerySourceDto(source.kind, source.stackId, source.cueId, source.label, source.lookId, source.name),
)

internal fun SceneryService.Entry.toStagedDto(nowMs: Long) = SceneryStagedEntryDto(
    elementUuid = elementUuid.toString(),
    state = sceneryStateObject(state),
    from = sceneryStateObject(from),
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
