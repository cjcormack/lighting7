package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = LoggerFactory.getLogger("FxEngine")

/**
 * Central effect processing engine: the tick loops and the active-effect set.
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
 * val effectId = engine.addEffect(FxInstance(
 *     effect = SineWave(),
 *     target = SliderTarget("front-wash-1", "dimmer"),
 *     timing = FxTiming(BeatDivision.HALF)
 * ))
 *
 * // Remove when done
 * engine.removeEffect(effectId)
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

    private val nextEffectId = AtomicLong(0)
    private val activeEffects = ConcurrentHashMap<Long, FxInstance>()

    /**
     * Bank membership version the effects' runtime slots were last bound against; when the
     * bank's [SpeedMasterBank.version] moves past this, the next beat pass re-binds every
     * active instance ([rebindSpeedMasters]).
     */
    @Volatile private var boundBankVersion: Long = -1L

    // Read lock-free by the hot tick loops; rebuilt under [effectSnapshotLock] on mutation.
    private val effectSnapshotLock = Any()
    private val sortedEffectsComparator = compareBy<FxInstance>({ it.priority }, { it.id })
    @Volatile private var sortedBeatEffects: List<FxInstance> = emptyList()
    @Volatile private var sortedWallClockEffects: List<FxInstance> = emptyList()

    @Volatile private var lastTickMs: Long = 0L
    @Volatile private var lastWallClockTickMs: Long = 0L

    /**
     * Productive-pass counter handed to stateful wall-clock effects as `tick.tickNumber`.
     *
     * Owned solely by [processWallClockTickSuspend], which is a single coroutine, so it needs
     * no synchronisation. Counts only passes that reach the apply stage: burning numbers while
     * nothing is running would hand a re-armed effect an arbitrary jump.
     */
    private var wallClockPassNumber: Long = 0L

    // Shared with [CascadePublisher] so one fault keeps one suppression history wherever
    // it is reported from — see [FxLogThrottle].
    private val throttle = FxLogThrottle(logger)

    /** Stable throttle key for a fault attributable to [effect] — see [FxLogThrottle]. */
    private fun faultKey(prefix: String, effect: FxInstance): String =
        "$prefix-${effect.effectTypeId}-${effect.target.targetKey}.${effect.target.propertyName}"

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
     * The counter is owned by the tick loops (an instance is on the beat path or the wall-clock
     * path, never both), and an `updateEffect` swap resets it — which is right, since changed
     * parameters deserve a fresh verdict.
     */
    private fun noteTickFailure(
        effect: FxInstance,
        e: Throwable,
        what: String,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
    ) {
        val failures = ++effect.consecutiveTickFailures

        val describe = "$what ${effect.id} ('${effect.effect.name}' on " +
            "${effect.target.targetKey}.${effect.target.propertyName})"

        if (failures >= MAX_CONSECUTIVE_TICK_FAILURES) {
            effect.consecutiveTickFailures = 0
            effect.pause()
            // isRunning is part of effect coverage; see [rebuildSortedSnapshots].
            effectListEpoch.incrementAndGet()

            // An effect can throw part-way through its own targets — a group effect that dies on
            // member 7 has already painted 0..6 this pass. From the next pass on, a paused effect
            // is skipped by [resetActiveProperties], so whatever it managed to write would stay
            // frozen on those channels with nothing left to move it. Put the layer below back
            // now, into this pass's open transaction, so the property lands somewhere defined.
            // (A manual pause deliberately freezes instead: that frame is one the operator chose.)
            for (key in resolveEffectFixtureKeys(effect)) {
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
     * its internal slot bindings. As two fields, racing tick loops could pair an older map
     * with a newer epoch and serve stale suppression until the next programmer mutation.
     */
    private class SuppressionSnapshot(
        val byFixture: Map<String, Set<String>>,
        val epoch: Long,
    )

    // Per-tick programmer suppression snapshot, cached on the store's coverage epoch so the
    // 50 Hz loops rebuild it only when the set of covered keys changes — a busk rewriting
    // already-held keys bumps [ProgrammerStore.epoch] per write but never this. The coverage
    // epoch is bumped *after* the mutation it reports, so a rebuild that observed the new
    // epoch also observes the write. Both tick loops may still race the rebuild; each
    // publishes a self-consistent (map, epoch) pair, and a pair that lost the race carries
    // the older epoch with it, so the next tick detects the mismatch and rebuilds — one
    // stale tick at worst, never a latch.
    @Volatile private var suppressionCache = SuppressionSnapshot(emptyMap(), epoch = -1L)

    /**
     * fixtureKey → property names with an active programmer entry, or empty when blind is
     * engaged (blind removes the programmer from the merge, so effects paint normally).
     * An entry here suppresses every effect on that (fixture, property) except effects in
     * the programmer priority band — the "programmer wins over effects" rule.
     */
    private fun programmerSuppression(): Map<String, Set<String>> {
        if (programmerStore.blind) return emptyMap()
        val epoch = programmerStore.coverageEpoch
        var cached = suppressionCache
        if (epoch != cached.epoch) {
            cached = SuppressionSnapshot(programmerStore.activePropertiesByFixture(), epoch)
            suppressionCache = cached
        }
        return cached.byFixture
    }

    /**
     * The (fixtureKey, propertyName) set running effects cover, and the stamps it was built
     * from, published as ONE reference — the same single-snapshot reasoning as
     * [SuppressionSnapshot].
     */
    private class EffectCoverageSnapshot(
        val covered: Set<Pair<String, String>>,
        val effectListEpoch: Long,
        val structureVersion: Long,
    )

    // Effect-coverage snapshot for the Layer 4 / cascade publish paths, cached until the
    // effect list or the fixture register moves. Crossfade republishes consult this at
    // ~62 fps and used to rebuild it per frame from a [resolveEffectFixtureKeys] walk over
    // every active effect (sweep item C3). [effectListEpoch] is bumped immediately *after*
    // each mutation and *before* any repaint or publish the same flow goes on to do — in
    // [rebuildSortedSnapshots] for the map mutations, explicitly at the isRunning flips and
    // [updateEffect]'s non-swap path (see [rebuildSortedSnapshots] for why not
    // [emitStateUpdate]). A rebuild racing a cross-thread mutation publishes the older epoch
    // with its set, so the next read detects the mismatch and rebuilds — one stale publish
    // at worst, never a latch, and the mutating flow's own repaint runs on fresh coverage.
    @Volatile private var effectCoverageCache =
        EffectCoverageSnapshot(emptySet(), effectListEpoch = -1L, structureVersion = -1L)
    private val effectListEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    /** The (fixtureKey, propertyName) pairs running effects cover — see [effectCoverageCache]. */
    private fun coveredByRunningEffects(): Set<Pair<String, String>> {
        // Read the stamps BEFORE the scan they cover, so a mutation landing mid-scan leaves
        // this rebuild carrying the older stamp and the next read rebuilds.
        val epoch = effectListEpoch.get()
        val version = fixtures.structureVersion
        val cached = effectCoverageCache
        if (cached.effectListEpoch == epoch && cached.structureVersion == version) {
            return cached.covered
        }
        val covered: Set<Pair<String, String>> = if (activeEffects.isEmpty()) {
            emptySet()
        } else buildSet {
            for (effect in activeEffects.values) {
                if (!effect.isRunning) continue
                val propertyName = effect.target.propertyName
                for (fixtureKey in resolveEffectFixtureKeys(effect)) {
                    add(fixtureKey to propertyName)
                }
            }
        }
        effectCoverageCache = EffectCoverageSnapshot(covered, epoch, version)
        return covered
    }

    /**
     * Should [effect] skip painting `(fixtureKey, propertyName)` this tick?
     *
     * Two independent reasons, and the order matters:
     *
     * 1. **Within-cue / within-stack stomp** — a higher layer with `stomp` set asserts this
     *    property, so this layer's effect is switched off on it. Checked *first*, and deliberately
     *    outside the programmer-band exemption below: a programmer layer's effects live in that band
     *    by construction, so exempting the band would make programmer stomp a no-op.
     * 2. **Programmer suppression** — the programmer holds this key, so effects must not paint over
     *    it. Band effects are exempt: they modulate on top of the programmer rather than fighting it.
     *
     * The reset pass ([resetActiveProperties]) has already put the layer below on the property, so a
     * skipped apply *shows the cooked value* rather than freezing the effect's last frame. That is
     * what makes suppression recoverable where removal would not be: the instance keeps running, and
     * clearing the stomp brings it back with its phase intact.
     */
    private fun isSuppressed(
        suppression: Map<String, Set<String>>,
        fixtureKey: String,
        propertyName: String,
        effect: FxInstance,
    ): Boolean {
        if (cueLayer.isLayerStomped(effect, fixtureKey, propertyName)) return true

        if (suppression.isEmpty()) return false
        if (isProgrammerFxPriority(effect.priority)) return false
        return suppression[fixtureKey]?.contains(propertyName) == true
    }

    private fun rebuildSortedSnapshots() {
        // Every mutation of [activeEffects] calls this immediately afterwards — before any
        // repaint or Layer 4 publish the same flow goes on to do — which makes it the earliest
        // reliable place to invalidate the effect-coverage cache. Bumping later (say, in
        // [emitStateUpdate]) leaves the mutation→bump window spanning those publishes, and a
        // publish reading stale coverage skips keys nothing will repaint. The isRunning flips
        // ([pauseEffect]/[resumeEffect]/the tick-failure auto-pause) and [updateEffect]'s
        // non-swap path don't come through here and bump [effectListEpoch] themselves.
        effectListEpoch.incrementAndGet()
        synchronized(effectSnapshotLock) {
            val beat = ArrayList<FxInstance>(activeEffects.size)
            val wall = ArrayList<FxInstance>()
            for (effect in activeEffects.values) {
                when (effect.timingSource) {
                    TimingSource.BEAT -> beat.add(effect)
                    TimingSource.WALL_CLOCK -> wall.add(effect)
                }
            }
            beat.sortWith(sortedEffectsComparator)
            wall.sortWith(sortedEffectsComparator)
            sortedBeatEffects = beat
            sortedWallClockEffects = wall
        }
    }

    private var processingJob: Job? = null
    private var wallClockJob: Job? = null

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

        /**
         * How many consecutive passes an effect may throw out of before the engine pauses it.
         *
         * In passes rather than seconds, so the grace period varies with how fast the desk is
         * ticking: ~2.4s on the 50 Hz wall-clock loop, 5s for a lone master at 60 BPM (24
         * ticks/beat), 1s for one at 300 BPM, and less again with several out-of-phase masters
         * driving the pass. See [noteTickFailure] for why that direction is the safe one.
         */
        const val MAX_CONSECUTIVE_TICK_FAILURES = 120

        /**
         * Reserved priority band for programmer-owned effects (Session 2's busking FX).
         * Strictly above every cue-derived priority
         * (`cueDerivedPriority(stackId, sort) = stackId*1M + sort*1K + 1` in
         * `routes/projectCuesHelpers.kt`) and every manual effect (priority 0). Effects in
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

        /** True when [priority] sits in the programmer-owned effect band. */
        fun isProgrammerFxPriority(priority: Int): Boolean = priority >= PROGRAMMER_FX_PRIORITY_BASE
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
    private val _fxStateFlow = MutableStateFlow(FxStateUpdate(activeEffectIds = emptyList(), effectStates = emptyMap()))
    val fxStateFlow: StateFlow<FxStateUpdate> = _fxStateFlow.asStateFlow()

    // --- E1 split components ---
    //
    // Four components extracted from what used to be a ~3,400-line engine (sweep item E1):
    // the engine keeps the tick loops and the effect set; these own the rest. Constructed in
    // dependency order — the lambdas defer the two cyclic edges (cueLayer → provenance,
    // provenance → the engine's effect set) until first call, which is safely after
    // construction.

    /**
     * Layer-4/cascade → controller transmit machinery, and the Layer 4 publish lock.
     * Public for [CascadePublisher.resolveChannelCoveringKey], which routes consult; the
     * publish entry points themselves are internal.
     */
    val cascade: CascadePublisher = CascadePublisher(
        fixtures, layerResolver, parkManager, ::coveredByRunningEffects, throttle,
    )

    /** Per-cue Layer 4 assignment bookkeeping and the within-cue stomp registry. */
    val cueLayer: CueAssignmentLayer = CueAssignmentLayer(layerResolver, cascade) { weightsOnly ->
        provenance.emitUpdate(cueFadeOnly = weightsOnly)
    }

    /**
     * Provenance computation + broadcast. The lambdas hand it read access to the engine's
     * live effect set without a service → engine reference.
     */
    val provenance: ProvenanceService = ProvenanceService(
        fixtures, programmerStore, layerResolver, cascade,
        activeEffects = { activeEffects.values },
        coverageKeys = ::resolveEffectFixtureKeys,
        isLayerStomped = cueLayer::isLayerStomped,
        cueStackIdFor = cueLayer::cueStackIdFor,
        effectOrder = sortedEffectsComparator,
    )

    /** PROGRAMMER-layer write delegation. */
    val programmer: ProgrammerWriter = ProgrammerWriter(fixtures, programmerStore, cascade) {
        provenance.emitUpdate()
    }

    /**
     * Rewrite the composition priority of live rows owned by the cues in [priorities]
     * (`cueId → new priority`).
     *
     * Cue priority is derived from stack position and stamped at apply time
     * (`cueDerivedPriority`), so reordering a stack that has cues on stage would otherwise leave
     * those cues composing in their *old* relative order until each was re-applied. Callers that
     * change `sort_order` hand the fresh map here to keep the engine consistent with the stack.
     *
     * Touches both layers a cue can own — Layer 3 [FxInstance.priority] and the Layer 4
     * assignment rows — and leaves the crossfade weights alone so a repriority mid-crossfade doesn't
     * disturb the fade. Entries whose priority already matches are skipped, which makes the
     * common single-live-cue reorder a no-op. Returns the number of rows changed.
     */
    fun repriorityCues(priorities: Map<Int, Int>): Int {
        if (priorities.isEmpty()) return 0
        var changed = 0

        // Layer 3 — mutate in place, then re-sort the snapshots the tick loop reads.
        val staleEffects = activeEffects.values.filter { effect ->
            val target = priorities[effect.cueId ?: return@filter false] ?: return@filter false
            effect.priority != target
        }
        if (staleEffects.isNotEmpty()) {
            for (effect in staleEffects) effect.priority = priorities.getValue(effect.cueId!!)
            changed += staleEffects.size
            rebuildSortedSnapshots()
            emitStateUpdate()
        }

        // Layer 4 — the assignment rows live in [cueLayer]; it rebuilds them by copy.
        changed += cueLayer.repriorityAssignments(priorities)
        return changed
    }

    /**
     * Remove all effects that belong to a specific cue stack.
     *
     * Used both for a cue transition within a stack and for fully deactivating one. There used to
     * be two functions here because a stack carried a positional colour list that had to survive a
     * transition but not a deactivation; with that gone the two callers want the same thing.
     *
     * @param stackId The cue stack ID whose effects should be removed
     * @return Number of effects removed
     */
    fun removeEffectsForCueStack(stackId: Int): Int {
        val toRemove = activeEffects.values.filter { it.cueStackId == stackId }
        toRemove.forEach { activeEffects.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            // Sorted snapshots are read lock-free by [processBeatTick] / [processWallClockTick].
            // Without rebuilding, the tick loops keep firing the orphaned [FxInstance] refs
            // against their old targets — one of the reasons a cue's effects would "refuse to
            // die" after a cue transition.
            rebuildSortedSnapshots()
            resetUncoveredProperties(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Represents a state update for broadcasting. Carries [EffectDto] directly: the effect
     * report has one shape across every transport, so there is nothing left for a
     * broadcast-only intermediate to add (sweep item F8).
     */
    data class FxStateUpdate(
        val activeEffectIds: List<Long>,
        val effectStates: Map<Long, EffectDto>
    )

    /**
     * Start the FX engine.
     *
     * @param scope The coroutine scope to run the engine in
     */
    fun start(scope: CoroutineScope) {
        speedMasters.start(scope)
        provenance.start(scope)

        // Beat processing loop: one pass per wake-up, over one coherent frame of every
        // master's current tick. The wake channel is CONFLATED, so ticks from N masters
        // landing while a pass is in flight collapse into a single follow-up pass — the
        // pass rate is bounded by the fastest master, and one pass means one
        // ControllerTransaction however many masters are ticking.
        //
        // Both loops swallow a failed pass rather than letting it out: anything escaping here
        // cancels the job for the rest of the process, and the desk's effects would stop for
        // good with one line on whatever the uncaught-exception handler is. The per-effect
        // catches inside a pass cover the common case; this covers the pass-level work around
        // them (the reset sweep, the suppression rebuild, the transaction commit). Throwable
        // rather than Exception for the same reason those are — a script effect can raise an
        // Error — with cancellation rethrown so [stop] still stops the loop.
        processingJob = scope.launch(Dispatchers.Default) {
            for (wake in speedMasters.wake) {
                try {
                    processBeatTickSuspend(speedMasters.snapshotFrame())
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    throttle.log("beat-pass", t) { "FX engine: beat pass failed" }
                }
            }
        }

        // Wall-clock processing loop (50Hz, independent of BPM)
        wallClockJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(WALL_CLOCK_INTERVAL_MS)
                try {
                    processWallClockTickSuspend()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    throttle.log("wall-clock-pass", t) { "FX engine: wall-clock pass failed" }
                }
            }
        }
    }

    /**
     * Stop the FX engine and all active effects.
     */
    fun stop() {
        provenance.stop()
        processingJob?.cancel()
        processingJob = null
        wallClockJob?.cancel()
        wallClockJob = null
        speedMasters.stop()
        val allEffects = activeEffects.values.toList()
        activeEffects.clear()
        rebuildSortedSnapshots()
        cueLayer.clearAll()
        resetUncoveredProperties(allEffects)
        emitStateUpdate()
    }

    /**
     * Add an effect and return its ID.
     *
     * @param effect The effect instance to add
     * @return The assigned effect ID
     */
    fun addEffect(effect: FxInstance): Long {
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
     * IDs still come from [nextEffectId] in list order, which is what keeps spawn order equal
     * to composition order for same-priority effects — see the note on `sortedEffectsComparator`
     * at the cue apply sites. The one rebuild lands before any repaint or Layer 4 publish the
     * calling flow goes on to do, which is the invariant [rebuildSortedSnapshots] documents.
     */
    fun addEffects(effects: List<FxInstance>): List<Long> {
        if (effects.isEmpty()) return emptyList()
        val ids = effects.map { insert(it) }
        rebuildSortedSnapshots()
        emitStateUpdate()
        return ids
    }

    /**
     * Stamp an instance and put it in [activeEffects], without rebuilding or broadcasting.
     * Every caller must follow with [rebuildSortedSnapshots] then [emitStateUpdate].
     */
    private fun insert(effect: FxInstance): Long {
        val id = nextEffectId.incrementAndGet()
        effect.id = id
        effect.startedAtMs = System.currentTimeMillis()

        // Auto-tag with CueContext if set and effect doesn't already have a cueId.
        // Programmer-band effects are exempt: they belong to the operator's programmer, not
        // to whatever cue happens to be running an FX_APPLICATION script at the time, and a
        // stray cue tag would let `removeEffectsForCue` sweep them out from under a busk.
        if (!isProgrammerFxPriority(effect.priority)) {
            currentCueContext?.let { ctx ->
                if (effect.cueId == null) effect.cueId = ctx.cueId
                if (effect.cueStackId == null) effect.cueStackId = ctx.cueStackId
            }
        }

        if (effect.effect is StatefulEffect) {
            (effect.effect as StatefulEffect).initialize()
        }

        // Bind the persisted master uuids to runtime bank slots. Unknown or null → master 1
        // for the beat master; for the rate master only *unknown* does, since null there
        // means no rate master at all — see [rateSlotFor].
        effect.speedMasterSlot = speedMasters.slotFor(effect.speedMasterUuid)
        effect.rateMasterSlot = rateSlotFor(effect.rateSpeedMasterUuid)

        activeEffects[id] = effect
        return id
    }

    /**
     * Remove an effect by ID.
     *
     * @param effectId The ID of the effect to remove
     * @return true if an effect was removed
     */
    fun removeEffect(effectId: Long): Boolean {
        val removed = activeEffects.remove(effectId)
        if (removed != null) {
            rebuildSortedSnapshots()
            resetUncoveredProperties(listOf(removed))
            emitStateUpdate()
        }
        return removed != null
    }

    /**
     * Get an effect by ID.
     *
     * @param effectId The effect ID
     * @return The effect instance, or null if not found
     */
    fun getEffect(effectId: Long): FxInstance? = activeEffects[effectId]

    /**
     * Get all active effect instances.
     */
    fun getActiveEffects(): List<FxInstance> = activeEffects.values.toList()

    /**
     * Report a batch of live effects. The only shape callers should use: [toEffectDto] needs a
     * [SpeedMasterBank.masterStates] snapshot and an [isMultiElementExpanded] answer, both of
     * which only the engine can supply, and hoisting the snapshot once per batch is what keeps
     * this off O(effects x masters).
     */
    fun effectDtos(instances: Collection<FxInstance>): List<EffectDto> {
        val masterStates = speedMasters.masterStates()
        return instances.map { it.toEffectDto(masterStates, isMultiElementExpanded(it)) }
    }

    /** Report a single live effect; see [effectDtos]. */
    fun effectDto(instance: FxInstance): EffectDto =
        instance.toEffectDto(speedMasters.masterStates(), isMultiElementExpanded(instance))

    /**
     * The live programmer-*layer* effects, keyed by the identity stamped at spawn.
     *
     * This is the engine owning the band (sweep item E6): `ProgrammerLayerStack.syncEffects`
     * classifies against this snapshot instead of keeping its own instance map, so a removal it
     * did not perform — [removeProgrammerBandEffects], the FX sheet's remove — needs no
     * reconciliation step to be seen. Band effects with no key (Include's ad-hoc children, a
     * manual busk effect at band priority) are not layer effects and are absent by construction.
     *
     * One instance per key: two live instances sharing a key would collapse to one here, and the
     * loser could then never be retracted by a recook — only a band sweep would take it. The
     * spawn path makes that unreachable (`syncEffects` dedupes desired keys and holds its lock
     * across the whole classify-spawn-retract pass), which is why collapsing is safe.
     */
    fun programmerLayerEffects(): Map<ProgrammerLayerEffectKey, FxInstance> =
        activeEffects.values.mapNotNull { effect ->
            effect.programmerLayerEffectKey?.let { it to effect }
        }.toMap()

    /**
     * Get all active effects targeting a specific group.
     *
     * @param groupName The group name
     * @return List of effect instances targeting this group
     */
    fun getEffectsForGroup(groupName: String): List<FxInstance> {
        return activeEffects.values.filter {
            it.isGroupEffect && it.target.targetKey == groupName
        }
    }

    /**
     * Get all active effects directly targeting a specific fixture.
     *
     * @param fixtureKey The fixture key
     * @return List of effect instances directly targeting this fixture
     */
    fun getEffectsForFixture(fixtureKey: String): List<FxInstance> {
        return activeEffects.values.filter {
            !it.isGroupEffect && it.target.targetKey == fixtureKey
        }
    }

    /**
     * Get all active effects that indirectly affect a fixture through group membership.
     *
     * @param fixtureKey The fixture key
     * @return List of group effect instances whose groups contain this fixture
     */
    fun getIndirectEffectsForFixture(fixtureKey: String): List<FxInstance> {
        val groupNames = fixtures.groupsForFixture(fixtureKey).toSet()
        if (groupNames.isEmpty()) return emptyList()

        return activeEffects.values.filter {
            it.isGroupEffect && it.target.targetKey in groupNames
        }
    }

    /**
     * Update a running effect in place.
     *
     * Dynamics fields (phaseOffset, distributionStrategy, elementMode, elementFilter,
     * stepTiming) are applied as **one** [FxInstance.updateDynamics] call, so a tick can
     * never observe half of an update. Truly immutable fields (effect, timing, blendMode)
     * trigger a swap — a new [FxInstance] replaces the old one, preserving id, start time,
     * and (by sharing the old instance's dynamics cell) the running state and any
     * concurrent pause/resume or dynamics edit. Speed-master reassignment is applied to
     * whichever instance survives.
     *
     * @param effectId The effect ID to update
     * @param newEffect New effect (or null to keep existing)
     * @param newTiming New timing (or null to keep existing)
     * @param newBlendMode New blend mode (or null to keep existing)
     * @param newPhaseOffset New phase offset (or null to keep existing)
     * @param newDistributionStrategy New distribution strategy (or null to keep existing)
     * @param newElementMode New element mode (or null to keep existing)
     * @return The updated effect instance, or null if not found
     */
    fun updateEffect(
        effectId: Long,
        newEffect: Effect? = null,
        newTiming: FxTiming? = null,
        newBlendMode: BlendMode? = null,
        newPhaseOffset: Double? = null,
        newDistributionStrategy: DistributionStrategy? = null,
        newElementMode: ElementMode? = null,
        newElementFilter: ElementFilter? = null,
        newStepTiming: Boolean? = null,
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
         * The canonical registration id of [newEffect], when the caller swapped the effect for one
         * of a different type. Null = keep, like every other param. Passing [newEffect] without
         * this leaves [FxInstance.registrationId] naming the type the instance no longer runs.
         */
        newRegistrationId: String? = null,
    ): FxInstance? {
        val existing = activeEffects[effectId] ?: return null

        // One atomic dynamics update covering both branches: the cell is shared across the
        // swap below, so applying it up front reaches whichever instance survives — and a
        // tick pass sees either none of this update or all of it, never half.
        if (newPhaseOffset != null || newDistributionStrategy != null || newElementMode != null ||
            newElementFilter != null || newStepTiming != null
        ) {
            existing.updateDynamics { dyn ->
                dyn.copy(
                    phaseOffset = newPhaseOffset ?: dyn.phaseOffset,
                    distributionStrategy = newDistributionStrategy ?: dyn.distributionStrategy,
                    elementMode = newElementMode ?: dyn.elementMode,
                    elementFilter = newElementFilter ?: dyn.elementFilter,
                    stepTiming = newStepTiming ?: dyn.stepTiming,
                )
            }
        }

        // Determine if we need an atomic swap (immutable fields changed)
        val needsSwap = newEffect != null || newTiming != null || newBlendMode != null

        val updated = if (needsSwap) {
            FxInstance(
                effect = newEffect ?: existing.effect,
                target = existing.target,
                timing = newTiming ?: existing.timing,
                blendMode = newBlendMode ?: existing.blendMode,
                // The dynamics (running state, phase offset, distribution, element
                // mode/filter, step timing) travel by this shared cell, not by copying —
                // which is what makes a pause/resume racing this swap unlosable.
                dynamicsRef = existing.dynamicsRef,
            ).apply {
                id = existing.id
                registrationId = newRegistrationId ?: existing.registrationId
                source = existing.source
                // The layer ids are what `isSuppressed` looks a stomp mask up by, and what
                // Record reads to tell a layer's effect from a loose one. Dropping them here
                // un-stomps an edited layer effect and makes Record write it back a second
                // time as an ad-hoc child.
                cueLayerId = existing.cueLayerId
                programmerLayerEffectKey = existing.programmerLayerEffectKey
                programmerOrigin = existing.programmerOrigin
                cueId = existing.cueId
                cueStackId = existing.cueStackId
                priority = existing.priority
                // A manual fade lives here; without it, editing an effect mid-fade snaps it to
                // full output.
                intensityMultiplier = existing.intensityMultiplier
                startedAtMs = existing.startedAtMs
                startedAtBeat = existing.startedAtBeat
                lastPhase = existing.lastPhase
                // Wall-clock phase derives from this and nothing else, so dropping it here
                // would snap an edited wall-clock effect back to the start of its cycle —
                // the very discontinuity the accumulator replaced `startedAtMs` to avoid.
                accumulatedScaledMs = existing.accumulatedScaledMs
                timingSource = existing.timingSource
                // `expansion` is deliberately NOT carried across: the fresh instance rebuilds
                // it on first read, which costs one walk and is the safe direction. Carrying it
                // would survive a future `updateEffect` that learns to retarget, and the effect
                // would silently keep painting the old fixtures.
                //
                // Master assignment must survive the swap: this block hand-enumerates every
                // carried field, and missing one silently yanks an edited effect back to
                // master 1 the moment its beat division or blend mode is tweaked.
                speedMasterUuid = newSpeedMasterUuid ?: existing.speedMasterUuid
                rateSpeedMasterUuid = newRateSpeedMasterUuid ?: existing.rateSpeedMasterUuid
                speedMasterSlot = if (newSpeedMasterUuid != null) {
                    speedMasters.slotFor(newSpeedMasterUuid)
                } else {
                    existing.speedMasterSlot
                }
                rateMasterSlot = if (newRateSpeedMasterUuid != null) {
                    rateSlotFor(newRateSpeedMasterUuid)
                } else {
                    existing.rateMasterSlot
                }
            }
        } else {
            newSpeedMasterUuid?.let {
                existing.speedMasterUuid = it
                existing.speedMasterSlot = speedMasters.slotFor(it)
            }
            newRateSpeedMasterUuid?.let {
                existing.rateSpeedMasterUuid = it
                existing.rateMasterSlot = rateSlotFor(it)
            }
            existing
        }

        if (needsSwap) {
            activeEffects[effectId] = updated
            rebuildSortedSnapshots()
        } else {
            // A non-swap update can still move coverage (elementFilter changes the expansion);
            // see [rebuildSortedSnapshots].
            effectListEpoch.incrementAndGet()
        }
        emitStateUpdate()
        return updated
    }

    /**
     * Pause an effect by ID.
     */
    fun pauseEffect(effectId: Long) {
        activeEffects[effectId]?.pause()
        // isRunning is part of effect coverage; see [rebuildSortedSnapshots].
        effectListEpoch.incrementAndGet()
        emitStateUpdate()
    }

    /**
     * Resume a paused effect by ID.
     */
    fun resumeEffect(effectId: Long) {
        activeEffects[effectId]?.resume()
        // isRunning is part of effect coverage; see [rebuildSortedSnapshots].
        effectListEpoch.incrementAndGet()
        emitStateUpdate()
    }

    /**
     * Remove all effects targeting a specific fixture.
     *
     * @param fixtureKey The fixture key
     * @return Number of effects removed
     */
    fun removeEffectsForFixture(fixtureKey: String): Int {
        val toRemove = activeEffects.values.filter {
            !it.isGroupEffect && it.target.targetKey == fixtureKey
        }
        toRemove.forEach { activeEffects.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredProperties(toRemove)
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
    fun removeEffectsForGroup(groupName: String): Int {
        val toRemove = activeEffects.values.filter {
            it.isGroupEffect && it.target.targetKey == groupName
        }
        toRemove.forEach { activeEffects.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredProperties(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
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
    fun repriorityProgrammerLayerEffects(priorities: Map<Long, Int>): Int {
        if (priorities.isEmpty()) return 0
        val stale = activeEffects.values.filter { effect ->
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
    fun removeProgrammerBandEffects(): Int {
        val toRemove = activeEffects.values.filter { isProgrammerFxPriority(it.priority) }
        toRemove.forEach { activeEffects.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredProperties(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Identifies a single (fixture, property) pair for stomp overlap checks.
     */
    data class PropertyKey(val targetKey: String, val propertyName: String)

    /**
     * Remove ad-hoc effects owned by *other* cues that target properties in the [overlap]
     * set. Effects owned by the stomping cue itself are not stomped — they co-exist with its
     * Layer 4 assertions. Manual (uncued) effects are not stomped either.
     *
     * @param stompingCueId the cue whose apply triggered the stomp.
     * @param overlap the set of (targetKey, propertyName) pairs the stomping cue covers.
     * @return number of effects removed.
     */
    fun stompForCue(stompingCueId: Int, overlap: Set<PropertyKey>): Int {
        if (overlap.isEmpty()) return 0
        val toRemove = activeEffects.values.filter { effect ->
            val owner = effect.cueId ?: return@filter false
            if (owner == stompingCueId) return@filter false
            PropertyKey(effect.target.targetKey, effect.target.propertyName) in overlap
        }
        if (toRemove.isEmpty()) return 0
        for (effect in toRemove) activeEffects.remove(effect.id)
        rebuildSortedSnapshots()
        resetUncoveredProperties(toRemove)
        emitStateUpdate()
        return toRemove.size
    }

    /**
     * Remove all effects that were applied as part of a specific cue.
     *
     * @param cueId The cue ID whose effects should be removed
     * @return Number of effects removed
     */
    fun removeEffectsForCue(cueId: Int): Int {
        val toRemove = activeEffects.values.filter { it.cueId == cueId }
        toRemove.forEach { activeEffects.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredProperties(toRemove)
            emitStateUpdate()
        }
        cueLayer.removeAssignments(cueId)
        return toRemove.size
    }

    /**
     * Remove all active effects.
     */
    fun clearAllEffects() {
        val allEffects = activeEffects.values.toList()
        activeEffects.clear()
        rebuildSortedSnapshots()
        cueLayer.clearAll()
        resetUncoveredProperties(allEffects)
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
     * Process all BEAT-timed effects over one frame of every master's current tick.
     *
     * `internal` so that `FxEnginePipelineTest` can drive synthetic ticks without waiting on the
     * real-time tick loop — through the `processBeatTick` shim in `FxEngineTickShims.kt` (test
     * source), which is where the `runBlocking` wrapper lives. Production never blocks a thread
     * on a pass: the loops in [start] call these suspend forms directly.
     */
    internal suspend fun processBeatTickSuspend(frame: SpeedMasterBank.Frame) {
        // Membership changes re-bind lazily, at pass start: slots only matter during a
        // pass, and Frame clamps an out-of-range slot to master 1 in the window between
        // a deletion and this re-bind.
        val bankVersion = speedMasters.version
        if (bankVersion != boundBankVersion) {
            rebindSpeedMasters()
            boundBankVersion = bankVersion
        }

        // Snapshot read is lock-free (volatile). If empty, nothing to do.
        val beatEffects = sortedBeatEffects
        if (beatEffects.isEmpty()) return
        if (beatEffects.none { it.isRunning }) return

        // deltaMs comes from the pass timestamp, not any one master's tick timestamp — a
        // slow master's tick time only moves when *it* ticks, which would hand stateful
        // effects a garbage delta on passes woken by a faster master.
        val deltaMs = if (lastTickMs > 0) frame.timestampMs - lastTickMs else 0L
        lastTickMs = frame.timestampMs

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        // Reset properties controlled by BEAT effects to the layer below (Layer 2 → Layer 4 →
        // Layer 5 baseline) before applying. This prevents accumulative blend modes from
        // ratcheting across ticks and keeps direct writes + cue state visible under effects.
        resetActiveProperties(fixturesWithTx, beatEffects, beatResetSeen)

        // Programmer suppression: any non-band effect skips its apply on (fixture,
        // property) pairs the programmer holds — the reset pass has already painted the
        // programmer value there, and the effect must not repaint over it.
        val suppression = programmerSuppression()

        // Iterate in priority-ascending order. Under non-OVERRIDE blend modes, higher-priority
        // effects compose on top and dominate. Each effect computes phase from *its own*
        // master's tick; effects on the same master stay locked, effects on different
        // masters drift apart, which is the point.
        for (effect in beatEffects) {
            // This effect's one dynamics read for the pass — everything below works from
            // this snapshot, so a concurrent edit lands wholly on this pass or the next.
            val dyn = effect.dynamics
            if (!dyn.isRunning) continue

            val tick = frame.tick(effect.speedMasterSlot)
            try {
                if (effect.isGroupEffect) {
                    processGroupEffect(tick, effect, dyn, fixturesWithTx, deltaMs, suppression, PhaseSource.Beat)
                } else {
                    processFixtureEffect(tick, effect, dyn, fixturesWithTx, deltaMs, suppression, PhaseSource.Beat)
                }
                noteTickSuccess(effect)
            } catch (t: Throwable) {
                // Throwable, not Exception: every built-in effect is a compiled script, so a
                // recursive helper in one (StackOverflowError) or a script class that fails to
                // link (NoClassDefFoundError) is an Error, and letting one past here would kill
                // the pass loop for the life of the process.
                noteTickFailure(effect, t, "effect", fixturesWithTx)
            }
        }

        transaction.applySuspend()
    }

    /**
     * Re-resolve every active instance's runtime master slots against the bank. A uuid the
     * bank no longer knows resolves to slot 0 — a deleted master's effects fall back to the
     * global tempo rather than stopping.
     */
    private fun rebindSpeedMasters() {
        for (effect in activeEffects.values) {
            effect.speedMasterSlot = speedMasters.slotFor(effect.speedMasterUuid)
            effect.rateMasterSlot = rateSlotFor(effect.rateSpeedMasterUuid)
        }
    }

    /**
     * Process all WALL_CLOCK-timed effects on the fixed-interval timer.
     *
     * Wall-clock effects use elapsed real time for phase calculation instead of beat
     * position, making them independent of BPM. That is the only difference in how *phase* is
     * derived, so both passes share [processFixtureEffect] / [processGroupEffect] and differ
     * by the [PhaseSource] they hand them.
     *
     * `internal` so that `FxEnginePipelineTest` can drive the wall-clock path synchronously,
     * through the `processWallClockTick` shim in `FxEngineTickShims.kt` (test source).
     */
    internal suspend fun processWallClockTickSuspend() {
        // Same lazy rebind as the beat pass — rate-master slots must follow membership
        // changes even when no beat effect is running.
        val bankVersion = speedMasters.version
        if (bankVersion != boundBankVersion) {
            rebindSpeedMasters()
            boundBankVersion = bankVersion
        }

        // Stamp the pass time *before* the early returns. Leaving it stale while nothing was
        // running meant the first pass afterwards saw a deltaMs covering the whole idle
        // period and handed it to every wall-clock effect — including one just created with
        // an accumulator of 0, which would then render its first frame at an arbitrary point
        // in its cycle instead of the start.
        val now = System.currentTimeMillis()
        val deltaMs = if (lastWallClockTickMs > 0) now - lastWallClockTickMs else 0L
        lastWallClockTickMs = now

        val wallClockEffects = sortedWallClockEffects
        if (wallClockEffects.isEmpty()) return
        if (wallClockEffects.none { it.isRunning }) return

        // One coherent rate sample per pass, and only when a rate master is actually assigned
        // to something — the common case is none, and then every effect advances unscaled.
        // Deliberately NOT snapshotFrame() even so: this path never reads ticks, and a full
        // frame would allocate a per-master tick array 50 times a second purely to discard it.
        val rateScales =
            if (wallClockEffects.any { it.rateMasterSlot != FxInstance.NO_RATE_MASTER })
                speedMasters.rateScales()
            else null

        // Advance every wall-clock effect's scaled clock once, before anything reads a
        // phase, so all the phase calls in this pass see one coherent value. Paused effects
        // advance too, as long as *something* is running: a wall-clock effect has always
        // kept its place in real time while paused, and this preserves that — only the
        // rate-change discontinuity is fixed.
        for (effect in wallClockEffects) {
            val slot = effect.rateMasterSlot
            val scale =
                if (slot == FxInstance.NO_RATE_MASTER || rateScales == null) 1.0
                else rateScales.getOrElse(slot) { 1.0 }
            effect.advanceWallClock(deltaMs, scale)
        }

        // The synthetic ClockTick stateful effects receive. The beat-position fields stay 0 —
        // a wall-clock pass has no beat position, by definition — but `tickNumber` is a real
        // monotonically-increasing count of passes.
        //
        // It used to be pinned at 0, and that was not the harmless placeholder it looked like:
        // a StatefulEffect reads the tick, and `CandleFlicker` (STATEFUL + WALL_CLOCK, so this
        // is the *only* tick it ever sees) re-picks its target from
        // `sin(tickNumber * 127.0) * cos(tickNumber * 311.0)`. With tickNumber constant that
        // noise term was always exactly 0, the target was always `baseLevel`, and a candle on
        // the rig held dead-steady. `BuiltInEffectBehaviourTest` missed it because it drives
        // `calculateStateful` with its own advancing tick — a sequence the engine never
        // produced. `FxEnginePipelineTest` now pins the engine end of that contract.
        val syntheticTick = MasterClock.ClockTick(
            tickNumber = wallClockPassNumber++,
            beatNumber = 0,
            tickInBeat = 0,
            phase = 0.0,
            timestampMs = now,
        )

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        // Reset properties controlled by WALL_CLOCK effects to the layer below.
        resetActiveProperties(fixturesWithTx, wallClockEffects, wallClockResetSeen)

        val suppression = programmerSuppression()

        for (effect in wallClockEffects) {
            // Same one-snapshot-per-pass rule as the beat loop.
            val dyn = effect.dynamics
            if (!dyn.isRunning) continue

            try {
                if (effect.isGroupEffect) {
                    processGroupEffect(
                        syntheticTick, effect, dyn, fixturesWithTx, deltaMs, suppression, PhaseSource.WallClock,
                    )
                } else {
                    processFixtureEffect(
                        syntheticTick, effect, dyn, fixturesWithTx, deltaMs, suppression, PhaseSource.WallClock,
                    )
                }
                noteTickSuccess(effect)
            } catch (t: Throwable) {
                // See the beat loop's catch for why this is Throwable.
                noteTickFailure(effect, t, "wall-clock effect", fixturesWithTx)
            }
        }

        transaction.applySuspend()
    }

    /**
     * Calculate the output for an effect, handling stateless and stateful effects.
     *
     * A [CompositeEffect] needs no arm of its own: one [FxInstance] drives one
     * [FxTarget], so its `calculate` default picks the entry matching the effect's
     * primary [Effect.outputType] out of the composite map and the rest are ignored.
     * See `docs/fx-engineering.md` §"Composite Effects".
     */
    private fun calculateEffectOutput(
        effect: FxInstance,
        tick: MasterClock.ClockTick,
        deltaMs: Long,
        phase: Double,
        context: EffectContext,
    ): FxOutput {
        // Stateful effects
        val raw = if (effect.effect is StatefulEffect) {
            (effect.effect as StatefulEffect).calculateStateful(tick, deltaMs, context)
        } else {
            effect.effect.calculate(phase, context)
        }
        return raw.scaled(effect.intensityMultiplier)
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
     * pre-sorted effect list (priority-ascending, id-ascending tie-break); ordering only
     * matters downstream for effect composition, not for the reset pass.
     */
    private fun resetActiveProperties(
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        effects: List<FxInstance>,
        seen: ResetSeen,
    ) {
        seen.beginPass(fixtures.structureVersion)

        for (effect in effects) {
            if (!effect.isRunning) continue

            val keys = resolveEffectFixtureKeys(effect)
            val target = effect.target

            for (key in keys) {
                if (seen.add(key, target.propertyName)) {
                    resetOne(fixturesWithTx, key, target)
                }
            }
        }
    }

    /**
     * The (fixture, property) dedupe scratch for one tick loop's [resetActiveProperties] pass,
     * reused across passes rather than rebuilt per tick.
     *
     * Two-level rather than a compound key so there is no key object per tuple — on a
     * 168-fixture × 2-property rig that is 336 avoided allocations per tick, and reusing the
     * structure removes the outer map and one `HashSet` per fixture on top of that. Clearing the
     * inner sets in place is what keeps them: a pass touches the same fixture keys as the last
     * one. The whole map is dropped when the register generation moves, so keys for fixtures
     * that no longer exist can't accumulate.
     *
     * **One instance per tick loop, never shared.** The beat and wall-clock loops run on
     * separate coroutines and each owns its own; a shared one would be mutated concurrently.
     */
    private class ResetSeen {
        private val byKey = HashMap<String, HashSet<String>>()
        private var structureVersion = Long.MIN_VALUE

        fun beginPass(version: Long) {
            if (version != structureVersion) {
                byKey.clear()
                structureVersion = version
            } else {
                for (properties in byKey.values) properties.clear()
            }
        }

        /** True when this is the first time [property] has been seen on [key] this pass. */
        fun add(key: String, property: String): Boolean =
            byKey.getOrPut(key) { HashSet() }.add(property)
    }

    /** [resetActiveProperties] scratch owned by the beat tick loop. */
    private val beatResetSeen = ResetSeen()

    /** [resetActiveProperties] scratch owned by the wall-clock tick loop. */
    private val wallClockResetSeen = ResetSeen()

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
            throttle.log("reset-$fixtureKey.${target.propertyName}", debug = true) {
                "FX engine: reset to fallback failed for $fixtureKey.${target.propertyName}: ${e.message}"
            }
        }
    }


    /**
     * Where an effect's phase comes from on a pass: its master's beat position, or its own
     * scaled wall-clock accumulator.
     *
     * The two passes are otherwise identical over a given effect — same expansion, same
     * distribution plan, same suppression, same apply — so this is the only thing the shared
     * `process*` functions below are parameterised on. Before this they were two hand-kept
     * copies, and every fix *inside the per-effect apply* had to land twice; the two outer
     * pass functions above still own their own tick sourcing and reset/suppression setup, so
     * a change to pass mechanics still lands twice one level up.
     *
     * Objects rather than lambdas: two instances for the life of the process, nothing allocated
     * per tick. [WallClock] ignores `tick` for *phase* — that is a function of
     * `accumulatedScaledMs` — but the tick itself is not inert on that path: it still reaches
     * stateful effects through [calculateEffectOutput], carrying the pass timestamp and the
     * pass counter (see [processWallClockTickSuspend]).
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
     * is automatically expanded to all elements with distribution strategy support.
     */
    private fun processFixtureEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        dyn: FxDynamics,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long,
        suppression: Map<String, Set<String>>,
        phases: PhaseSource,
    ) {
        val expansion = expansionFor(effect, dyn)
        when (expansion.kind) {
            FxTargetExpansion.Kind.DIRECT_FIXTURE -> {
                val fixtureKey = effect.target.targetKey
                val effectPhase = phases.single(effect, tick, dyn)
                val output = calculateEffectOutput(effect, tick, deltaMs, effectPhase, EffectContext.SINGLE)
                if (!isSuppressed(suppression, fixtureKey, effect.target.propertyName, effect)) {
                    effect.target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode)
                }
            }

            FxTargetExpansion.Kind.FIXTURE_ELEMENTS -> processElementKeys(
                tick, effect, dyn, fixturesWithTx, expansion.flat,
                plansFor(effect, expansion, dyn).flat, deltaMs, suppression, phases,
            )

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
     * Uses the same distribution strategy machinery as group effects; the per-element offsets
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
        suppression: Map<String, Set<String>>,
        phases: PhaseSource,
    ) {
        // Already filtered and in distribution order — see [FxTargetExpansion.flat] /
        // [FxTargetExpansion.perFixture]. Distribution indices run over the *included* elements,
        // so phase offsets spread evenly across only those.
        val filteredCount = elementKeys.size
        if (filteredCount == 0) return

        var lastMemberPhase = 0.0
        for ((distributionIdx, elementKey) in elementKeys.withIndex()) {
            val distOffset = plan.offsets[distributionIdx]
            val memberPhase = phases.member(effect, tick, filteredCount, dyn, distOffset)
            lastMemberPhase = memberPhase

            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, plan.contexts[distributionIdx])
            if (!isSuppressed(suppression, elementKey, effect.target.propertyName, effect)) {
                effect.target.applyValue(fixturesWithTx, elementKey, output, effect.blendMode)
            }
        }
        effect.lastPhase = lastMemberPhase
    }

    /**
     * Process an effect targeting a group - expands to all members with distribution.
     *
     * If members have the target property directly, applies the effect to each member
     * using the distribution strategy (existing behaviour).
     *
     * If members are [MultiElementFixture]s whose elements have the target property,
     * the [ElementMode] on the effect instance determines the expansion strategy:
     * - [ElementMode.PER_FIXTURE]: Each fixture gets the effect independently across
     *   its own elements. All fixtures look the same.
     * - [ElementMode.FLAT]: All elements across all fixtures form one flat list.
     *   Distribution runs across the entire set.
     */
    private fun processGroupEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        dyn: FxDynamics,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long,
        suppression: Map<String, Set<String>>,
        phases: PhaseSource,
    ) {
        val expansion = expansionFor(effect, dyn)
        when (expansion.kind) {
            FxTargetExpansion.Kind.GROUP_MEMBERS -> {
                val allMembers = expansion.members
                val groupSize = allMembers.size
                val plan = plansFor(effect, expansion, dyn).members
                var lastMemberPhase = 0.0
                for ((memberIdx, member) in allMembers.withIndex()) {
                    val distOffset = plan.offsets[memberIdx]
                    val memberPhase = phases.member(effect, tick, groupSize, dyn, distOffset)
                    lastMemberPhase = memberPhase
                    val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, plan.contexts[memberIdx])
                    if (!isSuppressed(suppression, member.key, effect.target.propertyName, effect)) {
                        effect.target.applyValue(fixturesWithTx, member.key, output, effect.blendMode)
                    }
                }
                if (allMembers.isNotEmpty()) effect.lastPhase = lastMemberPhase
            }

            FxTargetExpansion.Kind.GROUP_ELEMENTS -> {
                val plans = plansFor(effect, expansion, dyn)
                when (dyn.elementMode) {
                    // Each fixture gets the effect independently across its own elements, so its
                    // own list size is the group size the phase calculation sees.
                    ElementMode.PER_FIXTURE ->
                        for ((memberIdx, memberKeys) in expansion.perFixture.withIndex()) {
                            processElementKeys(
                                tick, effect, dyn, fixturesWithTx, memberKeys,
                                plans.perFixture[memberIdx], deltaMs, suppression, phases,
                            )
                        }
                    // All elements across all fixtures as one list.
                    ElementMode.FLAT -> processElementKeys(
                        tick, effect, dyn, fixturesWithTx, expansion.flat, plans.flat,
                        deltaMs, suppression, phases,
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
    private fun resetUncoveredProperties(removedEffects: List<FxInstance>) {
        if (removedEffects.isEmpty()) return

        data class AffectedProperty(val fixtureKey: String, val target: FxTarget)

        val affectedProperties = mutableSetOf<AffectedProperty>()
        for (removed in removedEffects) {
            for (key in resolveEffectFixtureKeys(removed)) {
                affectedProperties.add(AffectedProperty(key, removed.target))
            }
        }

        val remainingEffects = activeEffects.values.toList()
        val uncovered = affectedProperties.filter { affected ->
            !isPropertyCoveredByAny(affected.fixtureKey, affected.target.propertyName, remainingEffects)
        }

        if (uncovered.isEmpty()) return

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        for (affected in uncovered) {
            try {
                val fixture = fixturesWithTx.untypedGroupableFixture(affected.fixtureKey)
                if (cascade.allChannelsParked(affected.target, fixture)) continue
                val fallback = layerResolver.fallbackFor(affected.target, fixture, affected.fixtureKey)
                affected.target.resetToFallback(fixture, fallback)
            } catch (e: Exception) {
                throttle.log("reset-uncovered-${affected.fixtureKey}.${affected.target.propertyName}", e) {
                    "FX engine: failed to reset ${affected.target.propertyName} " +
                        "on '${affected.fixtureKey}'"
                }
            }
        }

        transaction.apply()
    }

    /**
     * The fixture/element keys [effect] currently writes to. Public form of
     * [resolveEffectFixtureKeys] — used by Include to report which heads a spawned group
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
    private fun resolveEffectFixtureKeys(effect: FxInstance): List<String> =
        expansionFor(effect, effect.dynamics).coverageKeys

    /**
     * [effect]'s cached expansion, re-derived when the fixture register has been rebuilt under
     * it or when `updateEffect` has moved the two fields the expansion depends on.
     *
     * `elementFilter` is part of the validity check rather than an invalidation hook on
     * `updateEffect`: it is a plain enum, so the check is one identity compare on a path that
     * already reads the instance, and there is no mutation site left to forget. `elementMode`
     * is not checked — both shapes it selects between are built up front. `distributionStrategy`
     * is not either: it moves the offsets, not which keys resolve. `target` is a `val`.
     *
     * The filter comes from [dyn], the caller's per-pass snapshot, so the expansion checked
     * here and the element mode branching around it can't come from two different updates.
     */
    private fun expansionFor(effect: FxInstance, dyn: FxDynamics): FxTargetExpansion {
        // Read the version BEFORE the lookups it stamps — see [Fixtures.structureVersion].
        val version = fixtures.structureVersion
        val filter = dyn.elementFilter
        val cached = effect.expansion
        if (cached != null && cached.structureVersion == version && cached.elementFilter == filter) {
            return cached
        }
        // Clearing the plans here is not needed for correctness — [plansFor] compares the
        // expansion by identity, so a rebuild invalidates them anyway. It is needed so a stale
        // plan (and, through it, the whole superseded expansion's key lists) isn't pinned on an
        // effect whose new expansion is DIRECT_FIXTURE or NONE, which never reach [plansFor].
        effect.distributionPlans = null
        return buildExpansion(effect, version, filter).also { effect.expansion = it }
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
     * The distribution plans for [expansion] under this pass's strategy — see
     * [FxInstance.distributionPlans]. Same cache-then-validate shape as [expansionFor], and
     * checked against the *identity* of the expansion the caller already resolved, so the two
     * caches cannot disagree about which generation of the register they describe.
     */
    private fun plansFor(
        effect: FxInstance,
        expansion: FxTargetExpansion,
        dyn: FxDynamics,
    ): FxDistributionPlans {
        val strategy = dyn.distributionStrategy
        val cached = effect.distributionPlans
        if (cached != null && cached.expansion === expansion && cached.strategy == strategy) {
            return cached
        }
        return FxDistributionPlans.build(expansion, strategy).also { effect.distributionPlans = it }
    }

    /**
     * Walk the register once and resolve everything the tick loops need for [effect]: which
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

        if (effect.isGroupEffect) {
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
            if (effect.target.fixtureHasProperty(firstMemberFixture)) {
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
            if (firstElements.isEmpty() || !effect.target.fixtureHasProperty(firstElements.first())) {
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
                // FLAT filters across the whole set, on the global flat index; PER_FIXTURE
                // filters within each fixture, on that fixture's own element count. Both are
                // built here so `elementMode` stays out of the cache identity.
                flat = applyFilter(coverage),
                perFixture = perMemberKeys.map { applyFilter(it) },
                members = emptyList(),
            )
        }

        // Fixture effect
        val fixtureKey = effect.target.targetKey
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (e: Exception) {
            throttle.log("missing-fixture-$fixtureKey", e) {
                "FX engine: fixture '$fixtureKey' not found for effect ${effect.id}"
            }
            return none()
        }

        if (effect.target.fixtureHasProperty(fixture)) {
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
            if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
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
     * Check if any effect in the list covers a (fixtureKey, propertyName) pair.
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
    private fun isPropertyCoveredByAny(
        fixtureKey: String,
        propertyName: String,
        remainingEffects: List<FxInstance>
    ): Boolean {
        for (effect in remainingEffects) {
            if (effect.target.propertyName != propertyName) continue

            if (!effect.isGroupEffect) {
                if (effect.target.targetKey == fixtureKey) return true

                // Check multi-element: parent effect covers element keys
                val fixture = try {
                    fixtures.untypedFixture(effect.target.targetKey)
                } catch (_: Exception) { continue }
                if (fixture is MultiElementFixture<*> && !effect.target.fixtureHasProperty(fixture)) {
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

    private fun emitStateUpdate() {
        // Deliberately does NOT touch [effectListEpoch]: this is a broadcaster, and the
        // coverage-cache invalidation must happen at the mutation itself (see
        // [rebuildSortedSnapshots]) — the flows between a mutation and this call include the
        // very Layer 4 publishes that consult the cache.
        // One bank snapshot for the whole emit — masterStates() maps every slot into a
        // fresh list, and calling it per effect made this O(effects x masters) allocation.
        val masterStates = speedMasters.masterStates()
        val states = activeEffects.mapValues { (_, instance) ->
            instance.toEffectDto(masterStates, isMultiElementExpanded(instance))
        }

        _fxStateFlow.value = FxStateUpdate(
            activeEffectIds = activeEffects.keys.toList(),
            effectStates = states,
        )
        // Effect lifecycle changes move provenance winners — piggyback on the same sites.
        provenance.emitUpdate()
    }
}
