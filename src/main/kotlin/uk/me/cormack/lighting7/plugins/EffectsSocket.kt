package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.EffectsService
import java.time.Instant

/**
 * The `effects.*` family (stage-view plan session 9, P2): three outbound frames and **no inbound**.
 * Arm, fire and reload are REST (`routes/projectEffects.kt`), so the socket gains no operation —
 * `FU-AUTH-WS-PER-MESSAGE` stays unfired and the remote listener's refusal lives in one place. A
 * socket only ever *reports* the desk's one-shot effects.
 */
@Serializable
sealed class EffectsOutMessage : OutMessage()

/** One spent tube: fired on this machine and not reloaded since. */
@Serializable
data class SpentTubeDto(val fixture: String, val trigger: String, val spentAt: String)

/**
 * The desk's arm, and every spent tube on this machine. `StateFlow`-backed, so the subscription is
 * the connect snapshot and every change is a whole frame.
 *
 * [remainingMs] is how long the arm had left **when this frame was sent**, beside the absolute
 * [armedUntil]: a client counts down from its own receive time, as it animates a cue's fade, so a
 * tablet with a skewed clock shows the right number. [rehearsal] is a blind programmer: every fire
 * is rehearsed and the cannons' arm channels are held down.
 */
@Serializable
@SerialName("effects.armed")
data class EffectsArmedOutMessage(
    val armed: Boolean = false,
    val armedUntil: String? = null,
    val remainingMs: Long? = null,
    val rehearsal: Boolean = false,
    val spent: List<SpentTubeDto> = emptyList(),
    val projectId: Int? = null,
) : EffectsOutMessage()

/**
 * One tube fired — or, with [rehearsed], rehearsed: announced to every window, which draws the
 * burst, while nothing reached the wire. [at] is when the desk fired it.
 */
@Serializable
@SerialName("effects.fired")
data class EffectsFiredOutMessage(
    val fixture: String,
    val fixtureName: String,
    val trigger: String,
    val label: String,
    val at: String,
    val rehearsed: Boolean = false,
    /** `cue`, `panel` or `surface`. */
    val source: String,
    val cueId: Int? = null,
) : EffectsOutMessage()

/** One tube a skip names. */
@Serializable
data class SkippedTubeDto(val fixture: String, val trigger: String)

/**
 * Fires that did **not** happen, said so — a cue's events on an unarmed desk (one frame for the
 * whole cue), a spent tube a cue event reached, an arm that dropped before an event's offset, a
 * surface's fire on an unarmed desk. Never queued: this is the whole of what becomes of them.
 */
@Serializable
@SerialName("effects.skipped")
data class EffectsSkippedOutMessage(
    /** `UNARMED`, `SPENT`, `ARM_DROPPED` or `UNKNOWN_TRIGGER`. */
    val reason: String,
    val message: String,
    val tubes: List<SkippedTubeDto> = emptyList(),
    val source: String,
    val cueId: Int? = null,
    val cueLabel: String? = null,
) : EffectsOutMessage()

internal fun EffectsService.ArmState.toMessage(nowMs: Long = System.currentTimeMillis()) = EffectsArmedOutMessage(
    armed = armed,
    armedUntil = armedUntilMs?.let { Instant.ofEpochMilli(it).toIsoUtc() },
    remainingMs = armedUntilMs?.let { (it - nowMs).coerceAtLeast(0) },
    rehearsal = rehearsal,
    spent = spent.map { SpentTubeDto(it.fixtureKey, it.trigger, it.spentAt.toIsoUtc()) },
    projectId = projectId,
)

internal fun EffectsService.Fired.toMessage() = EffectsFiredOutMessage(
    fixture = fixtureKey,
    fixtureName = fixtureName,
    trigger = trigger,
    label = label,
    at = at.toIsoUtc(),
    rehearsed = rehearsed,
    source = source.name.lowercase(),
    cueId = cueId,
)

internal fun EffectsService.Skipped.toMessage() = EffectsSkippedOutMessage(
    reason = reason.name,
    message = message,
    tubes = tubes.map { (fixture, trigger) -> SkippedTubeDto(fixture, trigger) },
    source = source.name.lowercase(),
    cueId = cueId,
    cueLabel = cueLabel,
)

/**
 * Registered in the **show band** of [configureSockets]: the cannons are the current project's.
 * `effects.armed` is a `StateFlow`, so it is its own snapshot; `effects.fired` and `effects.skipped`
 * are events with nothing to replay — a window that connects after a fire has nothing to draw.
 */
fun setupEffectsSubscriptions(scope: SocketScope) {
    val effects = scope.state.effectsService
    scope.subscribe(effects.armed) { scope.send(it.toMessage()) }
    scope.subscribe(effects.fired) { scope.send(it.toMessage()) }
    scope.subscribe(effects.skipped) { scope.send(it.toMessage()) }
}
