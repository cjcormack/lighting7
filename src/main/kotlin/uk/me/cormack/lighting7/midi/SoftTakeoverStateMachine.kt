package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * How one continuous control takes a move (surface-setup plan D7) — one rule for every fader and ring.
 *
 * - [DRIVEN]: the desk can put the control where the function stands — a motor fader, an encoder with
 *   a ring. A move within [SoftTakeoverStateMachine.JUMP_STEPS] of what it shows, or one made while it
 *   is touched, is taken; any other is ignored and the control is shown its value again, so a stale
 *   position the device held (a ring not yet fed, a fast flick) cannot jump the function.
 * - [UNDRIVEN]: a fader the desk cannot move picks up when it crosses the value it shows.
 * - [JUMP]: every move is taken: the binding asked for it (`takeover_policy` IMMEDIATE).
 */
enum class JumpPolicy {
    DRIVEN, UNDRIVEN, JUMP;

    companion object {
        /** [control]'s policy under [takeover]: PICKUP (the default) is driven or undriven by what the control is. */
        fun of(control: ControlDescriptor, takeover: BindingTakeoverPolicy?): JumpPolicy = when {
            takeover == BindingTakeoverPolicy.IMMEDIATE -> JUMP
            control is FaderDescriptor && control.hasMotor -> DRIVEN
            control is EncoderDescriptor && control.ringCc != null -> DRIVEN
            else -> UNDRIVEN
        }
    }
}

/**
 * The jump rule's state, per device and control ([JumpPolicy] says which arm a control is on).
 *
 * The desk records what each control **shows** ([setLogical]) and judges each inbound move against it
 * ([acceptInboundFader]). A mixed or absent value shows nothing, and then any move is taken: it is
 * meant to write every head and make them agree. After a move is taken, what the control shows stays
 * the physical position it was moved to while the function reads back to it — within the reading's
 * tolerance — so a motor never chases its own write rounded through the function and back.
 *
 * An undriven fader enters [State.AWAITING_PICKUP] when the value it shows diverges from where it
 * physically is, and leaves it when it crosses that value; [changes] reports those transitions so the
 * `/surfaces` view can draw a pickup indicator.
 *
 * Keyed by `(displayKey, controlId)`. Thread-safety is [ConcurrentHashMap]'s; a race between two
 * operations would at worst misjudge one move that was already in flight.
 */
class SoftTakeoverStateMachine {

    @Serializable
    enum class State {
        @SerialName("engaged") ENGAGED,
        @SerialName("awaitingPickup") AWAITING_PICKUP,
    }

    data class Entry(
        val state: State,
        /** Last-known physical (MIDI 7-bit) value from inbound events, or null if unknown. */
        val lastPhysical: UByte?,
        /** The value an undriven fader must cross in [State.AWAITING_PICKUP]. Null when ENGAGED. */
        val target: UByte?,
        /** What the control shows now; null for a mixed or absent value. */
        val shown: UByte? = null,
        /** The position of the last move taken, held while the function reads back to it. */
        val echo: UByte? = null,
    )

    data class PickupStateChange(
        val displayKey: String,
        val controlId: String,
        val state: State,
        val target: UByte?,
    )

    /** What [acceptInboundFader] decided. */
    enum class Verdict {
        /** Take the move. */
        ACCEPT,

        /** Drop it: an undriven fader still waiting to cross. */
        IGNORE,

        /** Drop it and show the control its value again: a driven control moved from a position the desk did not show it. */
        RESHOW,
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _changes = MutableSharedFlow<PickupStateChange>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<PickupStateChange> = _changes.asSharedFlow()

    private fun key(displayKey: String, controlId: String): String = "$displayKey|$controlId"

    /** Current state for a given control, defaulting to [State.ENGAGED] / no physical value. */
    fun stateFor(displayKey: String, controlId: String): Entry =
        entries[key(displayKey, controlId)] ?: DEFAULT

