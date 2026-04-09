package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.TogglePresetTarget
import uk.me.cormack.lighting7.routes.createInstanceFromPresetForCue
import uk.me.cormack.lighting7.routes.resolveTargetForCue
import uk.me.cormack.lighting7.scripts.ScriptType
import uk.me.cormack.lighting7.state.State
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private val logger = LoggerFactory.getLogger("CueTriggerManager")

/**
 * Manages the runtime lifecycle of cue triggers and timed effects.
 *
 * **Timed effects**: Preset applications and ad-hoc effects with delay/interval
 * timing fields. These are "effects with scheduling" — they fire at specific
 * times relative to cue activation.
 *
 * **Script triggers**: RUN_SCRIPT lifecycle hooks that execute at cue activation,
 * deactivation, after a delay, or on a recurring interval.
 *
 * Effects created by timed actions are tagged with the parent cue's ID so they
 * participate in crossfades and are cleaned up when the cue/stack deactivates.
 */
class CueTriggerManager(
    private val fxEngine: FxEngine,
    private val state: State,
) {
    /** Active coroutine jobs per cue ID (covers both timed effects and script triggers) */
    private val activeTriggerJobs = ConcurrentHashMap<Int, MutableList<Job>>()

    /** Effect IDs created by timed effects/triggers per cue ID (for targeted cleanup) */
    private val triggerEffectIds = ConcurrentHashMap<Int, MutableList<Long>>()

    /** Map of cueId → stackId for stack-level cleanup */
    private val cueToStack = ConcurrentHashMap<Int, Int>()

    /** Stored DEACTIVATION script triggers per cue ID (fired on deactivation) */
    private val deactivationTriggers = ConcurrentHashMap<Int, List<CueTriggerDto>>()

    // ─── Timed effect activation ───────────────────────────────────────────

    /**
     * Activate timed (delayed/recurring) preset applications and ad-hoc effects for a cue.
     *
     * Call this after the cue's immediate effects have been applied. Only effects
     * with non-null delayMs or intervalMs should be passed here.
     */
    fun activateTimedEffectsForCue(
        cueId: Int,
        cueStackId: Int?,
        timedPresets: List<CuePresetApplicationDto>,
        timedAdHocEffects: List<CueAdHocEffectDto>,
        scope: CoroutineScope,
    ) {
        if (timedPresets.isEmpty() && timedAdHocEffects.isEmpty()) return

        cueStackId?.let { cueToStack[cueId] = it }

        val jobs = mutableListOf<Job>()
        val effectIds = triggerEffectIds.getOrPut(cueId) { mutableListOf() }

        // Launch timed preset applications
        for (preset in timedPresets) {
            val job = launchTimedAction(preset.delayMs, preset.intervalMs, preset.randomWindowMs, scope) {
                applyPresetToTargets(preset.presetId, preset.targets, cueId, cueStackId, effectIds)
            }
            if (job != null) jobs.add(job)
        }

        // Launch timed ad-hoc effects
        for (effect in timedAdHocEffects) {
            val job = launchTimedAction(effect.delayMs, effect.intervalMs, effect.randomWindowMs, scope) {
                applyAdHocEffect(effect, cueId, cueStackId, effectIds)
            }
            if (job != null) jobs.add(job)
        }

        if (jobs.isNotEmpty()) {
            activeTriggerJobs.getOrPut(cueId) { mutableListOf() }.addAll(jobs)
        }
    }

    // ─── Script trigger activation ─────────────────────────────────────────

    /**
     * Activate script triggers for a cue that was just applied.
     *
     * Triggers are now exclusively for running scripts at cue lifecycle events.
     * Call this after the cue's effects (immediate + timed) have been set up.
     */
    fun activateTriggersForCue(
        cueId: Int,
        cueStackId: Int?,
        triggers: List<CueTriggerDto>,
        scope: CoroutineScope,
    ) {
        if (triggers.isEmpty()) return

        cueStackId?.let { cueToStack[cueId] = it }

        val jobs = mutableListOf<Job>()
        val effectIds = triggerEffectIds.getOrPut(cueId) { mutableListOf() }

        // Store deactivation triggers for later
        val deactivation = triggers.filter { it.triggerType == "DEACTIVATION" }
        if (deactivation.isNotEmpty()) {
            deactivationTriggers[cueId] = deactivation
        }

        for (trigger in triggers) {
            when (trigger.triggerType) {
                "ACTIVATION" -> {
                    // Check if this trigger has timing (delay/recurring)
                    val hasTiming = (trigger.intervalMs != null && trigger.intervalMs > 0)
                            || (trigger.delayMs != null && trigger.delayMs > 0)
                    if (hasTiming) {
                        val job = launchTimedAction(trigger.delayMs, trigger.intervalMs, trigger.randomWindowMs, scope) {
                            executeScriptTrigger(trigger.scriptId, cueId, cueStackId, effectIds)
                        }
                        if (job != null) jobs.add(job)
                    } else {
                        // Immediate activation
                        try {
                            executeScriptTrigger(trigger.scriptId, cueId, cueStackId, effectIds)
                        } catch (e: Exception) {
                            logger.error("Error executing ACTIVATION script trigger for cue $cueId", e)
                        }
                    }
                }
                "DEACTIVATION" -> {
                    // Stored above, no action now
                }
            }
        }

        if (jobs.isNotEmpty()) {
            activeTriggerJobs.getOrPut(cueId) { mutableListOf() }.addAll(jobs)
        }
    }

    // ─── Deactivation ──────────────────────────────────────────────────────

    /**
     * Deactivate all timed effects and triggers for a specific cue.
     *
     * Fires DEACTIVATION script triggers, cancels all pending jobs, and removes
     * all timed/trigger-created effects.
     */
    fun deactivateTriggersForCue(cueId: Int) {
        // Fire deactivation script triggers
        deactivationTriggers.remove(cueId)?.forEach { trigger ->
            try {
                executeScriptTrigger(trigger.scriptId, cueId, cueToStack[cueId], mutableListOf())
            } catch (e: Exception) {
                logger.error("Error executing DEACTIVATION script trigger for cue $cueId", e)
            }
        }

        // Cancel all running jobs (timed effects + script triggers)
        activeTriggerJobs.remove(cueId)?.forEach { it.cancel() }

        // Remove timed/trigger-created effects
        triggerEffectIds.remove(cueId)?.forEach { effectId ->
            fxEngine.removeEffect(effectId)
        }

        cueToStack.remove(cueId)
    }

    /**
     * Deactivate all timed effects and triggers for all cues in a stack.
     */
    fun deactivateTriggersForStack(stackId: Int) {
        val cueIds = cueToStack.entries
            .filter { it.value == stackId }
            .map { it.key }

        for (cueId in cueIds) {
            deactivateTriggersForCue(cueId)
        }
    }

    // ─── Internal helpers ──────────────────────────────────────────────────

    /**
     * Launch a coroutine for a timed action (delayed one-shot or recurring).
     */
    private fun launchTimedAction(
        delayMs: Long?,
        intervalMs: Long?,
        randomWindowMs: Long?,
        scope: CoroutineScope,
        action: () -> Unit,
    ): Job? {
        return when {
            // Recurring: fire at intervalMs with optional initial delay
            intervalMs != null && intervalMs > 0 -> {
                scope.launch {
                    // If there's also a delay, wait before starting the recurring loop
                    if (delayMs != null && delayMs > 0) delay(delayMs)
                    while (isActive) {
                        val actualInterval = computeRandomisedInterval(intervalMs, randomWindowMs)
                        delay(actualInterval)
                        try { action() } catch (e: Exception) {
                            logger.error("Error in recurring timed action", e)
                        }
                    }
                }
            }
            // Delayed one-shot
            delayMs != null && delayMs > 0 -> {
                scope.launch {
                    delay(delayMs)
                    try { action() } catch (e: Exception) {
                        logger.error("Error in delayed timed action", e)
                    }
                }
            }
            else -> null // Should not happen — caller should filter immediate effects
        }
    }

    /**
     * Apply a preset to targets, creating FxInstances and adding to the engine.
     * Used by both timed preset applications and immediate apply paths.
     */
    internal fun applyPresetToTargets(
        presetId: Int,
        targets: List<CueTargetDto>,
        cueId: Int,
        cueStackId: Int?,
        effectIds: MutableList<Long>,
    ) {
        val presetEffects = transaction(state.database) {
            val preset = DaoFxPreset.findById(presetId) ?: return@transaction null
            preset.effects
        }
        if (presetEffects.isNullOrEmpty()) return

        for (target in targets) {
            val toggleTarget = TogglePresetTarget(type = target.type, key = target.key)
            for (presetEffect in presetEffects) {
                val fxTarget = try {
                    resolveTargetForCue(state, toggleTarget, presetEffect)
                } catch (_: Exception) { null } ?: continue

                val instance = createInstanceFromPresetForCue(
                    presetEffect, fxTarget, presetId, state, cueId
                )
                instance.cueId = cueId
                instance.cueStackId = cueStackId

                val id = fxEngine.addEffect(instance)
                synchronized(effectIds) { effectIds.add(id) }
            }
        }
    }

    /**
     * Apply an ad-hoc effect, creating an FxInstance and adding to the engine.
     */
    private fun applyAdHocEffect(
        effect: CueAdHocEffectDto,
        cueId: Int,
        cueStackId: Int?,
        effectIds: MutableList<Long>,
    ) {
        val toggleTarget = TogglePresetTarget(type = effect.targetType, key = effect.targetKey)
        val presetEffect = FxPresetEffectDto(
            effectType = effect.effectType,
            category = effect.category,
            propertyName = effect.propertyName,
            beatDivision = effect.beatDivision,
            blendMode = effect.blendMode,
            distribution = effect.distribution,
            phaseOffset = effect.phaseOffset,
            elementMode = effect.elementMode,
            elementFilter = effect.elementFilter,
            stepTiming = effect.stepTiming,
            parameters = effect.parameters,
        )

        val fxTarget = try {
            resolveTargetForCue(state, toggleTarget, presetEffect)
        } catch (_: Exception) { null } ?: return

        val instance = createInstanceFromPresetForCue(
            presetEffect, fxTarget, null, state, cueId
        )
        instance.cueId = cueId
        instance.cueStackId = cueStackId

        val id = fxEngine.addEffect(instance)
        synchronized(effectIds) { effectIds.add(id) }
    }

    /**
     * Execute a script trigger action.
     */
    private fun executeScriptTrigger(
        scriptId: Int,
        cueId: Int,
        cueStackId: Int?,
        effectIds: MutableList<Long>,
    ) {
        // Load script from database
        val scriptBody = transaction(state.database) {
            val script = DaoScript.findById(scriptId) ?: return@transaction null
            script.script
        } ?: return

        // Set CueContext so effects created by the script are auto-tagged
        fxEngine.currentCueContext = CueContext(cueId, cueStackId)
        try {
            val beforeEffects = fxEngine.getActiveEffects().map { it.id }.toSet()

            // Run as FX_APPLICATION script (lightweight, implicit engine access)
            state.show.runLiteralScript(
                literalScript = scriptBody,
                scriptSettings = emptyList(),
                scriptName = "cue-trigger-$cueId",
                scriptType = ScriptType.FX_APPLICATION,
                scriptId = scriptId,
            )

            // Track any new effects created during script execution
            val afterEffects = fxEngine.getActiveEffects().map { it.id }.toSet()
            val newEffectIds = afterEffects - beforeEffects
            synchronized(effectIds) { effectIds.addAll(newEffectIds) }
        } finally {
            fxEngine.currentCueContext = null
        }
    }

    companion object {
        private const val MINIMUM_INTERVAL_MS = 100L

        fun computeRandomisedInterval(baseMs: Long, windowMs: Long?): Long {
            if (windowMs == null || windowMs <= 0) return baseMs.coerceAtLeast(MINIMUM_INTERVAL_MS)
            val offset = Random.nextLong(-windowMs, windowMs + 1)
            return (baseMs + offset).coerceAtLeast(MINIMUM_INTERVAL_MS)
        }
    }
}

/**
 * Context for auto-tagging effects created during trigger script execution.
 */
data class CueContext(val cueId: Int, val cueStackId: Int?)
