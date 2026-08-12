package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.midi.PropertyChannelResolver
import uk.me.cormack.lighting7.show.Fixtures
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.awt.Color

/**
 * Central effect processing engine.
 *
 * FxEngine manages active effects and processes them on each Master Clock tick,
 * applying calculated values to fixture properties through the DMX system.
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
    val masterClock: MasterClock,
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
    val layerResolver: LayerResolver = LayerResolver(Layer3Resolver(), programmerStore),
    /**
     * Layer 1 park query. If non-null, the engine skips effect reset / apply for channels
     * that are parked. The parked value is still re-applied at transmit time in
     * [uk.me.cormack.lighting7.dmx.ArtNetController] as defence-in-depth.
     */
    private val parkManager: ParkManager? = null,
) {
    private val nextEffectId = AtomicLong(0)
    private val activeEffects = ConcurrentHashMap<Long, FxInstance>()

    // Read lock-free by the hot tick loops; rebuilt under [effectSnapshotLock] on mutation.
    private val effectSnapshotLock = Any()
    private val sortedEffectsComparator = compareBy<FxInstance>({ it.priority }, { it.id })
    @Volatile private var sortedBeatEffects: List<FxInstance> = emptyList()
    @Volatile private var sortedWallClockEffects: List<FxInstance> = emptyList()

    @Volatile private var lastTickMs: Long = 0L
    @Volatile private var lastWallClockTickMs: Long = 0L

    // Per-tick programmer suppression snapshot, cached on the store's epoch so the 50 Hz
    // loops rebuild it only when the programmer actually changed. Both tick loops may race
    // the rebuild; they compute identical values, so last-write-wins is benign.
    @Volatile private var suppressionCache: Map<String, Set<String>> = emptyMap()
    @Volatile private var suppressionCacheEpoch: Long = -1L

    /**
     * fixtureKey → property names with an active programmer entry, or empty when blind is
     * engaged (blind removes the programmer from the merge, so effects paint normally).
     * An entry here suppresses every effect on that (fixture, property) except effects in
     * the programmer priority band — the "programmer wins over effects" rule.
     */
    private fun programmerSuppression(): Map<String, Set<String>> {
        if (programmerStore.blind) return emptyMap()
        val epoch = programmerStore.epoch
        if (epoch != suppressionCacheEpoch) {
            suppressionCache = programmerStore.activePropertiesByFixture()
            suppressionCacheEpoch = epoch
        }
        return suppressionCache
    }

    private fun isSuppressed(
        suppression: Map<String, Set<String>>,
        fixtureKey: String,
        propertyName: String,
        effect: FxInstance,
    ): Boolean {
        if (suppression.isEmpty()) return false
        if (isProgrammerFxPriority(effect.priority)) return false
        return suppression[fixtureKey]?.contains(propertyName) == true
    }

    private fun rebuildSortedSnapshots() {
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

        /** Coalescing window for provenance recomputes — see [emitProvenanceUpdate]. */
        const val PROVENANCE_COALESCE_MS = 50L

        /**
         * Reserved priority band for programmer-owned effects (Session 2's busking FX).
         * Strictly above every cue-derived priority
         * (`cueDerivedPriority(stackId, sort) = stackId*1M + sort*1K + 1` in
         * `routes/projectCuesHelpers.kt`) and every manual effect (priority 0). Effects in
         * this band are exempt from programmer suppression — they modulate *on top of*
         * programmer values rather than being overridden by them.
         */
        const val PROGRAMMER_FX_PRIORITY_BASE = Int.MAX_VALUE - 1_000_000

        /** True when [priority] sits in the programmer-owned effect band. */
        fun isProgrammerFxPriority(priority: Int): Boolean = priority >= PROGRAMMER_FX_PRIORITY_BASE
    }

    private val _fxStateFlow = MutableSharedFlow<FxStateUpdate>(replay = 1, extraBufferCapacity = 1)

    /** Flow of FX state updates for WebSocket broadcasting */
    val fxStateFlow: SharedFlow<FxStateUpdate> = _fxStateFlow.asSharedFlow()

    // --- Provenance ---

    /** Which layer produced the current winning value for one (target, property). */
    enum class ProvenanceSource { PARKED, PROGRAMMER, EFFECT, CUE }

    /**
     * The winning contributor for one (target, property) — the "who owns this value"
     * answer. BASELINE keys are omitted from snapshots entirely (absence = baseline).
     */
    data class ProvenanceEntry(
        val targetKey: String,
        val propertyName: String,
        val source: ProvenanceSource,
        val cueId: Int? = null,
        val cueStackId: Int? = null,
        val effectId: Long? = null,
    )

    // Conflated: recomputed on layer events only (programmer mutation, cue republish,
    // effect lifecycle, park change) — never per frame. Full-state snapshots rather than
    // diffs: the entry set is small (the union of active keys) and event-rate, so diffing
    // buys nothing over the conflation.
    private val _provenanceFlow = MutableSharedFlow<List<ProvenanceEntry>>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Flow of full provenance snapshots for WebSocket broadcasting. */
    val provenanceFlow: SharedFlow<List<ProvenanceEntry>> = _provenanceFlow.asSharedFlow()

    // Coalesces provenance recomputes: emitProvenanceUpdate is called from every
    // layer-event site — including per-MIDI-CC programmer writes and per-crossfade-tick
    // Layer 3 republishes that run while holding [cueAssignmentsLock] — so the marker must
    // be near-free and the O(effects + keys) recompute must happen off the caller's
    // thread, outside any lock. `dirty` is flipped false *before* computing so a mutation
    // landing mid-compute schedules a fresh cycle.
    private val provenanceDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var provenanceScope: CoroutineScope? = null

    /**
     * Mark provenance stale and schedule a coalesced recompute + broadcast. Called from
     * every layer-event site (programmer writes/clears/blind, Layer 3 republish, effect
     * lifecycle changes via [emitStateUpdate]) and by the park handlers. Cheap enough to
     * call while holding locks. Before [start] wires a scope (unit tests), the recompute
     * runs synchronously so assertions stay deterministic.
     */
    fun emitProvenanceUpdate() {
        val scope = provenanceScope
        if (scope == null) {
            _provenanceFlow.tryEmit(computeProvenance())
            return
        }
        if (provenanceDirty.compareAndSet(false, true)) {
            scope.launch(Dispatchers.Default) {
                delay(PROVENANCE_COALESCE_MS)
                provenanceDirty.set(false)
                _provenanceFlow.tryEmit(computeProvenance())
            }
        }
    }

    /**
     * Compute the winning contributor for every key any layer currently covers. Winner
     * order mirrors the output stack: park → programmer (unless blind) → highest-priority
     * running effect → cue layer. Keys nothing covers are omitted (baseline).
     */
    fun computeProvenance(): List<ProvenanceEntry> {
        val programmerKeys = if (programmerStore.blind) {
            emptySet()
        } else {
            val keys = HashSet(programmerStore.activeKeys())
            // Sideband slots drive the wire too (raw pan/tilt drags, unpark hand-downs):
            // attribute them to the property covering the channel. Channels with no
            // backing property stay unreported — there is no (target, property) to name.
            for (entry in programmerStore.channelEntries()) {
                resolveChannelCoveringKey(entry.universe, entry.channel)?.let { keys.add(it) }
            }
            keys
        }

        // Highest-priority running effect per (fixtureKey, propertyName).
        val effectByKey = HashMap<Pair<String, String>, FxInstance>()
        for (effect in activeEffects.values) {
            if (!effect.isRunning) continue
            val propertyName = effect.target.propertyName
            for (fixtureKey in resolveEffectFixtureKeys(effect)) {
                val k = fixtureKey to propertyName
                val current = effectByKey[k]
                if (current == null || sortedEffectsComparator.compare(effect, current) > 0) {
                    effectByKey[k] = effect
                }
            }
        }

        val layer3State = layerResolver.currentLayer3State
        val layer3Winners = layerResolver.currentLayer3Winners

        val keys = HashSet<Layer3Resolver.Key>(programmerKeys)
        keys.addAll(layer3State.keys)
        for ((pair, _) in effectByKey) {
            keys.add(Layer3Resolver.Key.fixture(pair.first, pair.second))
        }

        val entries = ArrayList<ProvenanceEntry>(keys.size)
        for (key in keys) {
            val fixture = try {
                fixtures.untypedGroupableFixture(key.targetKey)
            } catch (_: Exception) {
                continue
            }
            val target = inferTargetForProperty(fixture, key)

            val parked = target != null && allChannelsParked(target, fixture)
            val programmerActive = key in programmerKeys
            val effect = effectByKey[key.targetKey to key.propertyName]
            // A programmer entry suppresses non-band effects, so it outranks them here too;
            // a band effect modulates on top of the programmer and wins the provenance.
            val bandEffect = effect != null && isProgrammerFxPriority(effect.priority)

            val entry = when {
                parked -> ProvenanceEntry(key.targetKey, key.propertyName, ProvenanceSource.PARKED)
                programmerActive && (effect == null || !bandEffect) ->
                    ProvenanceEntry(key.targetKey, key.propertyName, ProvenanceSource.PROGRAMMER)
                effect != null -> ProvenanceEntry(
                    key.targetKey, key.propertyName, ProvenanceSource.EFFECT,
                    cueId = effect.cueId, cueStackId = effect.cueStackId, effectId = effect.id,
                )
                key in layer3State -> ProvenanceEntry(
                    key.targetKey, key.propertyName, ProvenanceSource.CUE,
                    cueId = layer3Winners[key],
                )
                else -> continue
            }
            entries.add(entry)
        }
        entries.sortWith(compareBy({ it.targetKey }, { it.propertyName }))
        return entries
    }

    // --- Palette ---

    private val _palette = mutableListOf(
        ExtendedColour.fromColor(Color.RED),
        ExtendedColour.fromColor(Color.GREEN),
        ExtendedColour.fromColor(Color.BLUE),
    )

    /** Version counter incremented on every palette change, for caching in palette-aware effects. */
    @Volatile
    var paletteVersion: Long = 0L
        private set

    private val _paletteFlow = MutableSharedFlow<List<ExtendedColour>>(replay = 1, extraBufferCapacity = 1)

    /** Flow of palette updates for WebSocket broadcasting */
    val paletteFlow: SharedFlow<List<ExtendedColour>> = _paletteFlow.asSharedFlow()

    /** Get a thread-safe copy of the current palette. */
    fun getPalette(): List<ExtendedColour> = synchronized(_palette) { _palette.toList() }

    /** Replace the entire palette. */
    fun setPalette(colours: List<ExtendedColour>) {
        synchronized(_palette) {
            _palette.clear()
            _palette.addAll(colours)
            paletteVersion++
        }
        emitPaletteUpdate()
    }

    /** Update a single palette slot by index. */
    fun setPaletteColour(index: Int, colour: ExtendedColour) {
        synchronized(_palette) {
            if (index in _palette.indices) {
                _palette[index] = colour
                paletteVersion++
            }
        }
        emitPaletteUpdate()
    }

    /** Append a colour to the palette. */
    fun addPaletteColour(colour: ExtendedColour) {
        synchronized(_palette) {
            _palette.add(colour)
            paletteVersion++
        }
        emitPaletteUpdate()
    }

    /** Remove a colour from the palette by index. */
    fun removePaletteColour(index: Int) {
        synchronized(_palette) {
            if (index in _palette.indices) {
                _palette.removeAt(index)
                paletteVersion++
            }
        }
        emitPaletteUpdate()
    }

    private fun emitPaletteUpdate() {
        _paletteFlow.tryEmit(getPalette())
    }

    private val _stackPaletteFlow = MutableSharedFlow<Map<Int, List<ExtendedColour>>>(replay = 1, extraBufferCapacity = 1)

    /** Flow of stack palette updates for WebSocket broadcasting */
    val stackPaletteFlow: SharedFlow<Map<Int, List<ExtendedColour>>> = _stackPaletteFlow.asSharedFlow()

    private fun emitStackPaletteUpdate() {
        _stackPaletteFlow.tryEmit(getAllStackPalettes())
    }

    // --- Per-Cue Palettes ---

    private data class CuePaletteEntry(
        val colours: List<ExtendedColour>,
        val version: Long
    )

    private val cuePalettes = ConcurrentHashMap<Int, CuePaletteEntry>()
    private val cuePaletteVersionCounter = AtomicLong(0)

    fun setCuePalette(cueId: Int, colours: List<ExtendedColour>) {
        cuePalettes[cueId] = CuePaletteEntry(colours, cuePaletteVersionCounter.incrementAndGet())
    }

    fun getCuePalette(cueId: Int): List<ExtendedColour>? = cuePalettes[cueId]?.colours

    fun getCuePaletteVersion(cueId: Int): Long = cuePalettes[cueId]?.version ?: 0L

    fun removeCuePalette(cueId: Int) {
        cuePalettes.remove(cueId)
    }

    // --- Per-Cue Layer 3 Assignments ---
    //
    // Tracks the property assignments contributed by each currently-active cue. All writes go
    // through [cueAssignmentsLock] so the "mutate map + republish flat snapshot" step is atomic
    // — concurrent apply/stop calls must not publish a stale view. Tick-loop reads go through
    // [LayerResolver.fallbackFor]'s `@Volatile` snapshot and stay lock-free.
    //
    // The map is plain [HashMap] because every access is already serialised by the lock; a
    // [ConcurrentHashMap] would add internal striping we don't need.

    private val cueAssignments = HashMap<Int, List<Layer3Resolver.Assignment>>()

    // Per-cue crossfade weight in [0, 1]. Absent entries default to 1.0 (fully in). Scales each
    // stored Assignment's own `fadeWeight` at flat-list build time — the composition resolver
    // sees the product. Kept separate from `cueAssignments` so the stored assignment list stays
    // constant across a crossfade; only the scalar per-cue weight ticks. See
    // [updateCueFadeWeights] / Phase 1b in `docs/plans/completed/cue-authoring-unification-plan.md`.
    private val cueFadeWeights = HashMap<Int, Double>()

    private val cueAssignmentsLock = Any()

    /**
     * Replace the Layer 3 assignments contributed by [cueId]. An empty list removes the cue's
     * contribution (equivalent to [removeCueAssignments]).
     *
     * Does not touch the cue's fade weight — callers that want to publish at a weight other
     * than 1.0 should follow with [updateCueFadeWeights]. In the common non-crossfade apply
     * path the absent-entry default (1.0) is correct.
     */
    /**
     * Replace [cueId]'s Layer 3 assignments. Empty [assignments] removes the cue entirely
     * (equivalent to [removeCueAssignments]). The optional [weight] sets the cue's crossfade
     * weight atomically in the same publish — used by the crossfade-start path to pin the
     * incoming cue at 0 without briefly flashing its full value onto stage. A weight of 1.0
     * (the default) clears any prior entry in the fade-weight map so reapplying a cue resets
     * it to steady state. Clamped to `[0, 1]`.
     */
    fun setCueAssignments(
        cueId: Int,
        assignments: List<Layer3Resolver.Assignment>,
        weight: Double = 1.0,
    ) {
        synchronized(cueAssignmentsLock) {
            if (assignments.isEmpty()) {
                val removed = cueAssignments.remove(cueId) != null
                cueFadeWeights.remove(cueId)
                if (removed) republishLayer3Assignments()
                return
            }
            cueAssignments[cueId] = assignments
            val clamped = weight.coerceIn(0.0, 1.0)
            if (clamped >= 1.0) {
                cueFadeWeights.remove(cueId)
            } else {
                cueFadeWeights[cueId] = clamped
            }
            republishLayer3Assignments()
        }
    }

    /**
     * Update the crossfade weight for one or more cues atomically. Only cues present in
     * [cueAssignments] have an effect — unknown cue ids are ignored (silent no-op) because a
     * crossfade tick may fire during the tiny window between an outgoing cue's end-of-fade
     * [removeCueAssignments] and the next tick being cancelled.
     *
     * A single republish runs per call regardless of how many cues are updated, so crossfade
     * ticks that update both outgoing and incoming cues pay one publish pass per frame.
     *
     * Weights are clamped to `[0, 1]`. Setting a weight of exactly 1.0 (the default) clears
     * the entry — no need to accumulate stale entries once the crossfade is over.
     */
    fun updateCueFadeWeights(updates: Map<Int, Double>) {
        if (updates.isEmpty()) return
        synchronized(cueAssignmentsLock) {
            var changed = false
            for ((cueId, rawWeight) in updates) {
                if (cueId !in cueAssignments) continue
                val weight = rawWeight.coerceIn(0.0, 1.0)
                val previous = cueFadeWeights[cueId] ?: 1.0
                if (previous == weight) continue
                if (weight >= 1.0) {
                    cueFadeWeights.remove(cueId)
                } else {
                    cueFadeWeights[cueId] = weight
                }
                changed = true
            }
            if (changed) republishLayer3Assignments()
        }
    }

    /**
     * Append [additions] to [cueId]'s Layer 3 assignments without touching existing rows. Used
     * by the runtime timed-preset fire path to contribute Layer 3 rows at fire time rather than
     * at cue-apply time (immediate presets fan their assignments in during [applyCue]; timed
     * presets stay effects-only until fired). Creates the cue's entry if absent.
     */
    fun appendCueAssignments(cueId: Int, additions: List<Layer3Resolver.Assignment>) {
        if (additions.isEmpty()) return
        mutateCueAssignments(cueId, toRemove = emptyList(), additions = additions)
    }

    /**
     * Remove rows matching [toRemove] from [cueId]'s Layer 3 assignments by structural equality.
     * Each element in [toRemove] removes exactly one matching occurrence — so
     * appendCueAssignments(X) followed by removeCueAssignmentSubset(X) round-trips cleanly even
     * when the cue independently asserts a value-equal row.
     */
    fun removeCueAssignmentSubset(cueId: Int, toRemove: List<Layer3Resolver.Assignment>) {
        if (toRemove.isEmpty()) return
        mutateCueAssignments(cueId, toRemove = toRemove, additions = emptyList())
    }

    /**
     * Atomically remove [toRemove] and append [additions] in a single locked mutation with one
     * republish. Used by the recurring timed-preset fire path — retracting the prior fire's
     * rows and appending the new ones separately costs two full Layer 3 publishes per tick;
     * this collapses them into one.
     */
    fun replaceCueAssignmentSubset(
        cueId: Int,
        toRemove: List<Layer3Resolver.Assignment>,
        additions: List<Layer3Resolver.Assignment>,
    ) {
        if (toRemove.isEmpty() && additions.isEmpty()) return
        mutateCueAssignments(cueId, toRemove = toRemove, additions = additions)
    }

    /**
     * Shared implementation of append / remove-subset / replace-subset. Removes `toRemove` rows
     * by structural equality (one occurrence per element), then adds `additions`. Drops the
     * cue's entry (and fade-weight) if the list ends up empty. Republishes once on any change.
     */
    private fun mutateCueAssignments(
        cueId: Int,
        toRemove: List<Layer3Resolver.Assignment>,
        additions: List<Layer3Resolver.Assignment>,
    ) {
        synchronized(cueAssignmentsLock) {
            val existing = cueAssignments[cueId]
            if (existing == null) {
                if (additions.isEmpty()) return
                cueAssignments[cueId] = ArrayList(additions)
                republishLayer3Assignments()
                return
            }
            val mutable = ArrayList<Layer3Resolver.Assignment>(existing.size + additions.size)
            mutable.addAll(existing)
            var changed = additions.isNotEmpty()
            for (row in toRemove) {
                if (mutable.remove(row)) changed = true
            }
            if (!changed) return
            mutable.addAll(additions)
            if (mutable.isEmpty()) {
                cueAssignments.remove(cueId)
                cueFadeWeights.remove(cueId)
            } else {
                cueAssignments[cueId] = mutable
            }
            republishLayer3Assignments()
        }
    }

    /** Drop all Layer 3 contributions from [cueId]. */
    fun removeCueAssignments(cueId: Int) {
        synchronized(cueAssignmentsLock) {
            val removed = cueAssignments.remove(cueId) != null
            cueFadeWeights.remove(cueId)
            if (removed) {
                republishLayer3Assignments()
            }
        }
    }

    /** Drop every cue's Layer 3 contribution — used by [stop] / [clearAllEffects] callers. */
    fun clearAllCueAssignments() {
        synchronized(cueAssignmentsLock) {
            if (cueAssignments.isEmpty() && cueFadeWeights.isEmpty()) return
            cueAssignments.clear()
            cueFadeWeights.clear()
            republishLayer3Assignments()
        }
    }

    /**
     * Snapshot the set of cue ids currently contributing Layer 3 assignments. Used by
     * `snapshot-from-live` to read each active cue's pre-expansion DB rows and preserve the
     * group-scoped shape in the captured state.
     */
    fun activeCueAssignmentIds(): Set<Int> = synchronized(cueAssignmentsLock) {
        cueAssignments.keys.toSet()
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
     * Touches both layers a cue can own — Layer 2 [FxInstance.priority] and the Layer 3
     * assignment rows — and leaves [cueFadeWeights] alone so a repriority mid-crossfade doesn't
     * disturb the fade. Entries whose priority already matches are skipped, which makes the
     * common single-live-cue reorder a no-op. Returns the number of rows changed.
     */
    fun repriorityCues(priorities: Map<Int, Int>): Int {
        if (priorities.isEmpty()) return 0
        var changed = 0

        // Layer 2 — mutate in place, then re-sort the snapshots the tick loop reads.
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

        // Layer 3 — Assignment.priority is a val, so the rows are rebuilt by copy.
        synchronized(cueAssignmentsLock) {
            var layer3Changed = false
            for ((cueId, target) in priorities) {
                val rows = cueAssignments[cueId] ?: continue
                if (rows.all { it.priority == target }) continue
                cueAssignments[cueId] = rows.map {
                    if (it.priority == target) it else it.copy(priority = target)
                }
                changed += rows.count { it.priority != target }
                layer3Changed = true
            }
            if (layer3Changed) republishLayer3Assignments()
        }

        return changed
    }

    // --- Programmer property writes ---
    //
    // Sticky manual values at property granularity. Callers (web busking, MIDI faders,
    // flash, locate, preset toggles) hand a typed `PropertyValue` plus their
    // `ProgrammerOwner`; the writer stores one property-level slot in `ProgrammerStore`
    // under that owner and publishes via `LayerResolver.fallbackFor`. Clears remove only
    // the caller's own slot; the property falls back to the most recent surviving owner
    // before cascading to the layers below.
    //
    // `fadeMs` is accepted on every write/clear and threaded to the publish; half (a)
    // ignores it (snap), half (b) drives the DmxController ramp for keys no running effect
    // covers.

    /**
     * Lay a [value] onto the programmer for [propertyName] of [fixture] as [owner] and
     * publish immediately. Returns the resolved channel writes so callers can record
     * whether the write landed (empty = the property didn't resolve; nothing was stored).
     *
     * [absorbSideband] drops raw-channel sideband slots under the property's channels so a
     * stale unpark or raw-channel value cannot resurface when this entry clears. Sticky
     * operator writes (web, faders) absorb; momentary owners (flash, locate, presets) pass
     * false so their release still reveals whatever the sideband held.
     *
     * Accepts any [GroupableFixture] — a [Fixture] or a [FixtureElement]. For elements the
     * publish key is the element's own key so subsequent reads see the write.
     */
    fun writeProgrammerProperty(
        owner: ProgrammerOwner,
        fixture: GroupableFixture,
        propertyName: String,
        value: Layer3Resolver.PropertyValue,
        touched: Boolean = true,
        sourceGroup: String? = null,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val writes = PropertyChannelWriter.resolve(fixture, propertyName, value)
        if (writes.isEmpty()) return writes
        programmerStore.put(owner, fixture.targetKey, propertyName, value, touched, sourceGroup)
        if (absorbSideband) absorbSidebandUnder(writes)
        synchronized(cueAssignmentsLock) {
            publishCascadeForKeys(setOf(Layer3Resolver.Key.fixture(fixture.targetKey, propertyName)), fadeMs)
        }
        emitProvenanceUpdate()
        return writes
    }

    /** Group overload — fan out to every member, tagging slots with the group name (§7.1). */
    fun writeProgrammerGroupProperty(
        owner: ProgrammerOwner,
        group: FixtureGroup<*>,
        propertyName: String,
        value: Layer3Resolver.PropertyValue,
        touched: Boolean = true,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> = writeProgrammerProperties(
        owner,
        group.fixtures.filterIsInstance<Fixture>().map {
            ProgrammerPropertyWrite(it, propertyName, value, sourceGroup = group.name)
        },
        touched = touched,
        absorbSideband = absorbSideband,
        fadeMs = fadeMs,
    ).flatten()

    /**
     * Clear [owner]'s programmer entry for [propertyName] on [fixture]. The property
     * cascades back to the most recent surviving owner, the cue layer (if a cue asserts
     * it), or baseline. Accepts any [GroupableFixture].
     */
    fun clearProgrammerProperty(
        owner: ProgrammerOwner,
        fixture: GroupableFixture,
        propertyName: String,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val channels = PropertyChannelWriter.channelsFor(fixture, propertyName)
        programmerStore.clear(owner, fixture.targetKey, propertyName)
        if (channels.isEmpty()) return channels
        synchronized(cueAssignmentsLock) {
            publishCascadeForKeys(setOf(Layer3Resolver.Key.fixture(fixture.targetKey, propertyName)), fadeMs)
        }
        emitProvenanceUpdate()
        return channels
    }

    /** Group overload for [clearProgrammerProperty]. */
    fun clearProgrammerGroupProperty(
        owner: ProgrammerOwner,
        group: FixtureGroup<*>,
        propertyName: String,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> = clearProgrammerProperties(
        owner,
        group.fixtures.filterIsInstance<Fixture>().map { it to propertyName },
        fadeMs = fadeMs,
    )

    /** One entry of a [writeProgrammerProperties] batch. */
    data class ProgrammerPropertyWrite(
        val fixture: GroupableFixture,
        val propertyName: String,
        val value: Layer3Resolver.PropertyValue,
        /** Group name when this entry came from a group control, else null (§7.1). */
        val sourceGroup: String? = null,
    )

    /**
     * Batch counterpart of [writeProgrammerProperty]: store every entry, then publish all
     * affected keys under one [cueAssignmentsLock] acquisition and one controller
     * transaction. A locate on a large group is hundreds of property writes — issuing them
     * one-by-one would take the lock, rescan the active effects and commit a DMX transaction
     * per property.
     *
     * Returns one channel-write list per input entry (empty where the property didn't
     * resolve — nothing stored for that entry), in input order, so callers can record which
     * entries actually landed.
     */
    fun writeProgrammerProperties(
        owner: ProgrammerOwner,
        writes: List<ProgrammerPropertyWrite>,
        touched: Boolean = true,
        absorbSideband: Boolean = true,
        fadeMs: Long = 0,
    ): List<List<PropertyChannelResolver.ChannelWrite>> {
        val resolved = writes.map { PropertyChannelWriter.resolve(it.fixture, it.propertyName, it.value) }
        val keys = HashSet<Layer3Resolver.Key>()
        for ((index, channelWrites) in resolved.withIndex()) {
            if (channelWrites.isEmpty()) continue
            val write = writes[index]
            programmerStore.put(
                owner, write.fixture.targetKey, write.propertyName, write.value, touched, write.sourceGroup,
            )
            if (absorbSideband) absorbSidebandUnder(channelWrites)
            keys += Layer3Resolver.Key.fixture(write.fixture.targetKey, write.propertyName)
        }
        if (keys.isNotEmpty()) {
            synchronized(cueAssignmentsLock) {
                publishCascadeForKeys(keys, fadeMs)
            }
            emitProvenanceUpdate()
        }
        return resolved
    }

    /**
     * Batch counterpart of [clearProgrammerProperty]: clear [owner]'s slot for every
     * (fixture, property) pair, then cascade all affected keys back to the surviving owner /
     * cue layer / baseline under one lock acquisition and one controller transaction.
     */
    fun clearProgrammerProperties(
        owner: ProgrammerOwner,
        clears: List<Pair<GroupableFixture, String>>,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        val keys = HashSet<Layer3Resolver.Key>()
        for ((fixture, propertyName) in clears) {
            programmerStore.clear(owner, fixture.targetKey, propertyName)
            val channels = PropertyChannelWriter.channelsFor(fixture, propertyName)
            if (channels.isEmpty()) continue
            keys += Layer3Resolver.Key.fixture(fixture.targetKey, propertyName)
            all += channels
        }
        if (keys.isNotEmpty()) {
            synchronized(cueAssignmentsLock) {
                publishCascadeForKeys(keys, fadeMs)
            }
            emitProvenanceUpdate()
        }
        return all
    }

    /**
     * Release **every** owner's slot on each (fixture, property) pair — the operator
     * "clear this entry" gesture — with one store sweep, one locked cascade publish, and
     * one provenance update. Clearing owner-by-owner would transmit each surviving owner's
     * value as an intermediate step (and, with [fadeMs] > 0, restart the ramp per owner);
     * this releases each property in a single step to whatever sits below the programmer.
     */
    fun clearProgrammerEntries(
        clears: List<Pair<GroupableFixture, String>>,
        fadeMs: Long = 0,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        val keys = HashSet<Layer3Resolver.Key>()
        var clearedAny = false
        for ((fixture, propertyName) in clears) {
            for (slot in programmerStore.slotsFor(fixture.targetKey, propertyName)) {
                programmerStore.clear(slot.owner, fixture.targetKey, propertyName)
                clearedAny = true
            }
            val channels = PropertyChannelWriter.channelsFor(fixture, propertyName)
            if (channels.isEmpty()) continue
            keys += Layer3Resolver.Key.fixture(fixture.targetKey, propertyName)
            all += channels
        }
        if (keys.isNotEmpty()) {
            synchronized(cueAssignmentsLock) {
                publishCascadeForKeys(keys, fadeMs)
            }
        }
        if (clearedAny) emitProvenanceUpdate()
        return all
    }

    /**
     * Raw-channel write into the programmer's sideband — the compatibility path for
     * `updateChannel` on channels the property model can't lift (position axes, channels
     * with no backing property) and for unpark hand-downs.
     *
     * When [coveringKey] identifies the (fixture, property) whose channels include this one,
     * the key is republished so the sideband value reaches the wire through the normal
     * cascade. When the channel has no backing property at all ([coveringKey] = null),
     * nothing below it exists in the cascade — the value is written straight to the
     * controller.
     */
    fun writeProgrammerChannel(
        owner: ProgrammerOwner,
        universe: Int,
        channel: Int,
        value: UByte,
        coveringKey: Layer3Resolver.Key?,
        touched: Boolean = true,
        fadeMs: Long = 0,
    ) {
        programmerStore.putChannel(owner, universe, channel, value, touched)
        if (coveringKey != null) {
            synchronized(cueAssignmentsLock) {
                publishCascadeForKeys(setOf(coveringKey), fadeMs)
            }
        } else if (!programmerStore.blind) {
            fixtures.controllerOrNull(Universe(0, universe))
                ?.setValue(channel, value, fadeMs)
        }
        emitProvenanceUpdate()
    }

    /**
     * Sweep the entire programmer — every owner's property entries and the raw-channel
     * sideband — and release everything to the layers below in one pass. The operator
     * escape hatch behind `programmer.clearAll` and `POST /api/rest/programmer/clear-all`.
     *
     * Property-backed state (including sideband slots whose channel a property covers)
     * releases through the normal cascade publish. Sideband channels with no backing
     * property have nothing below them; they release to DMX 0.
     *
     * Returns the number of entries removed (properties + sideband channels). Callers that
     * own toggle bookkeeping (locate, preset toggles) must reset it themselves — see
     * `clearProgrammerCompletely` in `routes/programmer.kt`.
     */
    fun clearProgrammerAll(fadeMs: Long = 0): Int {
        val keys = HashSet(programmerStore.activeKeys())
        val channelEntries = programmerStore.channelEntries()
        val count = programmerStore.size + channelEntries.size
        if (count == 0) return 0

        // Map each sideband channel to the property that covers it (so it releases through
        // the cascade) or remember it as unbacked (released to 0 below).
        val unbacked = mutableListOf<Pair<Int, Int>>()
        for (entry in channelEntries) {
            val key = resolveChannelCoveringKey(entry.universe, entry.channel)
            if (key != null) keys += key else unbacked += entry.universe to entry.channel
        }

        programmerStore.clearAll()

        if (keys.isNotEmpty()) {
            synchronized(cueAssignmentsLock) {
                publishCascadeForKeys(keys, fadeMs)
            }
        }
        for ((universe, channel) in unbacked) {
            fixtures.controllerOrNull(Universe(0, universe))
                ?.setValue(channel, 0u, fadeMs)
        }
        emitProvenanceUpdate()
        return count
    }

    /**
     * Set the programmer's blind gate and republish every key it holds so the change lands
     * on stage: entering blind releases programmer-held properties to the layers below;
     * exiting restores the staged values. The stored programmer state is untouched either
     * way. [fadeMs] rides the same publish plumbing as clears (snap in half (a)).
     */
    fun setProgrammerBlind(blind: Boolean, fadeMs: Long = 0) {
        if (programmerStore.blind == blind) return
        programmerStore.blind = blind

        val keys = HashSet(programmerStore.activeKeys())
        val unbacked = mutableListOf<ProgrammerStore.ChannelEntryView>()
        for (entry in programmerStore.channelEntries()) {
            val key = resolveChannelCoveringKey(entry.universe, entry.channel)
            if (key != null) keys += key else unbacked += entry
        }
        if (keys.isNotEmpty()) {
            synchronized(cueAssignmentsLock) {
                publishCascadeForKeys(keys, fadeMs)
            }
        }
        // Unbacked sideband channels have no cascade below them: blind writes 0, unblind
        // restores the sideband value.
        for (entry in unbacked) {
            val value = if (blind) {
                0u.toUByte()
            } else {
                (entry.slots.firstOrNull()?.value?.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
                    ?: continue
            }
            fixtures.controllerOrNull(Universe(0, entry.universe))
                ?.setValue(entry.channel, value, fadeMs)
        }
        emitProvenanceUpdate()
    }

    /** Drop all sideband slots under the given channel writes — see [writeProgrammerProperty]. */
    private fun absorbSidebandUnder(writes: List<PropertyChannelResolver.ChannelWrite>) {
        programmerStore.clearChannelsAbsorbedBy(
            writes.map { packChannelKey(it.universe.universe, it.channel) }
        )
    }

    /**
     * The (fixture, property) key whose channels include (universe, channel), or null when
     * no property backs the channel. Walks the owning fixture's property catalogue plus the
     * position axes — the same channel set [FxTarget.fallbackFromProgrammer]'s sideband
     * lookups consult.
     */
    fun resolveChannelCoveringKey(universe: Int, channel: Int): Layer3Resolver.Key? {
        val mappings = fixtures.getChannelMappings()
        val fixtureKey = mappings[universe]?.get(channel)?.fixtureKey ?: return null
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (_: Exception) {
            return null
        }

        for (prop in fixture.fixtureProperties) {
            val value = try {
                prop.classProperty.call(fixture)
            } catch (_: Exception) {
                continue
            } ?: continue
            when (value) {
                is DmxSlider -> if (value.channelNo == channel) {
                    return Layer3Resolver.Key.fixture(fixture.key, prop.name)
                }
                is DmxFixtureSetting<*> -> if (value.channelNo == channel) {
                    return Layer3Resolver.Key.fixture(fixture.key, prop.name)
                }
                is DmxColour -> if (
                    channel == value.redSlider.channelNo ||
                    channel == value.greenSlider.channelNo ||
                    channel == value.blueSlider.channelNo
                ) {
                    return Layer3Resolver.Key.fixture(fixture.key, prop.name)
                }
            }
        }

        val positionFixture = fixture as? WithPosition
        if (positionFixture != null) {
            val pan = positionFixture.pan as? DmxSlider
            val tilt = positionFixture.tilt as? DmxSlider
            if (pan?.channelNo == channel || tilt?.channelNo == channel) {
                return Layer3Resolver.Key.fixture(fixture.key, "position")
            }
        }
        return null
    }

    /**
     * Transmit the composed cascade fallback (cue layer → programmer → baseline) for each
     * affected (fixtureKey, propertyName) key. Same publish machinery as
     * [publishLayer3ToControllers], scoped to a caller-supplied key set rather than the
     * full Layer 3 diff.
     *
     * Skips keys a currently-running effect covers and fully-parked targets. The
     * effect-covered skip stays valid with the programmer above effects because the tick's
     * reset pass is programmer-aware: it repaints suppressed keys with programmer values
     * within one frame (≤20 ms) — the consequence is that writes/clears on effect-covered
     * keys settle on the next tick and do **not** fade. Callers hold [cueAssignmentsLock]
     * so this doesn't race with a concurrent Layer 3 republish or fade-weight update
     * reading the same `layer3State`.
     *
     * [fadeMs] > 0 drives the per-channel [uk.me.cormack.lighting7.dmx.TickerState] ramp
     * for the uncovered keys this publish writes.
     */
    private fun publishCascadeForKeys(keys: Set<Layer3Resolver.Key>, fadeMs: Long = 0) {
        if (keys.isEmpty()) return

        // Empty effects is the common preset-toggle case; skip the scan and transaction alloc.
        val coveredByEffects = if (activeEffects.isEmpty()) {
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

        if (coveredByEffects.isNotEmpty() &&
            keys.all { (it.targetKey to it.propertyName) in coveredByEffects }) {
            return
        }

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)
        var wrote = false

        for (key in keys) {
            if ((key.targetKey to key.propertyName) in coveredByEffects) continue

            val fixture = try {
                fixturesWithTx.untypedGroupableFixture(key.targetKey)
            } catch (e: Exception) {
                System.err.println(
                    "FX Engine: cascade publish could not find fixture '${key.targetKey}': ${e.message}"
                )
                continue
            }

            val target = inferTargetForProperty(fixture, key) ?: continue
            if (allChannelsParked(target, fixture)) continue

            try {
                val fallback = layerResolver.fallbackFor(target, fixture, key.targetKey)
                target.resetToFallback(fixture, fallback, fadeMs)
                wrote = true
            } catch (e: Exception) {
                System.err.println(
                    "FX Engine: failed to publish cascade for ${key.targetKey}.${key.propertyName}: ${e.message}"
                )
            }
        }

        if (wrote) transaction.apply()
    }

    /**
     * Infer the [FxTarget] kind for a cascade publish from the backing DMX property type on
     * [fixture]. Mirrors the type-dispatch that [resolveTargetForLayer3Key] does from a
     * [Layer3Resolver.PropertyValue], but resolves the backing value by name via
     * [PropertyChannelWriter.resolveProperty] instead — the clear path doesn't have a value
     * in hand. Handles [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement]s
     * as well as whole fixtures.
     *
     * Returns null when the property can't be resolved; caller should skip that key.
     */
    private fun inferTargetForProperty(
        fixture: uk.me.cormack.lighting7.fixture.GroupableFixture,
        key: Layer3Resolver.Key,
    ): FxTarget? {
        if (key.propertyName.equals("position", ignoreCase = true)) {
            if (fixture !is WithPosition) return null
            return PositionTarget(FxTargetRef.fixture(key.targetKey), key.propertyName)
        }
        val resolved = PropertyChannelWriter.resolveProperty(fixture, key.propertyName) ?: return null
        return when (resolved.value) {
            is DmxColour -> ColourTarget(FxTargetRef.fixture(key.targetKey), key.propertyName)
            is DmxFixtureSetting<*> -> SettingTarget(key.targetKey, key.propertyName)
            is DmxSlider -> SliderTarget(key.targetKey, key.propertyName)
            else -> null
        }
    }

    /** Callers hold [cueAssignmentsLock]. */
    private fun republishLayer3Assignments() {
        val beforeState = layerResolver.currentLayer3State
        if (cueAssignments.isEmpty()) {
            layerResolver.applyAssignments(emptyList())
        } else {
            val flat = ArrayList<Layer3Resolver.Assignment>()
            for ((cueId, list) in cueAssignments) {
                val cueWeight = cueFadeWeights[cueId] ?: 1.0
                if (cueWeight >= 1.0) {
                    flat.addAll(list)
                } else {
                    for (assignment in list) {
                        flat.add(assignment.copy(fadeWeight = assignment.fadeWeight * cueWeight))
                    }
                }
            }
            layerResolver.applyAssignments(flat)
        }
        val afterState = layerResolver.currentLayer3State
        publishLayer3ToControllers(beforeState, afterState)
        emitProvenanceUpdate()
    }

    /**
     * Transmit the composed Layer 3 → Layer 4 → Layer 5 fallback for every property whose
     * Layer 3 state changed. Without this, cues that contribute only property assignments
     * (no effects) never paint the stage — the tick loop early-returns when no effects are
     * running, and the effect-reset pass is the only other site that writes the composed
     * cascade onto controllers.
     *
     * Walks the union of (fixtureKey, propertyName) keys from the before and after Layer 3
     * snapshots. Skips keys a currently-running effect covers (the effect tick will paint
     * them) and fully-parked targets (park wins at transmit regardless). Otherwise opens a
     * single [ControllerTransaction] and writes the resolved fallback via
     * [FxTarget.resetToFallback] — same mechanism [resetActiveProperties] uses.
     *
     * Release semantics: when a key is in [beforeState] but not [afterState],
     * [LayerResolver.fallbackFor] naturally falls through to Layer 4 (sticky direct writes)
     * then Layer 5 (baseline), so the channel releases to whatever's underneath rather than
     * to zero.
     *
     * Callers hold [cueAssignmentsLock]. The controller write is in-memory buffering on the
     * transaction; the actual transmit-side work is quick enough that running it under the
     * lock is fine — mirrors the pattern in the `updateChannel` handler which also writes
     * through to the controller synchronously.
     */
    private fun publishLayer3ToControllers(
        beforeState: Map<Layer3Resolver.Key, Layer3Resolver.PropertyValue>,
        afterState: Map<Layer3Resolver.Key, Layer3Resolver.PropertyValue>,
    ) {
        if (beforeState.isEmpty() && afterState.isEmpty()) return

        val keys = HashSet<Layer3Resolver.Key>(beforeState.size + afterState.size)
        keys.addAll(beforeState.keys)
        keys.addAll(afterState.keys)

        // Precompute the (fixtureKey, propertyName) set covered by running effects — one walk
        // instead of re-scanning effects per Layer 3 key. The resolver already handles group
        // expansion + multi-element keys, matching the behaviour of [isPropertyCoveredByAny].
        val coveredByEffects = buildSet {
            for (effect in activeEffects.values) {
                if (!effect.isRunning) continue
                val propertyName = effect.target.propertyName
                for (fixtureKey in resolveEffectFixtureKeys(effect)) {
                    add(fixtureKey to propertyName)
                }
            }
        }

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)
        var wrote = false

        for (key in keys) {
            if ((key.targetKey to key.propertyName) in coveredByEffects) continue

            val before = beforeState[key]
            val after = afterState[key]
            // Skip keys whose composed Layer 3 value didn't actually change. Crossfade ticks
            // call republish at ~60 fps; mid-fade the eased weight often quantises to the
            // same UByte for several ticks in a row, and any cue not involved in the fade
            // keeps a constant composed value the whole way through. Equality is a cheap
            // data-class check.
            if (before == after) continue

            val typeSource = after ?: before ?: continue
            val target = resolveTargetForLayer3Key(key, typeSource)

            try {
                val fixture = fixturesWithTx.untypedGroupableFixture(key.targetKey)
                if (allChannelsParked(target, fixture)) continue
                val fallback = layerResolver.fallbackFor(target, fixture, key.targetKey)
                target.resetToFallback(fixture, fallback)
                wrote = true
            } catch (e: Exception) {
                System.err.println(
                    "FX Engine: failed to publish Layer 3 for ${key.targetKey}.${key.propertyName}: ${e.message}"
                )
            }
        }

        if (wrote) transaction.apply()
    }

    /** Construct the [FxTarget] for a Layer 3 [key], deriving target kind from [typeSource]. */
    private fun resolveTargetForLayer3Key(
        key: Layer3Resolver.Key,
        typeSource: Layer3Resolver.PropertyValue,
    ): FxTarget = when (typeSource) {
        is Layer3Resolver.PropertyValue.Slider ->
            SliderTarget(key.targetKey, key.propertyName)
        is Layer3Resolver.PropertyValue.Colour ->
            ColourTarget(FxTargetRef.fixture(key.targetKey), key.propertyName)
        is Layer3Resolver.PropertyValue.Position ->
            PositionTarget(FxTargetRef.fixture(key.targetKey), key.propertyName)
        is Layer3Resolver.PropertyValue.Setting ->
            SettingTarget(key.targetKey, key.propertyName)
    }

    // --- Per-Stack Palettes ---

    private val stackPalettes = ConcurrentHashMap<Int, CuePaletteEntry>()
    private val stackPaletteVersionCounter = AtomicLong(0)

    fun setStackPalette(stackId: Int, colours: List<ExtendedColour>) {
        stackPalettes[stackId] = CuePaletteEntry(colours, stackPaletteVersionCounter.incrementAndGet())
        emitStackPaletteUpdate()
    }

    fun getStackPalette(stackId: Int): List<ExtendedColour>? = stackPalettes[stackId]?.colours

    /** Get a snapshot of all active stack palettes keyed by stack ID. */
    fun getAllStackPalettes(): Map<Int, List<ExtendedColour>> =
        stackPalettes.mapValues { (_, entry) -> entry.colours }

    fun getStackPaletteVersion(stackId: Int): Long = stackPalettes[stackId]?.version ?: 0L

    fun removeStackPalette(stackId: Int) {
        stackPalettes.remove(stackId)
        emitStackPaletteUpdate()
    }

    /**
     * Remove all effects that belong to a specific cue stack, preserving the stack palette.
     * Used during cue transitions within a stack where the palette should carry over.
     *
     * @param stackId The cue stack ID whose effects should be removed
     * @return Number of effects removed
     */
    fun removeEffectsForCueStackKeepPalette(stackId: Int): Int {
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
     * Remove all effects that belong to a specific cue stack and clean up its palette.
     * Used when fully deactivating a stack.
     *
     * @param stackId The cue stack ID whose effects should be removed
     * @return Number of effects removed
     */
    fun removeEffectsForCueStack(stackId: Int): Int {
        val count = removeEffectsForCueStackKeepPalette(stackId)
        removeStackPalette(stackId)
        return count
    }

    /**
     * Represents a state update for broadcasting.
     */
    data class FxStateUpdate(
        val activeEffectIds: List<Long>,
        val effectStates: Map<Long, FxInstanceState>
    )

    /**
     * State of a single effect instance.
     */
    data class FxInstanceState(
        val id: Long,
        val effectType: String,
        val targetKey: String,
        val propertyName: String,
        val isGroupTarget: Boolean,
        val distributionStrategy: String?,
        val elementMode: String?,
        val isRunning: Boolean,
        val currentPhase: Double,
        val blendMode: BlendMode,
        val cueId: Int? = null,
        val cueStackId: Int? = null,
        val timingSource: String = "BEAT",
    )

    /**
     * Start the FX engine.
     *
     * @param scope The coroutine scope to run the engine in
     */
    fun start(scope: CoroutineScope) {
        masterClock.start(scope)
        provenanceScope = scope

        // Emit initial palette so new WebSocket subscribers get it immediately
        emitPaletteUpdate()
        // Seed the provenance replay so subscribers connecting before any layer event get
        // a (usually empty) snapshot instead of nothing.
        emitProvenanceUpdate()

        // BPM-synced processing loop (24 ticks per beat)
        processingJob = scope.launch(Dispatchers.Default) {
            masterClock.tickFlow.collect { tick ->
                processBeatTickSuspend(tick)
            }
        }

        // Wall-clock processing loop (50Hz, independent of BPM)
        wallClockJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(WALL_CLOCK_INTERVAL_MS)
                processWallClockTickSuspend()
            }
        }
    }

    /**
     * Stop the FX engine and all active effects.
     */
    fun stop() {
        provenanceScope = null
        processingJob?.cancel()
        processingJob = null
        wallClockJob?.cancel()
        wallClockJob = null
        masterClock.stop()
        val allEffects = activeEffects.values.toList()
        activeEffects.clear()
        rebuildSortedSnapshots()
        clearAllCueAssignments()
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
        activeEffects[id] = effect
        rebuildSortedSnapshots()
        emitStateUpdate()
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
     * Get all active effects for a given target key and property.
     *
     * @param targetKey The fixture key or group name
     * @param propertyName The property name
     * @return List of matching effect instances
     */
    fun getEffectsForTarget(targetKey: String, propertyName: String): List<FxInstance> {
        return activeEffects.values.filter {
            it.target.targetKey == targetKey && it.target.propertyName == propertyName
        }
    }

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
     * Mutable fields (phaseOffset, distributionStrategy, elementMode) are updated directly.
     * Immutable fields (effect, timing, blendMode) trigger an atomic swap -
     * a new FxInstance replaces the old one, preserving id, start time, and running state.
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
        newStepTiming: Boolean? = null
    ): FxInstance? {
        val existing = activeEffects[effectId] ?: return null

        // Determine if we need an atomic swap (immutable fields changed)
        val needsSwap = newEffect != null || newTiming != null || newBlendMode != null

        val updated = if (needsSwap) {
            FxInstance(
                effect = newEffect ?: existing.effect,
                target = existing.target,
                timing = newTiming ?: existing.timing,
                blendMode = newBlendMode ?: existing.blendMode
            ).apply {
                id = existing.id
                presetId = existing.presetId
                cueId = existing.cueId
                cueStackId = existing.cueStackId
                priority = existing.priority
                startedAtMs = existing.startedAtMs
                startedAtBeat = existing.startedAtBeat
                isRunning = existing.isRunning
                lastPhase = existing.lastPhase
                phaseOffset = newPhaseOffset ?: existing.phaseOffset
                distributionStrategy = newDistributionStrategy ?: existing.distributionStrategy
                elementMode = newElementMode ?: existing.elementMode
                elementFilter = newElementFilter ?: existing.elementFilter
                stepTiming = newStepTiming ?: existing.stepTiming
                timingSource = existing.timingSource
            }
        } else {
            // Only mutable fields changed - update in place
            newPhaseOffset?.let { existing.phaseOffset = it }
            newDistributionStrategy?.let { existing.distributionStrategy = it }
            newElementMode?.let { existing.elementMode = it }
            newElementFilter?.let { existing.elementFilter = it }
            newStepTiming?.let { existing.stepTiming = it }
            existing
        }

        if (needsSwap) {
            activeEffects[effectId] = updated
            rebuildSortedSnapshots()
        }
        emitStateUpdate()
        return updated
    }

    /**
     * Pause an effect by ID.
     */
    fun pauseEffect(effectId: Long) {
        activeEffects[effectId]?.pause()
        emitStateUpdate()
    }

    /**
     * Resume a paused effect by ID.
     */
    fun resumeEffect(effectId: Long) {
        activeEffects[effectId]?.resume()
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
     * Remove every effect in the programmer's reserved priority band ([PROGRAMMER_FX_PRIORITY_BASE])
     * — the busking effects the operator added on top of their programmer values. Cue-owned and
     * plain manual effects are untouched.
     *
     * Kept independently callable (rather than folded into [clearProgrammerAll]) because the
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
     * Remove all effects that were applied as part of a specific cue.
     *
     * @param cueId The cue ID whose effects should be removed
     * @return Number of effects removed
     */
    /**
     * Identifies a single (fixture, property) pair for stomp overlap checks.
     *
     * Phase 0 builds these from the stomping cue's own ad-hoc effect targets because Layer 3
     * assignments don't exist yet. Phase 1 switches the overlap source to the cue's property
     * assignments. The shape is stable across the transition.
     */
    data class PropertyKey(val targetKey: String, val propertyName: String)

    /**
     * Remove ad-hoc effects owned by *other* cues that target properties in the [overlap]
     * set. Effects owned by the stomping cue itself are not stomped — they co-exist with its
     * Layer 3 assertions. Manual (uncued) effects are not stomped either.
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

    fun removeEffectsForCue(cueId: Int): Int {
        val toRemove = activeEffects.values.filter { it.cueId == cueId }
        toRemove.forEach { activeEffects.remove(it.id) }
        if (toRemove.isNotEmpty()) {
            rebuildSortedSnapshots()
            resetUncoveredProperties(toRemove)
            emitStateUpdate()
        }
        removeCuePalette(cueId)
        removeCueAssignments(cueId)
        return toRemove.size
    }

    /**
     * Remove all active effects.
     */
    fun clearAllEffects() {
        val allEffects = activeEffects.values.toList()
        activeEffects.clear()
        rebuildSortedSnapshots()
        clearAllCueAssignments()
        resetUncoveredProperties(allEffects)
        emitStateUpdate()
    }

    /**
     * Process all BEAT-timed effects on a Master Clock tick.
     *
     * `internal` so that `FxEnginePipelineTest` can drive synthetic ticks without waiting on the
     * real-time tick loop.
     */
    /**
     * Non-suspend entry point used by tests (`runBlocking` shim). Production calls
     * [processBeatTickSuspend] directly from the collect loop so the transaction commit
     * doesn't pin the calling thread on a `runBlocking`.
     */
    internal fun processBeatTick(tick: MasterClock.ClockTick) = runBlocking {
        processBeatTickSuspend(tick)
    }

    internal suspend fun processBeatTickSuspend(tick: MasterClock.ClockTick) {
        // Snapshot read is lock-free (volatile). If empty, nothing to do.
        val beatEffects = sortedBeatEffects
        if (beatEffects.isEmpty()) return
        if (beatEffects.none { it.isRunning }) return

        val deltaMs = if (lastTickMs > 0) tick.timestampMs - lastTickMs else 0L
        lastTickMs = tick.timestampMs

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        // Reset properties controlled by BEAT effects to the layer below (Layer 3 → Layer 4 →
        // Layer 5 baseline) before applying. This prevents accumulative blend modes from
        // ratcheting across ticks and keeps direct writes + cue state visible under effects.
        resetActiveProperties(fixturesWithTx, beatEffects)

        // Programmer suppression: any non-band effect skips its apply on (fixture,
        // property) pairs the programmer holds — the reset pass has already painted the
        // programmer value there, and the effect must not repaint over it.
        val suppression = programmerSuppression()

        // Iterate in priority-ascending order. Under non-OVERRIDE blend modes, higher-priority
        // effects compose on top and dominate.
        for (effect in beatEffects) {
            if (!effect.isRunning) continue

            try {
                if (effect.isGroupEffect) {
                    processGroupEffect(tick, effect, fixturesWithTx, deltaMs, suppression)
                } else {
                    processFixtureEffect(tick, effect, fixturesWithTx, deltaMs, suppression)
                }
            } catch (e: Exception) {
                System.err.println("FX Engine error processing effect ${effect.id}: ${e.message}")
            }
        }

        transaction.applySuspend()
    }

    /**
     * Process all WALL_CLOCK-timed effects on the fixed-interval timer.
     *
     * Wall-clock effects use elapsed real time for phase calculation instead of
     * beat position, making them independent of BPM. The phase calculation is
     * handled by [FxInstance.calculateWallClockPhase] and
     * [FxInstance.calculateWallClockPhaseForMember].
     *
     * `internal` so that `FxEnginePipelineTest` can drive the wall-clock path synchronously.
     */
    /**
     * Non-suspend entry point used by tests (`runBlocking` shim). Production calls
     * [processWallClockTickSuspend] directly from the wall-clock loop.
     */
    internal fun processWallClockTick() = runBlocking {
        processWallClockTickSuspend()
    }

    internal suspend fun processWallClockTickSuspend() {
        val wallClockEffects = sortedWallClockEffects
        if (wallClockEffects.isEmpty()) return
        if (wallClockEffects.none { it.isRunning }) return

        val now = System.currentTimeMillis()
        val deltaMs = if (lastWallClockTickMs > 0) now - lastWallClockTickMs else 0L
        lastWallClockTickMs = now

        // Create a synthetic ClockTick for stateful effects that need the tick parameter.
        // The beat/phase fields are unused for wall-clock effects, but the timestampMs is used.
        val syntheticTick = MasterClock.ClockTick(
            tickNumber = 0,
            beatNumber = 0,
            tickInBeat = 0,
            phase = 0.0,
            timestampMs = now,
        )

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        // Reset properties controlled by WALL_CLOCK effects to the layer below.
        resetActiveProperties(fixturesWithTx, wallClockEffects)

        val suppression = programmerSuppression()

        for (effect in wallClockEffects) {
            if (!effect.isRunning) continue

            try {
                if (effect.isGroupEffect) {
                    processWallClockGroupEffect(syntheticTick, effect, fixturesWithTx, deltaMs, suppression)
                } else {
                    processWallClockFixtureEffect(syntheticTick, effect, fixturesWithTx, deltaMs, suppression)
                }
            } catch (e: Exception) {
                System.err.println("FX Engine error processing wall-clock effect ${effect.id}: ${e.message}")
            }
        }

        transaction.applySuspend()
    }

    /**
     * Process a wall-clock fixture effect using elapsed time for phase.
     */
    private fun processWallClockFixtureEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val fixtureKey = effect.target.targetKey
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (e: Exception) {
            return
        }

        if (effect.target.fixtureHasProperty(fixture)) {
            val effectPhase = effect.calculateWallClockPhase()
            val output = calculateEffectOutput(effect, tick, deltaMs, effectPhase, EffectContext.SINGLE, fixturesWithTx, fixtureKey, suppression)
            if (!isSuppressed(suppression, fixtureKey, effect.target.propertyName, effect)) {
                effect.target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode)
            }
        } else if (fixture is MultiElementFixture<*>) {
            val elements = fixture.elements
            if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
                processWallClockMultiElementEffect(tick, effect, fixturesWithTx, elements, deltaMs, suppression)
            }
        }
    }

    /**
     * Process a wall-clock effect expanded across multi-element fixture elements.
     */
    private fun processWallClockMultiElementEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        elements: List<uk.me.cormack.lighting7.fixture.group.FixtureElement<*>>,
        deltaMs: Long,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val filter = effect.elementFilter
        val elementCount = elements.size

        val filteredElements = if (filter == ElementFilter.ALL) {
            elements.mapIndexed { idx, el -> idx to el }
        } else {
            elements.withIndex().filter { (idx, _) -> filter.includes(idx, elementCount) }
                .map { (idx, el) -> idx to el }
        }
        val filteredCount = filteredElements.size
        if (filteredCount == 0) return

        for ((distributionIdx, pair) in filteredElements.withIndex()) {
            val (_, element) = pair
            val memberInfo = object : DistributionMemberInfo {
                override val index: Int = distributionIdx
                override val normalizedPosition: Double =
                    if (filteredCount > 1) distributionIdx.toDouble() / (filteredCount - 1) else 0.5
            }

            val memberPhase = effect.calculateWallClockPhaseForMember(memberInfo, filteredCount)
            val distOffset = effect.distributionStrategy.calculateOffset(memberInfo, filteredCount)

            val context = EffectContext(groupSize = filteredCount, memberIndex = distributionIdx, distributionOffset = distOffset, hasDistributionSpread = effect.distributionStrategy.hasSpread, numDistinctSlots = effect.distributionStrategy.distinctSlots(filteredCount), trianglePhase = effect.distributionStrategy.usesTrianglePhase)
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, element.elementKey, suppression)
            if (!isSuppressed(suppression, element.elementKey, effect.target.propertyName, effect)) {
                effect.target.applyValue(fixturesWithTx, element.elementKey, output, effect.blendMode)
            }
        }
    }

    /**
     * Process a wall-clock group effect using elapsed time for phase.
     */
    private fun processWallClockGroupEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val groupName = effect.target.targetKey
        val group = try {
            fixtures.untypedGroup(groupName)
        } catch (e: Exception) {
            return
        }

        val allMembers = group.allMembers
        if (allMembers.isEmpty()) return

        val firstMemberFixture = try {
            fixtures.untypedFixture(allMembers.first().key)
        } catch (_: Exception) { return }

        if (effect.target.fixtureHasProperty(firstMemberFixture)) {
            val groupSize = allMembers.size
            for (member in allMembers) {
                val memberPhase = effect.calculateWallClockPhaseForMember(member, groupSize)
                val distOffset = effect.distributionStrategy.calculateOffset(member, groupSize)
                val context = EffectContext(groupSize = groupSize, memberIndex = member.index, distributionOffset = distOffset, hasDistributionSpread = effect.distributionStrategy.hasSpread, numDistinctSlots = effect.distributionStrategy.distinctSlots(groupSize), trianglePhase = effect.distributionStrategy.usesTrianglePhase)
                val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, member.key, suppression)
                if (!isSuppressed(suppression, member.key, effect.target.propertyName, effect)) {
                    effect.target.applyValue(fixturesWithTx, member.key, output, effect.blendMode)
                }
            }
            return
        }

        // Multi-element expansion
        if (firstMemberFixture !is MultiElementFixture<*>) return
        val firstElements = firstMemberFixture.elements
        if (firstElements.isEmpty() || !effect.target.fixtureHasProperty(firstElements.first())) return

        when (effect.elementMode) {
            ElementMode.PER_FIXTURE -> {
                for (member in allMembers) {
                    val parentFixture = try {
                        fixtures.untypedFixture(member.key)
                    } catch (_: Exception) { continue }

                    if (parentFixture is MultiElementFixture<*>) {
                        processWallClockMultiElementEffect(tick, effect, fixturesWithTx, parentFixture.elements, deltaMs, suppression)
                    }
                }
            }
            ElementMode.FLAT -> {
                processWallClockGroupFlatElementEffect(tick, effect, fixturesWithTx, allMembers, deltaMs, suppression)
            }
        }
    }

    /**
     * Process a wall-clock group effect in FLAT element mode.
     */
    private fun processWallClockGroupFlatElementEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        allMembers: List<uk.me.cormack.lighting7.fixture.group.GroupMember<*>>,
        deltaMs: Long,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val filter = effect.elementFilter

        data class FlatElement(val elementKey: String, val globalIndex: Int)

        val allFlatElements = mutableListOf<FlatElement>()
        for (member in allMembers) {
            val parentFixture = try {
                fixtures.untypedFixture(member.key)
            } catch (_: Exception) { continue }

            if (parentFixture is MultiElementFixture<*>) {
                for (element in parentFixture.elements) {
                    allFlatElements.add(FlatElement(element.elementKey, allFlatElements.size))
                }
            }
        }

        if (allFlatElements.isEmpty()) return
        val totalUnfilteredCount = allFlatElements.size

        val flatElements = if (filter == ElementFilter.ALL) {
            allFlatElements
        } else {
            allFlatElements.filter { filter.includes(it.globalIndex, totalUnfilteredCount) }
        }
        if (flatElements.isEmpty()) return
        val filteredCount = flatElements.size

        for ((distributionIdx, flatElement) in flatElements.withIndex()) {
            val memberInfo = object : DistributionMemberInfo {
                override val index: Int = distributionIdx
                override val normalizedPosition: Double =
                    if (filteredCount > 1) distributionIdx.toDouble() / (filteredCount - 1) else 0.5
            }

            val memberPhase = effect.calculateWallClockPhaseForMember(memberInfo, filteredCount)
            val distOffset = effect.distributionStrategy.calculateOffset(memberInfo, filteredCount)

            val context = EffectContext(groupSize = filteredCount, memberIndex = distributionIdx, distributionOffset = distOffset, hasDistributionSpread = effect.distributionStrategy.hasSpread, numDistinctSlots = effect.distributionStrategy.distinctSlots(filteredCount), trianglePhase = effect.distributionStrategy.usesTrianglePhase)
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, flatElement.elementKey, suppression)
            if (!isSuppressed(suppression, flatElement.elementKey, effect.target.propertyName, effect)) {
                effect.target.applyValue(fixturesWithTx, flatElement.elementKey, output, effect.blendMode)
            }
        }
    }

    /**
     * Calculate the output for an effect, handling stateless, stateful, and composite effects.
     *
     * For [CompositeEffect]s with [FxInstance.compositeTargets], this also applies
     * secondary outputs to their respective targets. The primary output is returned
     * for the caller to apply to the primary target as usual.
     */
    private fun calculateEffectOutput(
        effect: FxInstance,
        tick: MasterClock.ClockTick,
        deltaMs: Long,
        phase: Double,
        context: EffectContext,
        fixturesWithTx: Fixtures.FixturesWithTransaction? = null,
        fixtureKey: String? = null,
        suppression: Map<String, Set<String>> = emptyMap(),
    ): FxOutput {
        // Composite effects produce multiple outputs
        if (effect.effect is CompositeEffect && effect.compositeTargets != null) {
            val outputs = (effect.effect as CompositeEffect).calculateComposite(phase, context)
            // Apply secondary outputs to their targets — each constituent property is
            // suppression-checked individually.
            val secondaryTargets = effect.compositeTargets!!
            for ((outputType, target) in secondaryTargets) {
                val output = outputs[outputType]?.scaled(effect.intensityMultiplier) ?: continue
                if (fixturesWithTx != null && fixtureKey != null &&
                    !isSuppressed(suppression, fixtureKey, target.propertyName, effect)
                ) {
                    target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode)
                }
            }
            // Return the primary output
            val primaryOutput = outputs[effect.effect.outputType] ?: effect.effect.calculate(phase, context)
            return primaryOutput.scaled(effect.intensityMultiplier)
        }

        // Stateful effects
        val raw = if (effect.effect is StatefulEffect) {
            (effect.effect as StatefulEffect).calculateStateful(tick, deltaMs, context)
        } else {
            effect.effect.calculate(phase, context)
        }
        return raw.scaled(effect.intensityMultiplier)
    }

    /**
     * Reset properties controlled by running effects to the layer below (Layer 3 → Layer 4 →
     * Layer 5 baseline). This ensures blend modes operate against the correct baseline each
     * tick rather than accumulating from previous ticks — and crucially that direct
     * `updateChannel` writes (Layer 4) remain visible under running effects instead of being
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
    ) {
        // Two-level dedupe avoids allocating a compound-key data class per (fixture, property)
        // tuple. On a 168-fixture × 2-property rig that's 336 avoided allocations per tick.
        val seen = HashMap<String, HashSet<String>>()

        for (effect in effects) {
            if (!effect.isRunning) continue

            val keys = resolveEffectFixtureKeys(effect)
            val primary = effect.target
            val composite = effect.compositeTargets

            for (key in keys) {
                val seenForKey = seen.getOrPut(key) { HashSet() }
                if (seenForKey.add(primary.propertyName)) {
                    resetOne(fixturesWithTx, key, primary)
                }
                if (composite != null) {
                    for (target in composite.values) {
                        if (seenForKey.add(target.propertyName)) {
                            resetOne(fixturesWithTx, key, target)
                        }
                    }
                }
            }
        }
    }

    private fun resetOne(
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        fixtureKey: String,
        target: FxTarget,
    ) {
        try {
            val fixture = fixturesWithTx.untypedGroupableFixture(fixtureKey)
            if (allChannelsParked(target, fixture)) return
            val fallback = layerResolver.fallbackFor(target, fixture, fixtureKey)
            target.resetToFallback(fixture, fallback)
        } catch (_: Exception) {
            // Non-fatal — the effect application will also handle missing fixtures
        }
    }

    /**
     * Is every DMX channel backing [target] on [fixture] parked?
     *
     * When true, the caller can skip reset work entirely because [ArtNetController] will
     * overwrite the value at transmit time with the parked value regardless. Partial parking
     * (rare) is treated as "not all parked" — the channels that aren't parked still need their
     * reset path to run.
     */
    private fun allChannelsParked(
        target: FxTarget,
        fixture: uk.me.cormack.lighting7.fixture.GroupableFixture,
    ): Boolean {
        val pm = parkManager ?: return false
        return target.isPropertyFullyParked(fixture, pm)
    }

    /** Whether a programmer write of one property would reach the wire, and if not, why. */
    enum class ProgrammerPublishability {
        /** The property resolves to channels and at least one of them is unparked. */
        PUBLISHABLE,

        /** Every channel backing the property is parked — park wins at transmit. */
        PARK_MASKED,

        /** The property has no DMX-backed channels on this fixture; a write is a no-op. */
        UNRESOLVED,
    }

    /**
     * Would a programmer write of [propertyName] on [fixture] actually reach the wire?
     *
     * This is the public, resolved-by-name form of the two guards [publishCascadeForKeys]
     * applies per key — [inferTargetForProperty] returning null, and [allChannelsParked] —
     * *in that order and via the same helpers*, so a caller that pre-filters on this can never
     * disagree with what the publish then does. Resolving park through
     * [FxTarget.isPropertyFullyParked] rather than enumerating channels directly matters:
     * [ColourTarget] scopes its extended white/amber/UV channels by `bundleWithColour`, which
     * is not the same set [PropertyChannelWriter.channelsFor] enumerates by trait.
     *
     * Locate is the caller: it must know whether writing a property can achieve anything
     * before recording a toggle write for it — an unpublishable write would strand a
     * bookkeeping row for a value that never reached the wire.
     */
    fun programmerPublishability(
        fixture: uk.me.cormack.lighting7.fixture.GroupableFixture,
        propertyName: String,
    ): ProgrammerPublishability {
        val key = Layer3Resolver.Key.fixture(fixture.targetKey, propertyName)
        val target = inferTargetForProperty(fixture, key) ?: return ProgrammerPublishability.UNRESOLVED
        if (PropertyChannelWriter.channelsFor(fixture, propertyName).isEmpty()) {
            // A property whose descriptor exists but is not DMX-backed (e.g. `position` on a
            // Hue-backed head): `writeProgrammerProperties` resolves zero channels for it.
            return ProgrammerPublishability.UNRESOLVED
        }
        return if (allChannelsParked(target, fixture)) {
            ProgrammerPublishability.PARK_MASKED
        } else {
            ProgrammerPublishability.PUBLISHABLE
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
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long = 0L,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val fixtureKey = effect.target.targetKey
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (e: Exception) {
            System.err.println("FX Engine: Fixture '$fixtureKey' not found for effect ${effect.id}")
            return
        }

        // Check if the parent fixture has the target property
        if (effect.target.fixtureHasProperty(fixture)) {
            // Direct application to the parent fixture
            val effectPhase = effect.calculatePhase(tick, masterClock)
            val output = calculateEffectOutput(effect, tick, deltaMs, effectPhase, EffectContext.SINGLE, fixturesWithTx, fixtureKey, suppression)
            if (!isSuppressed(suppression, fixtureKey, effect.target.propertyName, effect)) {
                effect.target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode)
            }
        } else if (fixture is MultiElementFixture<*>) {
            // Parent doesn't have the property — check if elements do
            val elements = fixture.elements
            if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
                processMultiElementEffect(tick, effect, fixturesWithTx, elements, deltaMs, suppression)
            }
        }
        // If neither parent nor elements have the property, silently skip
    }

    /**
     * Process an effect expanded across multi-element fixture elements.
     *
     * Uses the same distribution strategy machinery as group effects,
     * creating lightweight [DistributionMemberInfo] wrappers for each element.
     */
    private fun processMultiElementEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        elements: List<uk.me.cormack.lighting7.fixture.group.FixtureElement<*>>,
        deltaMs: Long = 0L,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val filter = effect.elementFilter
        val elementCount = elements.size

        // Build filtered list for distribution calculation
        // Distribution indices are based on the filtered set so that phase
        // offsets distribute evenly across only the included elements.
        val filteredElements = if (filter == ElementFilter.ALL) {
            elements.mapIndexed { idx, el -> idx to el }
        } else {
            elements.withIndex().filter { (idx, _) -> filter.includes(idx, elementCount) }
                .map { (idx, el) -> idx to el }
        }
        val filteredCount = filteredElements.size
        if (filteredCount == 0) return

        for ((distributionIdx, pair) in filteredElements.withIndex()) {
            val (_, element) = pair
            val memberInfo = object : DistributionMemberInfo {
                override val index: Int = distributionIdx
                override val normalizedPosition: Double =
                    if (filteredCount > 1) distributionIdx.toDouble() / (filteredCount - 1) else 0.5
            }

            val memberPhase = effect.calculatePhaseForMember(
                tick, masterClock, memberInfo, filteredCount
            )
            val distOffset = effect.distributionStrategy.calculateOffset(memberInfo, filteredCount)

            val context = EffectContext(groupSize = filteredCount, memberIndex = distributionIdx, distributionOffset = distOffset, hasDistributionSpread = effect.distributionStrategy.hasSpread, numDistinctSlots = effect.distributionStrategy.distinctSlots(filteredCount), trianglePhase = effect.distributionStrategy.usesTrianglePhase)
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, element.elementKey, suppression)
            if (!isSuppressed(suppression, element.elementKey, effect.target.propertyName, effect)) {
                effect.target.applyValue(fixturesWithTx, element.elementKey, output, effect.blendMode)
            }
        }
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
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long = 0L,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val groupName = effect.target.targetKey
        val group = try {
            fixtures.untypedGroup(groupName)
        } catch (e: Exception) {
            System.err.println("FX Engine: Group '$groupName' not found for effect ${effect.id}")
            return
        }

        val allMembers = group.allMembers
        if (allMembers.isEmpty()) return

        // Check if members have the target property directly
        val firstMemberFixture = try {
            fixtures.untypedFixture(allMembers.first().key)
        } catch (_: Exception) { return }

        if (effect.target.fixtureHasProperty(firstMemberFixture)) {
            // Direct application to members (existing behaviour)
            val groupSize = allMembers.size
            for (member in allMembers) {
                val memberPhase = effect.calculatePhaseForMember(
                    tick, masterClock, member, groupSize
                )
                val distOffset = effect.distributionStrategy.calculateOffset(member, groupSize)
                val context = EffectContext(groupSize = groupSize, memberIndex = member.index, distributionOffset = distOffset, hasDistributionSpread = effect.distributionStrategy.hasSpread, numDistinctSlots = effect.distributionStrategy.distinctSlots(groupSize), trianglePhase = effect.distributionStrategy.usesTrianglePhase)
                val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, member.key, suppression)
                if (!isSuppressed(suppression, member.key, effect.target.propertyName, effect)) {
                    effect.target.applyValue(fixturesWithTx, member.key, output, effect.blendMode)
                }
            }
            return
        }

        // Members don't have the property — check for multi-element expansion
        if (firstMemberFixture !is MultiElementFixture<*>) return
        val firstElements = firstMemberFixture.elements
        if (firstElements.isEmpty() || !effect.target.fixtureHasProperty(firstElements.first())) return

        when (effect.elementMode) {
            ElementMode.PER_FIXTURE -> {
                // Each fixture gets the effect independently across its own elements
                for (member in allMembers) {
                    val parentFixture = try {
                        fixtures.untypedFixture(member.key)
                    } catch (_: Exception) { continue }

                    if (parentFixture is MultiElementFixture<*>) {
                        processMultiElementEffect(tick, effect, fixturesWithTx, parentFixture.elements, deltaMs, suppression)
                    }
                }
            }
            ElementMode.FLAT -> {
                // Collect all elements across all fixtures into one flat list
                processGroupFlatElementEffect(tick, effect, fixturesWithTx, allMembers, deltaMs, suppression)
            }
        }
    }

    /**
     * Process a group effect in FLAT element mode — all elements across all
     * group members form a single flat list for distribution.
     *
     * For example, 2 fixtures with 4 heads each = 8 elements total,
     * distributed as indices 0-7.
     */
    private fun processGroupFlatElementEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        allMembers: List<uk.me.cormack.lighting7.fixture.group.GroupMember<*>>,
        deltaMs: Long = 0L,
        suppression: Map<String, Set<String>> = emptyMap(),
    ) {
        val filter = effect.elementFilter

        // Collect all elements in order
        data class FlatElement(
            val elementKey: String,
            val globalIndex: Int
        )

        val allFlatElements = mutableListOf<FlatElement>()
        for (member in allMembers) {
            val parentFixture = try {
                fixtures.untypedFixture(member.key)
            } catch (_: Exception) { continue }

            if (parentFixture is MultiElementFixture<*>) {
                for (element in parentFixture.elements) {
                    allFlatElements.add(FlatElement(element.elementKey, allFlatElements.size))
                }
            }
        }

        if (allFlatElements.isEmpty()) return
        val totalUnfilteredCount = allFlatElements.size

        // Apply element filter on the flat list
        val flatElements = if (filter == ElementFilter.ALL) {
            allFlatElements
        } else {
            allFlatElements.filter { filter.includes(it.globalIndex, totalUnfilteredCount) }
        }
        if (flatElements.isEmpty()) return
        val filteredCount = flatElements.size

        for ((distributionIdx, flatElement) in flatElements.withIndex()) {
            val memberInfo = object : DistributionMemberInfo {
                override val index: Int = distributionIdx
                override val normalizedPosition: Double =
                    if (filteredCount > 1) distributionIdx.toDouble() / (filteredCount - 1) else 0.5
            }

            val memberPhase = effect.calculatePhaseForMember(
                tick, masterClock, memberInfo, filteredCount
            )
            val distOffset = effect.distributionStrategy.calculateOffset(memberInfo, filteredCount)

            val context = EffectContext(groupSize = filteredCount, memberIndex = distributionIdx, distributionOffset = distOffset, hasDistributionSpread = effect.distributionStrategy.hasSpread, numDistinctSlots = effect.distributionStrategy.distinctSlots(filteredCount), trianglePhase = effect.distributionStrategy.usesTrianglePhase)
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, flatElement.elementKey, suppression)
            if (!isSuppressed(suppression, flatElement.elementKey, effect.target.propertyName, effect)) {
                effect.target.applyValue(fixturesWithTx, flatElement.elementKey, output, effect.blendMode)
            }
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
    fun isMultiElementExpanded(instance: FxInstance): Boolean {
        if (instance.isGroupEffect) {
            // Check if group members need element expansion
            val group = try {
                fixtures.untypedGroup(instance.target.targetKey)
            } catch (_: Exception) {
                return false
            }
            val firstMember = group.allMembers.firstOrNull() ?: return false
            val fixture = try {
                fixtures.untypedFixture(firstMember.key)
            } catch (_: Exception) {
                return false
            }
            if (instance.target.fixtureHasProperty(fixture)) return false
            if (fixture !is MultiElementFixture<*>) return false
            val elements = fixture.elements
            return elements.isNotEmpty() && instance.target.fixtureHasProperty(elements.first())
        }

        // Fixture effect
        val fixture = try {
            fixtures.untypedFixture(instance.target.targetKey)
        } catch (_: Exception) {
            return false
        }
        if (instance.target.fixtureHasProperty(fixture)) return false
        if (fixture !is MultiElementFixture<*>) return false
        val elements = fixture.elements
        return elements.isNotEmpty() && instance.target.fixtureHasProperty(elements.first())
    }

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
            // Collect all targets: primary + composite secondary targets
            val targets = buildList {
                add(removed.target)
                removed.compositeTargets?.values?.let { addAll(it) }
            }
            for (key in resolveEffectFixtureKeys(removed)) {
                for (target in targets) {
                    affectedProperties.add(AffectedProperty(key, target))
                }
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
                if (allChannelsParked(affected.target, fixture)) continue
                val fallback = layerResolver.fallbackFor(affected.target, fixture, affected.fixtureKey)
                affected.target.resetToFallback(fixture, fallback)
            } catch (e: Exception) {
                System.err.println("FX Engine: Failed to reset ${affected.target.propertyName} on '${affected.fixtureKey}': ${e.message}")
            }
        }

        transaction.apply()
    }

    /**
     * Resolve all fixture/element keys that an effect was writing to.
     *
     * For fixture effects: the target fixture key (or element keys if multi-element expanded).
     * For group effects: all member keys (or element keys if multi-element expanded).
     */
    private fun resolveEffectFixtureKeys(effect: FxInstance): List<String> {
        if (effect.isGroupEffect) {
            val group = try {
                fixtures.untypedGroup(effect.target.targetKey)
            } catch (_: Exception) { return emptyList() }

            val allMembers = group.allMembers
            if (allMembers.isEmpty()) return emptyList()

            val firstMemberFixture = try {
                fixtures.untypedFixture(allMembers.first().key)
            } catch (_: Exception) { return emptyList() }

            if (effect.target.fixtureHasProperty(firstMemberFixture)) {
                return allMembers.map { it.key }
            }

            // Multi-element expansion for group
            if (firstMemberFixture is MultiElementFixture<*>) {
                val elements = firstMemberFixture.elements
                if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
                    return allMembers.flatMap { member ->
                        val fixture = try {
                            fixtures.untypedFixture(member.key)
                        } catch (_: Exception) { return@flatMap emptyList() }
                        if (fixture is MultiElementFixture<*>) {
                            fixture.elements.map { it.elementKey }
                        } else emptyList()
                    }
                }
            }

            return emptyList()
        }

        // Fixture effect
        val fixtureKey = effect.target.targetKey
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (_: Exception) { return emptyList() }

        if (effect.target.fixtureHasProperty(fixture)) {
            return listOf(fixtureKey)
        }

        // Multi-element expansion for fixture
        if (fixture is MultiElementFixture<*>) {
            val elements = fixture.elements
            if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
                return elements.map { it.elementKey }
            }
        }

        return emptyList()
    }

    /**
     * Check if any effect in the list covers a (fixtureKey, propertyName) pair.
     *
     * Handles direct fixture effects, group effects whose members include the
     * fixture, and multi-element expansion at both levels. Paused effects still
     * count as covering their channels.
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
        val states = activeEffects.mapValues { (_, instance) ->
            val expanded = isMultiElementExpanded(instance)
            val showDistribution = instance.isGroupEffect || expanded
            FxInstanceState(
                id = instance.id,
                effectType = instance.effect.name,
                targetKey = instance.target.targetKey,
                propertyName = instance.target.propertyName,
                isGroupTarget = instance.isGroupEffect,
                distributionStrategy = if (showDistribution)
                    instance.distributionStrategy.javaClass.simpleName else null,
                elementMode = if (instance.isGroupEffect && expanded)
                    instance.elementMode.name else null,
                isRunning = instance.isRunning,
                currentPhase = instance.lastPhase,
                blendMode = instance.blendMode,
                cueId = instance.cueId,
                cueStackId = instance.cueStackId,
                timingSource = instance.timingSource.name,
            )
        }

        _fxStateFlow.tryEmit(FxStateUpdate(
            activeEffectIds = activeEffects.keys.toList(),
            effectStates = states
        ))
        // Effect lifecycle changes move provenance winners — piggyback on the same sites.
        emitProvenanceUpdate()
    }
}
