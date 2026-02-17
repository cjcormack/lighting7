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
        activeEffects.clear()
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
        val removed = activeEffects.remove(effectId) != null
        if (removed) {
            emitStateUpdate()
        }
        return removed
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
        newElementMode: ElementMode? = null
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
            }
        } else {
            // Only mutable fields changed - update in place
            newPhaseOffset?.let { existing.phaseOffset = it }
            newDistributionStrategy?.let { existing.distributionStrategy = it }
            newElementMode?.let { existing.elementMode = it }
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
            emitStateUpdate()
        }
        return toRemove.size
    }

    /**
     * Remove all active effects.
     */
    fun clearAllEffects() {
        activeEffects.clear()
        emitStateUpdate()
    }

    private fun processTick(tick: MasterClock.ClockTick) {
        if (activeEffects.isEmpty()) return

        val transaction = ControllerTransaction(fixtures.controllers)
        val fixturesWithTx = fixtures.withTransaction(transaction)

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
            val output = effect.effect.calculate(effectPhase)
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
        val elementCount = elements.size

        for ((idx, element) in elements.withIndex()) {
            val memberInfo = object : DistributionMemberInfo {
                override val index: Int = idx
                override val normalizedPosition: Double =
                    if (elementCount > 1) idx.toDouble() / (elementCount - 1) else 0.5
            }

            val memberPhase = effect.calculatePhaseForMember(
                tick, masterClock, memberInfo, elementCount
            )

            val output = effect.effect.calculate(memberPhase)
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
                val output = effect.effect.calculate(memberPhase)
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
        // Collect all elements in order
        data class FlatElement(
            val elementKey: String,
            val globalIndex: Int
        )

        val flatElements = mutableListOf<FlatElement>()
        for (member in allMembers) {
            val parentFixture = try {
                fixtures.untypedFixture(member.key)
            } catch (_: Exception) { continue }

            if (parentFixture is MultiElementFixture<*>) {
                for (element in parentFixture.elements) {
                    flatElements.add(FlatElement(element.elementKey, flatElements.size))
                }
            }
        }

        if (flatElements.isEmpty()) return
        val totalCount = flatElements.size

        for (flatElement in flatElements) {
            val memberInfo = object : DistributionMemberInfo {
                override val index: Int = flatElement.globalIndex
                override val normalizedPosition: Double =
                    if (totalCount > 1) flatElement.globalIndex.toDouble() / (totalCount - 1) else 0.5
            }

            val memberPhase = effect.calculatePhaseForMember(
                tick, masterClock, memberInfo, totalCount
            )

            val output = effect.effect.calculate(memberPhase)
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
