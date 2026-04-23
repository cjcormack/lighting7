package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.TogglePresetTarget
import uk.me.cormack.lighting7.routes.buildLayer3AssignmentsForPreset
import uk.me.cormack.lighting7.routes.createInstanceFromPresetForCue
import uk.me.cormack.lighting7.routes.resolveTargetForCue
import uk.me.cormack.lighting7.routes.toPropertyAssignmentDtos
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
     *
     * [priority] is the cue-derived Layer 3 priority (see
     * [uk.me.cormack.lighting7.routes.cueDerivedPriority]). Timed preset fires produce Layer 3
     * rows at this priority so they compose consistently with the cue's apply-time rows.
     *
     * [cuePalette] is the cue's declared palette (parsed to [ExtendedColour]) or empty when the
     * cue doesn't declare one. Combined with each preset's own palette and the global palette
     * at fire time, see [PaletteCascade].
     */
    fun activateTimedEffectsForCue(
        cueId: Int,
        cueStackId: Int?,
        priority: Int,
        timedPresets: List<CuePresetApplicationDto>,
        timedAdHocEffects: List<CueAdHocEffectDto>,
        scope: CoroutineScope,
        cuePalette: List<ExtendedColour> = emptyList(),
    ) {
        if (timedPresets.isEmpty() && timedAdHocEffects.isEmpty()) return

        cueStackId?.let { cueToStack[cueId] = it }

        val jobs = mutableListOf<Job>()
        val effectIds = triggerEffectIds.getOrPut(cueId) { mutableListOf() }

        // Hoisted so recurring fires don't re-synchronise the global palette on every tick.
        // The global palette only changes when the operator mutates it, and a palette edit
        // will re-apply the cue anyway.
        val baseCascade = PaletteCascade(cue = cuePalette, global = fxEngine.getPalette())

        // Timed presets contribute their property assignments to Layer 3 atomically alongside
        // spawning effects; recurring fires retract the prior tick's rows in the same
        // mutation so the cue's assignment list does not accumulate duplicates. Cue
        // deactivation wipes the whole cue's Layer 3 via [FxEngine.removeCueAssignments] —
        // no explicit retract of the final fire is needed.
        for (presetApp in timedPresets) {
            val job = launchTimedActionWithState(
                delayMs = presetApp.delayMs,
                intervalMs = presetApp.intervalMs,
                randomWindowMs = presetApp.randomWindowMs,
                scope = scope,
                initialState = emptyList<Layer3Resolver.Assignment>(),
            ) { priorLayer3Rows ->
                // One transaction per fire loads both effects, property assignments, and the
                // preset's palette — splitting them would double the DB hit on recurring presets.
                val loaded = transaction(state.database) {
                    val preset = DaoFxPreset.findById(presetApp.presetId) ?: return@transaction null
                    Triple(
                        preset.effects,
                        preset.toPropertyAssignmentDtos(),
                        preset.palette.toPaletteColours(),
                    )
                }
                if (loaded == null) return@launchTimedActionWithState priorLayer3Rows
                val (presetEffects, presetAssignments, presetPalette) = loaded

                applyPresetToTargets(presetApp.presetId, presetApp.targets, cueId, cueStackId, presetEffects, effectIds)

                val newRows = if (presetAssignments.isEmpty()) emptyList() else buildLayer3AssignmentsForPreset(
                    state.show.fixtures, cueId, priority,
                    presetApp.presetId, presetAssignments, presetApp.targets,
                    cascade = baseCascade.copy(preset = presetPalette),
                )
                fxEngine.replaceCueAssignmentSubset(cueId, priorLayer3Rows, newRows)
                newRows
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
    ): Job? = launchTimedActionWithState(
        delayMs = delayMs,
        intervalMs = intervalMs,
        randomWindowMs = randomWindowMs,
        scope = scope,
        initialState = Unit,
    ) { action() }

    /**
     * Launch a coroutine for a timed action that threads state across recurring fires.
     *
     * The state seed is [initialState]; each fire receives the state returned by the previous
     * fire and emits the next state. For one-shot (delayed-only) actions the state is simply
     * consumed by the single fire and never re-used.
     *
     * Used by the timed-preset path to carry the previous fire's Layer 3 contribution across
     * ticks so each fire can retract it before appending the new one.
     */
    private fun <T> launchTimedActionWithState(
        delayMs: Long?,
        intervalMs: Long?,
        randomWindowMs: Long?,
        scope: CoroutineScope,
        initialState: T,
        action: (T) -> T,
    ): Job? {
        return when {
            // Recurring: fire at intervalMs with optional initial delay
            intervalMs != null && intervalMs > 0 -> {
                scope.launch {
                    // If there's also a delay, wait before starting the recurring loop
                    if (delayMs != null && delayMs > 0) delay(delayMs)
                    var state = initialState
                    while (isActive) {
                        val actualInterval = computeRandomisedInterval(intervalMs, randomWindowMs)
                        delay(actualInterval)
                        try { state = action(state) } catch (e: Exception) {
                            logger.error("Error in recurring timed action", e)
                        }
                    }
                }
            }
            // Delayed one-shot
            delayMs != null && delayMs > 0 -> {
                scope.launch {
                    delay(delayMs)
                    try { action(initialState) } catch (e: Exception) {
                        logger.error("Error in delayed timed action", e)
                    }
                }
            }
            else -> null // Should not happen — caller should filter immediate effects
        }
    }

    /**
     * Spawn effects for a timed preset application. Takes the preset's effects list preloaded
     * by the caller so the fire path can share one DB transaction with the Layer 3 property-
     * assignment lookup.
     */
    private fun applyPresetToTargets(
        presetId: Int,
        targets: List<CueTargetDto>,
        cueId: Int,
        cueStackId: Int?,
        presetEffects: List<FxPresetEffectDto>,
        effectIds: MutableList<Long>,
    ) {
        if (presetEffects.isEmpty()) return
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
