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
 * Per-device, per-control soft-takeover state machine. Non-motor faders enter a "pickup"
 * state when the logical (application-side) value diverges from their last-known physical
 * position — inbound fader events are ignored until the physical value crosses the logical
 * value, at which point the fader re-engages and subsequent events write live.
 *
 * Motor faders don't need soft takeover: the motor drives the fader back to match the
 * logical value automatically, so [BindingTakeoverPolicy.IMMEDIATE] applies. Non-motor
 * faders default to [BindingTakeoverPolicy.PICKUP], with per-binding override supported via
 * the persisted takeover policy column.
 *
 * Keyed by `(displayKey, controlId)`. Thread-safety is provided by [ConcurrentHashMap];
 * transitions are not strictly atomic across two operations but the window is narrow and
 * any race would at worst suppress one fader event that was already about to be processed.
 *
 * Emits [PickupStateChange] on [changes] so WebSocket consumers can surface a pickup
 * indicator on the frontend — the `/surfaces` view and operator badges.
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
        /** Target logical value to cross in AWAITING_PICKUP state. Null when ENGAGED. */
        val target: UByte?,
    )

    data class PickupStateChange(
        val displayKey: String,
        val controlId: String,
        val state: State,
        val target: UByte?,
    )

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

    /**
     * Called by the router on every inbound fader event. Returns true if the value should be
     * applied (ENGAGED) or false if pickup has not yet occurred (AWAITING_PICKUP).
     *
     * Always updates `lastPhysical` so subsequent calls to [setLogical] can detect
     * divergence correctly.
     */
    fun acceptInboundFader(
        displayKey: String,
        controlId: String,
        value: UByte,
        policy: BindingTakeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
    ): Boolean {
        if (policy == BindingTakeoverPolicy.IMMEDIATE) {
            entries[key(displayKey, controlId)] = Entry(State.ENGAGED, value, null)
            return true
        }
        val k = key(displayKey, controlId)
        val current = entries[k] ?: DEFAULT
        return when (current.state) {
            State.ENGAGED -> {
                entries[k] = current.copy(lastPhysical = value)
                true
            }
            State.AWAITING_PICKUP -> {
                val target = current.target
                if (target != null && crossed(current.lastPhysical, value, target)) {
                    entries[k] = Entry(State.ENGAGED, value, null)
                    _changes.tryEmit(PickupStateChange(displayKey, controlId, State.ENGAGED, null))
                    true
                } else {
                    entries[k] = current.copy(lastPhysical = value)
                    false
                }
            }
        }
    }

    /**
     * Record the logical value the control is bound to. If the physical position is unknown
     * or differs from the logical by more than one step (tolerance for MIDI jitter), enter
     * [State.AWAITING_PICKUP] when [policy] is [BindingTakeoverPolicy.PICKUP]. Otherwise the
     * control is [State.ENGAGED].
     *
     * On [BindingTakeoverPolicy.IMMEDIATE] the motor drives to the logical value; the stored
     * `lastPhysical` stays at its prior reading (the motor itself will update the physical
     * position before the next inbound event, so no assumption is made here).
     *
     * No-ops when the resulting entry equals what's already stored — avoids allocation and
     * spurious emissions on every DMX tick when the logical value hasn't moved.
     */
    fun setLogical(
        displayKey: String,
        controlId: String,
        value: UByte,
        policy: BindingTakeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
    ) {
        val k = key(displayKey, controlId)
        val current = entries[k] ?: DEFAULT
        val next = if (policy == BindingTakeoverPolicy.IMMEDIATE) {
            Entry(State.ENGAGED, current.lastPhysical, null)
        } else {
            val physical = current.lastPhysical
            val diverges = physical == null || abs(physical.toInt() - value.toInt()) > 1
            if (diverges) Entry(State.AWAITING_PICKUP, physical, value)
            else Entry(State.ENGAGED, physical, null)
        }
        if (current == next) return
        entries[k] = next
        if (current.state != next.state || current.target != next.target) {
            _changes.tryEmit(PickupStateChange(displayKey, controlId, next.state, next.target))
        }
    }

    /**
     * Force the control into [State.AWAITING_PICKUP] regardless of current physical position.
     * Used on bank switch — bindings in the new bank start uncertain until the user moves
     * the fader across the new logical value.
     */
    fun forcePickup(
        displayKey: String,
        controlId: String,
        target: UByte,
    ) {
        val k = key(displayKey, controlId)
        val current = entries[k] ?: DEFAULT
        val next = Entry(State.AWAITING_PICKUP, current.lastPhysical, target)
        entries[k] = next
        if (current.state != State.AWAITING_PICKUP || current.target != target) {
            _changes.tryEmit(PickupStateChange(displayKey, controlId, State.AWAITING_PICKUP, target))
        }
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
    }
}
