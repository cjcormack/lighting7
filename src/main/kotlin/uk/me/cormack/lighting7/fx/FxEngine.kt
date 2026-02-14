package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uk.me.cormack.lighting7.dmx.ControllerTransaction
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
     * Mutable fields (phaseOffset, distributionStrategy) are updated directly.
     * Immutable fields (effect, timing, blendMode) trigger an atomic swap -
     * a new FxInstance replaces the old one, preserving id, start time, and running state.
     *
     * @param effectId The effect ID to update
     * @param newEffect New effect (or null to keep existing)
     * @param newTiming New timing (or null to keep existing)
     * @param newBlendMode New blend mode (or null to keep existing)
     * @param newPhaseOffset New phase offset (or null to keep existing)
     * @param newDistributionStrategy New distribution strategy (or null to keep existing)
     * @return The updated effect instance, or null if not found
     */
    fun updateEffect(
        effectId: Long,
        newEffect: Effect? = null,
        newTiming: FxTiming? = null,
        newBlendMode: BlendMode? = null,
        newPhaseOffset: Double? = null,
        newDistributionStrategy: DistributionStrategy? = null
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
                startedAtMs = existing.startedAtMs
                startedAtBeat = existing.startedAtBeat
                isRunning = existing.isRunning
                lastPhase = existing.lastPhase
                phaseOffset = newPhaseOffset ?: existing.phaseOffset
                distributionStrategy = newDistributionStrategy ?: existing.distributionStrategy
            }
        } else {
            // Only mutable fields changed - update in place
            newPhaseOffset?.let { existing.phaseOffset = it }
            newDistributionStrategy?.let { existing.distributionStrategy = it }
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
     */
    private fun processFixtureEffect(
        tick: MasterClock.ClockTick,
        effect: FxInstance,
        fixturesWithTx: Fixtures.FixturesWithTransaction
    ) {
        // Calculate effect phase based on timing
        val effectPhase = effect.calculatePhase(tick, masterClock)

        // Get output value from effect
        val output = effect.effect.calculate(effectPhase)

        // Apply to fixture property via target
        effect.target.applyValue(fixturesWithTx, effect.target.targetKey, output, effect.blendMode)
    }

    /**
     * Process an effect targeting a group - expands to all members with distribution.
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
        val groupSize = allMembers.size

        // Apply to each member with distributed phase (includes subgroup members)
        for (member in allMembers) {
            val memberPhase = effect.calculatePhaseForMember(
                tick, masterClock, member, groupSize
            )

            val output = effect.effect.calculate(memberPhase)
            effect.target.applyValue(fixturesWithTx, member.key, output, effect.blendMode)
        }
    }

    private fun emitStateUpdate() {
        val states = activeEffects.mapValues { (_, instance) ->
            FxInstanceState(
                id = instance.id,
                effectType = instance.effect.name,
                targetKey = instance.target.targetKey,
                propertyName = instance.target.propertyName,
                isGroupTarget = instance.isGroupEffect,
                distributionStrategy = if (instance.isGroupEffect)
                    instance.distributionStrategy.javaClass.simpleName else null,
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
