package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uk.me.cormack.lighting7.dmx.ControllerTransaction
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
    val masterClock: MasterClock
) {
    private val nextEffectId = AtomicLong(0)
    private val activeEffects = ConcurrentHashMap<Long, FxInstance>()

    private var processingJob: Job? = null

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
        val blendMode: BlendMode
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

        processingJob = scope.launch(Dispatchers.Default) {
            masterClock.tickFlow.collect { tick ->
                processTick(tick)
            }
        }
    }

    /**
     * Stop the FX engine and all active effects.
     */
    fun stop() {
        processingJob?.cancel()
        processingJob = null
        masterClock.stop()
        val allEffects = activeEffects.values.toList()
        activeEffects.clear()
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
        activeEffects[id] = effect
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
                startedAtMs = existing.startedAtMs
                startedAtBeat = existing.startedAtBeat
                isRunning = existing.isRunning
                lastPhase = existing.lastPhase
                phaseOffset = newPhaseOffset ?: existing.phaseOffset
                distributionStrategy = newDistributionStrategy ?: existing.distributionStrategy
                elementMode = newElementMode ?: existing.elementMode
                elementFilter = newElementFilter ?: existing.elementFilter
                stepTiming = newStepTiming ?: existing.stepTiming
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
            resetUncoveredProperties(toRemove)
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Remove all active effects.
     */
    fun clearAllEffects() {
        val allEffects = activeEffects.values.toList()
        activeEffects.clear()
        resetUncoveredProperties(allEffects)
        emitStateUpdate()
    }

    private fun processTick(tick: MasterClock.ClockTick) {
        if (activeEffects.isEmpty()) return

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

        // Reset all FX-controlled properties to neutral before applying effects.
        // This prevents accumulative blend modes (MAX, ADDITIVE, etc.) from
        // ratcheting values up/down across ticks by always blending against
        // a clean baseline rather than the previous tick's result.
        resetActiveProperties(fixturesWithTx)

        // Process each active effect
        for ((_, effect) in activeEffects) {
            if (!effect.isRunning) continue

            try {
                if (effect.isGroupEffect) {
                    processGroupEffect(tick, effect, fixturesWithTx)
                } else {
                    processFixtureEffect(tick, effect, fixturesWithTx)
                }
            } catch (e: Exception) {
                // Log but don't crash the engine
                System.err.println("FX Engine error processing effect ${effect.id}: ${e.message}")
            }
        }

        transaction.apply()
    }

    /**
     * Reset all properties that are actively controlled by running effects
     * to their neutral values. This ensures blend modes operate against a
     * clean baseline each tick rather than accumulating from previous ticks.
     */
    private fun resetActiveProperties(fixturesWithTx: Fixtures.FixturesWithTransaction) {
        data class PropertyKey(val fixtureKey: String, val propertyName: String)

        val seen = mutableSetOf<PropertyKey>()

        for ((_, effect) in activeEffects) {
            if (!effect.isRunning) continue

            val keys = resolveEffectFixtureKeys(effect)
            for (key in keys) {
                if (seen.add(PropertyKey(key, effect.target.propertyName))) {
                    try {
                        effect.target.applyNeutralValue(fixturesWithTx, key)
                    } catch (e: Exception) {
                        // Non-fatal — the effect application will also handle missing fixtures
                    }
                }
            }
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
        fixturesWithTx: Fixtures.FixturesWithTransaction
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
            val output = effect.effect.calculate(effectPhase, EffectContext.SINGLE)
            effect.target.applyValue(fixturesWithTx, fixtureKey, output, effect.blendMode)
        } else if (fixture is MultiElementFixture<*>) {
            // Parent doesn't have the property — check if elements do
            val elements = fixture.elements
            if (elements.isNotEmpty() && effect.target.fixtureHasProperty(elements.first())) {
                processMultiElementEffect(tick, effect, fixturesWithTx, elements)
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
        elements: List<uk.me.cormack.lighting7.fixture.group.FixtureElement<*>>
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
            val output = effect.effect.calculate(memberPhase, context)
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
        fixturesWithTx: Fixtures.FixturesWithTransaction
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
                val output = effect.effect.calculate(memberPhase, context)
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
                        processMultiElementEffect(tick, effect, fixturesWithTx, parentFixture.elements)
                    }
                }
            }
            ElementMode.FLAT -> {
                // Collect all elements across all fixtures into one flat list
                processGroupFlatElementEffect(tick, effect, fixturesWithTx, allMembers)
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
        allMembers: List<uk.me.cormack.lighting7.fixture.group.GroupMember<*>>
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
            val output = effect.effect.calculate(memberPhase, context)
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
                affected.target.applyNeutralValue(fixturesWithTx, affected.fixtureKey)
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
                blendMode = instance.blendMode
            )
        }

        _fxStateFlow.tryEmit(FxStateUpdate(
            activeEffectIds = activeEffects.keys.toList(),
            effectStates = states
        ))
    }
}
