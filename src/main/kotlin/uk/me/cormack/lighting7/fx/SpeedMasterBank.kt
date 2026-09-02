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
    /** Follow ratio over [followTargetUuid] — both null = manual, both positive = follower (D2). */
    val followNum: Int? = null,
    val followDen: Int? = null,
    /** Which master this one follows; null means master 1, the pre-follow-target spelling. */
    val followTargetUuid: UUID? = null,
)

/**
 * The per-show bank of speed-master clocks.
 *
 * Owns one [MasterClock] per master; **slot 0 is always master 1**, the global tempo every
 * pre-existing surface maps to and every unassigned effect resolves to. Before [load] runs
 * the bank holds a synthetic master 1 at [MasterClock.DEFAULT_BPM], so `null → master 1`
 * always resolves — mid-boot, in tests, and after a hand-edited DB alike.
 *
 * **Following is phase lock, not just tempo.** A master with a [Follow] link has no timer of
 * its own: its leader's tick is mapped onto its counter (`floor(leaderTick × num / den)`,
 * anchored at tick 0), so a ½ follower's beats land on every second leader beat and stay
 * there. Chains are allowed and rooted at master 1; cycles and dangling targets degrade to
 * manual at [load]. Everything else — the engine, [slotFor], the persister, the WS streams —
 * still sees an ordinary master whose tempo happens to move.
 *
 * **One processing pass, N timebases.** Each manual clock advances its own tick counter on its
 * own timer, but no clock drives effect processing directly: every tick nudges the CONFLATED
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
        /** Follow ratio over [followTargetUuid]; both null = manual. */
        val followNum: Int? = null,
        val followDen: Int? = null,
        /**
         * The master this one follows, null when it follows master 1 or isn't following at
         * all — [followNum] tells the two apart. Reported as the *resolved* leader, so a row
         * whose stored target was dangling or looped reads back as the manual master the bank
         * degraded it to, rather than as a link that isn't running.
         */
        val followTargetUuid: UUID? = null,
    )

    /**
     * A follower's time signature over its leader: it ticks — and therefore beats — at the
     * leader's rate times `num / den`. [target] is the leader's uuid, or null for master 1.
     */
    data class Follow(val target: UUID?, val num: Int, val den: Int)

    /**
     * What a tempo write ([setBpm]/[tap]) did. Richer than the old Boolean because a follower
     * refusal (D5) must be reported distinctly from a dropped write to an unknown uuid.
     */
    sealed interface TempoWriteOutcome {
        data object Applied : TempoWriteOutcome

        /** Uuid names no master — a dropped write, never a fallback to master 1. */
        data object UnknownMaster : TempoWriteOutcome

        /**
         * D5: the master follows another master, so its tempo is derived, not set. Refused
         * rather than auto-unlinked — a stray TAP mid-show must not silently sever a
         * relationship the operator set up deliberately; the fix is to unlink in the
         * speed-master sheet.
         */
        data class RefusedFollower(
            val uuid: UUID?,
            val index: Int,
            val name: String,
            val num: Int,
            val den: Int,
            /** The leader's display name, so the refusal names the master to retune instead. */
            val leaderName: String,
        ) : TempoWriteOutcome {
            /**
             * The operator-facing refusal, one phrasing for every surface (WS error frame,
             * MIDI log) — the advice names the fix, so a wording change must not have to hunt
             * per-surface copies. (The AI tool appends model-specific advice of its own.)
             */
            val describe: String
                get() = "$name follows $leaderName at $num/$den — unlink it in the " +
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
        /**
         * The live link, or null for a manual master. Set from the row at [load] and cleared
         * there when the row's target is dangling or loops — so everything downstream can read
         * "follow != null" as "this clock is driven", with no second validity question.
         */
        @Volatile var follow: Follow? = null,
        /** Resolved leader, non-null exactly when [follow] is. */
        @Volatile var leader: Entry? = null,
        /** Resolved followers, in slot order. Empty for most masters; the tick cascade's edge list. */
        @Volatile var followers: List<Entry> = emptyList(),
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
        /**
         * Every follower, leaders before their own followers — the order a chain must be
         * recomputed in, and empty in the (usual) all-manual bank, which is what lets the
         * per-tick hot path skip the cascade with one reference read.
         */
        val driveOrder: List<Entry> = emptyList(),
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
        master1Clock.onTick = { onClockTick(master1Clock) }
        master1Clock.onBeat = { beat -> emitBeat(master1Clock, beat) }
    }

    /**
     * One master ticked: nudge the engine, then hand the tick down to anything following this
     * master (and to whatever follows *those*, transitively).
     *
     * Runs on the ticking clock's own timer coroutine and takes no lock — it reads the single
     * volatile [bindings] reference, so a concurrent [load] swaps a whole consistent graph in
     * rather than being observed half-built. The all-manual bank pays one reference read and a
     * list-empty check per tick; the identity scan only happens when something is following.
     */
    private fun onClockTick(clock: MasterClock) {
        wake.trySend(Unit)
        val current = bindings
        if (current.driveOrder.isEmpty()) return
        val entry = current.slots.firstOrNull { it.clock === clock } ?: return
        driveFollowers(entry)
    }

    /**
     * Map a leader's tick onto each of its followers' clocks, depth-first.
     *
     * `followerTick = 1 + floor((leaderTick - 1) × num / den)` — anchored at the counters'
     * shared origin rather than at the moment of linking, which is *the* reason follow now
     * means what an operator expects: a ½ follower's beat boundaries land on every second
     * leader beat, permanently, instead of free-running at wherever its own counter happened
     * to sit (`FU-SPEED-PHASE-LOCK`). The cost is that linking snaps this master's effects
     * once, which is the behaviour the feature is asking for.
     *
     * The `-1`/`+1` is the clocks' 1-based tick numbering, where a beat lands on the *first*
     * tick of the beat: without it a ½ follower beats one leader tick early, which is small
     * enough to pass a rate test and obvious to anyone watching two beat lamps.
     *
     * Nested floors compose exactly — `floor(floor(t/2)/2) == floor(t/4)` — so a chain's
     * grandchild is aligned to the root, not merely to its parent.
     */
    private fun driveFollowers(entry: Entry) {
        val followers = entry.followers
        if (followers.isEmpty()) return
        val tick = entry.clock.currentTick
        for (follower in followers) {
            val follow = follower.follow ?: continue
            follower.clock.driveTo(
                1 + Math.floorDiv((tick.tickNumber - 1) * follow.num, follow.den.toLong()),
                tick.timestampMs,
            )
            driveFollowers(follower)
        }
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
            followTargetUuid = entry.leader?.uuid,
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
            // Clocks whose *mapping* changed — a new ratio or a new leader — which is the one
            // reload that must re-anchor an already-driven counter rather than leave it where
            // it stands. See [MasterClock.adoptDriven]'s `snap`.
            val snapping = HashSet<MasterClock>()
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
                    // Read before the write: on the reuse branch `entry` *is* `existing`, so
                    // the old spec is gone the moment the new one lands.
                    val previousFollow = existing.follow
                    entry.follow = followOf(row)
                    if (entry.follow != null && entry.follow != previousFollow) {
                        snapping.add(entry.clock)
                    }
                    if (firstLoad && entry.clock === previous[0].clock) {
                        entry.clock.setBpm(row.bpm)
                        entry.source = row.source
                    }
                    newSlots.add(entry)
                } else {
                    val clock = MasterClock()
                    clock.setBpm(row.bpm)
                    clock.onTick = { onClockTick(clock) }
                    clock.onBeat = { beat -> emitBeat(clock, beat) }
                    newSlots.add(
                        Entry(
                            row.uuid, row.index, row.name, clock, row.source,
                            usage = row.usage, follow = followOf(row),
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

            val slotByUuid = newSlots.withIndex()
                .mapNotNull { (i, e) -> e.uuid?.let { it to i } }
                .toMap()
            val driveOrder = resolveFollowGraph(newSlots, slotByUuid)

            bindings = Bindings(
                slots = newSlots.toTypedArray(),
                slotByUuid = slotByUuid,
                version = bindings.version + 1,
                driveOrder = driveOrder,
            )

            val clamped = applyClockRolesLocked(newSlots, snapping)

            // D2's "on load" half: a follower adopts `leader.bpm × ratio` immediately,
            // overriding whatever stored bpm its row carried — a boot or import with a
            // follower comes up already in step. The no-change check inside the sweep keeps an
            // ordinary CRUD reload (derived value already persisted) from emitting anything.
            clamped + sweepFollowersLocked()
        }
        swept.forEach { emitChange(it) }
    }

    /**
     * A snapshot's follow link, or null when manual/malformed/on master 1 — the ratio rule
     * lives in [speedMasterFollowRatioOrNull] (models), shared with `DaoSpeedMaster.followRatio`
     * so a row reads the same on every surface. An index-1 row claiming a ratio is ignored
     * outright: master 1 is the root every chain ends at, and honouring it would make the
     * graph's source amount to a self-reference. Whether the *target* is usable is a separate
     * question, answered in [resolveFollowGraph] where the whole bank is visible.
     */
    private fun followOf(row: SpeedMasterSnapshot): Follow? =
        speedMasterFollowRatioOrNull(row.index, row.followNum, row.followDen)
            ?.let { (num, den) -> Follow(row.followTargetUuid, num, den) }

    /**
     * Resolve every row's stored target into a live leader, drop the links that can't work,
     * and return the followers leaders-first.
     *
     * Three degradations, all to "manual", all silent by design — this runs on a reload, not
     * on a write, and the alternative to degrading is a master with no tempo at all:
     *
     * - **Dangling target**: the named master isn't in this bank — an import that carried a
     *   follower but not its leader, or a hand-edited row. (Not a forced delete: that route
     *   unlinks the followers itself, so the stored columns and this graph agree.) Same
     *   principle as an effect whose speed master vanished: fall back to something that runs.
     * - **Self-follow**: only reachable from a hand-edited row; the write boundary refuses it.
     * - **Cycles, and anything following into one**: an entry whose leader chain never reaches
     *   a manual master has no timer anywhere upstream, so nothing would ever drive it. Every
     *   member is degraded, not just the one the iteration happened to reach first — leaving a
     *   half-broken chain standing would be a link the write boundary would have rejected.
     *
     * Called under [lock] as part of [load], and mutates the entries it is given before they
     * are published in a new [Bindings].
     */
    private fun resolveFollowGraph(slots: List<Entry>, slotByUuid: Map<UUID, Int>): List<Entry> {
        val master1 = slots[0]
        slots.forEach { it.leader = null; it.followers = emptyList() }

        for (entry in slots) {
            val follow = entry.follow ?: continue
            val leader = if (follow.target == null) master1 else slotByUuid[follow.target]?.let { slots[it] }
            if (leader == null || leader === entry) {
                entry.follow = null
            } else {
                entry.leader = leader
            }
        }

        // A walk longer than the bank is a loop, by pigeonhole. Evaluated against the graph as
        // it stands, before anything is cleared, so every member of a cycle fails together.
        val looping = slots.filter { entry ->
            if (entry.follow == null) return@filter false
            var hops = 0
            var current = entry.leader
            while (current != null) {
                if (hops++ > slots.size) return@filter true
                current = current.leader
            }
            false
        }
        looping.forEach { it.follow = null; it.leader = null }

        val byLeader = slots.filter { it.follow != null }.groupBy { it.leader }
        slots.forEach { entry -> entry.followers = byLeader[entry].orEmpty() }

        // Leaders before their followers, so one pass of the bpm sweep derives a whole chain.
        // Cycles are gone by now, so this always drains.
        val ordered = ArrayList<Entry>()
        val remaining = slots.filter { it.follow != null }.toMutableList()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { it.leader?.follow == null || it.leader in ordered }
            if (ready.isEmpty()) break
            ordered.addAll(ready)
            remaining.removeAll(ready)
        }
        return ordered
    }

    /**
     * Give every clock the role its row asks for: a follower's timer is cancelled and its
     * ticks come from its leader ([MasterClock.adoptDriven]), a manual master runs its own.
     *
     * An unlink resumes the timer **without resetting the tick counter**, so the master picks
     * up its own timing from exactly where its leader left it — the operator stopped following,
     * they didn't ask every effect on that master to jump. (Linking *does* snap, unavoidably:
     * that is what phase alignment means — and so does a *re-*link at a new ratio or onto a new
     * leader, which is what [snapping] carries; a follower driven at one ratio holds a counter
     * that means nothing under the next one.) [MasterClock.start] no-ops on an already-running
     * clock, so this is safe to run for the whole bank on every reload.
     *
     * Returns the entries whose tempo had to be pulled back into [MasterClock.MIN_BPM]..
     * [MasterClock.MAX_BPM] on the way out of being driven, for the caller to emit once the
     * lock is released: a derived tempo is deliberately unclamped ([MasterClock.setDerivedBpm]),
     * so an unlinked 2×-of-200 master would otherwise hand its own timer 400 BPM — a rate the
     * write boundary refuses to accept and the row would go on storing.
     */
    private fun applyClockRolesLocked(
        slots: List<Entry>,
        snapping: Set<MasterClock> = emptySet(),
    ): List<Entry> {
        val scope = startedScope
        val clamped = mutableListOf<Entry>()
        slots.forEach { entry ->
            if (entry.follow != null) {
                entry.clock.adoptDriven(snap = entry.clock in snapping)
            } else {
                if (entry.clock.driven) {
                    val before = entry.clock.bpm.value
                    entry.clock.setBpm(before)
                    if (entry.clock.bpm.value != before) clamped.add(entry)
                }
                scope?.let { entry.clock.start(it, resetCounter = false) }
            }
        }
        return clamped
    }

    fun start(scope: CoroutineScope) {
        // Under the lock, unlike the old body: this now assigns clock *roles*, and a load
        // landing halfway through would leave a follower with both a timer and a leader.
        val clamped = synchronized(lock) {
            startedScope = scope
            // Followers are driven by their leader rather than by a timer of their own, so they
            // are handed their role here too — otherwise a bank started after load would give
            // every follower back its own free-running clock.
            applyClockRolesLocked(bindings.slots.toList())
        }
        clamped.forEach { emitChange(it) }
    }

    /** Under [lock] like [start], so a concurrent [load] cannot restart a clock after this. */
    fun stop() {
        synchronized(lock) {
            startedScope = null
            bindings.slots.forEach { it.clock.stop() }
        }
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
                return TempoWriteOutcome.RefusedFollower(
                    entry.uuid, entry.index, entry.name, it.num, it.den,
                    leaderName = entry.leader?.name ?: "Master 1",
                )
            }
            write(entry)
            // Any master can now have followers, so every write sweeps. The sweep's no-change
            // filter makes that free for the (usual) case of a write to a master nothing
            // follows — where the old slot-0 test was the shape of the answer, not an
            // optimisation.
            listOf(entry) + sweepFollowersLocked()
        }
        moved.forEach { emitChange(it) }
        return TempoWriteOutcome.Applied
    }

    /**
     * Recompute every follower's *reported* tempo as `leader.bpm × num / den`, returning the
     * entries whose tempo actually moved, leaders first. Caller holds [lock] and emits the
     * changes after releasing it.
     *
     * This is bookkeeping, not timing: a follower's clock is driven tick-for-tick by its
     * leader ([driveFollowers]), so what its rate *is* never depends on this pass. What
     * depends on it is what the strip displays, what the row persists, and the wall-clock
     * rate multiplier in [rateScales] — which is exactly why it uses
     * [MasterClock.setDerivedBpm] and not [MasterClock.setBpm]: clamping 2× of 200 BPM to the
     * timer's 300 ceiling would report a tempo the master is demonstrably not running at.
     *
     * - **Chains in one pass**: `bindings.driveOrder` is leaders-first, so a grandchild sees
     *   its parent's already-derived value.
     * - **The no-change check is the persist/reload loop-breaker**: a flush writes the derived
     *   bpm to the row, the next [load] sweeps and derives the same value, nothing is emitted,
     *   nothing re-arms the persister's debounce.
     * - **Multiply before dividing**: `126.0 * 1 / 3` is exactly `42.0` in IEEE 754;
     *   `126.0 * (1.0 / 3.0)` is not.
     */
    private fun sweepFollowersLocked(): List<Entry> {
        return bindings.driveOrder.filter { entry ->
            val ratio = entry.follow ?: return@filter false
            val leader = entry.leader ?: return@filter false
            val before = entry.clock.bpm.value
            val beforeSource = entry.source
            entry.clock.setDerivedBpm(leader.clock.bpm.value * ratio.num / ratio.den)
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
