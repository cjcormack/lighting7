package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import uk.me.cormack.lighting7.dmx.ControllerTransaction
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
     * Get all active effects for a given fixture/property.
     *
     * @param fixtureKey The fixture key
     * @param propertyName The property name
     * @return List of matching effect instances
     */
    fun getEffectsForTarget(fixtureKey: String, propertyName: String): List<FxInstance> {
        return activeEffects.values.filter {
            it.target.fixtureKey == fixtureKey && it.target.propertyName == propertyName
        }
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
        val toRemove = activeEffects.values.filter { it.target.fixtureKey == fixtureKey }
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

        // Group effects by target (for future stacking support)
        val effectsByTarget = activeEffects.values.groupBy { it.target }

        for ((target, effects) in effectsByTarget) {
            // Currently: single effect per target (last wins)
            // Future: blend multiple effects based on priority/stacking rules
            val effect = effects.filter { it.isRunning }.lastOrNull() ?: continue

            try {
                // Calculate effect phase based on timing
                val effectPhase = effect.calculatePhase(tick, masterClock)

                // Get output value from effect
                val output = effect.effect.calculate(effectPhase)

                // Apply to fixture property via target
                target.applyValue(fixturesWithTx, output, effect.blendMode)
            } catch (e: Exception) {
                // Log but don't crash the engine
                System.err.println("FX Engine error processing effect ${effect.id}: ${e.message}")
            }
        }

        transaction.apply()
    }

    private fun emitStateUpdate() {
        val states = activeEffects.mapValues { (_, instance) ->
            FxInstanceState(
                id = instance.id,
                effectType = instance.effect.name,
                targetKey = "${instance.target.fixtureKey}.${instance.target.propertyName}",
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
