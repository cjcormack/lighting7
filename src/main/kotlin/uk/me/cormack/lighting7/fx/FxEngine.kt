package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import uk.me.cormack.lighting7.models.Timing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.perf.DeskMetrics
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("FxEngine")

/**
 * Central effect processing engine: the compose pass and the active-effect set.
 *
 * FxEngine manages active effects and processes them on each Master Clock tick,
 * applying calculated values to fixture properties through the DMX system. The rest of
 * what historically lived here is split into components reached through the engine
 * (sweep item E1): [cascade] (Layer-4/cascade transmit + the publish lock), [cueLayer]
 * (per-cue Layer 4 assignment bookkeeping + the stomp registry), [provenance]
 * ("who owns this value" computation + broadcast) and [programmer] (PROGRAMMER-layer
 * write delegation).
 *
 * Usage:
 * ```
 * val engine = FxEngine(fixtures, MasterClock())
 * engine.start(GlobalScope)
 *
 * // Add an effect
 * val fxId = engine.addFx(FxInstance(
 *     effect = SineWave(),
 *     target = SliderTarget("front-wash-1", "dimmer"),
 *     timing = FxTiming(CycleLength.TWO_BEATS)
 * ))
 *
 * // Remove when done
 * engine.removeFx(fxId)
 * ```
 */
