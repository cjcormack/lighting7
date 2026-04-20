package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
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
     * Layer 4 sticky direct-write store. Read during effect reset so that manual `updateChannel`
     * writes remain visible under running effects. Defaults to a fresh empty store for tests;
     * the real show wires in the per-project store from [uk.me.cormack.lighting7.show.Show].
     */
    val directWriteStore: DirectWriteStore = DirectWriteStore(),
    /**
     * Layer 3 composition resolver. Resolves per-cue property assignments to the composed
     * value that sits below effects. Phase 0: always empty input. Phase 1 wires real data.
     */
    val layerResolver: LayerResolver = LayerResolver(Layer3Resolver(), directWriteStore),
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
    }

    private val _fxStateFlow = MutableSharedFlow<FxStateUpdate>(replay = 1, extraBufferCapacity = 1)

    /** Flow of FX state updates for WebSocket broadcasting */
    val fxStateFlow: SharedFlow<FxStateUpdate> = _fxStateFlow.asSharedFlow()

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
    // [updateCueFadeWeights] / Phase 1b in `docs/cue-authoring-unification-plan.md`.
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
    fun setCueAssignments(cueId: Int, assignments: List<Layer3Resolver.Assignment>) {
        synchronized(cueAssignmentsLock) {
            val changed = if (assignments.isEmpty()) {
                val removed = cueAssignments.remove(cueId) != null
                cueFadeWeights.remove(cueId)
                removed
            } else {
                cueAssignments[cueId] = assignments
                true
            }
            if (changed) republishLayer3Assignments()
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

        // Emit initial palette so new WebSocket subscribers get it immediately
        emitPaletteUpdate()

        // BPM-synced processing loop (24 ticks per beat)
        processingJob = scope.launch(Dispatchers.Default) {
            masterClock.tickFlow.collect { tick ->
                processBeatTick(tick)
            }
        }

        // Wall-clock processing loop (50Hz, independent of BPM)
        wallClockJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(WALL_CLOCK_INTERVAL_MS)
                processWallClockTick()
            }
        }
    }

    /**
     * Stop the FX engine and all active effects.
     */
    fun stop() {
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

        // Auto-tag with CueContext if set and effect doesn't already have a cueId
        currentCueContext?.let { ctx ->
            if (effect.cueId == null) effect.cueId = ctx.cueId
            if (effect.cueStackId == null) effect.cueStackId = ctx.cueStackId
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
     */
    private fun processBeatTick(tick: MasterClock.ClockTick) {
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

        // Iterate in priority-ascending order. Under non-OVERRIDE blend modes, higher-priority
        // effects compose on top and dominate.
        for (effect in beatEffects) {
            if (!effect.isRunning) continue

            try {
                if (effect.isGroupEffect) {
                    processGroupEffect(tick, effect, fixturesWithTx, deltaMs)
                } else {
                    processFixtureEffect(tick, effect, fixturesWithTx, deltaMs)
                }
            } catch (e: Exception) {
                System.err.println("FX Engine error processing effect ${effect.id}: ${e.message}")
            }
        }

        transaction.apply()
    }

    /**
     * Process all WALL_CLOCK-timed effects on the fixed-interval timer.
     *
     * Wall-clock effects use elapsed real time for phase calculation instead of
     * beat position, making them independent of BPM. The phase calculation is
     * handled by [FxInstance.calculateWallClockPhase] and
     * [FxInstance.calculateWallClockPhaseForMember].
     */
    private fun processWallClockTick() {
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

        for (effect in wallClockEffects) {
            if (!effect.isRunning) continue

            try {
                if (effect.isGroupEffect) {
                    processWallClockGroupEffect(syntheticTick, effect, fixturesWithTx, deltaMs)
                } else {
                    processWallClockFixtureEffect(syntheticTick, effect, fixturesWithTx, deltaMs)
                }
            } catch (e: Exception) {
                System.err.println("FX Engine error processing wall-clock effect ${effect.id}: ${e.message}")
            }
        }

        transaction.apply()
    }

    /**
     * Process a wall-clock fixture effect using elapsed time for phase.
     */
    private fun processWallClockFixtureEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction,
        deltaMs: Long,
    ) {
        val fixtureKey = effect.target.targetKey
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (e: Exception) {
            return
        }

        if (effect.target.fixtureHasProperty(fixture)) {
            val effectPhase = effect.calculateWallClockPhase()
            val output = calculateEffectOutput(effect, tick, deltaMs, effectPhase, EffectContext.SINGLE, fixturesWithTx, fixtureKey)
            effect.target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode)
        } else if (fixture is MultiElementFixture<*>) {
            val elements = fixture.elements
            if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
                processWallClockMultiElementEffect(tick, effect, fixturesWithTx, elements, deltaMs)
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
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, element.elementKey)
            effect.target.applyValue(fixturesWithTx, element.elementKey, output, effect.blendMode)
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
                val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, member.key)
                effect.target.applyValue(fixturesWithTx, member.key, output, effect.blendMode)
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
                        processWallClockMultiElementEffect(tick, effect, fixturesWithTx, parentFixture.elements, deltaMs)
                    }
                }
            }
            ElementMode.FLAT -> {
                processWallClockGroupFlatElementEffect(tick, effect, fixturesWithTx, allMembers, deltaMs)
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
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, flatElement.elementKey)
            effect.target.applyValue(fixturesWithTx, flatElement.elementKey, output, effect.blendMode)
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
    ): FxOutput {
        // Composite effects produce multiple outputs
        if (effect.effect is CompositeEffect && effect.compositeTargets != null) {
            val outputs = (effect.effect as CompositeEffect).calculateComposite(phase, context)
            // Apply secondary outputs to their targets
            val secondaryTargets = effect.compositeTargets!!
            for ((outputType, target) in secondaryTargets) {
                val output = outputs[outputType]?.scaled(effect.intensityMultiplier) ?: continue
                if (fixturesWithTx != null && fixtureKey != null) {
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
        data class PropertyKey(val fixtureKey: String, val propertyName: String)

        val seen = mutableSetOf<PropertyKey>()

        for (effect in effects) {
            if (!effect.isRunning) continue

            val keys = resolveEffectFixtureKeys(effect)

            // Collect all targets: primary + composite secondary targets
            val targets = buildList {
                add(effect.target)
                effect.compositeTargets?.values?.let { addAll(it) }
            }

            for (key in keys) {
                for (target in targets) {
                    if (!seen.add(PropertyKey(key, target.propertyName))) continue
                    try {
                        val fixture = fixturesWithTx.untypedGroupableFixture(key)
                        if (allChannelsParked(target, fixture)) continue
                        val fallback = layerResolver.fallbackFor(target, fixture, key)
                        target.resetToFallback(fixture, fallback)
                    } catch (_: Exception) {
                        // Non-fatal — the effect application will also handle missing fixtures
                    }
                }
            }
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
            val output = calculateEffectOutput(effect, tick, deltaMs, effectPhase, EffectContext.SINGLE, fixturesWithTx, fixtureKey)
            effect.target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode)
        } else if (fixture is MultiElementFixture<*>) {
            // Parent doesn't have the property — check if elements do
            val elements = fixture.elements
            if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
                processMultiElementEffect(tick, effect, fixturesWithTx, elements, deltaMs)
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
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, element.elementKey)
            effect.target.applyValue(fixturesWithTx, element.elementKey, output, effect.blendMode)
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
                val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, member.key)
                effect.target.applyValue(fixturesWithTx, member.key, output, effect.blendMode)
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
                        processMultiElementEffect(tick, effect, fixturesWithTx, parentFixture.elements, deltaMs)
                    }
                }
            }
            ElementMode.FLAT -> {
                // Collect all elements across all fixtures into one flat list
                processGroupFlatElementEffect(tick, effect, fixturesWithTx, allMembers, deltaMs)
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
            val output = calculateEffectOutput(effect, tick, deltaMs, memberPhase, context, fixturesWithTx, flatElement.elementKey)
            effect.target.applyValue(fixturesWithTx, flatElement.elementKey, output, effect.blendMode)
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
    }
}
