package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.speedMasterFollowRatioOrNull
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
    /** Effect-library category this master is the apply-time default for; null routes nothing. */
    val usage: String? = null,
    /** Follow ratio over master 1 — both null = manual, both positive = follower (D2). */
    val followNum: Int? = null,
    val followDen: Int? = null,
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
        /** Effect-library category this master is the apply-time default for; null routes nothing. */
        val usage: String? = null,
        /** Follow ratio over master 1; both null = manual. */
        val followNum: Int? = null,
        val followDen: Int? = null,
    )

    /** A follower's time signature over master 1: `bpm = m1.bpm × num / den`. */
    data class Ratio(val num: Int, val den: Int)

    /**
     * What a tempo write ([setBpm]/[tap]) did. Richer than the old Boolean because a follower
     * refusal (D5) must be reported distinctly from a dropped write to an unknown uuid.
     */
    sealed interface TempoWriteOutcome {
        data object Applied : TempoWriteOutcome

        /** Uuid names no master — a dropped write, never a fallback to master 1. */
        data object UnknownMaster : TempoWriteOutcome

        /**
         * D5: the master follows master 1, so its tempo is derived, not set. Refused rather
         * than auto-unlinked — a stray TAP mid-show must not silently sever a relationship the
         * operator set up deliberately; the fix is to unlink in the speed-master sheet.
         */
        data class RefusedFollower(
            val uuid: UUID?,
            val index: Int,
            val name: String,
            val num: Int,
            val den: Int,
        ) : TempoWriteOutcome {
            /**
             * The operator-facing refusal, one phrasing for every surface (WS error frame,
             * MIDI log) — the advice names the fix, so a wording change must not have to hunt
             * per-surface copies. (The AI tool appends model-specific advice of its own.)
             */
            val describe: String
                get() = "$name follows Master 1 at $num/$den — unlink it in the " +
                    "speed-master sheet to set its tempo"
        }
    }

    /** One master's tempo moved (setBpm / tap). Streamed to clients and the persister. */
    data class Change(
        val uuid: UUID?,
        val index: Int,
        val bpm: Double,
        val source: SpeedMasterSource,
        val timestampMs: Long,
    )

    /**
     * One master crossed a beat boundary, tagged with which master, so a client can pulse an
     * indicator against the master its effect actually runs on.
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
        @Volatile var usage: String? = null,
        @Volatile var follow: Ratio? = null,
    )

    /**
     * A coherent per-pass sample of every master: `ticks[slot]` is that master's most
     * recent tick. Out-of-range slots resolve to slot 0 — a deleted master's not-yet-rebound
     * effect degrades to the global tempo, never to a crash. (The wall-clock rate scale is a
     * separate per-pass sample — see [rateScales] — since a beat-only pass never needs it.)
     */
    class Frame internal constructor(
        private val ticks: Array<MasterClock.ClockTick>,
        /** Pass timestamp — the engine's deltaMs source, independent of any one master's tick rate. */
        val timestampMs: Long,
    ) {
        fun tick(slot: Int): MasterClock.ClockTick =
            if (slot in ticks.indices) ticks[slot] else ticks[0]

        companion object {
            /** Every slot answers with [tick] — the synthetic-tick shim for tests and benchmarks. */
            fun uniform(tick: MasterClock.ClockTick): Frame =
                Frame(arrayOf(tick), tick.timestampMs)
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

    /** Master 1's clock — the global tempo, and what a null reference resolves to. */
    fun master1(): MasterClock = bindings.slots[0].clock

    /**
     * Master 1's uuid, or null before [load] has run (the synthetic master has no row to name
     * it). Callers that need to *match* master 1 against something tagged from a bank entry —
     * a beat frame, say — have to go through this rather than assuming null, because a loaded
     * master 1 is tagged with its real uuid like any other master.
     */
    fun master1Uuid(): UUID? = bindings.slots[0].uuid

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
            usage = entry.usage,
            followNum = entry.follow?.num,
            followDen = entry.follow?.den,
        )
    }

    /** One coherent sample of every master for a single engine pass. */
    fun snapshotFrame(): Frame {
        val current = bindings.slots
        val ticks = Array(current.size) { current[it].clock.currentTick }
        return Frame(ticks, System.currentTimeMillis())
    }

    /**
     * Just the per-slot rate scales (`bpm / MasterClock.DEFAULT_BPM`) — the wall-clock pass's
     * snapshot. It never reads ticks, so handing it a full [Frame] would allocate a per-master
     * tick array 50 times a second purely to discard it. The pass skips this call entirely when
     * no effect has a rate master, which is the usual case.
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
     * The row with `index == 1` keeps slot 0's clock instance, whatever its uuid was before.
     * `master1Clock`'s [MasterClock.onTick] and [MasterClock.onBeat] are wired once in this
     * class's `init` and never re-wired here, so replacing the object would silently orphan
     * the engine's wake nudge and the beat fan-out — master 1 would stop driving the engine
     * at all. It is also the only way to say "keep the live tempo and tick counter" for
     * master 1, whose uuid changes on the first load.
     */
    fun load(rows: List<SpeedMasterSnapshot>) {
        val swept: List<Entry> = synchronized(lock) {
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
                    // Refreshed on the reuse branch as much as the rebuild branch: a usage or
                    // ratio edit arrives as a reload of the same uuid, and stale values here
                    // would silently ignore it.
                    entry.usage = row.usage
                    entry.follow = followRatioOf(row)
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
                    newSlots.add(
                        Entry(
                            row.uuid, row.index, row.name, clock, row.source,
                            usage = row.usage, follow = followRatioOf(row),
                        )
                    )
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

            // D2's "on load" half: a follower adopts `m1.bpm × ratio` immediately, overriding
            // whatever stored bpm its row carried — a boot or import with a follower comes up
            // already in step. The no-change check inside the sweep keeps an ordinary CRUD
            // reload (derived value already persisted) from emitting anything.
            sweepFollowersLocked()
        }
        swept.forEach { emitChange(it) }
    }

    /**
     * A snapshot's follow ratio, or null when manual/malformed/on master 1 — the single rule
     * lives in [speedMasterFollowRatioOrNull] (models), shared with `DaoSpeedMaster.followRatio`
     * so a row reads the same on every surface. An index-1 row claiming a ratio is ignored
     * outright: master 1 is what followers derive from, and honouring it would make the
     * sweep's source amount to a self-reference.
     */
    private fun followRatioOf(row: SpeedMasterSnapshot): Ratio? =
        speedMasterFollowRatioOrNull(row.index, row.followNum, row.followDen)
            ?.let { (num, den) -> Ratio(num, den) }

    fun start(scope: CoroutineScope) {
        startedScope = scope
        bindings.slots.forEach { it.clock.start(scope) }
    }

    fun stop() {
        startedScope = null
        bindings.slots.forEach { it.clock.stop() }
    }

    /**
     * Retune one master (null → master 1). [source] records how — MANUAL for typed, TAP for
     * tapped. An unknown uuid is a dropped write, not a fallback (see [entryFor]); a follower
     * is refused before its clock is touched (D5). A write to master 1 sweeps its followers.
     */
    fun setBpm(uuid: UUID?, bpm: Double, source: SpeedMasterSource): TempoWriteOutcome =
        tempoWrite(uuid) { entry ->
            entry.clock.setBpm(bpm)
            entry.source = source
        }

    /** Tap one master's tempo (null → master 1). Same refusal rules as [setBpm]. */
    fun tap(uuid: UUID?): TempoWriteOutcome =
        tempoWrite(uuid) { entry ->
            entry.clock.tap()
            entry.source = SpeedMasterSource.TAP
        }

    /**
     * The shared write path. The lock is held over the clock writes only — [emitChange] (and
     * therefore the persister's [onChangeSync]) runs outside it, so a hook can never deadlock
     * the bank — and master 1's change is emitted before its followers', which is the causal
     * order a client wants.
     */
    private inline fun tempoWrite(uuid: UUID?, write: (Entry) -> Unit): TempoWriteOutcome {
        val moved: List<Entry> = synchronized(lock) {
            val entry = entryFor(uuid) ?: return TempoWriteOutcome.UnknownMaster
            entry.follow?.let {
                return TempoWriteOutcome.RefusedFollower(entry.uuid, entry.index, entry.name, it.num, it.den)
            }
            write(entry)
            if (entry === bindings.slots[0]) listOf(entry) + sweepFollowersLocked() else listOf(entry)
        }
        moved.forEach { emitChange(it) }
        return TempoWriteOutcome.Applied
    }

    /**
     * Write-through follow (D2): recompute every follower as `m1.bpm × num / den` on the
     * follower's own clock, returning the entries whose tempo actually moved, slot order.
     * Caller holds [lock] and emits the changes after releasing it.
     *
     * - **No recursion**: this writes `entry.clock.setBpm` directly, never [setBpm], and the
     *   only trigger is a write to slot 0, which is never itself a follower (validation at the
     *   write boundary plus the slot-0 guard here) — so there is no chain to propagate (D4).
     * - **The no-change check is the persist/reload loop-breaker**: a flush writes the derived
     *   bpm to the row, the next [load] sweeps and derives the same value, nothing is emitted,
     *   nothing re-arms the persister's debounce.
     * - **Multiply before dividing**: `126.0 * 1 / 3` is exactly `42.0` in IEEE 754;
     *   `126.0 * (1.0 / 3.0)` is not.
     * - M1 reads back through its clock, so a derived bpm outside the clock's range clamps the
     *   same way a typed one does, and the next sweep re-derives from M1's live bpm rather than
     *   the clamped value — no ratchet.
     */
    private fun sweepFollowersLocked(): List<Entry> {
        val slots = bindings.slots
        val m1Bpm = slots[0].clock.bpm.value
        return slots.filter { entry ->
            if (entry === slots[0]) return@filter false
            val ratio = entry.follow ?: return@filter false
            val before = entry.clock.bpm.value
            val beforeSource = entry.source
            entry.clock.setBpm(m1Bpm * ratio.num / ratio.den)
            // A follower's tempo is derived, so its provenance reads MANUAL — no new source
            // value on the wire (the session's additive-only promise), and the follower's UI
            // shows a ratio chip, not TAP. Unconditional, not only-on-change: a master tapped
            // to 60 and then linked at ½ of 120 derives the same 60, and leaving its stored
            // TAP standing would badge a master that refuses taps as tap-sourced.
            entry.source = SpeedMasterSource.MANUAL
            // Reported when *either* half moved, not just the bpm: the source correction above
            // only reaches the row through an emitted Change, and the ½-of-120 case moves no
            // bpm at all — without this the row keeps `TAP` for a master that refuses taps,
            // and exports/clones carry it. Still terminating: once the flush has written
            // MANUAL, every later sweep sees `beforeSource == MANUAL` and emits nothing.
            entry.clock.bpm.value != before || beforeSource != SpeedMasterSource.MANUAL
        }
    }

    /**
     * Resolve a *write* target. Null means master 1 (the script API, the strip's M1 tile); a
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