    /** Judge one inbound move at [value] under [policy]; [touched] for a touch-sensing control held by a finger. */
    fun acceptInboundFader(
        displayKey: String,
        controlId: String,
        value: UByte,
        policy: JumpPolicy,
        touched: Boolean = false,
    ): Verdict {
        val k = key(displayKey, controlId)
        val current = entries[k] ?: DEFAULT
        val taken = Entry(State.ENGAGED, value, null, shown = value, echo = value)
        val verdict = when (policy) {
            JumpPolicy.JUMP -> Verdict.ACCEPT
            JumpPolicy.DRIVEN -> {
                val shown = current.shown
                if (shown == null || touched || abs(value.toInt() - shown.toInt()) <= JUMP_STEPS) Verdict.ACCEPT
                else Verdict.RESHOW
            }
            JumpPolicy.UNDRIVEN -> {
                val target = current.target
                if (current.state == State.ENGAGED || (target != null && crossed(current.lastPhysical, value, target))) {
                    Verdict.ACCEPT
                } else {
                    Verdict.IGNORE
                }
            }
        }
        entries[k] = if (verdict == Verdict.ACCEPT) taken else current.copy(lastPhysical = value)
        if (verdict == Verdict.ACCEPT && current.state != State.ENGAGED) {
            _changes.tryEmit(PickupStateChange(displayKey, controlId, State.ENGAGED, null))
        }
        return verdict
    }

    /**
     * Record what the function reads back as, [value] null for mixed or nothing, within [tolerance];
     * answers what the control should be shown. A reading within tolerance of the last move taken
     * shows that move's position. Otherwise it shows [value], and an undriven fader whose physical
     * position diverges by more than a step waits to pick it up — always, with [rearm], since then
     * the physical position is stale (attach, a layer or project change). [reshow] shows [value]
     * whatever was last moved: the function is correcting the device.
     */
    fun setLogical(
        displayKey: String,
        controlId: String,
        value: UByte?,
        policy: JumpPolicy,
        tolerance: Int = 0,
        rearm: Boolean = false,
        reshow: Boolean = false,
    ): UByte? {
        val k = key(displayKey, controlId)
        val current = entries[k] ?: DEFAULT
        val echo = current.echo
        val next = when {
            value == null -> Entry(State.ENGAGED, current.lastPhysical, null)
            !rearm && !reshow && echo != null && abs(echo.toInt() - value.toInt()) <= maxOf(1, tolerance) -> current.copy(shown = echo)
            policy != JumpPolicy.UNDRIVEN -> Entry(State.ENGAGED, current.lastPhysical, null, shown = value)
            else -> {
                val physical = current.lastPhysical
                val diverges = rearm || physical == null || abs(physical.toInt() - value.toInt()) > 1
                if (diverges) Entry(State.AWAITING_PICKUP, physical, value, shown = value)
                else Entry(State.ENGAGED, physical, null, shown = value)
            }
        }
        if (current != next) {
            entries[k] = next
            if (current.state != next.state || current.target != next.target) {
                _changes.tryEmit(PickupStateChange(displayKey, controlId, next.state, next.target))
            }
        }
        return next.shown
    }

    fun clearDevice(displayKey: String) {
        val prefix = "$displayKey|"
        val removed = entries.keys.filter { it.startsWith(prefix) }
        for (k in removed) entries.remove(k)
    }

    fun clearAll() {
        entries.clear()
    }

    private fun crossed(last: UByte?, value: UByte, target: UByte): Boolean {
        val t = target.toInt()
        val v = value.toInt()
        if (last == null) return abs(v - t) <= 1
        val l = last.toInt()
        // Crossed from below or from above, inclusive of equality.
        return (l <= t && v >= t) || (l >= t && v <= t)
    }

    companion object {
        private val DEFAULT = Entry(State.ENGAGED, null, null)

        /** How far a driven control may move from what it shows and still be taken: a fast turn's few detents. */
        const val JUMP_STEPS = 8
    }
}
