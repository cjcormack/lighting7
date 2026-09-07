package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** What a button's LED has been told. [NONE] when the control has no LED-bearing binding. */
@Serializable
enum class LedState {
    @SerialName("on") ON,
    @SerialName("off") OFF,
    @SerialName("none") NONE,
}

/**
 * What an encoder's ring shows: a value ([ON]), its off state for mixed / unbound / no selection
 * ([OFF]), or nothing because the control has no ring or no binding ([NONE]).
 */
@Serializable
enum class RingState {
    @SerialName("on") ON,
    @SerialName("off") OFF,
    @SerialName("none") NONE,
}

/**
 * One control's state as the hardware has been told it — the row of the `surfaceControls.*`
 * stream. [value] is the fed-back 7-bit position, null for mixed, unbound or no selection;
 * [physical] is the last inbound position of a fader, which a non-motor fader's operator wants
 * to see beside its pickup target because the hardware is never told it.
 */
@Serializable
data class ControlState(
    val value: Int? = null,
    val physical: Int? = null,
    val touched: Boolean = false,
    val led: LedState = LedState.NONE,
    val ring: RingState = RingState.NONE,
) {
    companion object {
        val UNBOUND = ControlState()
    }
}

/**
 * The per-control state of every attached surface, written by [SurfaceFeedbackPublisher] at its
 * send sites and read by the `surfaceControls.state` / `.changed` socket family. The screen
 * draws *what the hardware was told*, never a recomputation from DMX: if the picture and the
 * desk disagree, the publisher is wrong, which is the bug worth finding
 * (`docs/plans/completed/midi-surface-plan.md` D7).
 *
 * Writes arrive from the ArtNet transmit thread, the router's coroutine and the publisher's
 * subscribers, so the store is lock-free ([ConcurrentHashMap.compute]) like the takeover machine.
 * Changes are conflated per device: a write marks the control dirty and wakes the flusher, which
 * waits [flushIntervalMs] and emits one [Delta] per dirty device carrying each dirty control's
 * *current* state — a fader dragged through twenty values in one window is one row. A [Snapshot]
 * is the whole device, emitted by [publishSnapshot] after a full resync and read by
 * [snapshot] for the connect burst.
 */
class ControlStateTracker(private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS) {

    companion object {
        /** ~20 Hz per device: the delta cadence the view animates at. */
        const val DEFAULT_FLUSH_INTERVAL_MS = 50L
    }

    /** Every control of one device — the connect frame, and the frame after a full resync. */
    data class Snapshot(val displayKey: String, val controls: Map<String, ControlState>)

    /** The controls of one device that changed since the last flush, at their current state. */
    data class Delta(val displayKey: String, val controls: Map<String, ControlState>)

    private val states = ConcurrentHashMap<String, ConcurrentHashMap<String, ControlState>>()
    private val dirty = ConcurrentHashMap<String, MutableSet<String>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _deltas = MutableSharedFlow<Delta>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deltas: SharedFlow<Delta> = _deltas.asSharedFlow()

    private val _snapshots = MutableSharedFlow<Snapshot>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snapshots: SharedFlow<Snapshot> = _snapshots.asSharedFlow()

    private var flusher: Job? = null

    fun start(scope: CoroutineScope) {
        if (flusher != null) return
        flusher = scope.launch(CoroutineName("ControlStateTracker-flush")) {
            for (unit in wake) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    fun stop() {
        flusher?.cancel()
        flusher = null
    }

    /**
     * Seed [displayKey] with every profile control at its unbound default, keeping what is a
     * physical fact rather than a binding's doing — `touched` and `physical` survive a resync.
     * Emits nothing: the caller drives the hardware and then calls [publishSnapshot].
     */
    fun reset(displayKey: String, controlIds: Collection<String>) {
        // In place, never a swap: a write racing this on another thread must land in the map
        // the next flush reads, not in one that has just been discarded.
        val device = states.getOrPut(displayKey) { ConcurrentHashMap() }
        val keep = controlIds.toHashSet()
        for (id in controlIds) {
            device.compute(id) { _, old ->
                if (old == null) ControlState.UNBOUND
                else ControlState(physical = old.physical, touched = old.touched)
            }
        }
        device.keys.retainAll(keep)
        dirty.remove(displayKey)
    }

    /** Forget a detached device; an empty snapshot tells the view to drop its picture. */
    fun remove(displayKey: String) {
        states.remove(displayKey)
        dirty.remove(displayKey)
        _snapshots.tryEmit(Snapshot(displayKey, emptyMap()))
    }

    /** The device's whole state, or null for a device this tracker has never seen. */
    fun snapshot(displayKey: String): Snapshot? =
        states[displayKey]?.let { Snapshot(displayKey, it.toMap()) }

    /** Every device the tracker holds — the connect burst. */
    fun snapshots(): List<Snapshot> = states.keys.mapNotNull { snapshot(it) }

    /** Emit the device's whole state and drop any pending delta, which it subsumes. */
    fun publishSnapshot(displayKey: String) {
        dirty.remove(displayKey)
        snapshot(displayKey)?.let { _snapshots.tryEmit(it) }
    }

    fun update(displayKey: String, controlId: String, transform: (ControlState) -> ControlState) {
        val device = states.getOrPut(displayKey) { ConcurrentHashMap() }
        var changed = false
        device.compute(controlId) { _, current ->
            val before = current ?: ControlState.UNBOUND
            val after = transform(before)
            changed = after != before
            after
        }
        if (!changed) return
        dirty.getOrPut(displayKey) { ConcurrentHashMap.newKeySet() }.add(controlId)
        wake.trySend(Unit)
    }

    fun setValue(displayKey: String, controlId: String, value: Int?, ring: RingState) =
        update(displayKey, controlId) { it.copy(value = value, ring = ring) }

    fun setPhysical(displayKey: String, controlId: String, physical: Int) =
        update(displayKey, controlId) { it.copy(physical = physical) }

    fun setTouched(displayKey: String, controlId: String, touched: Boolean) =
        update(displayKey, controlId) { it.copy(touched = touched) }

    fun setLed(displayKey: String, controlId: String, led: LedState) =
        update(displayKey, controlId) { it.copy(led = led) }

    /** Drain every dirty device now, without waiting for the flusher. */
    internal fun flushForTest() = flush()

    private fun flush() {
        for (displayKey in dirty.keys.toList()) {
            // Drain the set rather than removing the key: a writer that fetched the set before
            // this pass adds into a set the next pass still sees, instead of an orphan nothing
            // ever flushes. The state is read *after* the ids are cleared, so a write that lands
            // in between is either carried here or re-marked for the next pass — never lost.
            val set = dirty[displayKey] ?: continue
            val ids = set.toList()
            if (ids.isEmpty()) continue
            set.removeAll(ids.toSet())
            val device = states[displayKey] ?: continue
            val rows = HashMap<String, ControlState>(ids.size)
            for (id in ids) device[id]?.let { rows[id] = it }
            if (rows.isNotEmpty()) _deltas.tryEmit(Delta(displayKey, rows))
        }
    }
}
