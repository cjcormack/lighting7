package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import uk.me.cormack.lighting7.models.SpeedMasterSource
import java.util.UUID

/**
 * Parse a stored speed-master reference, or null when absent/garbled. Null means master 1,
 * so a corrupted reference degrades to the global tempo rather than failing the apply.
 */
fun speedMasterUuidOrNull(raw: String?): UUID? = raw?.let {
    try {
        UUID.fromString(it)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** One persisted master row, flattened for [SpeedMasterBank.load]. */
data class SpeedMasterSnapshot(
    val uuid: UUID,
    val index: Int,
    val name: String,
    val bpm: Double,
    val source: SpeedMasterSource,
)

/**
 * The per-show bank of speed-master clocks.
 *
 * Owns one [MasterClock] per master; **slot 0 is always master 1**, the global tempo every
 * pre-existing surface maps to and every unassigned effect resolves to. Before [load] runs
 * the bank holds a synthetic master 1 at [MasterClock.DEFAULT_BPM], so `null → master 1`
 * always resolves — mid-boot, in tests, and after a hand-edited DB alike.
 *
 * **One processing pass, N timebases.** Each clock advances its own tick counter on its own
 * timer, but no clock drives effect processing directly: every tick nudges the CONFLATED
 * [wake] channel, and the engine runs *one* pass per wake-up over one coherent [Frame] of
 * per-master ticks ([snapshotFrame]). Ticks landing while a pass is in flight collapse into
 * a single wake, so the pass rate is bounded by the fastest master and never backlogs — and
 * because effect phase is a pure function of the tick counter (see
 * [MasterClock.phaseForDivision]), sampling a master's tick between its own ticks costs
 * nothing in accuracy. One pass means one `ControllerTransaction` and one reset sweep, so
 * effects on different masters can never fight over a property within a frame.
 *
 * Effects hold a persisted `speedMasterUuid` and a runtime slot index bound by the engine
 * ([slotFor]); the hot path is an array index, never a UUID map lookup. [version] bumps on
 * any membership change so the engine knows to re-bind.
 */
class SpeedMasterBank(master1Clock: MasterClock = MasterClock()) {

    /** Live state of one master, as reported to WS/REST readers. */
    data class MasterState(
        val uuid: UUID?,
        val index: Int,
        val name: String,
        val bpm: Double,
        val isRunning: Boolean,
        val source: SpeedMasterSource,
    )

    /** One master's tempo moved (setBpm / tap). Streamed to clients and the persister. */
    data class Change(
        val uuid: UUID?,
        val index: Int,
        val bpm: Double,
        val source: SpeedMasterSource,
        val timestampMs: Long,
    )

    /**
     * One master crossed a beat boundary — the keyed analogue of the master-1-only
     * `beatSync`, so a client can pulse an indicator against the master its effect
     * actually runs on.
     */
    data class Beat(
        val uuid: UUID?,
        val index: Int,
        val beatNumber: Long,
        val bpm: Double,
        val timestampMs: Long,
    )

    private class Entry(
        val uuid: UUID?,
        val index: Int,
        @Volatile var name: String,
        val clock: MasterClock,
        @Volatile var source: SpeedMasterSource,
    )

    /**
     * A coherent per-pass sample of every master: `ticks[slot]` is that master's most
     * recent tick, `rateScales[slot]` is `bpm / 120` (the wall-clock rate-master scale).
     * Out-of-range slots resolve to slot 0 — a deleted master's not-yet-rebound effect
     * degrades to the global tempo, never to a crash.
     */
    class Frame internal constructor(
        private val ticks: Array<MasterClock.ClockTick>,
        private val rateScales: DoubleArray,
        /** Pass timestamp — the engine's deltaMs source, independent of any one master's tick rate. */
        val timestampMs: Long,
    ) {
        fun tick(slot: Int): MasterClock.ClockTick =
            if (slot in ticks.indices) ticks[slot] else ticks[0]

        fun rateScale(slot: Int): Double =
            if (slot in rateScales.indices) rateScales[slot] else 1.0

        companion object {
            /** Every slot answers with [tick] — the synthetic-tick shim for tests and benchmarks. */
            fun uniform(tick: MasterClock.ClockTick): Frame =
                Frame(arrayOf(tick), doubleArrayOf(1.0), tick.timestampMs)
        }
    }

    private val lock = Any()

    /**
     * The slot array, the uuid index over it, and the membership version, published as ONE
     * volatile reference. They must never be separate fields: readers are lock-free, and a
     * reader that paired a new array with the old map (or vice versa) mid-[load] could
     * resolve a stale slot number into the wrong master — or out of bounds.
     */
    private class Bindings(
        val slots: Array<Entry>,
        val slotByUuid: Map<UUID, Int>,
        val version: Long,
    )

    @Volatile
    private var bindings: Bindings = Bindings(
        slots = arrayOf(
            Entry(uuid = null, index = 1, name = "Master 1", clock = master1Clock, source = SpeedMasterSource.MANUAL),
        ),
        slotByUuid = emptyMap(),
        version = 0L,
    )

    /** Bumped on every membership change ([load]); the engine re-binds effect slots when it moves. */
    val version: Long get() = bindings.version

    /** Any master's tick wakes the engine; CONFLATED so a pass in flight absorbs the burst. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64)

    /** Per-master tempo pushes — the live-BPM stream for WS clients and the write-through persister. */
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    private val _beats = MutableSharedFlow<Beat>(extraBufferCapacity = 64)

    /**
     * Every master's beat boundaries, tagged with which master they came from. One flow for
     * the whole bank rather than one subscription per clock: a WS connection subscribes
     * once and keeps working across a [load], because the tagging happens here at emit time
     * rather than being captured into a per-connection collector.
     */
    val beats: SharedFlow<Beat> = _beats.asSharedFlow()

    @Volatile
    private var startedScope: CoroutineScope? = null

    init {
        master1Clock.onTick = { wake.trySend(Unit) }
        master1Clock.onBeat = { beat -> emitBeat(master1Clock, beat) }
    }

    /**
     * Tag a clock's beat with its master's current identity. The lookup is by clock
     * *instance* and happens per beat, so a rename or reorder between beats is reflected
     * immediately without re-wiring the callback. A retired clock resolves to no entry and
     * is dropped — defensive only, since [load] stops any clock it drops and a stopped
     * clock cannot fire again.
     */
    private fun emitBeat(clock: MasterClock, beat: MasterClock.BeatEvent) {
        val entry = bindings.slots.firstOrNull { it.clock === clock } ?: return
        _beats.tryEmit(
            Beat(
                uuid = entry.uuid,
                index = entry.index,
                beatNumber = beat.beatNumber,
                bpm = clock.bpm.value,
                timestampMs = beat.timestampMs,
            )
        )
    }

    /** Master 1's clock — the compatibility surface for everything that predates the bank. */
    fun master1(): MasterClock = bindings.slots[0].clock

    /**
     * Runtime slot for [uuid]; null or unknown resolves to master 1. This fallback is for
     * *reading* paths (effect slot binding, frames): a dangling effect reference degrades
     * to the global tempo. Write paths go through [entryFor], which deliberately does NOT
     * fall back for an unknown uuid.
     */
    fun slotFor(uuid: UUID?): Int = uuid?.let { bindings.slotByUuid[it] } ?: 0

    fun clockFor(slot: Int): MasterClock {
        val current = bindings.slots
        return if (slot in current.indices) current[slot].clock else current[0].clock
    }

    /** Live state of every master, slot order (master 1 first). */
    fun masterStates(): List<MasterState> = bindings.slots.map { entry ->
        MasterState(
            uuid = entry.uuid,
            index = entry.index,
            name = entry.name,
            bpm = entry.clock.bpm.value,
            isRunning = entry.clock.isRunning.value,
            source = entry.source,
        )
    }

    /** One coherent sample of every master for a single engine pass. */
    fun snapshotFrame(): Frame {
        val current = bindings.slots
        val ticks = Array(current.size) { current[it].clock.currentTick }
        val scales = DoubleArray(current.size) { current[it].clock.bpm.value / MasterClock.DEFAULT_BPM }
        return Frame(ticks, scales, System.currentTimeMillis())
    }

    /**
     * Just the per-slot rate scales (`bpm / 120`) — the wall-clock pass's snapshot. It never
     * reads ticks, so handing it a full [Frame] would allocate a per-master tick array 50
     * times a second purely to discard it.
     */
    fun rateScales(): DoubleArray {
        val current = bindings.slots
        return DoubleArray(current.size) { current[it].clock.bpm.value / MasterClock.DEFAULT_BPM }
    }

    /**
     * Adopt the persisted rows: create clocks for new masters (starting at the stored bpm),
     * drop clocks whose row is gone, and keep every surviving clock's **live** tempo and
     * tick counter — a reload after a rename must not clobber a tapped tempo back to the
     * stored default, and keeping the counter is what keeps phase continuous. The one
     * exception is the synthetic pre-load master 1, which adopts the stored bpm on the
     * first load (nothing live has been tapped into a bank that was never loaded).
     *
     * The row with `index == 1` keeps slot 0's clock instance, whatever its uuid was
     * before: `beatSync` and `fxState` subscriptions hold that clock's StateFlows, and
     * replacing the object would silently orphan them.
     */
    fun load(rows: List<SpeedMasterSnapshot>) {
        synchronized(lock) {
            val previous = bindings.slots
            val previousByUuid = previous.mapNotNull { e -> e.uuid?.let { it to e } }.toMap()
            val firstLoad = previous.size == 1 && previous[0].uuid == null

            val ordered = rows.sortedBy { it.index }
            val newSlots = ArrayList<Entry>(ordered.size.coerceAtLeast(1))
            for (row in ordered) {
                val existing = previousByUuid[row.uuid]
                    ?: if (row.index == 1) previous[0] else null
                if (existing != null) {
                    existing.name = row.name
                    val entry = if (existing.uuid == row.uuid && existing.index == row.index) {
                        existing
                    } else {
                        Entry(row.uuid, row.index, row.name, existing.clock, existing.source)
                    }
                    if (firstLoad && entry.clock === previous[0].clock) {
                        entry.clock.setBpm(row.bpm)
                        entry.source = row.source
                    }
                    newSlots.add(entry)
                } else {
                    val clock = MasterClock()
                    clock.setBpm(row.bpm)
                    clock.onTick = { wake.trySend(Unit) }
                    clock.onBeat = { beat -> emitBeat(clock, beat) }
                    startedScope?.let { clock.start(it) }
                    newSlots.add(Entry(row.uuid, row.index, row.name, clock, row.source))
                }
            }
            if (newSlots.isEmpty() || newSlots[0].index != 1) {
                // No index-1 row (hand-edited DB): keep the previous master 1 so slot 0 —
                // and everything `null` resolves to — always exists.
                newSlots.add(0, previous[0])
            }

            val kept = newSlots.mapTo(HashSet()) { it.clock }
            previous.filter { it.clock !in kept }.forEach { it.clock.stop() }

            bindings = Bindings(
                slots = newSlots.toTypedArray(),
                slotByUuid = newSlots.withIndex()
                    .mapNotNull { (i, e) -> e.uuid?.let { it to i } }
                    .toMap(),
                version = bindings.version + 1,
            )
        }
    }

    fun start(scope: CoroutineScope) {
        startedScope = scope
        bindings.slots.forEach { it.clock.start(scope) }
    }

    fun stop() {
        startedScope = null
        bindings.slots.forEach { it.clock.stop() }
    }

    /** Retune one master (null → master 1). [source] records how — MANUAL for typed, TAP for tapped. */
    fun setBpm(uuid: UUID?, bpm: Double, source: SpeedMasterSource) {
        val entry = entryFor(uuid) ?: return
        entry.clock.setBpm(bpm)
        entry.source = source
        emitChange(entry)
    }

    /** Tap one master's tempo (null → master 1). */
    fun tap(uuid: UUID?) {
        val entry = entryFor(uuid) ?: return
        entry.clock.tap()
        entry.source = SpeedMasterSource.TAP
        emitChange(entry)
    }

    /**
     * Resolve a *write* target. Null means master 1 (the legacy unkeyed surfaces); a
     * non-null uuid the bank doesn't know returns null so the write is DROPPED — falling
     * back to master 1 here would let a tap on a just-deleted master's stale UI tile
     * silently retune the global tempo. (Read paths keep the master-1 fallback; see
     * [slotFor].) One bindings snapshot for both lookups, so a concurrent [load] can't
     * pair a stale index with a new array.
     */
    private fun entryFor(uuid: UUID?): Entry? {
        val current = bindings
        if (uuid == null) return current.slots[0]
        val slot = current.slotByUuid[uuid] ?: return null
        return current.slots.getOrNull(slot)
    }

    /**
     * Invoked synchronously from [setBpm]/[tap] on the caller's thread, before the [changes]
     * emission. The write-through persister hangs off this rather than collecting [changes]:
     * a flow collector can be cancelled with an emission still buffered, which is exactly how
     * a tempo tapped just before shutdown would get lost.
     */
    @Volatile
    var onChangeSync: ((Change) -> Unit)? = null

    private fun emitChange(entry: Entry) {
        val change = Change(
            uuid = entry.uuid,
            index = entry.index,
            bpm = entry.clock.bpm.value,
            source = entry.source,
            timestampMs = System.currentTimeMillis(),
        )
        onChangeSync?.invoke(change)
        _changes.tryEmit(change)
    }
}