class FxEngine(
    private val fixtures: Fixtures,
    /**
     * The show's speed-master clocks. Slot 0 is master 1 — the global tempo. Effects
     * resolve their [FxInstance.speedMasterSlot] against this bank; the engine's beat
     * pass is driven by the bank's conflated wake channel rather than any one clock.
     */
    val speedMasters: SpeedMasterBank = SpeedMasterBank(),
    /**
     * PROGRAMMER-layer store: sticky manual property entries + the raw-channel sideband.
     * Read during effect reset so that manual writes remain visible under running effects.
     * Defaults to a fresh empty store for tests; the real show wires in the per-project
     * store from [uk.me.cormack.lighting7.show.Show].
     */
    val programmerStore: ProgrammerStore = ProgrammerStore(),
    /**
     * Cue-layer composition resolver. Resolves per-cue property assignments to the composed
     * value that sits below effects.
     */
    val layerResolver: LayerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
    /**
     * Layer 1 park query. If non-null, the engine skips effect reset / apply for channels
     * that are parked. The parked value is still re-applied at transmit time in
     * [uk.me.cormack.lighting7.dmx.ArtNetController] as defence-in-depth.
     */
    private val parkManager: ParkManager? = null,
) {
    /**
     * Master 1's clock — the global tempo. Scripts (`setBpm`/`tapTempo`/`masterClock`), the
     * AI `set_bpm` tool, and every effect with no explicit master all mean this clock.
     */
    val masterClock: MasterClock get() = speedMasters.master1()

    private val nextFxId = AtomicLong(0)
    private val activeFx = ConcurrentHashMap<Long, FxInstance>()

    /**
     * Bank membership version the effects' runtime slots were last bound against; when the
     * bank's [SpeedMasterBank.version] moves past this, the next pass re-binds every
     * active instance ([rebindSpeedMasters]).
     */
    @Volatile private var boundBankVersion: Long = -1L

    // Read lock-free by the compose pass; rebuilt under [fxSnapshotLock] on mutation.
    private val fxSnapshotLock = Any()

    /**
     * Held while a Local FX's rank is read or written, or copied to an instance that replaces it: an
     * insert, a swap, a Detach and a move.
     */
    private val localRankLock = Any()
    private val sortedFxComparator = applyOrder({ it.priority }, { it.composeOrder }, { it.id })

    /**
     * The apply order over the three keys the compose pass sorts by, here so the sampler can order a
     * draft by the keys [insert] would give it without a second copy of the order.
     */
    private fun applyOrder(
        priority: (FxInstance) -> Int,
        composeOrder: (FxInstance) -> Long,
        id: (FxInstance) -> Long,
    ): Comparator<FxInstance> = compareBy(priority, composeOrder, id)

    /** Every effect, beat and wall-clock alike, in apply order — what one compose pass folds. */
    @Volatile private var sortedFx: List<FxInstance> = emptyList()

    /**
     * One compose pass at a time ([composePass]). The production driver is a single coroutine,
     * so this is uncontended there; it is what keeps a test's direct pass off a running one.
     */
    private val passLock = kotlinx.coroutines.sync.Mutex()

    // The fields below are owned by [composePass], under [passLock].

    /** The frame of the last pass a beat woke: where a pass the ticker woke paints beat effects. */
    private var lastFrame: SpeedMasterBank.Frame? = null

    /** The last beat-woken pass's frame time: stateful beat effects' deltaMs. */
    private var lastTickMs: Long = 0L

    /** The last ticker-woken pass's time: stateful wall-clock effects' deltaMs. */
    private var lastWallClockTickMs: Long = 0L

    /** The last pass's time, whatever woke it: how far wall-clock accumulators advance. */
    private var lastPassMs: Long = 0L

    /**
     * Productive-pass counter handed to stateful wall-clock effects as `tick.tickNumber`.
     *
     * Counts only ticker-woken passes that reach the apply stage: burning numbers while nothing is
     * running would hand a re-armed effect an arbitrary jump, and counting beat wakes would make a
     * Candle Flicker flicker faster the faster a master runs.
     */
    private var wallClockPassNumber: Long = 0L

    /** The tick stateful wall-clock effects saw on the last ticker-woken pass. */
    private var wallClockTick = MasterClock.ClockTick(
        tickNumber = 0, beatNumber = 0, tickInBeat = 0, phase = 0.0, timestampMs = 0L,
    )

    // Shared with [CascadePublisher] so one fault keeps one suppression history wherever
    // it is reported from — see [FxLogThrottle].
    private val throttle = FxLogThrottle(logger)

    /** Stable throttle key for a fault attributable to [effect] — see [FxLogThrottle]. */
    private fun faultKey(prefix: String, effect: FxInstance): String =
        "$prefix-${effect.fxTypeId}-${effect.target.targetKey}.${effect.target.attribute}"

    /**
     * Record that [effect] threw out of its pass, and auto-pause it once it has thrown out of
     * [MAX_CONSECUTIVE_TICK_FAILURES] passes in a row.
     *
     * A script effect that throws on every `calculate` is not going to be fixed by retrying it
     * 120 times a second: it burns pass budget and floods the log for a property it will never
     * paint. Pausing keeps the instance — its phase, its parameters, its place in the sheet —
     * so the operator can fix the definition and hit resume, and the reset pass goes on showing
     * the layer below meanwhile. It also surfaces in the UI, because `isRunning` already
     * streams on `fxState`.
     *
     * Deliberately a tick count rather than a wall-clock duration: it makes the run *faster* to
     * trip the more often the desk is ticking, which is the safe direction — a fault that clears
     * itself (a fixture missing for the length of a reload) gets more real time to clear on the
     * slow paths, and a genuinely broken effect on a fast one stops in about a second.
     *
     * Only passes the effect's own clock woke count, so the grace period follows that clock alone; a
     * failure on another pass is logged and not counted. The counter is owned by [composePass], and
     * an `updateFx` swap resets it — which is right, since changed parameters deserve a fresh
     * verdict.
     */
    private fun noteTickFailure(
        effect: FxInstance,
        e: Throwable,
        what: String,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        counts: Boolean,
    ) {
        val describe = "$what ${effect.id} ('${effect.effect.name}' on " +
            "${effect.target.targetKey}.${effect.target.attribute})"

        if (counts && ++effect.consecutiveTickFailures >= MAX_CONSECUTIVE_TICK_FAILURES) {
            val failures = effect.consecutiveTickFailures
            effect.consecutiveTickFailures = 0
            effect.pause()
            // isRunning is part of effect coverage; see [rebuildSortedSnapshots].
            fxListEpoch.incrementAndGet()

            // An effect can throw part-way through its own targets — a group effect that dies on
            // member 7 has already painted 0..6 this pass. From the next pass on, a paused effect
            // is skipped by [resetActiveAttributes], so whatever it managed to write would stay
            // frozen on those channels with nothing left to move it. Put the layer below back
            // now, into this pass's open transaction, so the property lands somewhere defined.
            // (A manual pause deliberately freezes instead: that frame is one the operator chose.)
            for (key in resolveFxFixtureKeys(effect)) {
                resetOne(fixturesWithTx, key, effect.target)
            }

            logger.error(
                "FX engine paused $describe after $failures consecutive failing ticks", e,
            )
            emitStateUpdate()
            return
        }

        throttle.log(faultKey("tick-failure", effect), e) { "FX engine error processing $describe" }
    }

    /** Clear [effect]'s failure run after a pass it survived. */
    private fun noteTickSuccess(effect: FxInstance) {
        // Guarded so the overwhelmingly common case is a plain read, not a write.
        if (effect.consecutiveTickFailures != 0) effect.consecutiveTickFailures = 0
    }

    /**
     * The suppression map and the coverage epoch it was built from, published as ONE
     * volatile reference — the same single-reference publication [SpeedMasterBank] uses for
     * its internal slot bindings. As two fields, racing readers (the pass, provenance) could pair an older map
     * with a newer epoch and serve stale suppression until the next programmer mutation.
     */
    private class SuppressionSnapshot(
        val byFixture: Map<String, Set<String>>,
        val epoch: Long,
    )

    // Per-tick programmer suppression snapshot, cached on the store's coverage epoch so the
    // compose pass rebuilds it only when the set of covered keys changes — a busk rewriting
    // already-held keys bumps [ProgrammerStore.epoch] per write but never this. The coverage
    // epoch is bumped *after* the mutation it reports, so a rebuild that observed the new
    // epoch also observes the write. The pass and provenance may still race the rebuild; each
    // publishes a self-consistent (map, epoch) pair, and a pair that lost the race carries
    // the older epoch with it, so the next tick detects the mismatch and rebuilds — one
    // stale tick at worst, never a latch.
    @Volatile private var suppressionCache = SuppressionSnapshot(emptyMap(), epoch = -1L)

    /**
     * fixtureKey → property names with an active programmer entry, or empty when blind is
     * engaged (blind removes the programmer from the merge, so its values hold no effect back;
     * what Blind holds instead is the programmer band, [BlindHold]).
     * An entry here suppresses every effect on that (fixture, property) except effects in
     * the programmer priority band — the "programmer wins over effects" rule.
     */
    internal fun programmerSuppression(): Map<String, Set<String>> = programmerSuppression(programmerStore.blind)

    /**
     * [programmerSuppression] against one read of blind the caller has already taken — the compose pass,
     * which ask [BlindHold] of the same read, so a pass cannot pair an empty (blind) suppression map
     * with a band that is not held, or the reverse, across a Blind toggle landing mid-pass.
     */
    private fun programmerSuppression(blind: Boolean): Map<String, Set<String>> {
        if (blind) return emptyMap()
        val epoch = programmerStore.coverageEpoch
        var cached = suppressionCache
        if (epoch != cached.epoch) {
            cached = SuppressionSnapshot(programmerStore.activeAttributesByFixture(), epoch)
            suppressionCache = cached
        }
        return cached.byFixture
    }

    /**
     * The (fixtureKey, attribute) set running effects cover, and the stamps it was built
     * from, published as ONE reference — the same single-snapshot reasoning as
     * [SuppressionSnapshot].
     */
    private class FxCoverageSnapshot(
        val covered: Set<Pair<String, String>>,
        val fxListEpoch: Long,
        val structureVersion: Long,
    )

    // Effect-coverage snapshot for the Layer 4 / cascade publish paths, cached until the
    // effect list or the fixture register moves. Crossfade republishes consult this at
    // ~62 fps and used to rebuild it per frame from a [resolveFxFixtureKeys] walk over
    // every active effect (sweep item C3). [fxListEpoch] is bumped immediately *after*
    // each mutation and *before* any repaint or publish the same flow goes on to do — in
    // [rebuildSortedSnapshots] for the map mutations, explicitly at the isRunning flips and
    // [updateFx]'s non-swap path (see [rebuildSortedSnapshots] for why not
    // [emitStateUpdate]). A rebuild racing a cross-thread mutation publishes the older epoch
    // with its set, so the next read detects the mismatch and rebuilds — one stale publish
    // at worst, never a latch, and the mutating flow's own repaint runs on fresh coverage.
    @Volatile private var fxCoverageCache =
        FxCoverageSnapshot(emptySet(), fxListEpoch = -1L, structureVersion = -1L)
    private val fxListEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    /** The (fixtureKey, attribute) pairs running effects cover — see [fxCoverageCache]. */
    private fun coveredByRunningFx(): Set<Pair<String, String>> {
        // Read the stamps BEFORE the scan they cover, so a mutation landing mid-scan leaves
        // this rebuild carrying the older stamp and the next read rebuilds.
        val epoch = fxListEpoch.get()
        val version = fixtures.structureVersion
        val cached = fxCoverageCache
        if (cached.fxListEpoch == epoch && cached.structureVersion == version) {
            return cached.covered
        }
        val covered: Set<Pair<String, String>> = if (activeFx.isEmpty()) {
            emptySet()
        } else buildSet {
            for (effect in activeFx.values) {
                if (!effect.isRunning) continue
                val attribute = effect.target.attribute
                for (fixtureKey in resolveFxFixtureKeys(effect)) {
                    add(fixtureKey to attribute)
                }
            }
        }
        fxCoverageCache = FxCoverageSnapshot(covered, epoch, version)
        return covered
    }

    /**
     * Should [effect] skip painting `(fixtureKey, attribute)` this tick? Held by Blind ([BlindHold] —
     * the programmer band while blind), or [FxSuppression]'s rule; provenance and the
     * `programmer.keyStack` read ask both too — see each for its reasons.
     */
    private fun isSuppressed(
        suppression: Map<String, Set<String>>,
        blind: Boolean,
        fixtureKey: String,
        attribute: String,
        effect: FxInstance,
    ): Boolean = BlindHold.holds(blind, effect) || FxSuppression.isSuppressed(
        suppression, fixtureKey, attribute, effect, isLayerStomped,
    )

    private fun rebuildSortedSnapshots() {
        // Every mutation of [activeFx] calls this immediately afterwards — before any
        // repaint or Layer 4 publish the same flow goes on to do — which makes it the earliest
        // reliable place to invalidate the effect-coverage cache. Bumping later (say, in
        // [emitStateUpdate]) leaves the mutation→bump window spanning those publishes, and a
        // publish reading stale coverage skips keys nothing will repaint. The isRunning flips
        // ([pauseFx]/[resumeFx]/the tick-failure auto-pause) and [updateFx]'s
        // non-swap path don't come through here and bump [fxListEpoch] themselves.
        fxListEpoch.incrementAndGet()
        synchronized(fxSnapshotLock) {
            val all = ArrayList<FxInstance>(activeFx.values)
            all.sortWith(sortedFxComparator)
            sortedFx = all
        }
    }

    private var beatWakeJob: Job? = null
    private var tickerJob: Job? = null
    private var passJob: Job? = null

    /**
     * When set, newly added effects without a cueId are automatically tagged
     * with this context. Used by [CueTriggerManager] to auto-tag effects
     * created during FX_APPLICATION script execution.
     *
     * Thread-safe because FX_APPLICATION scripts run on a single-threaded runner pool.
     */
    @Volatile
    var currentCueContext: CueContext? = null

    companion object {
        /** Wall-clock tick interval in milliseconds (50Hz) */
        const val WALL_CLOCK_INTERVAL_MS = 20L

        /** The shortest gap between two compose passes: wakes inside it wait and coalesce. */
        const val MIN_PASS_INTERVAL_MS = 5L

        /**
         * How many consecutive passes an effect may throw out of before the engine pauses it.
         *
         * In passes its own clock woke rather than seconds, so the grace period varies with how
         * fast the desk is ticking: ~2.4s for a wall-clock effect on the 50 Hz ticker, 5s for a
         * beat effect under a lone master at 60 BPM (24 ticks/beat), 1s for one at 300 BPM, and
         * less again with several out-of-phase masters waking the pass. See [noteTickFailure] for
         * why that direction is the safe one.
         */
        const val MAX_CONSECUTIVE_TICK_FAILURES = 120

        /**
         * Reserved priority band for programmer-owned effects (Session 2's busking FX).
         * Strictly above every cue priority — a stack hold's
         * (`cueDerivedPriority(stackId, sort) = stackId*1M + sort*1K + 1`, `fx/CueApply.kt`) and a
         * loose hold's ([CueHolds.LOOSE_BAND] `+ seq`) — and every manual effect (priority 0). Effects in
         * this band are exempt from programmer suppression — they modulate *on top of*
         * programmer values rather than being overridden by them.
         */
        const val PROGRAMMER_FX_PRIORITY_BASE = Int.MAX_VALUE - 1_000_000

        /**
         * Clamp for a programmer layer's rank offset within the band
         * ([ProgrammerLayerStack.priorityFor]). An order of magnitude below the band's
         * 1,000,000 width, so it is a backstop against a runaway rank rather than a bound
         * any real cue stack approaches.
         */
        const val PROGRAMMER_FX_RANK_CLAMP = 100_000

        /**
         * Where Local FX start within the band (FX editor plan D5, §3.5): `BASE + LOCAL_OFFSET + localRank`,
         * above every programmer layer's `BASE + layer index`, as Local values sit above every layer.
         * Above [PROGRAMMER_FX_RANK_CLAMP], so no layer rank reaches it.
         */
        const val LOCAL_OFFSET = 200_000

        /** True when [priority] sits in the programmer-owned effect band. */
        fun isProgrammerFxPriority(priority: Int): Boolean = priority >= PROGRAMMER_FX_PRIORITY_BASE

        /** The priority of a Local FX at [localRank]. */
        fun localFxPriority(localRank: Int): Int =
            PROGRAMMER_FX_PRIORITY_BASE + LOCAL_OFFSET + localRank.coerceIn(0, PROGRAMMER_FX_RANK_CLAMP)

        /** A Local FX's rank in the band, or -1 for a priority below the Local FX's part of it. */
        fun localRankOf(priority: Int): Int =
            (priority - (PROGRAMMER_FX_PRIORITY_BASE + LOCAL_OFFSET)).coerceAtLeast(-1)
    }

    /**
     * FX state snapshots for WebSocket broadcasting.
     *
     * A [MutableStateFlow] rather than a replay-1 [MutableSharedFlow] for two reasons: "no
     * effects are running" becomes an observable value instead of the absence of one, so a
     * fresh connection's `fxState` snapshot is unconditional (the one-snapshot rule in
     * docs/websocket-engineering.md) rather than contingent on some effect having been added
     * since boot; and the old `tryEmit` against a one-slot buffer could silently *drop* an
     * update if a subscriber was mid-send, whereas a StateFlow always holds the latest.
     */
    private val _fxStateFlow = MutableStateFlow(FxStateUpdate(activeFxIds = emptyList(), fxStates = emptyMap()))
    val fxStateFlow: StateFlow<FxStateUpdate> = _fxStateFlow.asStateFlow()

    // --- E1 split components ---
    //
    // Four components extracted from what used to be a ~3,400-line engine (sweep item E1):
    // the engine keeps the compose pass and the effect set; these own the rest. Constructed in
    // dependency order — the lambdas defer the two cyclic edges (cueLayer → provenance,
    // provenance → the engine's effect set) until first call, which is safely after
    // construction.

    /**
     * Layer-4/cascade → controller transmit machinery, and the Layer 4 publish lock.
     * Public for [CascadePublisher.resolveChannelCoveringKey], which routes consult; the
     * publish entry points themselves are internal.
     */
    val cascade: CascadePublisher = CascadePublisher(
        fixtures, layerResolver, parkManager, ::coveredByRunningFx, throttle,
    )

    /** Per-cue Layer 4 assignment bookkeeping and the within-cue stomp registry. */
    val cueLayer: CueAssignmentLayer = CueAssignmentLayer(layerResolver, cascade) { weightsOnly ->
        provenance.emitUpdate(cueFadeOnly = weightsOnly)
    }

    /**
     * [cueLayer]'s stomp check as one function value, built once: the compose pass asks
     * [FxSuppression.isSuppressed] per effect per member per pass, and a bound reference
     * written at the call site would allocate on every one of those.
     */
    private val isLayerStomped: (FxInstance, String, String) -> Boolean = cueLayer::isLayerStomped

    /** Each cue stack's FX amount (D10), set by the show's [CueStackManager]; null weighs nothing. */
    @Volatile
    var stackFxAmounts: StackFxAmounts? = null

    /** [effect]'s weight on this pass (P3): its own [FxInstance.weight] times its stack's size. */
    internal fun weightOf(effect: FxInstance, dyn: FxDynamics): Double =
        effect.weight(dyn, stackFxAmounts?.weightOf(effect) ?: StackFxAmounts.FULL)

    /**
     * Provenance computation + broadcast. The lambdas hand it read access to the engine's
     * live effect set without a service → engine reference.
     */
    val provenance: ProvenanceService = ProvenanceService(
        fixtures, programmerStore, layerResolver, cascade,
        activeFx = { activeFx.values },
        coverageKeys = ::resolveFxFixtureKeys,
        isLayerStomped = cueLayer::isLayerStomped,
        cueStackIdFor = cueLayer::cueStackIdFor,
        fxOrder = sortedFxComparator,
        programmerSuppression = ::programmerSuppression,
        fxWeight = { weightOf(it, it.dynamics) },
    )

    /** PROGRAMMER-layer write delegation. */
    val programmer: ProgrammerWriter = ProgrammerWriter(
        fixtures, programmerStore, cascade,
        onBlindLifting = { fadeMs -> growProgrammerBand(fadeMs) },
    ) {
        provenance.emitUpdate()
    }

    /**
     * Rewrite the composition priority of live rows owned by the cues in [priorities]
     * (`cueId → new priority`).
     *
     * A live cue's priority is its highest hold's ([CueHolds]) and is stamped as it comes on, so a
     * hold taken or let go, or a stack reordered under a held cue, would otherwise leave it
     * composing at its *old* priority until it was re-applied. The registry hands the fresh value
     * here whenever a hold changes it ([CueHolds.restampEntries] for a reorder).
     *
     * Touches both layers a cue can own — Layer 3 [FxInstance.priority] and the Layer 4
     * assignment rows — and leaves the crossfade weights alone so a repriority mid-crossfade doesn't
     * disturb the fade. Entries whose priority already matches are skipped, which makes the
     * common single-live-cue reorder a no-op. Returns the number of rows changed.
     */
    fun repriorityCues(priorities: Map<Int, Int>): Int {
        if (priorities.isEmpty()) return 0
        var changed = 0

        // Layer 3 — mutate in place, then re-sort the snapshot the compose pass reads.
        val staleFx = activeFx.values.filter { effect ->
            val target = priorities[effect.cueId ?: return@filter false] ?: return@filter false
            effect.priority != target
        }
        if (staleFx.isNotEmpty()) {
            for (effect in staleFx) effect.priority = priorities.getValue(effect.cueId!!)
            changed += staleFx.size
            rebuildSortedSnapshots()
            emitStateUpdate()
        }

        // Layer 4 — the assignment rows live in [cueLayer]; it rebuilds them by copy.
        changed += cueLayer.repriorityAssignments(priorities)
        return changed
    }

    /**
     * Remove the effects tagged with [stackId] that no cue owns. A cue's effects are removed by cue,
     * when its last hold goes ([CueHolds]): a stack moving on must not take down the effects of a cue
     * another hold keeps live (cue model plan D3). What is left for this is a stack's own non-cue
     * effects, if any remain.
     *
     * @return Number of effects removed
     */
    fun removeFxForCueStack(stackId: Int): Int =
        removeWhere { it.cueStackId == stackId && it.cueId == null }

    /**
     * Remove every effect of [cueId] — its layers', its ad-hoc ones, its timed fires' — leaving its
     * Layer 4 rows alone, which a crossfade may still be taking down. [CueHolds] calls this when the
     * cue's last hold goes.
     *
     * @return Number of effects removed
     */
    fun removeCueFx(cueId: Int): Int = removeWhere { it.cueId == cueId }

    // ─── Envelope fades (effects plan §3.5) ───────────────────────────────────────────────────

    /**
     * Set when an instance was marked leaving, cleared by a [retireFinishedLeaving] sweep that
     * found none left — so the 50 Hz sweep walks the effect list only while a fade-out is running.
     */
    @Volatile private var leavingPending = false

    /**
     * Grow [effects] from where each stands to full over [durationMs] on [curve] (a press, a GO's
     * arrival, a cue brought back on) — a 0 ms fade lands them at full now. A leaving instance taken
     * this way is no longer leaving; a retired one is skipped. Returns how many were set.
     */
    fun fadeInFx(
        effects: Collection<FxInstance>,
        durationMs: Long,
        curve: EasingCurve = EasingCurve.LINEAR,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = effects.count { effect ->
        effect.updateEnvelope { FxEnvelope.ramp(it.at(nowMs), 1.0, durationMs, curve, nowMs) }
    }

    /**
     * Let [effects] go over [durationMs] on [curve] (effects plan D1, D13): each shrinks from where it
     * stands to 0, leaving ([FxInstance.leaving]), and is removed once there by
     * [retireFinishedLeaving]. A 0 ms fade removes them now, which is the snap. Returns how many
     * were let go.
     */
    fun fadeOutFx(
        effects: Collection<FxInstance>,
        durationMs: Long,
        curve: EasingCurve = EasingCurve.LINEAR,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        if (effects.isEmpty()) return 0
        if (durationMs <= 0) return removeInstances(effects)
        val leaving = effects.count { effect ->
            effect.updateEnvelope { FxEnvelope.ramp(it.at(nowMs), 0.0, durationMs, curve, nowMs, leaving = true) }
        }
        if (leaving > 0) leavingPending = true
        // The leaving instances drop out of the band's lists (`programmerLayerFx`, the Local
        // rule); clients read `FxDto.leaving` off this broadcast.
        emitStateUpdate()
        return leaving
    }

    /**
     * Whether [effect] paints a key [valueMoves] names — a (fixture, property) where the programmer
     * value under it is about to move.
     */
    fun paintsMovingValue(effect: FxInstance, valueMoves: (fixtureKey: String, attribute: String) -> Boolean): Boolean {
        val attribute = effect.target.attribute
        return fixtureKeysCoveredBy(effect).any { valueMoves(it, attribute) }
    }

    /**
     * Let the programmer's [effects] go at [fadeMs] (effects plan D13), split by what is under each:
     * one painting a key whose programmer value this same gesture moves
     * ([valueMoves]) is stopped now, so that value ramps — a key an effect covers never fades, and the
     * value under a shrinking effect would snap; every other one shrinks to nothing over the fade. At
     * 0 every one is stopped. Returns how many were let go.
     */
    fun releaseFx(
        effects: Collection<FxInstance>,
        fadeMs: Long,
        valueMoves: (fixtureKey: String, attribute: String) -> Boolean,
    ): Int {
        if (fadeMs <= 0) return fadeOutFx(effects, 0)
        val (stop, shrink) = effects.partition { paintsMovingValue(it, valueMoves) }
        return removeInstances(stop) + fadeOutFx(shrink, fadeMs)
    }

    /**
     * Take [effects] back from a fade-out: a cue brought on again before its fade-out ended keeps
     * its instances, phase intact. Each is held at the level it
     * had reached, no longer leaving, for the caller to fade on from there. Answers the ones taken
     * back; an instance missing from the answer was retired first and is gone.
     */
    fun reclaimFx(effects: Collection<FxInstance>, nowMs: Long = System.currentTimeMillis()): List<FxInstance> =
        effects.filter { effect -> effect.updateEnvelope { FxEnvelope.constant(it.at(nowMs)) } }

    /** Hold [effects] where they stand now, ending any fade — a cancelled GO's arrival is pinned at full. */
    fun settleFx(effects: Collection<FxInstance>, level: Double = 1.0): Int =
        effects.count { effect -> effect.updateEnvelope { FxEnvelope.constant(level) } }

    /**
     * Rewrite live [effects]' ownership — cue, stack, priority, layer — with [retag], as one mutation:
     * one re-sort for the compose pass, one broadcast. A cue's GO adopting the outgoing cue's continuing
     * effects (effects plan P4), and a cue taking its fading ones back.
     */
    fun retagFx(effects: Collection<FxInstance>, retag: (FxInstance) -> Unit) {
        if (effects.isEmpty()) return
        effects.forEach(retag)
        rebuildSortedSnapshots()
        emitStateUpdate()
    }

    /**
     * Grow the programmer-band instances from nothing over [fadeMs] — leaving Blind, which held them
     * (effects plan §3.5). By [releaseFx]' rule: one over a value the programmer holds lands at
     * full, as before, since that value's own Blind fade would snap under it. A leaving one is left to
     * finish going. A 0 ms fade leaves them as they are.
     */
    private fun growProgrammerBand(fadeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (fadeMs <= 0) return
        for (effect in activeFx.values) {
            if (!isProgrammerFxPriority(effect.priority) || effect.leaving) continue
            if (paintsMovingValue(effect) { key, attribute -> programmerStore.get(key, attribute) != null }) continue
            effect.updateEnvelope { if (it.leaving) it else FxEnvelope.ramp(0.0, 1.0, fadeMs, EasingCurve.LINEAR, nowMs) }
        }
    }

    /**
     * Let every programmer-band instance go over [fadeMs] — the programmer's Clear (effects plan
     * D13), by [releaseFx]' rule against everything the programmer holds, which Clear releases.
     * A 0 ms fade removes them now, as [removeProgrammerBandFx] does. Returns how many.
     */
    fun fadeOutProgrammerBandFx(fadeMs: Long): Int =
        if (fadeMs <= 0) removeProgrammerBandFx()
        else releaseFx(activeFx.values.filter { isProgrammerFxPriority(it.priority) && !it.leaving }, fadeMs) { key, attribute ->
            programmerStore.get(key, attribute) != null
        }

    /**
     * Give [effects] fresh compose orders in the order given, so same-priority effects compose in
     * that order whatever they were created in — a cue's layer order over instances it carried over
     * from another cue or from its own fade-out.
     */
    fun restampComposeOrder(effects: List<FxInstance>) {
        if (effects.isEmpty()) return
        for (effect in effects) effect.composeOrder = nextFxId.incrementAndGet()
        rebuildSortedSnapshots()
    }

    /** Every live instance of [cueId] that is leaving — what its fade-out is still taking down. */
    fun leavingCueFx(cueId: Int): List<FxInstance> =
        activeFx.values.filter { it.cueId == cueId && it.leaving }

    /** Every live instance of [cueId] that is not leaving. */
    fun cueFx(cueId: Int): List<FxInstance> =
        activeFx.values.filter { it.cueId == cueId && !it.leaving }

    /**
     * Remove now every leaving instance [predicate] picks — a fade cut short, its instances snapped
     * off as its rows are dropped. Each is retired first, so a cue being brought back on at the same
     * moment cannot reclaim it after it is gone. Returns how many were removed.
     */
    fun retireLeaving(predicate: (FxInstance) -> Boolean): Int {
        val gone = activeFx.values.filter { effect ->
            if (!predicate(effect)) return@filter false
            val envelope = effect.envelope
            envelope === FxEnvelope.RETIRED || (envelope.leaving && effect.retire(envelope))
        }
        return removeInstances(gone)
    }

    /**
     * Remove every leaving instance whose fade has run to 0. Swept by every ticker-woken pass, at
     * 50 Hz whether or not any effect is running, so a fade-out ends on time even when every effect is
     * paused. `internal` so tests can sweep at a chosen instant.
     */
    internal fun retireFinishedLeaving(nowMs: Long = System.currentTimeMillis()): Int {
        if (!leavingPending) return 0
        leavingPending = false
        val leaving = activeFx.values.filter { it.leaving }
        if (leaving.isEmpty()) return 0
        // An instance already retired is still here only when an edit swapped it in after the
        // retirement and before the removal ([updateFx] shares the envelope cell): it goes now.
        val gone = leaving.filter { effect ->
            val envelope = effect.envelope
            envelope === FxEnvelope.RETIRED || (envelope.spentAt(nowMs) && effect.retire(envelope))
        }
        val removed = removeInstances(gone)
        if (removed < leaving.size) leavingPending = true
        return removed
    }

    /** False stops the wall-clock pass sweeping leaving instances, so a test sweeps at its own instants. */
    @Volatile internal var sweepsLeaving: Boolean = true

    /**
     * Remove exactly these instances, as one mutation. By identity: an id an edit has since swapped
     * for a new instance ([updateFx]) is that edit's, and stays.
     */
    private fun removeInstances(effects: Collection<FxInstance>): Int {
        val removed = effects.filter { activeFx.remove(it.id, it) }
        if (removed.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredAttributes(removed)
            emitStateUpdate()
        }
        return removed.size
    }

    /** Remove every effect matching [predicate], rebuilding the compose pass's snapshot once. */
    private fun removeWhere(predicate: (FxInstance) -> Boolean): Int {
        val toRemove = activeFx.values.filter(predicate)
        toRemove.forEach { activeFx.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            // The sorted snapshot is read lock-free by [composePass].
            // Without rebuilding, the compose pass keeps firing the orphaned [FxInstance] refs
            // against their old targets — one of the reasons a cue's effects would "refuse to
            // die" after a cue transition.
            rebuildSortedSnapshots()
            resetUncoveredAttributes(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Represents a state update for broadcasting. Carries [FxDto] directly: the effect
     * report has one shape across every transport, so there is nothing left for a
     * broadcast-only intermediate to add (sweep item F8).
     */
    data class FxStateUpdate(
        val activeFxIds: List<Long>,
        val fxStates: Map<Long, FxDto>
    )

    /**
     * Start the FX engine.
     *
     * @param scope The coroutine scope to run the engine in
     */
    fun start(scope: CoroutineScope) {
        speedMasters.start(scope)
        provenance.start(scope)

        // Two wake sources and one pass. Any master's tick (the bank's wake channel, CONFLATED,
        // so ticks from N masters landing while a pass is in flight collapse into one) and the
        // 50 Hz ticker each raise their flag and wake [passJob], which runs one [composePass]
        // over every running effect, at most every [MIN_PASS_INTERVAL_MS]: a wake inside that
        // gap waits for it, and every wake that lands meanwhile joins the same pass. The flags
        // tell the pass which clocks woke it — see [composePass] for what that decides.
        //
        // The pass loop swallows a failed pass rather than letting it out: anything escaping here
        // cancels the job for the rest of the process, and the desk's effects would stop for
        // good with one line on whatever the uncaught-exception handler is. The per-effect
        // catches inside a pass cover the common case; this covers the pass-level work around
        // them (the reset sweep, the suppression rebuild, the transaction commit). Throwable
        // rather than Exception for the same reason those are — a script effect can raise an
        // Error — with cancellation rethrown so [stop] still stops the loop.
        val beatPasses = DeskMetrics.global.fxTick(DeskMetrics.FxLoop.BEAT)
        val wallClockPasses = DeskMetrics.global.fxTick(DeskMetrics.FxLoop.WALL_CLOCK)
        val beatWoke = AtomicBoolean(false)
        val tickerWoke = AtomicBoolean(false)
        val passWake = Channel<Unit>(Channel.CONFLATED)

        beatWakeJob = scope.launch(Dispatchers.Default) {
            for (wake in speedMasters.wake) {
                beatWoke.set(true)
                passWake.trySend(Unit)
            }
        }

        // Wall-clock ticker (50Hz, independent of BPM)
        tickerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(WALL_CLOCK_INTERVAL_MS)
                tickerWoke.set(true)
                passWake.trySend(Unit)
            }
        }

        passJob = scope.launch(Dispatchers.Default) {
            var lastPassStartNs = System.nanoTime() - MIN_PASS_INTERVAL_MS * 1_000_000
            for (wake in passWake) {
                val waitNs = lastPassStartNs + MIN_PASS_INTERVAL_MS * 1_000_000 - System.nanoTime()
                if (waitNs > 0) delay((waitNs + 999_999) / 1_000_000)
                val beat = beatWoke.getAndSet(false)
                val ticker = tickerWoke.getAndSet(false)
                if (!beat && !ticker) continue
                val passStart = System.nanoTime()
                try {
                    // A skipped pass opens no gap: the next wake is not held for it.
                    if (composePass(beat = beat, ticker = ticker, frame = null, nowMs = System.currentTimeMillis())) {
                        lastPassStartNs = passStart
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastPassStartNs = passStart
                    throttle.log("compose-pass", t) { "FX engine: compose pass failed" }
                }
                (if (beat) beatPasses else wallClockPasses).record(System.nanoTime() - passStart)
            }
        }
    }

    /**
     * Stop the FX engine and all active effects.
     */
    fun stop() {
        provenance.stop()
        beatWakeJob?.cancel()
        beatWakeJob = null
        tickerJob?.cancel()
        tickerJob = null
        passJob?.cancel()
        passJob = null
        speedMasters.stop()
        val allFx = activeFx.values.toList()
        activeFx.clear()
        rebuildSortedSnapshots()
        cueLayer.clearAll()
        resetUncoveredAttributes(allFx)
        emitStateUpdate()
    }

    /**
     * Add an effect and return its ID.
     *
     * @param effect The effect instance to add
     * @return The assigned effect ID
     */
    fun addFx(effect: FxInstance): Long {
        val id = insert(effect)
        rebuildSortedSnapshots()
        emitStateUpdate()
        return id
    }

    /**
     * Add several effects as **one** mutation, returning their IDs in the order given.
     *
     * Spawning a cue's or a programmer stack's effects one at a time made
     * [rebuildSortedSnapshots] and [emitStateUpdate] run once per effect, and each of those
     * walks *every* active effect — [emitStateUpdate] with a group/multi-element lookup per
     * entry — so a cue with N effects cost O(N²) (sweep item C7). Batching the inserts leaves
     * exactly one rebuild and one broadcast for the whole spawn.
     *
     * IDs still come from [nextFxId] in list order, which is what keeps spawn order equal
     * to composition order for same-priority effects — see the note on `sortedFxComparator`
     * at the cue apply sites. The one rebuild lands before any repaint or Layer 4 publish the
     * calling flow goes on to do, which is the invariant [rebuildSortedSnapshots] documents.
     */
    fun addFxBatch(effects: List<FxInstance>): List<Long> {
        if (effects.isEmpty()) return emptyList()
        val ids = effects.map { insert(it) }
        rebuildSortedSnapshots()
        emitStateUpdate()
        return ids
    }

    /**
     * Stamp an instance and put it in [activeFx], without rebuilding or broadcasting.
     * Every caller must follow with [rebuildSortedSnapshots] then [emitStateUpdate].
     *
     * [carried] is for a copy that goes on running an effect already live ([headCopies]): it keeps
     * the start time and compose order it was given, and a stateful effect it shares with its
     * parent is not reset.
     */
    private fun insert(effect: FxInstance, carried: Boolean = false): Long {
        val id = nextFxId.incrementAndGet()
        effect.id = id
        if (!carried) {
            effect.startedAtMs = System.currentTimeMillis()
            effect.composeOrder = id
        }

        // Auto-tag with CueContext if set and effect doesn't already have a cueId.
        // Programmer-band effects are exempt: they belong to the operator's programmer, not
        // to whatever cue happens to be running an FX_APPLICATION script at the time, and a
        // stray cue tag would let `removeCueFx` sweep them out from under a busk.
        if (!isProgrammerFxPriority(effect.priority)) {
            currentCueContext?.let { ctx ->
                if (effect.cueId == null) effect.cueId = ctx.cueId
                if (effect.cueStackId == null) effect.cueStackId = ctx.cueStackId
            }
        }

        if (!carried && effect.effect is StatefulFx) {
            effect.effect.initialize()
        }

        // Bind the persisted master uuids to runtime bank slots. Unknown or null → master 1
        // for the beat master; for the rate master only *unknown* does, since null there
        // means no rate master at all — see [rateSlotFor].
        effect.speedMasterSlot = speedMasters.slotFor(effect.speedMasterUuid)
        effect.rateMasterSlot = rateSlotFor(effect.rateSpeedMasterUuid)

        if (!carried && effect.isLocalFx) {
            // Ranked and published under the one lock, so a move or another insert sees it.
            synchronized(localRankLock) {
                effect.priority = localFxPriority(topLocalRankFor(effect))
                activeFx[id] = effect
            }
        } else {
            activeFx[id] = effect
        }
        return id
    }

    /**
     * Remove an effect by ID.
     *
     * @param fxId The ID of the effect to remove
     * @return true if an effect was removed
     */
    fun removeFx(fxId: Long): Boolean {
        val removed = activeFx.remove(fxId)
        if (removed != null) {
            rebuildSortedSnapshots()
            resetUncoveredAttributes(listOf(removed))
            emitStateUpdate()
        }
        return removed != null
    }

    /**
     * Remove several effects as **one** mutation — one rebuild, one reset of what they left
     * uncovered, one broadcast — returning how many were live. `programmer.clearTarget`'s effect
     * half; [removeFx] in a loop would rebuild and broadcast the whole list once per effect.
     */
    fun removeFxBatch(fxIds: Collection<Long>): Int {
        val removed = fxIds.mapNotNull { activeFx.remove(it) }
        if (removed.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredAttributes(removed)
            emitStateUpdate()
        }
        return removed.size
    }

    /**
     * Replace each key of [replace] by its copies, and add [fresh], as **one** mutation: one
     * rebuild, one reset of what the replaced left uncovered (read after the adds, so a head the new
     * instances paint never drops to the layer below for a tick) and one broadcast. A key with no
     * copies is stopped. Returns the ids actually replaced: a parent another request removed or
     * swapped first is skipped with its copies, so a race cannot bring a stopped effect back.
     *
     * Each copy takes its parent's running state again at the moment of the swap, so a pause or a
     * dynamics edit that reached the parent after [headCopies] built the copies is not lost.
     */
    fun replaceFx(
        replace: Map<FxInstance, List<FxInstance>>,
        fresh: List<FxInstance> = emptyList(),
    ): Set<Long> {
        // By identity, so a copy is only ever added for a parent this call took out. The compose
        // pass keeps painting the parent from the sorted snapshot until the one rebuild below.
        // Under the rank lock, so a move between building the copies and this swap reaches them.
        val removed = synchronized(localRankLock) {
            val removed = replace.keys.filter { activeFx.remove(it.id, it) }
            for (parent in removed) {
                for (copy in replace.getValue(parent)) {
                    copy.takeRunningStateFrom(parent)
                    insert(copy, carried = true)
                }
            }
            fresh.forEach { insert(it) }
            removed
        }
        if (removed.isEmpty() && fresh.isEmpty()) return emptySet()
        rebuildSortedSnapshots()
        resetUncoveredAttributes(removed)
        emitStateUpdate()
        return removed.mapTo(HashSet()) { it.id }
    }

    /**
     * One head a running effect paints, as its tick pass spreads it (apply-model plan §3.4): the
     * offset, list size and context the pass hands the phase function and the effect for it.
     */
    data class PaintedHead(
        val key: String,
        val offset: Double,
        val groupSize: Int,
        val context: FxContext,
        /** Under [ElementMode.WHOLE], the index of the fixture the head belongs to; null otherwise. */
        val unit: Int? = null,
    )

    /**
     * The most slots any list [effect] spreads over has after its shape ([FxContext.numDistinctSlots]),
     * at least 1: what a `STEP` spread is turned into a `CYCLE` one over ([SpreadShaping.cycleSpread]).
     */
    fun spreadSlots(effect: FxInstance): Int =
        paintedHeads(effect).maxOfOrNull { it.context.numDistinctSlots }?.coerceAtLeast(1) ?: 1

    /**
     * The heads [effect] paints, in its order, each as the tick pass spreads it under its current
     * dynamics. Heads that paint nothing are left out: an element filter's, and a heterogeneous
     * group's members that lack the property (the group takes its first member's branch).
     */
    fun paintedHeads(effect: FxInstance): List<PaintedHead> {
        val dyn = effect.dynamics
        val expansion = expansionFor(effect, dyn)
        fun spread(keys: List<String>, plan: DistributionPlan): List<PaintedHead> =
            keys.mapIndexed { i, key -> PaintedHead(key, plan.offsets[i], keys.size, plan.contexts[i]) }
        fun whole(plan: DistributionPlan): List<PaintedHead> =
            expansion.whole.flatMapIndexed { i, keys ->
                keys.map { key -> PaintedHead(key, plan.offsets[i], expansion.whole.size, plan.contexts[i], unit = i) }
            }
        return when (expansion.kind) {
            FxTargetExpansion.Kind.NONE -> emptyList()
            FxTargetExpansion.Kind.DIRECT_FIXTURE -> {
                val carry = effect.splitMember
                listOf(
                    if (carry == null) PaintedHead(effect.target.targetKey, 0.0, 1, FxContext.SINGLE)
                    else PaintedHead(effect.target.targetKey, carry.offset, carry.groupSize, carry.context),
                )
            }
            FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> {
                val plans = plansFor(effect, expansion, dyn)
                if (dyn.elementMode == ElementMode.WHOLE) whole(plans.whole) else spread(expansion.flat, plans.flat)
            }
            FxTargetExpansion.Kind.GROUP_MEMBERS -> {
                val plan = plansFor(effect, expansion, dyn).members
                expansion.members.mapIndexedNotNull { i, member ->
                    val fixture = runCatching { fixtures.untypedGroupableFixture(member.key) }.getOrNull()
                    if (fixture == null || !effect.target.fixtureHasAttribute(fixture)) return@mapIndexedNotNull null
                    PaintedHead(member.key, plan.offsets[i], expansion.members.size, plan.contexts[i])
                }
            }
            FxTargetExpansion.Kind.GROUP_ELEMENTS -> {
                val plans = plansFor(effect, expansion, dyn)
                when (dyn.elementMode) {
                    ElementMode.WHOLE -> whole(plans.whole)
                    ElementMode.PER_FIXTURE -> expansion.perFixture.flatMapIndexed { i, keys -> spread(keys, plans.perFixture[i]) }
                    ElementMode.FLAT -> spread(expansion.flat, plans.flat)
                }
            }
        }
    }

    /**
     * [effect] rewritten as one instance per head it paints, less [drop] (apply-model plan §3.4):
     * built, not added — [replaceFx] swaps them in. The split and the Local replacement's
     * narrowing both start here.
     *
     * Each copy is the member it was ([SplitMember]) and keeps the parent's phase offset, compose
     * order, clocks, masters, timing, envelope and band, so nothing on stage moves. It shares the
     * parent's effect object, as the members did, and keeps the parent's cue Include origin, which
     * Update reads to retire the child the copies now stand for. A Look Include's is not kept: a
     * Look's effect is written back whole.
     */
    fun headCopies(effect: FxInstance, drop: Set<String> = emptySet()): List<FxInstance> {
        // A stateful effect under WHOLE steps once a fixture; its copies share that step, and a copy
        // split again keeps the one it has.
        val steps = HashMap<Int, SharedStep>()
        fun stepFor(head: PaintedHead): SharedStep? = effect.splitMember?.step
            ?: if (effect.effect is StatefulFx) head.unit?.let { steps.getOrPut(it) { SharedStep() } } else null
        return paintedHeads(effect).filter { it.key !in drop }.map { head ->
            val target = FxTargetFactory.forFixture(
                head.key, effect.target.attribute, effect.effect.outputType,
                runCatching { fixtures.untypedGroupableFixture(head.key) }.getOrNull(),
            )
            FxInstance(
                effect = effect.effect,
                target = target,
                timing = effect.timing,
                blendMode = effect.blendMode,
                dynamicsRef = AtomicReference(effect.dynamics),
            ).apply {
                composeOrder = effect.composeOrder
                registrationId = effect.registrationId
                priority = effect.priority
                programmerOrigin = effect.programmerOrigin
                startedAtMs = effect.startedAtMs
                startedAtBeat = effect.startedAtBeat
                timingSource = effect.timingSource
                seconds = effect.seconds
                speedMasterUuid = effect.speedMasterUuid
                rateSpeedMasterUuid = effect.rateSpeedMasterUuid
                splitMember = SplitMember(head.groupSize, head.offset, head.context, stepFor(head))
                takeRunningStateFrom(effect)
            }
        }
    }

    /** What a copy takes from its parent each time it is built or swapped in ([headCopies]). */
    private fun FxInstance.takeRunningStateFrom(parent: FxInstance) {
        priority = parent.priority
        dynamicsRef.set(parent.dynamics.copy(elementFilter = ElementFilter.ALL))
        carryEnvelope(parent.envelope)
        lastPhase = parent.lastPhase
        accumulatedScaledMs = parent.accumulatedScaledMs
        runsStartTick = parent.runsStartTick
        runsStartScaledMs = parent.runsStartScaledMs
        spent = parent.spent
    }

    /**
     * Get an effect by ID.
     *
     * @param fxId The effect ID
     * @return The effect instance, or null if not found
     */
    fun getFx(fxId: Long): FxInstance? = activeFx[fxId]

    /**
     * Get all active effect instances.
     */
    fun getActiveFx(): List<FxInstance> = activeFx.values.toList()

    /**
     * [effects] in the order a home stores them (FX editor plan §3.5): two that share a key — an
     * attribute on a head both cover — in apply order, so a home spawning them in this order folds
     * them as the programmer did; any others in the order they were made in, which for FX Include
     * brought back is the order their home stored them in, so an Update that moved nothing moves
     * nothing. Each FX's keys and place are read once, so an FX re-ranked meanwhile cannot make the
     * order contradict itself.
     */
    fun inStoredApplyOrder(effects: Collection<FxInstance>): List<FxInstance> {
        class Placed(val effect: FxInstance, val place: Triple<Int, Long, Long>, val keys: Set<String>)
        val apply = compareBy<Placed>({ it.place.first }, { it.place.second }, { it.place.third })
        val left = effects.map { Placed(it, Triple(it.priority, it.composeOrder, it.id), resolveFxFixtureKeys(it).toHashSet()) }
            .sortedWith(compareBy({ it.place.second }, { it.place.third }))
            .toMutableList()
        fun shareKey(a: Placed, b: Placed) =
            a.effect.target.attribute == b.effect.target.attribute && a.keys.any { it in b.keys }
        val ordered = ArrayList<FxInstance>(left.size)
        while (left.isNotEmpty()) {
            val next = left.first { candidate ->
                left.none { it !== candidate && shareKey(it, candidate) && apply.compare(it, candidate) < 0 }
            }
            left.remove(next)
            ordered += next.effect
        }
        return ordered
    }

    val activeFxCount: Int get() = activeFx.size

    /**
     * Report a batch of live effects. The only shape callers should use: [toFxDto] needs a
     * [SpeedMasterBank.masterStates] snapshot and an [isMultiElementExpanded] answer, both of
     * which only the engine can supply, and hoisting the snapshot once per batch is what keeps
     * this off O(effects x masters).
     */
    fun fxDtos(instances: Collection<FxInstance>): List<FxDto> {
        val masterStates = speedMasters.masterStates()
        return instances.map { it.toFxDto(masterStates, isMultiElementExpanded(it)) }
    }

    /** Report a single live effect; see [fxDtos]. */
    fun fxDto(instance: FxInstance): FxDto =
        instance.toFxDto(speedMasters.masterStates(), isMultiElementExpanded(instance))

    /**
     * The live programmer-*layer* effects, keyed by the identity stamped at spawn.
     *
     * This is the engine owning the band (sweep item E6): `ProgrammerLayerStack.syncFx`
     * classifies against this snapshot instead of keeping its own instance map, so a removal it
     * did not perform — [removeProgrammerBandFx], the FX sheet's remove — needs no
     * reconciliation step to be seen. Band effects with no key (Include's ad-hoc children, a
     * manual busk effect at band priority) are not layer effects and are absent by construction.
     *
     * One instance per key: two live instances sharing a key would collapse to one here, and the
     * loser could then never be retracted by a recook — only a band sweep would take it. The
     * spawn path makes that unreachable (`syncFx` dedupes desired keys and holds its lock
     * across the whole classify-spawn-retract pass), which is why collapsing is safe.
     */
    fun programmerLayerFx(): Map<ProgrammerLayerFxKey, FxInstance> =
        activeFx.values.mapNotNull { effect ->
            // A leaving instance is no longer its layer's (effects plan D13): a release faded it,
            // and the layer pressed again spawns afresh beside it rather than adopting it.
            effect.programmerLayerFxKey?.takeUnless { effect.leaving }?.let { it to effect }
        }.toMap()

    /**
     * Get all active effects targeting a specific group.
     *
     * @param groupName The group name
     * @return List of effect instances targeting this group
     */
    fun getFxForGroup(groupName: String): List<FxInstance> {
        return activeFx.values.filter {
            it.isGroupFx && it.target.targetKey == groupName
        }
    }

    /**
     * Get all active effects directly targeting a specific fixture.
     *
     * @param fixtureKey The fixture key
     * @return List of effect instances directly targeting this fixture
     */
    fun getFxForFixture(fixtureKey: String): List<FxInstance> {
        return activeFx.values.filter {
            !it.isGroupFx && it.target.targetKey == fixtureKey
        }
    }

    /**
     * Get all active effects that indirectly affect a fixture: through group membership, or as one
     * of a selection's units (effects plan D3).
     *
     * @param fixtureKey The fixture key
     * @return Group effects whose groups contain this fixture, and selection effects that reach it
     */
    fun getIndirectFxForFixture(fixtureKey: String): List<FxInstance> {
        val groupNames = fixtures.groupsForFixture(fixtureKey).toSet()
        return activeFx.values.filter {
            when (val ref = it.target.targetRef) {
                is FxTargetRef.GroupRef -> ref.targetKey in groupNames
                is FxTargetRef.SelectionRef -> fixtureKey in selectionUnitKeys(ref, it.id)
                is FxTargetRef.FixtureRef -> false
            }
        }
    }

    /**
     * Update a running effect in place.
     *
     * Dynamics fields (phaseOffset, order, elementMode, elementFilter,
     * timing) are applied as **one** [FxInstance.updateDynamics] call, so a tick can
     * never observe half of an update. Truly immutable fields (effect, timing, blendMode)
     * trigger a swap — a new [FxInstance] replaces the old one, preserving id, start time,
     * and (by sharing the old instance's dynamics cell) the running state and any
     * concurrent pause/resume or dynamics edit. Speed-master reassignment is applied to
     * whichever instance survives.
     *
     * @param fxId The effect ID to update
     * @param newFx New effect (or null to keep existing)
     * @param newTiming New timing (or null to keep existing)
     * @param newBlendMode New blend mode (or null to keep existing)
     * @param newPhaseOffset New phase offset (or null to keep existing)
     * @param newOrder New Order (or null to keep existing)
     * @param newElementMode New element mode (or null to keep existing)
     * @return The updated effect instance, or null if not found
     */
    fun updateFx(
        fxId: Long,
        newFx: Fx? = null,
        newTiming: FxTiming? = null,
        newBlendMode: BlendMode? = null,
        newPhaseOffset: Double? = null,
        newOrder: SpreadOrder? = null,
        newElementMode: ElementMode? = null,
        newElementFilter: ElementFilter? = null,
        newTimingMode: Timing? = null,
        /**
         * Reassign to another speed master (null = keep, like every other param).
         * Returning to the default means passing master 1's uuid — master 1 always
         * exists and behaves identically to the null default.
         */
        newSpeedMasterUuid: java.util.UUID? = null,
        /**
         * Reassign the wall-clock rate master, on the same null-means-keep terms as
         * [newSpeedMasterUuid]. Inert for BEAT effects, which never read a rate scale — the
         * two fields coexist rather than excluding each other, so an effect whose
         * `timingSource` changes doesn't lose the assignment for the mode it isn't in.
         */
        newRateSpeedMasterUuid: java.util.UUID? = null,
        /**
         * The canonical registration id of [newFx], when the caller swapped the effect for one
         * of a different type. Null = keep, like every other param. Passing [newFx] without
         * this leaves [FxInstance.registrationId] naming the type the instance no longer runs.
         */
        newRegistrationId: String? = null,
        /**
         * The timing source of [newFx]'s type, when the caller swapped the type. Null = keep.
         * A beat effect swapped for a wall-clock one (or back) runs on the other loop and reads the
         * other master, so a swap that kept the old source would run the new type on the wrong
         * clock. Only honoured on the swap path, which is the only path a type change takes.
         */
        newTimingSource: TimingSource? = null,
        /**
         * Set **both** masters to exactly these values, null meaning each one's default (master 1;
         * no rate master) — where [newSpeedMasterUuid] / [newRateSpeedMasterUuid] can only ever
         * move a master, never clear one. Reset to template (`POST /fx/{id}/reset`) is the caller:
         * it restores the template's assignment whatever the instance was edited to. Wins over the
         * two single-master parameters when given.
         */
        replaceMasters: MasterAssignment? = null,
        /**
         * Move the effect between *Seconds* and a master (venue-visit plan D7): true onto Seconds,
         * false onto a master, null = keep. A beat effect moving either way changes loop, so this
         * takes the swap path; see [applyTimingChoice] for what each state is. Leaving Seconds puts
         * a beat effect on [newSpeedMasterUuid] (null → master 1) and a wall-clock effect on
         * [newRateSpeedMasterUuid] — the route refuses a wall-clock move that names none, since a
         * null rate master *is* Seconds. A type swap ([newTimingSource]) keeps the choice the
         * instance had.
         */
        newSeconds: Boolean? = null,
        /** Amount (effects plan D2), 0.0–1.0; null = keep. A dynamics field: edited in place. */
        newAmount: Double? = null,
        /** Direction (D5); null = keep. A dynamics field. */
        newDirection: FxDirection? = null,
        /**
         * Runs (D9). Null keeps the count, so [clearRuns] is how an N-shot goes back to forever. A
         * changed count starts again from the next pass, and wakes a spent instance.
         */
        newRuns: Int? = null,
        clearRuns: Boolean = false,
        /**
         * The spread's shape (effects plan D4): Spread 0.0–2.0, Wings and Segments at least 1, Parts 0
         * (off) or more; each null = keep. Dynamics fields, edited in place like the Order, phase kept.
         * The caller has checked them against the Order they will run under (Wings refused under a
         * mirrored one) — [SpreadShaping] ignores such wings anyway.
         */
        newSpread: Double? = null,
        newWings: Int? = null,
        newSegments: Int? = null,
        newParts: Int? = null,
        /** What [newSpread] counts (fx-settings plan D6); null = keep. A dynamics field. */
        newSpreadUnit: SpreadUnit? = null,
    ): FxInstance? = updateFx(
        fxId,
        FxEdit(
            newFx = newFx, newTiming = newTiming, newBlendMode = newBlendMode, newPhaseOffset = newPhaseOffset,
            newOrder = newOrder, newElementMode = newElementMode, newElementFilter = newElementFilter,
            newTimingMode = newTimingMode, newSpeedMasterUuid = newSpeedMasterUuid,
            newRateSpeedMasterUuid = newRateSpeedMasterUuid, newRegistrationId = newRegistrationId,
            newTimingSource = newTimingSource, replaceMasters = replaceMasters, newSeconds = newSeconds,
            newAmount = newAmount, newDirection = newDirection, newRuns = newRuns, clearRuns = clearRuns,
            newSpread = newSpread, newWings = newWings, newSegments = newSegments, newParts = newParts,
            newSpreadUnit = newSpreadUnit,
        ),
    )

    /** One edit of a running FX: [updateFx]'s parameters of the same names, each null (or false) to keep. */
    data class FxEdit(
        val newFx: Fx? = null,
        val newTiming: FxTiming? = null,
        val newBlendMode: BlendMode? = null,
        val newPhaseOffset: Double? = null,
        val newOrder: SpreadOrder? = null,
        val newElementMode: ElementMode? = null,
        val newElementFilter: ElementFilter? = null,
        val newTimingMode: Timing? = null,
        val newSpeedMasterUuid: UUID? = null,
        val newRateSpeedMasterUuid: UUID? = null,
        val newRegistrationId: String? = null,
        val newTimingSource: TimingSource? = null,
        val replaceMasters: MasterAssignment? = null,
        val newSeconds: Boolean? = null,
        val newAmount: Double? = null,
        val newDirection: FxDirection? = null,
        val newRuns: Int? = null,
        val clearRuns: Boolean = false,
        val newSpread: Double? = null,
        val newWings: Int? = null,
        val newSegments: Int? = null,
        val newParts: Int? = null,
        val newSpreadUnit: SpreadUnit? = null,
    ) {
        val touchesDynamics: Boolean
            get() = newPhaseOffset != null || newOrder != null || newElementMode != null || newElementFilter != null ||
                newTimingMode != null || newAmount != null || newDirection != null || newRuns != null || clearRuns ||
                newSpread != null || newWings != null || newSegments != null || newParts != null || newSpreadUnit != null

        /** [dyn] with this edit's dynamics fields. */
        fun applyTo(dyn: FxDynamics): FxDynamics = dyn.copy(
            phaseOffset = newPhaseOffset ?: dyn.phaseOffset,
            order = newOrder ?: dyn.order,
            elementMode = newElementMode ?: dyn.elementMode,
            elementFilter = newElementFilter ?: dyn.elementFilter,
            timing = newTimingMode ?: dyn.timing,
            amount = newAmount ?: dyn.amount,
            direction = newDirection ?: dyn.direction,
            runs = if (clearRuns) null else newRuns ?: dyn.runs,
            spread = newSpread ?: dyn.spread,
            wings = newWings ?: dyn.wings,
            segments = newSegments ?: dyn.segments,
            parts = newParts ?: dyn.parts,
            spreadUnit = newSpreadUnit ?: dyn.spreadUnit,
        )

        /** Whether this edit moves [existing]'s Runs, which then count again from the next pass. */
        fun movesRuns(existing: FxInstance): Boolean =
            (clearRuns && existing.dynamics.runs != null) || (newRuns != null && newRuns != existing.dynamics.runs)

        /** D7: a timing move or a type swap decides the loop and which masters can stand. */
        fun timingMove(existing: FxInstance): Pair<TimingSource, Boolean>? =
            if (newSeconds != null || newTimingSource != null) {
                (newTimingSource ?: existing.registrationTimingSource) to (newSeconds ?: existing.onSeconds)
            } else null

        /** Whether this edit changes a field an instance holds immutably, so it is a swap. */
        fun swaps(existing: FxInstance): Boolean =
            newFx != null || newTiming != null || newBlendMode != null || timingMove(existing) != null
    }

    /** [updateFx], the edit as one [FxEdit]. */
    fun updateFx(fxId: Long, edit: FxEdit): FxInstance? {
        // Again from the top when another request swapped or split the instance under this one.
        while (true) {
            val existing = activeFx[fxId] ?: return null
            val runsChanged = edit.movesRuns(existing)

            // One atomic dynamics update covering both branches: the cell is shared across the
            // swap below, so applying it up front reaches whichever instance survives — and a
            // tick pass sees either none of this update or all of it, never half.
            if (edit.touchesDynamics) existing.updateDynamics(edit::applyTo)
            // A new count runs from now: the anchors re-take on the next pass. Written from this request
            // thread into tick-owned fields, which at worst re-anchors one pass late.
            if (runsChanged) existing.restartRuns()

            val needsSwap = edit.swaps(existing)
            val updated = if (needsSwap) {
                swapFor(existing, edit, existing.dynamicsRef, existing.envelopeCell)
            } else {
                edit.newSpeedMasterUuid?.let {
                    existing.speedMasterUuid = it
                    existing.speedMasterSlot = speedMasters.slotFor(it)
                }
                edit.newRateSpeedMasterUuid?.let {
                    existing.rateSpeedMasterUuid = it
                    existing.rateMasterSlot = rateSlotFor(it)
                }
                edit.replaceMasters?.let { assignMasters(existing, it) }
                existing
            }

            if (needsSwap) {
                // By identity: a plain put would bring back an instance a split has just replaced. The
                // priority is taken again under the rank lock, so a move made since the copy holds.
                val swapped = synchronized(localRankLock) {
                    updated.priority = existing.priority
                    activeFx.replace(fxId, existing, updated)
                }
                if (!swapped) continue
                rebuildSortedSnapshots()
            } else {
                // A non-swap update can still move coverage (elementFilter changes the expansion);
                // see [rebuildSortedSnapshots].
                fxListEpoch.incrementAndGet()
            }
            emitStateUpdate()
            return updated
        }
    }

    /**
     * [existing] with [edit] applied, as [updateFx] would leave it, built and never added (FX editor
     * plan §3.3): the preview of an edit not yet sent. It has its own dynamics and envelope cells, so
     * nothing the running FX holds moves (P4), and keeps the running FX's id, which is how the
     * sampler knows it stands in for it.
     */
    internal fun editedCopy(existing: FxInstance, edit: FxEdit): FxInstance =
        swapFor(
            existing, edit,
            dynamicsRef = AtomicReference(edit.applyTo(existing.dynamics)),
            envelopeRef = AtomicReference(existing.envelope),
        ).apply { if (edit.movesRuns(existing)) restartRuns() }

    private fun FxInstance.restartRuns() {
        runsStartTick = FxInstance.UNANCHORED
        runsStartScaledMs = Double.NaN
        spent = false
    }

    /**
     * The instance that replaces [existing] when [edit] swaps it, its dynamics and envelope in
     * [dynamicsRef] and [envelopeRef]: [updateFx] hands it the existing instance's cells, which is
     * what makes a pause/resume or a fade racing the swap unlosable.
     */
    private fun swapFor(
        existing: FxInstance,
        edit: FxEdit,
        dynamicsRef: AtomicReference<FxDynamics>,
        envelopeRef: AtomicReference<FxEnvelope>,
    ): FxInstance {
        val timingMove = edit.timingMove(existing)
        return FxInstance(
            effect = edit.newFx ?: existing.effect,
            target = existing.target,
            timing = edit.newTiming ?: existing.timing,
            blendMode = edit.newBlendMode ?: existing.blendMode,
            // The dynamics (running state, phase offset, distribution, element
            // mode/filter, step timing) travel by this shared cell, not by copying —
            // which is what makes a pause/resume racing this swap unlosable.
            dynamicsRef = dynamicsRef,
            // The envelope too, so a fade or a retirement racing the swap reaches both.
            envelopeRef = envelopeRef,
        ).apply {
            id = existing.id
            composeOrder = existing.composeOrder
            registrationId = edit.newRegistrationId ?: existing.registrationId
            source = existing.source
            // The layer ids are what `isSuppressed` looks a stomp mask up by, and what
            // Record reads to tell a layer's effect from a loose one. Dropping them here
            // un-stomps an edited layer effect and makes Record write it back a second
            // time as an ad-hoc child.
            cueLayerId = existing.cueLayerId
            programmerLayerFxKey = existing.programmerLayerFxKey
            programmerOrigin = existing.programmerOrigin
            includedLookFx = existing.includedLookFx
            cueId = existing.cueId
            cueStackId = existing.cueStackId
            priority = existing.priority
            layerFxUuid = existing.layerFxUuid
            runUuid = existing.runUuid
            startedAtMs = existing.startedAtMs
            startedAtBeat = existing.startedAtBeat
            lastPhase = existing.lastPhase
            // Wall-clock phase derives from this and nothing else, so dropping it here
            // would snap an edited wall-clock effect back to the start of its cycle —
            // the very discontinuity the accumulator replaced `startedAtMs` to avoid.
            accumulatedScaledMs = existing.accumulatedScaledMs
            // An N-shot's count runs on across an edit, as its phase does.
            runsStartTick = existing.runsStartTick
            runsStartScaledMs = existing.runsStartScaledMs
            spent = existing.spent
            timingSource = existing.timingSource
            seconds = existing.seconds
            // A split-off copy's member (apply-model plan §3.4): without it an edited copy
            // loses its set's step cycle, sweep and N-shot lag.
            splitMember = existing.splitMember
            // `expansion` is deliberately NOT carried across: the fresh instance rebuilds
            // it on first read, which costs one walk and is the safe direction. Carrying it
            // would survive a future `updateFx` that learns to retarget, and the effect
            // would silently keep painting the old fixtures.
            //
            // Master assignment must survive the swap: this block hand-enumerates every
            // carried field, and missing one silently yanks an edited effect back to
            // master 1 the moment its beat division or blend mode is tweaked.
            speedMasterUuid = edit.newSpeedMasterUuid ?: existing.speedMasterUuid
            rateSpeedMasterUuid = edit.newRateSpeedMasterUuid ?: existing.rateSpeedMasterUuid
            speedMasterSlot = if (edit.newSpeedMasterUuid != null) {
                speedMasters.slotFor(edit.newSpeedMasterUuid)
            } else {
                existing.speedMasterSlot
            }
            rateMasterSlot = if (edit.newRateSpeedMasterUuid != null) {
                rateSlotFor(edit.newRateSpeedMasterUuid)
            } else {
                existing.rateMasterSlot
            }
            edit.replaceMasters?.let { assignMasters(this, it) }
            // After the masters: Seconds clears whichever master its kind cannot have, and the
            // slots are bound again from what is left.
            timingMove?.let { (registrationSource, seconds) ->
                applyTimingChoice(registrationSource, seconds)
                speedMasterSlot = speedMasters.slotFor(speedMasterUuid)
                rateMasterSlot = rateSlotFor(rateSpeedMasterUuid)
            }
        }
    }

    /** Both of an effect's master assignments, as [updateFx]'s `replaceMasters` sets them. */
    data class MasterAssignment(val speedMasterUuid: java.util.UUID?, val rateSpeedMasterUuid: java.util.UUID?)

    private fun assignMasters(instance: FxInstance, masters: MasterAssignment) {
        instance.speedMasterUuid = masters.speedMasterUuid
        instance.speedMasterSlot = speedMasters.slotFor(masters.speedMasterUuid)
        instance.rateSpeedMasterUuid = masters.rateSpeedMasterUuid
        instance.rateMasterSlot = rateSlotFor(masters.rateSpeedMasterUuid)
    }

    /**
     * Pause an effect by ID.
     */
    fun pauseFx(fxId: Long) {
        activeFx[fxId]?.pause()
        // isRunning is part of effect coverage; see [rebuildSortedSnapshots].
        fxListEpoch.incrementAndGet()
        emitStateUpdate()
    }

    /**
     * Resume a paused effect by ID.
     */
    fun resumeFx(fxId: Long) {
        activeFx[fxId]?.resume()
        // isRunning is part of effect coverage; see [rebuildSortedSnapshots].
        fxListEpoch.incrementAndGet()
        emitStateUpdate()
    }

    /**
     * Remove all effects targeting a specific fixture.
     *
     * @param fixtureKey The fixture key
     * @return Number of effects removed
     */
    fun removeFxForFixture(fixtureKey: String): Int {
        val toRemove = activeFx.values.filter {
            !it.isGroupFx && it.target.targetKey == fixtureKey
        }
        toRemove.forEach { activeFx.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredAttributes(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Remove all effects targeting a specific group.
     *
     * @param groupName The group name
     * @return Number of effects removed
     */
    fun removeFxForGroup(groupName: String): Int {
        val toRemove = activeFx.values.filter {
            it.isGroupFx && it.target.targetKey == groupName
        }
        toRemove.forEach { activeFx.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredAttributes(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Remove every effect over a selection all of whose items are among [targets]. A selection
     * naming anything else keeps running, as a group's effect does when only some members are named.
     *
     * @return Number of effects removed
     */
    fun removeSelectionFxWithin(targets: Collection<TargetRef>): Int {
        val named = targets.toSet()
        val toRemove = activeFx.values.filter { effect ->
            val items = (effect.target.targetRef as? FxTargetRef.SelectionRef)?.items ?: return@filter false
            items.all { it in named }
        }
        toRemove.forEach { activeFx.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredAttributes(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * A programmer layer's *Detach*, effect half: every instance [layerId] spawned loses its layer
     * key and its [FxInstance.source], so it is a Local effect ([FxInstance.isLocalFx]) that
     * keeps its id and phase and that no recook will retract. It moves into the Local FX's part of
     * the band at their bottom, in the order it had, so it stays under every Local FX it shares a
     * key with — as Detach puts a layer's values beneath the Local values already held. A spent
     * N-shot is removed instead, since only a layer keeps one. Returns how many keep running.
     */
    fun detachProgrammerLayerFx(layerId: Int): Int {
        val spawned = activeFx.values.filter { it.programmerLayerFxKey?.layerId == layerId && !it.leaving }
        if (spawned.isEmpty()) return 0
        val (spent, running) = spawned.partition { it.spent }
        synchronized(localRankLock) {
            val detached = running.sortedWith(sortedFxComparator)
            val others = activeFx.values.filter { it.isLocalFx }.sortedWith(sortedFxComparator)
            for (effect in detached) {
                effect.programmerLayerFxKey = null
                effect.source = null
            }
            rerankLocal(detached + others, placed = detached.toSet())
        }
        if (running.isNotEmpty()) rebuildSortedSnapshots()
        if (spent.isNotEmpty()) removeFxBatch(spent.map { it.id }) else emitStateUpdate()
        return running.size
    }

    /**
     * [detachProgrammerLayerFx] in reverse: each running instance becomes the programmer layer
     * effect its key names, at its priority, keeping its id and phase, so a layer that takes over an
     * effect the programmer was already running does not restart it. It stops being Local, so it now
     * applies under every Local FX. A Look's Include no longer owns it; a cue's still does, so Update
     * retires the cue's own copy when it writes the layer (`splitIncludedChildren`). Answers the ids
     * adopted; one that has stopped or is leaving is not.
     */
    fun adoptIntoProgrammerLayer(adoptions: List<LayerFxAdoption>, source: LayerSource): Set<Long> {
        val adopted = HashSet<Long>()
        synchronized(localRankLock) {
            for ((effect, key, priority) in adoptions) {
                if (activeFx[effect.id] !== effect || effect.leaving) continue
                effect.programmerLayerFxKey = key
                effect.source = source
                effect.includedLookFx = null
                effect.priority = priority
                adopted += effect.id
            }
        }
        if (adopted.isNotEmpty()) {
            rebuildSortedSnapshots()
            emitStateUpdate()
        }
        return adopted
    }

    /** One instance [adoptIntoProgrammerLayer] hands to a layer: the key it takes and its priority. */
    data class LayerFxAdoption(val instance: FxInstance, val key: ProgrammerLayerFxKey, val priority: Int)

    /** What [moveLocalFx] did. */
    sealed interface LocalMoveOutcome {
        /** The FX whose priority moved, the moved one included when it did. */
        data class Moved(val changed: List<Long>) : LocalMoveOutcome

        /** [fxId] is not running. */
        data class NotFound(val fxId: Long) : LocalMoveOutcome

        /** [fxId] is running but is not a Local FX, so it has no place among them. */
        data class NotLocal(val fxId: Long) : LocalMoveOutcome
    }

    /**
     * Re-rank Local FX [fxId] to sit directly below [aboveId]'s, or at the bottom of the Local FX
     * when [aboveId] is null (`programmer.moveFx`, FX editor plan §3.5): every Local FX in one list in
     * apply order, the moved one taken out and put back, then [rerankLocal]. Nothing restarts: only
     * priorities change.
     */
    fun moveLocalFx(fxId: Long, aboveId: Long?): LocalMoveOutcome {
        val changed = ArrayList<Long>()
        synchronized(localRankLock) {
            val moved = activeFx[fxId] ?: return LocalMoveOutcome.NotFound(fxId)
            if (!moved.isLocalFx) return LocalMoveOutcome.NotLocal(fxId)
            val above = aboveId?.let { id ->
                val found = activeFx[id] ?: return LocalMoveOutcome.NotFound(id)
                if (!found.isLocalFx) return LocalMoveOutcome.NotLocal(id)
                found
            }
            if (above === moved) return LocalMoveOutcome.Moved(emptyList())
            val order = activeFx.values.filter { it.isLocalFx && it !== moved }
                .sortedWith(sortedFxComparator).toMutableList()
            val at = if (above == null) 0 else order.indexOf(above)
            // [above] stopped being Local (a release let it go) since it was read.
            if (at < 0) return LocalMoveOutcome.NotFound(aboveId!!)
            order.add(at, moved)
            changed += rerankLocal(order, placed = setOf(moved))
        }
        if (changed.isNotEmpty()) {
            rebuildSortedSnapshots()
            emitStateUpdate()
        }
        return LocalMoveOutcome.Moved(changed)
    }

    /**
     * Give Local FX [order], bottom first, the ranks that apply them in that order, answering the ids
     * whose priority moved. From the bottom, each keeps its rank if that still applies it after the one
     * below it, else takes the lowest rank that does; [placed] always take that lowest rank, so moving
     * one back where it was restores the order. Under [localRankLock].
     */
    private fun rerankLocal(order: List<FxInstance>, placed: Set<FxInstance>): List<Long> {
        val changed = ArrayList<Long>()
        var below: FxInstance? = null
        var belowRank = -1
        for (effect in order) {
            val rank = localRankOf(effect.priority)
            val lowest = when {
                below == null -> 0
                compareValuesBy(effect, below, { it.composeOrder }, { it.id }) > 0 -> belowRank
                else -> belowRank + 1
            }
            val next = if (effect !in placed && rank >= lowest) rank else lowest
            if (next != rank) {
                effect.priority = localFxPriority(next)
                changed += effect.id
            }
            below = effect
            belowRank = next
        }
        return changed
    }

    /**
     * The rank that puts Local FX [effect] at the top of its lanes: one above the highest FX in the
     * Local part of the band sharing a key with it — a head it covers and its attribute — else 0. One
     * still fading out counts, so a new FX never starts under it. Under [localRankLock].
     */
    private fun topLocalRankFor(
        effect: FxInstance,
        keysOf: (FxInstance) -> List<String> = ::resolveFxFixtureKeys,
    ): Int {
        val attribute = effect.target.attribute
        val keys = keysOf(effect).toHashSet()
        var highest = -1
        for (other in activeFx.values) {
            if (other === effect || other.target.attribute != attribute) continue
            val rank = localRankOf(other.priority)
            if (rank <= highest) continue
            if (keysOf(other).any { it in keys }) highest = rank
        }
        return highest + 1
    }

    /**
     * Re-rank live programmer-layer effects in place (`instanceId → new priority`).
     *
     * Layer order *is* effect order, and it is expressed as a priority offset within the programmer
     * band rather than as spawn order, so that reordering the stack does not have to respawn
     * anything. A respawn would restart every effect's phase — a visible hitch on a drag, and the
     * whole point of doing it this way.
     *
     * Layer 3 only: a programmer layer owns no Layer 4 assignment rows (its values are materialised
     * into the store), so unlike [repriorityCues] there is no second half. Returns rows changed.
     */
    fun repriorityProgrammerLayerFx(priorities: Map<Long, Int>): Int {
        if (priorities.isEmpty()) return 0
        val stale = activeFx.values.filter { effect ->
            val target = priorities[effect.id] ?: return@filter false
            effect.priority != target
        }
        if (stale.isEmpty()) return 0
        for (effect in stale) effect.priority = priorities.getValue(effect.id)
        rebuildSortedSnapshots()
        emitStateUpdate()
        return stale.size
    }

    /**
     * Remove every effect in the programmer's reserved priority band ([PROGRAMMER_FX_PRIORITY_BASE])
     * — the busking effects the operator added on top of their programmer values. Cue-owned and
     * plain manual effects are untouched.
     *
     * Kept independently callable (rather than folded into [ProgrammerWriter.clearAll]) because the
     * programmer's Clear is specified as "programmer values **and** programmer FX", and a
     * clear-FX-only variant wants the same sweep without touching stored values.
     *
     * @return Number of effects removed
     */
    fun removeProgrammerBandFx(): Int {
        val toRemove = activeFx.values.filter { isProgrammerFxPriority(it.priority) }
        toRemove.forEach { activeFx.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredAttributes(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Identifies a single (fixture, property) pair for stomp overlap checks.
     */
    data class TargetAttribute(val targetKey: String, val attribute: String)

    /**
     * Remove ad-hoc effects owned by *other* cues that target properties in the [overlap]
     * set. Effects owned by the stomping cue itself are not stomped — they co-exist with its
     * Layer 4 assertions. Manual (uncued) effects are not stomped either.
     *
     * @param stompingCueId the cue whose apply triggered the stomp.
     * @param overlap the set of (targetKey, attribute) pairs the stomping cue covers.
     * @return number of effects removed.
     */
    fun stompForCue(stompingCueId: Int, overlap: Set<TargetAttribute>): Int {
        if (overlap.isEmpty()) return 0
        val toRemove = activeFx.values.filter { effect ->
            val owner = effect.cueId ?: return@filter false
            if (owner == stompingCueId) return@filter false
            val attribute = effect.target.attribute
            // A selection goes only where the cue asserts every item, as a group goes only where the
            // cue asserts the group: one member asserted leaves the rest running.
            (effect.target.targetRef as? FxTargetRef.SelectionRef)?.items?.all { TargetAttribute(it.key, attribute) in overlap }
                ?: (TargetAttribute(effect.target.targetKey, attribute) in overlap)
        }
        if (toRemove.isEmpty()) return 0
        for (effect in toRemove) activeFx.remove(effect.id)
        rebuildSortedSnapshots()
        resetUncoveredAttributes(toRemove)
        emitStateUpdate()
        return toRemove.size
    }

    /**
     * Remove all active effects.
     */
    fun clearAllFx() {
        val allFx = activeFx.values.toList()
        activeFx.clear()
        rebuildSortedSnapshots()
        cueLayer.clearAll()
        resetUncoveredAttributes(allFx)
        emitStateUpdate()
    }

    /**
     * Single-master shim: every effect sees [tick] as its master's tick. Kept so the
     * synthetic-tick drivers in `FxEnginePipelineTest` / `FxEngineBenchmark` stay valid —
     * a uniform frame *is* the single-clock world.
     */
    internal suspend fun processBeatTickSuspend(tick: MasterClock.ClockTick) =
        processBeatTickSuspend(SpeedMasterBank.Frame.uniform(tick))

    /**
     * A pass a beat woke, over [frame]. `internal` so that tests can drive synthetic ticks without
     * waiting on the real-time loops — through the `processBeatTick` shim in `FxEngineTickShims.kt`
     * (test source), which is where the `runBlocking` wrapper lives. Production never blocks a
     * thread on a pass: the driver in [start] calls [composePass] directly.
     */
    internal suspend fun processBeatTickSuspend(
        frame: SpeedMasterBank.Frame,
        nowMs: Long = System.currentTimeMillis(),
    ) = composePass(beat = true, ticker = false, frame = frame, nowMs = nowMs)

    /** A pass the ticker woke, at [nowMs]; the `processWallClockTick` shim's form. */
    internal suspend fun processWallClockTickSuspend(nowMs: Long = System.currentTimeMillis()) =
        composePass(beat = false, ticker = true, frame = null, nowMs = nowMs)

    /**
     * One compose pass: reset every key a running effect covers to the layer below, once, then fold
     * every running effect, beat and wall-clock alike, in apply order ([sortedFxComparator]),
     * into one transaction, so a wall-clock effect and a beat effect on the same key blend.
     *
     * [beat] and [ticker] say which clocks woke it (both, when their wakes coalesced). Phases are
     * read on every pass: a beat effect's from its master's tick in the pass's frame — [frame] when
     * given (the test shims), else the bank's, taken after any re-bind; on a pass no beat woke, the
     * last beat-woken pass's frame, so it is painted where the last tick left it — and a wall-clock
     * effect's from its accumulator, advanced by the real time since the last pass, so the extra
     * passes leave its cycle length alone. What counts passes stays with the effect's own clock:
     * - a [StatefulFx] steps only on a pass its clock woke, and repaints its last output
     *   otherwise ([calculateFxOutput]); a Candle Flicker would otherwise flicker faster under
     *   a faster master;
     * - the tick-failure count ([noteTickFailure]) and an N-shot's end ([finishRuns]) count only
     *   passes its clock woke;
     * - [wallClockPassNumber] counts ticker-woken passes, and the leaving sweep runs on them.
     *
     * A pass with nothing running on a clock that woke it is skipped: with only beat effects
     * running, a ticker-woken pass would repaint them at the tick they are already on.
     *
     * Mutually exclusive under [passLock]. `nowMs` is a parameter so tests can step wall-clock time.
     * Answers whether the pass ran, false when it was skipped.
     */
    internal suspend fun composePass(
        beat: Boolean,
        ticker: Boolean,
        frame: SpeedMasterBank.Frame?,
        nowMs: Long,
    ): Boolean = passLock.withLock {
        // Membership changes re-bind lazily, at pass start: slots only matter during a
        // pass, and Frame clamps an out-of-range slot to master 1 in the window between
        // a deletion and this re-bind. A held frame predates the change, so it goes too.
        val bankVersion = speedMasters.version
        if (bankVersion != boundBankVersion) {
            rebindSpeedMasters()
            boundBankVersion = bankVersion
            lastFrame = null
        }
        if (beat) lastFrame = frame ?: speedMasters.snapshotFrame()

        val wallClockDeltaMs = if (ticker) {
            val delta = if (lastWallClockTickMs > 0) nowMs - lastWallClockTickMs else 0L
            lastWallClockTickMs = nowMs
            delta
        } else 0L

        // Fade-outs that have reached 0 end here, before the early returns below: the ticker wakes
        // a pass at 50 Hz whatever is running, so a leaving instance is removed on time even when
        // it, and every other effect, is paused.
        if (ticker && sweepsLeaving) retireFinishedLeaving(nowMs)

        // Snapshot read is lock-free (volatile).
        val effects = sortedFx
        var beatRunning = false
        var wallClockRunning = false
        var rateMasterAssigned = false
        for (effect in effects) {
            if (effect.timingSource == TimingSource.WALL_CLOCK) {
                if (effect.rateMasterSlot != FxInstance.NO_RATE_MASTER) rateMasterAssigned = true
                if (!wallClockRunning && effect.isRunning) wallClockRunning = true
            } else if (!beatRunning && effect.isRunning) {
                beatRunning = true
            }
        }

        // With no wall-clock effect running, no accumulator is owed the time since the last pass, so
        // the stamp moves on. Leaving it stale meant the first pass afterwards handed the whole idle
        // period to every wall-clock effect — including one just created with an accumulator of 0,
        // which would then render its first frame at an arbitrary point in its cycle.
        if (!wallClockRunning) lastPassMs = nowMs

        // A pass with nothing running on a clock that woke it would repaint the last frame: skipped,
        // and the time it covers is advanced by the next pass instead.
        if (!(beat && beatRunning) && !(ticker && wallClockRunning)) return@withLock false

        // A clock stepped backwards advances nothing.
        val advanceMs = if (lastPassMs > 0) (nowMs - lastPassMs).coerceAtLeast(0L) else 0L
        lastPassMs = nowMs

        // A beat effect's tick: this pass's frame, else the last beat-woken pass's, else (none yet,
        // or the bank moved since) the bank's current one.
        val beatFrame = if (beatRunning) lastFrame ?: speedMasters.snapshotFrame().also { lastFrame = it } else null

        // deltaMs comes from the frame timestamp, not any one master's tick timestamp — a slow
        // master's tick time only moves when *it* ticks, which would hand stateful effects a
        // garbage delta on passes woken by a faster master.
        val beatDeltaMs = if (beat && beatFrame != null) {
            val delta = if (lastTickMs > 0) beatFrame.timestampMs - lastTickMs else 0L
            lastTickMs = beatFrame.timestampMs
            delta
        } else 0L

        if (wallClockRunning) {
            // One coherent rate sample per pass, and only when a rate master is actually assigned
            // to something — the common case is none, and then every effect advances unscaled.
            val rateScales = if (rateMasterAssigned) speedMasters.rateScales() else null

            // Advance every wall-clock effect's scaled clock once, before anything reads a phase,
            // so all the phase calls in this pass see one coherent value. Paused effects advance
            // too, as long as some wall-clock effect is running: a wall-clock effect has always
            // kept its place in real time while paused.
            for (effect in effects) {
                if (effect.timingSource != TimingSource.WALL_CLOCK) continue
                val slot = effect.rateMasterSlot
                val scale =
                    if (slot == FxInstance.NO_RATE_MASTER || rateScales == null) 1.0
                    else rateScales.getOrElse(slot) { 1.0 }
                effect.advanceWallClock(advanceMs, scale)
            }

            // The tick stateful wall-clock effects receive. The beat-position fields stay 0 — a
            // wall-clock pass has no beat position, by definition — but `tickNumber` is a real
            // monotonically-increasing count of passes.
            //
            // It used to be pinned at 0, and that was not the harmless placeholder it looked like:
            // `CandleFlicker` (STATEFUL + WALL_CLOCK, so this is the *only* tick it ever sees)
            // re-picks its target from `sin(tickNumber * 127.0) * cos(tickNumber * 311.0)`. With
            // tickNumber constant that noise term was always exactly 0 and a candle on the rig held
            // dead-steady. `FxEnginePipelineTest` pins the engine end of that contract.
            if (ticker) {
                wallClockTick = MasterClock.ClockTick(
                    tickNumber = wallClockPassNumber++,
                    beatNumber = 0,
                    tickInBeat = 0,
                    phase = 0.0,
                    timestampMs = nowMs,
                )
            }
        }

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        // Reset every key a running effect covers to the layer below (Layer 2 → Layer 4 → Layer 5
        // baseline), once, before applying. This prevents accumulative blend modes from ratcheting
        // across passes and keeps direct writes + cue state visible under effects.
        resetActiveAttributes(fixturesWithTx, effects, resetSeen)

        // Programmer suppression: any non-band effect skips its apply on (fixture,
        // property) pairs the programmer holds — the reset pass has already painted the
        // programmer value there, and the effect must not repaint over it. Blind, read once for
        // the pass, empties that map and holds the band instead ([BlindHold]): a held instance
        // still computes its phase below and skips only its apply.
        val blind = programmerStore.blind
        val suppression = programmerSuppression(blind)

        // N-shots that run out on this pass, ended after the pass's frame is out ([finishRuns]).
        val spentThisPass = ArrayList<FxInstance>(0)

        // Apply order: under non-OVERRIDE blend modes, later effects compose on top of earlier
        // ones. Each beat effect computes phase from *its own* master's tick; effects on the same
        // master stay locked, effects on different masters drift apart, which is the point.
        for (effect in effects) {
            // This effect's one dynamics read for the pass — everything below works from
            // this snapshot, so a concurrent edit lands wholly on this pass or the next.
            val dyn = effect.dynamics
            if (!dyn.isRunning) continue

            val onBeat = effect.timingSource == TimingSource.BEAT
            val ownClock = if (onBeat) beat else ticker
            val tick: MasterClock.ClockTick
            val deltaMs: Long
            val phases: PhaseSource
            if (onBeat) {
                tick = (beatFrame ?: continue).tick(effect.speedMasterSlot)
                deltaMs = beatDeltaMs
                phases = PhaseSource.Beat
            } else {
                tick = wallClockTick
                deltaMs = wallClockDeltaMs
                phases = PhaseSource.WallClock
            }
            try {
                beginRunsPass(effect)
                if (effect.spreadsOverMembers) {
                    processGroupFx(
                        tick, effect, dyn, fixturesWithTx, deltaMs, ownClock, suppression, blind, phases,
                    )
                } else {
                    processFixtureFx(
                        tick, effect, dyn, fixturesWithTx, deltaMs, ownClock, suppression, blind, phases,
                    )
                }
                if (ownClock) {
                    if (runsRanOut(effect, dyn)) spentThisPass += effect
                    noteTickSuccess(effect)
                }
            } catch (t: Throwable) {
                // Throwable, not Exception: every built-in effect is a compiled script, so a
                // recursive helper in one (StackOverflowError) or a script class that fails to
                // link (NoClassDefFoundError) is an Error, and letting one past here would kill
                // the pass loop for the life of the process.
                noteTickFailure(effect, t, if (onBeat) "effect" else "wall-clock effect", fixturesWithTx, ownClock)
            }
        }

        transaction.applySuspend()
        resetRemovedSince(effects)
        finishRuns(spentThisPass)
        true
    }

    /**
     * After the pass's commit, reset what the FX in its list [painted] that a removal or an edit
     * has since taken out of [activeFx] leave uncovered. The removal reset it already, but this
     * pass may have committed their output over that reset, and with nothing left running no later
     * pass would paint it out. A removal takes an FX out of the map, then rebuilds [sortedFx],
     * then resets: an unchanged list means any reset still to come lands after this commit.
     */
    private suspend fun resetRemovedSince(painted: List<FxInstance>) {
        if (sortedFx === painted) return
        val gone = painted.filter { activeFx[it.id] !== it }
        uncoveredResetTransaction(gone)?.applySuspend()
    }

    /**
     * Re-resolve every active instance's runtime master slots against the bank. A uuid the
     * bank no longer knows resolves to slot 0 — a deleted master's effects fall back to the
     * global tempo rather than stopping.
     */
    private fun rebindSpeedMasters() {
        for (effect in activeFx.values) {
            effect.speedMasterSlot = speedMasters.slotFor(effect.speedMasterUuid)
            effect.rateMasterSlot = rateSlotFor(effect.rateSpeedMasterUuid)
        }
    }

    /** Clear [effect]'s per-pass N-shot flags before its pass; see [FxInstance.runsPending]. */
    private fun beginRunsPass(effect: FxInstance) {
        effect.runsPending = false
        effect.runsEvaluated = false
    }

    /**
     * Whether [effect], an N-shot (D9), ran out on the pass just made: some member's phase was
     * computed and none has runs left — the clock has run N cycles past the largest member offset.
     * A held effect (Blind, a stomp, the programmer) computes its phases all the same, so its count
     * runs on while it is held, as its clock does. An expansion with no members evaluates nothing
     * and is never spent by this, so a group emptied by a reload does not end an effect.
     */
    private fun runsRanOut(effect: FxInstance, dyn: FxDynamics): Boolean =
        dyn.runs != null && !effect.spent && effect.runsEvaluated && !effect.runsPending

    /**
     * End the N-shots a pass found spent, after its frame is out, as one mutation. A programmer
     * layer's instance is kept, [FxInstance.spent] — the layer's recook would otherwise spawn it
     * afresh and fire it again on the next unrelated programmer write; its members already hold
     * underneath, and the layer's release removes it. Every other instance is removed, which resets
     * whatever it alone covered and broadcasts the list.
     */
    private fun finishRuns(spent: List<FxInstance>) {
        if (spent.isEmpty()) return
        val (kept, ended) = spent.partition { it.programmerLayerFxKey != null }
        kept.forEach { it.spent = true }
        if (ended.isNotEmpty()) removeFxBatch(ended.map { it.id })
        if (kept.isNotEmpty() && ended.isEmpty()) emitStateUpdate()
    }

    /**
     * Calculate the output for an effect on [key], handling stateless and stateful effects.
     *
     * A [StatefulFx] steps only when [step] (a pass its own clock woke); on any other pass it
     * repaints what it last answered on [key], so the extra passes leave its pace alone. A key it
     * has never answered for steps whatever woke the pass, so an effect just added or edited paints
     * on its first pass.
     */
    private fun calculateFxOutput(
        effect: FxInstance,
        key: String,
        tick: MasterClock.ClockTick,
        deltaMs: Long,
        step: Boolean,
        phase: Double,
        context: FxContext,
    ): FxOutput {
        // The raw output, unscaled: size and the envelope weigh it after the blend, in the target
        // (effects plan P3, [FxInstance.weight]) — scaling it here is what dropped an OVERRIDE to 0.
        val fx = effect.effect
        if (fx !is StatefulFx) return fx.calculate(phase, context)
        val last = checkNotNull(effect.statefulOutputs)
        if (!step) last[key]?.let { return it }
        return fx.calculateStateful(tick, deltaMs, context).also { last[key] = it }
    }

    /**
     * Reset properties controlled by running effects to the layer below (Layer 2 → Layer 4 →
     * Layer 5 baseline). This ensures blend modes operate against the correct baseline each
     * tick rather than accumulating from previous ticks — and crucially that direct
     * `updateChannel` writes (Layer 2) remain visible under running effects instead of being
     * clobbered to zero.
     *
     * Layer 1 (parking) short-circuits: a fully-parked property skips the reset entirely
     * because the parked value wins at transmit time regardless. The caller passes a
     * pre-sorted effect list (priority, then compose order, then id); ordering only
     * matters downstream for effect composition, not for the reset pass.
     */
    private fun resetActiveAttributes(
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        effects: List<FxInstance>,
        seen: ResetSeen,
    ) {
        seen.beginPass(fixtures.structureVersion)

        for (effect in effects) {
            if (!effect.isRunning) continue

            val keys = resolveFxFixtureKeys(effect)
            val target = effect.target

            for (key in keys) {
                if (seen.add(key, target.attribute)) {
                    resetOne(fixturesWithTx, key, target)
                }
            }
        }
    }

    /**
     * The (fixture, property) dedupe scratch for [resetActiveAttributes], reused across passes
     * rather than rebuilt per pass.
     *
     * Two-level rather than a compound key so there is no key object per tuple — on a
     * 168-fixture × 2-property rig that is 336 avoided allocations per tick, and reusing the
     * structure removes the outer map and one `HashSet` per fixture on top of that. Clearing the
     * inner sets in place is what keeps them: a pass touches the same fixture keys as the last
     * one. The whole map is dropped when the register generation moves, so keys for fixtures
     * that no longer exist can't accumulate.
     *
     * Owned by [composePass], under [passLock].
     */
    private class ResetSeen {
        private val byKey = HashMap<String, HashSet<String>>()
        private var structureVersion = Long.MIN_VALUE

        fun beginPass(version: Long) {
            if (version != structureVersion) {
                byKey.clear()
                structureVersion = version
            } else {
                for (attributes in byKey.values) attributes.clear()
            }
        }

        /** True when this is the first time [attribute] has been seen on [key] this pass. */
        fun add(key: String, attribute: String): Boolean =
            byKey.getOrPut(key) { HashSet() }.add(attribute)
    }

    /** [resetActiveAttributes] scratch owned by [composePass]. */
    private val resetSeen = ResetSeen()

    private fun resetOne(
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        fixtureKey: String,
        target: FxTarget,
    ) {
        try {
            val fixture = fixturesWithTx.untypedGroupableFixture(fixtureKey)
            if (cascade.allChannelsParked(target, fixture)) return
            val fallback = layerResolver.fallbackFor(target, fixture, fixtureKey)
            target.resetToFallback(fixture, fallback)
        } catch (e: Exception) {
            // Non-fatal, and deliberately quieter than the apply path: the usual cause is a
            // fixture that has gone away, and the apply for the same effect will fail on the
            // next line and report it at warn (and eventually pause the effect). Logging both
            // at warn would double every line for one fault. Throttled all the same — this
            // runs once per (fixture, property) per tick.
            throttle.log("reset-$fixtureKey.${target.attribute}", debug = true) {
                "FX engine: reset to fallback failed for $fixtureKey.${target.attribute}: ${e.message}"
            }
        }
    }


    /**
     * Where an effect's phase comes from on a pass: its master's beat position, or its own
     * scaled wall-clock accumulator.
     *
     * Beat and wall-clock effects are otherwise handled identically — same expansion, same
     * distribution plan, same suppression, same apply — so this is the only thing the shared
     * `process*` functions below are parameterised on.
     *
     * Objects rather than lambdas: two instances for the life of the process, nothing allocated
     * per tick. [WallClock] ignores `tick` for *phase* — that is a function of
     * `accumulatedScaledMs` — but the tick itself is not inert on that path: it still reaches
     * stateful effects through [calculateFxOutput], carrying the ticker pass's timestamp and
     * counter (see [composePass]).
     */
    private sealed interface PhaseSource {
        /** Phase for an effect applied to one fixture or one whole target. */
        fun single(effect: FxInstance, tick: MasterClock.ClockTick, dyn: FxDynamics): Double

        /** Phase for member/element [memberIndexCount]-way distribution at [distributionOffset]. */
        fun member(
            effect: FxInstance,
            tick: MasterClock.ClockTick,
            memberIndexCount: Int,
            dyn: FxDynamics,
            distributionOffset: Double,
        ): Double

        object Beat : PhaseSource {
            override fun single(effect: FxInstance, tick: MasterClock.ClockTick, dyn: FxDynamics) =
                effect.calculatePhase(tick, dyn)

            override fun member(
                effect: FxInstance,
                tick: MasterClock.ClockTick,
                memberIndexCount: Int,
                dyn: FxDynamics,
                distributionOffset: Double,
            ) = effect.calculatePhaseForMember(tick, memberIndexCount, dyn, distributionOffset)
        }

        object WallClock : PhaseSource {
            override fun single(effect: FxInstance, tick: MasterClock.ClockTick, dyn: FxDynamics) =
                effect.calculateWallClockPhase(dyn)

            override fun member(
                effect: FxInstance,
                tick: MasterClock.ClockTick,
                memberIndexCount: Int,
                dyn: FxDynamics,
                distributionOffset: Double,
            ) = effect.calculateWallClockPhaseForMember(memberIndexCount, dyn, distributionOffset)
        }
    }

    /**
     * Process an effect targeting a single fixture.
     *
     * If the parent fixture doesn't have the target property but implements
     * [MultiElementFixture] and its elements do have the property, the effect
     * is automatically expanded to all elements with Order support.
     */
    private fun processFixtureFx(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        dyn: FxDynamics,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long,
        step: Boolean,
        suppression: Map<String, Set<String>>,
        blind: Boolean,
        phases: PhaseSource,
    ) {
        val expansion = expansionFor(effect, dyn)
        when (expansion.kind) {
            FxTargetExpansion.Kind.DIRECT_FIXTURE -> {
                val fixtureKey = effect.target.targetKey
                val fxPhase = phases.single(effect, tick, dyn)
                // An N-shot outside its runs holds what is underneath (D9).
                if (FxInstance.isHolding(fxPhase)) return
                // A copy split off a spread effect is the member it was (apply-model plan §3.4).
                val carry = effect.splitMember
                val context = carry?.context ?: FxContext.SINGLE
                val calculate = { calculateFxOutput(effect, fixtureKey, tick, deltaMs, step, fxPhase, context) }
                val output = carry?.step?.outputFor(effect.effect, tick, calculate) ?: calculate()
                if (!isSuppressed(suppression, blind, fixtureKey, effect.target.attribute, effect)) {
                    effect.target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode, weightOf(effect, dyn))
                }
            }

            FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> {
                val plans = plansFor(effect, expansion, dyn)
                if (dyn.elementMode == ElementMode.WHOLE) {
                    processWholeUnits(
                        tick, effect, dyn, fixturesWithTx, expansion.whole, plans.whole,
                        deltaMs, step, suppression, blind, phases,
                    )
                } else {
                    processElementKeys(
                        tick, effect, dyn, fixturesWithTx, expansion.flat, plans.flat,
                        deltaMs, step, suppression, blind, phases,
                    )
                }
            }

            // Neither parent nor elements have the property, or the fixture is gone:
            // silently skip. The group kinds cannot reach this function.
            FxTargetExpansion.Kind.NONE,
            FxTargetExpansion.Kind.GROUP_MEMBERS,
            FxTargetExpansion.Kind.GROUP_ELEMENTS,
            -> {}
        }
    }

    /**
     * Process an effect expanded across multi-element fixture elements.
     *
     * Uses the same Order machinery as group effects; the per-element offsets
     * and contexts come precomputed in [plan] — see [FxDistributionPlans].
     */
    private fun processElementKeys(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        dyn: FxDynamics,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        elementKeys: List<String>,
        plan: DistributionPlan,
        deltaMs: Long,
        step: Boolean,
        suppression: Map<String, Set<String>>,
        blind: Boolean,
        phases: PhaseSource,
    ) {
        // Already filtered and in distribution order — see [FxTargetExpansion.flat] /
        // [FxTargetExpansion.perFixture]. Distribution indices run over the *included* elements,
        // so phase offsets spread evenly across only those.
        val filteredCount = elementKeys.size
        if (filteredCount == 0) return

        val weight = weightOf(effect, dyn)
        var lastMemberPhase = Double.NaN
        for ((distributionIdx, elementKey) in elementKeys.withIndex()) {
            val distOffset = plan.offsets[distributionIdx]
            val memberPhase = phases.member(effect, tick, filteredCount, dyn, distOffset)
            // A member of an N-shot outside its own runs holds what is underneath (D9).
            if (FxInstance.isHolding(memberPhase)) continue
            lastMemberPhase = memberPhase

            val output = calculateFxOutput(
                effect, elementKey, tick, deltaMs, step, memberPhase, plan.contexts[distributionIdx],
            )
            if (!isSuppressed(suppression, blind, elementKey, effect.target.attribute, effect)) {
                effect.target.applyValue(fixturesWithTx, elementKey, output, effect.blendMode, weight)
            }
        }
        if (!lastMemberPhase.isNaN()) effect.lastPhase = lastMemberPhase
    }

    /**
     * Process an effect under [ElementMode.WHOLE]: each of [units] is one fixture, spread by
     * [plan] over the units, and its output is calculated once and written to every key it paints.
     */
    private fun processWholeUnits(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        dyn: FxDynamics,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        units: List<List<String>>,
        plan: DistributionPlan,
        deltaMs: Long,
        step: Boolean,
        suppression: Map<String, Set<String>>,
        blind: Boolean,
        phases: PhaseSource,
    ) {
        val weight = weightOf(effect, dyn)
        var lastMemberPhase = Double.NaN
        for ((unitIdx, keys) in units.withIndex()) {
            if (keys.isEmpty()) continue
            val memberPhase = phases.member(effect, tick, units.size, dyn, plan.offsets[unitIdx])
            if (FxInstance.isHolding(memberPhase)) continue
            lastMemberPhase = memberPhase
            // A stateful effect keeps its state under the unit's first key, so its elements share it.
            val output = calculateFxOutput(effect, keys.first(), tick, deltaMs, step, memberPhase, plan.contexts[unitIdx])
            for (key in keys) {
                if (!isSuppressed(suppression, blind, key, effect.target.attribute, effect)) {
                    effect.target.applyValue(fixturesWithTx, key, output, effect.blendMode, weight)
                }
            }
        }
        if (!lastMemberPhase.isNaN()) effect.lastPhase = lastMemberPhase
    }

    /**
     * Process an effect targeting a group or a selection - expands to all members with distribution.
     *
     * If members have the target property directly, applies the effect to each member
     * using the Order (existing behaviour).
     *
     * If members are [MultiElementFixture]s whose elements have the target property,
     * the [ElementMode] on the effect instance determines the expansion strategy:
     * - [ElementMode.PER_FIXTURE]: Each fixture gets the effect independently across
     *   its own elements. All fixtures look the same.
     * - [ElementMode.FLAT]: All elements across all fixtures form one flat list.
     *   Distribution runs across the entire set.
     * - [ElementMode.WHOLE]: Each fixture is one unit and its elements share its output.
     */
    private fun processGroupFx(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        dyn: FxDynamics,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long,
        step: Boolean,
        suppression: Map<String, Set<String>>,
        blind: Boolean,
        phases: PhaseSource,
    ) {
        val expansion = expansionFor(effect, dyn)
        when (expansion.kind) {
            FxTargetExpansion.Kind.GROUP_MEMBERS -> {
                val allMembers = expansion.members
                val groupSize = allMembers.size
                val plan = plansFor(effect, expansion, dyn).members
                val weight = weightOf(effect, dyn)
                var lastMemberPhase = Double.NaN
                for ((memberIdx, member) in allMembers.withIndex()) {
                    val distOffset = plan.offsets[memberIdx]
                    val memberPhase = phases.member(effect, tick, groupSize, dyn, distOffset)
                    // A member of an N-shot outside its own runs holds what is underneath (D9).
                    if (FxInstance.isHolding(memberPhase)) continue
                    lastMemberPhase = memberPhase
                    val output = calculateFxOutput(
                        effect, member.key, tick, deltaMs, step, memberPhase, plan.contexts[memberIdx],
                    )
                    if (!isSuppressed(suppression, blind, member.key, effect.target.attribute, effect)) {
                        effect.target.applyValue(fixturesWithTx, member.key, output, effect.blendMode, weight)
                    }
                }
                if (!lastMemberPhase.isNaN()) effect.lastPhase = lastMemberPhase
            }

            FxTargetExpansion.Kind.GROUP_ELEMENTS -> {
                val plans = plansFor(effect, expansion, dyn)
                when (dyn.elementMode) {
                    ElementMode.WHOLE -> processWholeUnits(
                        tick, effect, dyn, fixturesWithTx, expansion.whole, plans.whole,
                        deltaMs, step, suppression, blind, phases,
                    )
                    // Each fixture gets the effect independently across its own elements, so its
                    // own list size is the group size the phase calculation sees.
                    ElementMode.PER_FIXTURE ->
                        for ((memberIdx, memberKeys) in expansion.perFixture.withIndex()) {
                            processElementKeys(
                                tick, effect, dyn, fixturesWithTx, memberKeys,
                                plans.perFixture[memberIdx], deltaMs, step, suppression, blind, phases,
                            )
                        }
                    // All elements across all fixtures as one list.
                    ElementMode.FLAT -> processElementKeys(
                        tick, effect, dyn, fixturesWithTx, expansion.flat, plans.flat,
                        deltaMs, step, suppression, blind, phases,
                    )
                }
            }

            // Group missing, empty, or neither members nor their elements have the property.
            // The fixture kinds cannot reach this function.
            FxTargetExpansion.Kind.NONE,
            FxTargetExpansion.Kind.DIRECT_FIXTURE,
            FxTargetExpansion.Kind.FIXTURE_ELEMENTS,
            -> {}
        }
    }

    /**
     * Check if an effect expands to multi-element fixture elements.
     *
     * Returns true for:
     * - Fixture effects where the parent doesn't have the property but its elements do
     * - Group effects where members are multi-element fixtures and the target
     *   property is at the element level
     *
     * @param instance The effect instance to check
     * @return true if this effect will be expanded to elements
     */
    fun isMultiElementExpanded(instance: FxInstance): Boolean =
        expansionFor(instance, instance.dynamics).isElementExpanded

    /**
     * Reset fixture properties that are no longer covered by any active effect.
     *
     * For each removed effect, resolves all fixture keys it was controlling
     * (handling groups and multi-element expansion), checks if any remaining
     * active effect still covers the same property, and writes the neutral
     * value for uncovered properties.
     */
    private fun resetUncoveredAttributes(removedFx: List<FxInstance>) {
        uncoveredResetTransaction(removedFx)?.apply()
    }

    /** [resetUncoveredAttributes]'s writes, staged and not yet applied; null when there are none. */
    private fun uncoveredResetTransaction(removedFx: List<FxInstance>): ControllerTransaction? {
        if (removedFx.isEmpty()) return null

        data class AffectedAttribute(val fixtureKey: String, val target: FxTarget)

        val affectedAttributes = mutableSetOf<AffectedAttribute>()
        for (removed in removedFx) {
            for (key in resolveFxFixtureKeys(removed)) {
                affectedAttributes.add(AffectedAttribute(key, removed.target))
            }
        }

        val remainingFx = activeFx.values.toList()
        val uncovered = affectedAttributes.filter { affected ->
            !isAttributeCoveredByAny(affected.fixtureKey, affected.target.attribute, remainingFx)
        }

        if (uncovered.isEmpty()) return null

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        for (affected in uncovered) {
            try {
                val fixture = fixturesWithTx.untypedGroupableFixture(affected.fixtureKey)
                if (cascade.allChannelsParked(affected.target, fixture)) continue
                val fallback = layerResolver.fallbackFor(affected.target, fixture, affected.fixtureKey)
                affected.target.resetToFallback(fixture, fallback)
            } catch (e: Exception) {
                throttle.log("reset-uncovered-${affected.fixtureKey}.${affected.target.attribute}", e) {
                    "FX engine: failed to reset ${affected.target.attribute} " +
                        "on '${affected.fixtureKey}'"
                }
            }
        }

        return transaction
    }

    /**
     * The fixture/element keys [effect] currently writes to. Public form of
     * [resolveFxFixtureKeys] — used by Include to report which heads a spawned group
     * effect covers, so the sheet can select them.
     */
    fun fixtureKeysCoveredBy(effect: FxInstance): List<String> =
        expansionFor(effect, effect.dynamics).coverageKeys

    /**
     * Resolve all fixture/element keys that an effect was writing to.
     *
     * For fixture effects: the target fixture key (or element keys if multi-element expanded).
     * For group effects: all member keys (or element keys if multi-element expanded).
     *
     * Not filtered by [FxInstance.elementFilter] — coverage is what the effect *owns*, which
     * is a different question from which elements it paints on a given tick. See
     * [FxTargetExpansion.coverageKeys].
     */
    private fun resolveFxFixtureKeys(effect: FxInstance): List<String> =
        expansionFor(effect, effect.dynamics).coverageKeys

    /**
     * [effect]'s cached expansion, re-derived when the fixture register has been rebuilt under
     * it or when `updateFx` has moved the two fields the expansion depends on.
     *
     * `elementFilter` is part of the validity check rather than an invalidation hook on
     * `updateFx`: it is a plain enum, so the check is one identity compare on a path that
     * already reads the instance, and there is no mutation site left to forget. `elementMode`
     * is not checked — every shape it selects between is built up front. `order`
     * is not either: it moves the offsets, not which keys resolve. `target` is a `val`.
     *
     * The filter comes from [dyn], the caller's per-pass snapshot, so the expansion checked
     * here and the element mode branching around it can't come from two different updates.
     */
    private fun expansionFor(effect: FxInstance, dyn: FxDynamics): FxTargetExpansion {
        // Read the version BEFORE the lookups it stamps — see [Fixtures.structureVersion].
        val version = fixtures.structureVersion
        val filter = dyn.elementFilter
        validExpansion(effect, version, filter)?.let { return it }
        // Clearing the plans here is not needed for correctness — [plansFor] compares the
        // expansion by identity, so a rebuild invalidates them anyway. It is needed so a stale
        // plan (and, through it, the whole superseded expansion's key lists) isn't pinned on an
        // effect whose new expansion is DIRECT_FIXTURE or NONE, which never reach [plansFor].
        effect.distributionPlans = null
        return buildExpansion(effect, version, filter).also { effect.expansion = it }
    }

    /** [effect]'s cached expansion when it is still valid for the register at [version] and [filter]. */
    private fun validExpansion(effect: FxInstance, version: Long, filter: ElementFilter): FxTargetExpansion? =
        effect.expansion?.takeIf { it.structureVersion == version && it.elementFilter == filter }

    /** [effect]'s cached plans when they were built from [expansion] under [dyn]'s Order and shape. */
    private fun validPlans(effect: FxInstance, expansion: FxTargetExpansion, dyn: FxDynamics): FxDistributionPlans? {
        val cached = effect.distributionPlans ?: return null
        // The shape's fields compared one by one rather than through `dyn.spreadShape`, which
        // would allocate on every pass for a cache hit.
        return cached.takeIf {
            it.expansion === expansion && it.order == dyn.order &&
                it.shape.spread == dyn.spread && it.shape.wings == dyn.wings &&
                it.shape.segments == dyn.segments && it.shape.parts == dyn.parts &&
                it.shape.unit == dyn.spreadUnit && it.steps == effect.planSteps(dyn)
        }
    }

    /**
     * Rate-master slot for a wall-clock effect. Unlike [SpeedMasterBank.slotFor], a **null**
     * uuid is not master 1: it means the effect has no rate master and runs unscaled, which is
     * what [FxInstance.rateSpeedMasterUuid] documents. A uuid the bank no longer knows still
     * falls back to master 1, matching `slotFor`.
     */
    private fun rateSlotFor(uuid: java.util.UUID?): Int =
        if (uuid == null) FxInstance.NO_RATE_MASTER else speedMasters.slotFor(uuid)

    /**
     * The distribution plans for [expansion] under this pass's strategy and spread shape — see
     * [FxInstance.distributionPlans]. Same cache-then-validate shape as [expansionFor], and
     * checked against the *identity* of the expansion the caller already resolved, so the two
     * caches cannot disagree about which generation of the register they describe.
     */
    private fun plansFor(
        effect: FxInstance,
        expansion: FxTargetExpansion,
        dyn: FxDynamics,
    ): FxDistributionPlans {
        validPlans(effect, expansion, dyn)?.let { return it }
        return FxDistributionPlans.build(expansion, dyn.order, dyn.spreadShape, effect.planSteps(dyn))
            .also { effect.distributionPlans = it }
    }

    /**
     * The sampler over this engine (FX editor plan §3.2): the compose pass's own order, expansions,
     * plans, underneath and held-back rule, read and never written (P4). [freshFx] builds a new
     * effect object for an FX, so a sample never shares one with the pass.
     */
    internal fun sampler(freshFx: (FxInstance) -> Fx): FxSampler = FxSampler(samplerView, freshFx)

    private val samplerView = object : FxSampler.View {
        // A draft (never inserted) sorts where [insert] would put it: a Local one at the top of its
        // lane, and after every running FX of its priority. Its rank is read without the rank lock;
        // a preview racing an insert may place it one off. An edited copy of a running FX
        // ([editedCopy]) carries its id and place, and sorts where that FX is.
        override fun applyOrder(): Comparator<FxInstance> {
            val draftPriority = HashMap<FxInstance, Int>()
            return applyOrder(
                priority = {
                    if (isDraft(it) && it.isLocalFx) {
                        draftPriority.getOrPut(it) { localFxPriority(topLocalRankFor(it, ::previewKeys)) }
                    } else it.priority
                },
                composeOrder = { if (isDraft(it)) Long.MAX_VALUE else it.composeOrder },
                id = { if (isDraft(it)) Long.MAX_VALUE else it.id },
            )
        }

        private fun isDraft(effect: FxInstance) = activeFx[effect.id] == null

        private fun previewKeys(effect: FxInstance) = expansion(effect, effect.dynamics).coverageKeys

        override fun running(): List<FxInstance> = sortedFx

        override fun fixture(key: String): GroupableFixture = fixtures.untypedGroupableFixture(key)

        override fun underneath(target: FxTarget, fixture: GroupableFixture, key: String): FxOutput =
            layerResolver.fallbackFor(target, fixture, key)

        // The caches when they are still valid, else built and not kept: storing them is the pass's.
        override fun expansion(effect: FxInstance, dynamics: FxDynamics): FxTargetExpansion {
            val version = fixtures.structureVersion
            return validExpansion(effect, version, dynamics.elementFilter)
                ?: buildExpansion(effect, version, dynamics.elementFilter)
        }

        override fun plans(effect: FxInstance, expansion: FxTargetExpansion, dynamics: FxDynamics): FxDistributionPlans =
            validPlans(effect, expansion, dynamics)
                ?: FxDistributionPlans.build(expansion, dynamics.order, dynamics.spreadShape, effect.planSteps(dynamics))

        // A staging transaction that is never applied: the only way to read a head's values.
        override fun current(target: FxTarget, key: String): FxOutput? = try {
            target.currentValue(fixtures.withTransaction(ControllerTransaction(fixtures.controllers)).untypedGroupableFixture(key))
        } catch (_: Exception) {
            null
        }

        override fun coveredByRunning(key: String, attribute: String): Boolean = (key to attribute) in coveredByRunningFx()

        override fun parked(target: FxTarget, fixture: GroupableFixture): Boolean = cascade.allChannelsParked(target, fixture)

        override fun speedSlot(uuid: UUID?): Int = speedMasters.slotFor(uuid)

        override fun rateSlot(uuid: UUID?): Int = rateSlotFor(uuid)

        override fun heldBack(): (FxInstance, String, String) -> KeyStackLayer.HeldBackReason? {
            val blind = programmerStore.blind
            val held = programmerSuppression(blind)
            return { effect, key, attribute -> FxSuppression.heldBackReason(held, blind, key, attribute, effect, isLayerStomped) }
        }

        override fun stackAmount(effect: FxInstance): Double = stackFxAmounts?.weightOf(effect) ?: StackFxAmounts.FULL

        override fun clock(): FxSampleClock {
            val frame = speedMasters.snapshotFrame()
            val scales = speedMasters.rateScales()
            fun bpm(slot: Int) = scales.getOrElse(slot) { scales.getOrElse(0) { 1.0 } } * MasterClock.DEFAULT_BPM
            return FxSampleClock(
                ticks = { frame.tick(it).tickNumber },
                msPerTicks = { 60_000.0 / bpm(it) / MasterClock.TICKS_PER_BEAT },
                rateScales = { scales.getOrElse(it) { 1.0 } },
            )
        }
    }

    /**
     * Walk the register once and resolve everything the compose pass needs for [effect]: which
     * branch applies, the unfiltered coverage keys, and the filtered keys the apply loop walks.
     *
     * The "not found" diagnostics live here rather than in the `process*` functions, which is
     * where they used to be — an effect pointing at a deleted group printed on every tick, at
     * up to 120 Hz. Once per register generation says the same thing without the flood.
     */
    private fun buildExpansion(
        effect: FxInstance,
        version: Long,
        filter: ElementFilter,
    ): FxTargetExpansion {
        fun none() = FxTargetExpansion.none(version, filter)

        /** Filtered by index within [keys], against [keys]'s own size — see `ElementFilter`. */
        fun applyFilter(keys: List<String>): List<String> =
            if (filter == ElementFilter.ALL) keys
            else keys.filterIndexed { index, _ -> filter.includes(index, keys.size) }

        (effect.target.targetRef as? FxTargetRef.SelectionRef)?.let { ref ->
            return buildSelectionExpansion(effect, ref, version, filter, ::applyFilter)
        }

        if (effect.isGroupFx) {
            val groupName = effect.target.targetKey
            val group = try {
                fixtures.untypedGroup(groupName)
            } catch (e: Exception) {
                throttle.log("missing-group-$groupName", e) {
                    "FX engine: group '$groupName' not found for effect ${effect.id}"
                }
                return none()
            }

            val allMembers = group.allMembers
            if (allMembers.isEmpty()) return none()

            val firstMemberFixture = try {
                fixtures.untypedFixture(allMembers.first().key)
            } catch (_: Exception) { return none() }

            // The first member stands for the group, as it always has. A heterogeneous group
            // takes the branch its first member implies.
            if (effect.target.fixtureHasAttribute(firstMemberFixture)) {
                return FxTargetExpansion(
                    structureVersion = version,
                    elementFilter = filter,
                    kind = FxTargetExpansion.Kind.GROUP_MEMBERS,
                    coverageKeys = allMembers.map { it.key },
                    flat = emptyList(),
                    perFixture = emptyList(),
                    // Copied out of the GroupMembers rather than held — see [MemberSlot].
                    members = allMembers.map {
                        MemberSlot(it.key, it.index, it.normalizedPosition)
                    },
                )
            }

            if (firstMemberFixture !is MultiElementFixture<*>) return none()
            val firstElements = firstMemberFixture.elements
            if (firstElements.isEmpty() || !effect.target.fixtureHasAttribute(firstElements.first())) {
                return none()
            }

            // Element keys per member, in member order. A member that isn't multi-element
            // contributes nothing — the same skip all three old walks took.
            val perMemberKeys = ArrayList<List<String>>(allMembers.size)
            for (member in allMembers) {
                val memberFixture = try {
                    fixtures.untypedFixture(member.key)
                } catch (_: Exception) { continue }
                if (memberFixture is MultiElementFixture<*>) {
                    perMemberKeys.add(memberFixture.elements.map { it.elementKey })
                }
            }

            val coverage = perMemberKeys.flatten()
            if (coverage.isEmpty()) return none()

            return FxTargetExpansion(
                structureVersion = version,
                elementFilter = filter,
                kind = FxTargetExpansion.Kind.GROUP_ELEMENTS,
                coverageKeys = coverage,
                // FLAT filters across the whole set, on the global flat index; PER_FIXTURE and WHOLE
                // filters within each fixture, on that fixture's own element count. All are
                // built here so `elementMode` stays out of the cache identity.
                flat = applyFilter(coverage),
                perFixture = perMemberKeys.map { applyFilter(it) },
                members = emptyList(),
            )
        }

        // Fixture effect — or an element (cell) effect: `untypedGroupableFixture` answers an
        // element for an element key, so an effect template pressed on one cell paints that cell
        // (busk-further plan, session 1). A cell that has the property is a DIRECT_FIXTURE; the
        // multi-element branch below is for a parent that lacks it.
        val fixtureKey = effect.target.targetKey
        val fixture = try {
            fixtures.untypedGroupableFixture(fixtureKey)
        } catch (e: Exception) {
            throttle.log("missing-fixture-$fixtureKey", e) {
                "FX engine: fixture '$fixtureKey' not found for effect ${effect.id}"
            }
            return none()
        }

        if (effect.target.fixtureHasAttribute(fixture)) {
            val single = listOf(fixtureKey)
            return FxTargetExpansion(
                structureVersion = version,
                elementFilter = filter,
                kind = FxTargetExpansion.Kind.DIRECT_FIXTURE,
                coverageKeys = single,
                flat = single,
                perFixture = emptyList(),
                members = emptyList(),
            )
        }

        // Multi-element expansion for fixture
        if (fixture is MultiElementFixture<*>) {
            val elements = fixture.elements
            if (elements.isNotEmpty() && effect.target.fixtureHasAttribute(elements.first())) {
                val elementKeys = elements.map { it.elementKey }
                return FxTargetExpansion(
                    structureVersion = version,
                    elementFilter = filter,
                    kind = FxTargetExpansion.Kind.FIXTURE_ELEMENTS,
                    coverageKeys = elementKeys,
                    flat = applyFilter(elementKeys),
                    perFixture = emptyList(),
                    members = emptyList(),
                )
            }
        }

        return none()
    }

    /**
     * A selection's expansion (effects plan §3.4): its items flattened in order — a fixture or a
     * cell as itself, a group as its members in their own order — and each of those units either a
     * member (it has the property) or its heads (its elements do). A head reached twice counts once,
     * at its first place, and an item that resolves to nothing is skipped.
     *
     * Every unit a member is [FxTargetExpansion.Kind.GROUP_MEMBERS], spaced evenly in selection
     * order. Every unit's heads is [FxTargetExpansion.Kind.GROUP_ELEMENTS], exactly as a group of
     * multi-head fixtures. A mix of the two is laid out the same way, a member being one unit of one
     * head: per fixture, each multi-head fixture runs across its own heads beside it.
     */
    private fun buildSelectionExpansion(
        effect: FxInstance,
        ref: FxTargetRef.SelectionRef,
        version: Long,
        filter: ElementFilter,
        applyFilter: (List<String>) -> List<String>,
    ): FxTargetExpansion {
        val target = effect.target
        val seen = HashSet<String>()
        val perUnit = ArrayList<List<String>>()
        var anyElements = false
        for (unitKey in selectionUnitKeys(ref, effect.id)) {
            val unit = try {
                fixtures.untypedGroupableFixture(unitKey)
            } catch (_: Exception) { continue }
            if (target.fixtureHasAttribute(unit)) {
                if (seen.add(unitKey)) perUnit.add(listOf(unitKey))
                continue
            }
            if (unit !is MultiElementFixture<*>) continue
            val heads = unit.elements.filter { target.fixtureHasAttribute(it) }.map { it.elementKey }.filter { seen.add(it) }
            if (heads.isNotEmpty()) {
                perUnit.add(heads)
                anyElements = true
            }
        }
        if (perUnit.isEmpty()) return FxTargetExpansion.none(version, filter)

        val coverage = perUnit.flatten()
        if (!anyElements) {
            val last = (coverage.size - 1).coerceAtLeast(1)
            return FxTargetExpansion(
                structureVersion = version,
                elementFilter = filter,
                kind = FxTargetExpansion.Kind.GROUP_MEMBERS,
                coverageKeys = coverage,
                flat = emptyList(),
                perFixture = emptyList(),
                members = coverage.mapIndexed { i, key ->
                    MemberSlot(key, i, if (coverage.size > 1) i.toDouble() / last else 0.5)
                },
            )
        }
        val flat = applyFilter(coverage)
        return FxTargetExpansion(
            structureVersion = version,
            elementFilter = filter,
            kind = FxTargetExpansion.Kind.GROUP_ELEMENTS,
            coverageKeys = coverage,
            flat = flat,
            perFixture = perUnit.map(applyFilter),
            members = emptyList(),
        )
    }

    /**
     * A selection's units in order, before any property check: each fixture or cell it names, and
     * each member of each group it names. Repeats are left for the caller, which dedupes by head.
     */
    private fun selectionUnitKeys(ref: FxTargetRef.SelectionRef, fxId: Long): List<String> {
        val keys = ArrayList<String>()
        for (item in ref.items) {
            when (item) {
                is TargetRef.Fixture -> keys.add(item.key)
                is TargetRef.Group -> {
                    val group = try {
                        fixtures.untypedGroup(item.key)
                    } catch (e: Exception) {
                        throttle.log("missing-group-${item.key}", e) {
                            "FX engine: group '${item.key}' not found for selection effect $fxId"
                        }
                        continue
                    }
                    group.allMembers.mapTo(keys) { it.key }
                }
            }
        }
        return keys
    }

    /**
     * Check if any effect in the list covers a (fixtureKey, attribute) pair.
     *
     * Handles direct fixture effects, group effects whose members include the
     * fixture, and multi-element expansion at both levels. Paused effects still
     * count as covering their channels.
     *
     * **Deliberately not routed through [expansionFor].** This is broader than
     * [FxTargetExpansion.coverageKeys] on purpose: for an element-expanded group effect it
     * also answers true for the *member* keys, which coverage excludes. Swapping in the
     * cached list would narrow it and start resetting parents that a removed effect never
     * painted. Two questions, two answers — not one cache.
     */
    private fun isAttributeCoveredByAny(
        fixtureKey: String,
        attribute: String,
        remainingFx: List<FxInstance>
    ): Boolean {
        for (effect in remainingFx) {
            if (effect.target.attribute != attribute) continue

            val selection = effect.target.targetRef as? FxTargetRef.SelectionRef
            if (selection != null) {
                // The heads it paints, and — as for a group — the parents of heads it paints.
                if (fixtureKey in resolveFxFixtureKeys(effect)) return true
                if (fixtureKey in selectionUnitKeys(selection, effect.id)) return true
            } else if (!effect.isGroupFx) {
                if (effect.target.targetKey == fixtureKey) return true

                // Check multi-element: parent effect covers element keys
                val fixture = try {
                    fixtures.untypedFixture(effect.target.targetKey)
                } catch (_: Exception) { continue }
                if (fixture is MultiElementFixture<*> && !effect.target.fixtureHasAttribute(fixture)) {
                    if (fixture.elements.any { it.elementKey == fixtureKey }) return true
                }
            } else {
                val group = try {
                    fixtures.untypedGroup(effect.target.targetKey)
                } catch (_: Exception) { continue }

                if (group.allMembers.any { it.key == fixtureKey }) return true

                // Element-level match
                for (member in group.allMembers) {
                    val memberFixture = try {
                        fixtures.untypedFixture(member.key)
                    } catch (_: Exception) { continue }
                    if (memberFixture is MultiElementFixture<*>) {
                        if (memberFixture.elements.any { it.elementKey == fixtureKey }) return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Report every effect again, for a change to what an instance reports that was made outside the
     * engine's own mutators — Update re-baselining an included Look effect
     * ([FxInstance.includedLookFx]), which moves its `editedSinceInclude`. Without it the
     * clients' active-effect lists keep the old answer until something unrelated changes an effect.
     */
    fun republishFxStates() = emitStateUpdate()

    private fun emitStateUpdate() {
        // Deliberately does NOT touch [fxListEpoch]: this is a broadcaster, and the
        // coverage-cache invalidation must happen at the mutation itself (see
        // [rebuildSortedSnapshots]) — the flows between a mutation and this call include the
        // very Layer 4 publishes that consult the cache.
        // One bank snapshot for the whole emit — masterStates() maps every slot into a
        // fresh list, and calling it per effect made this O(effects x masters) allocation.
        val masterStates = speedMasters.masterStates()
        val states = activeFx.mapValues { (_, instance) ->
            instance.toFxDto(masterStates, isMultiElementExpanded(instance))
        }

        _fxStateFlow.value = FxStateUpdate(
            activeFxIds = activeFx.keys.toList(),
            fxStates = states,
        )
        // Effect lifecycle changes move provenance winners — piggyback on the same sites.
        provenance.emitUpdate()
    }
}
