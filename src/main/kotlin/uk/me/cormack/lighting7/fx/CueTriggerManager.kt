package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.CueTargetDto

import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.createInstanceFromPreset
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

    /**
     * `cueId → the *layer* ids of its timed layers that have fired`, so a re-cook reproduces their
     * contribution instead of dropping it.
     *
     * Layer ids, not look ids: one cue may layer the same Look twice at different delays, and keying
     * this on the look would make the first fire pull in the second layer too.
     *
     * This exists because firing a timed layer **re-cooks the whole cue** rather than appending the
     * layer's rows. Appending was what the preset era did (subset-mutating the cue's Layer 4 rows,
     * matching the prior fire's rows by structural equality), and it cannot survive cooking: two
     * contributors would then sit on one (fixture, property) key inside one cue, and the LTP
     * tie-break between them falls to `HashMap` iteration order in `republishCueAssignments`.
     * Re-cooking keeps the one-row-per-key invariant globally, and publishing through
     * [FxEngine.replaceCueAssignments] preserves an in-flight crossfade weight where
     * `setCueAssignments` would reset it to fully-in.
     */
    private val firedTimedLooks = ConcurrentHashMap<Int, MutableSet<Int>>()

    /**
     * The timed layers of [cueId] that have already fired.
     *
     * Anything that rebuilds a live cue's Layer 4 must pass this to
     * [uk.me.cormack.lighting7.routes.buildCombinedCueLayerRows] — a Look edit, a preview or any
     * other republish that omits it re-cooks the cue *without* the fired layers and so silently
     * retracts their contribution, permanently for a one-shot delay.
     */
    internal fun firedTimedLayerIds(cueId: Int): Set<Int> = firedTimedLooks[cueId]?.toSet() ?: emptySet()

    /** Stored DEACTIVATION script triggers per cue ID (fired on deactivation) */
    private val deactivationTriggers = ConcurrentHashMap<Int, List<CueTriggerDto>>()

    // ─── Timed effect activation ───────────────────────────────────────────

    /**
     * Activate timed (delayed/recurring) preset applications and ad-hoc effects for a cue.
     *
     * Call this after the cue's immediate effects have been applied. Only effects
     * with non-null delayMs or intervalMs should be passed here.
     *
     * [priority] is the cue-derived Layer 4 priority (see
     * [uk.me.cormack.lighting7.routes.cueDerivedPriority]). Timed preset fires produce Layer 4
     * rows at this priority so they compose consistently with the cue's apply-time rows.
     */
    internal fun activateTimedEffectsForCue(
        cueId: Int,
        cueStackId: Int?,
        priority: Int,
        cueData: uk.me.cormack.lighting7.routes.CueApplyData,
        timedAdHocEffects: List<CueAdHocEffectDto>,
        scope: CoroutineScope,
    ) {
        val timedLayers = cueData.layers.filter { it.enabled && it.isTimed }
        if (timedLayers.isEmpty() && timedAdHocEffects.isEmpty()) return

        cueStackId?.let { cueToStack[cueId] = it }

        val jobs = mutableListOf<Job>()
        val effectIds = triggerEffectIds.getOrPut(cueId) { mutableListOf() }
        // A fresh activation has fired nothing: start from empty rather than inheriting a previous
        // run's set, which would make a re-applied cue skip its delays.
        val fired: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        firedTimedLooks[cueId] = fired

        // A timed layer contributes to Layer 4 by joining the cue's fired set and re-cooking, so
        // the published rows are always a single cook of "apply-time layers + whatever has fired".
        // Recurring fires are therefore idempotent on Layer 4 — only the effects re-trigger — and
        // the cue's assignment list can never accumulate duplicates. Cue deactivation wipes the
        // whole cue's Layer 4 via [FxEngine.removeCueAssignments]; no explicit retract is needed.
        for (layer in timedLayers) {
            val job = launchTimedAction(
                delayMs = layer.delayMs,
                intervalMs = layer.intervalMs,
                randomWindowMs = layer.randomWindowMs,
                scope = scope,
            ) {
                fired.add(layer.layerId)

                // Effects first, so a fire that spawns nothing still publishes its values.
                for ((firedLayer, lookEffect, target) in CueComposer.cookEffects(
                    state.show.fixtures, cueId, listOf(layer), state.show.lookRegistry,
                    includeTimed = setOf(layer.layerId),
                )) {
                    applyLookEffectToTarget(firedLayer, lookEffect, target, cueId, cueStackId, effectIds)
                }

                val localRows = uk.me.cormack.lighting7.routes.buildCueAssignmentsForCue(
                    state.show.fixtures, cueData,
                )
                val cooked = CueComposer.cook(
                    fixtures = state.show.fixtures,
                    cueId = cueId,
                    priority = priority,
                    layers = cueData.layers,
                    localRows = localRows,
                    lookRegistry = state.show.lookRegistry,
                    templateRegistry = state.show.templateRegistry,
                    includeTimed = fired.toSet(),
                )
                // Suppression rides along with the rows: a timed layer asserts nothing until it
                // fires, so its arrival is exactly when a `stomp` on it must silence the layers
                // below — and a recurring fire re-states it rather than accumulating.
                fxEngine.replaceCueAssignments(
                    mapOf(cueId to cooked.rows),
                    mapOf(cueId to cooked.stompSuppression),
                )
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

        firedTimedLooks.remove(cueId)
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
     * Spawn effects for a timed preset application. Takes the preset's effects list preloaded
     * by the caller so the fire path can share one DB transaction with the Layer 4 property-
     * assignment lookup.
     */
    private fun applyLookEffectToTarget(
        layer: CookLayer,
        lookEffect: LookEffectEntry,
        target: uk.me.cormack.lighting7.models.TargetRef,
        cueId: Int,
        cueStackId: Int?,
        effectIds: MutableList<Long>,
    ) {
        val effectSpec = lookEffect.toEffectSpec()
        val fxTarget = try {
            resolveTargetForCue(state, CueTargetDto(target), effectSpec)
        } catch (_: Exception) { null } ?: return

        val instance = createInstanceFromPreset(
            effectSpec, fxTarget, state = state,
            overrideSpeedMasterUuid = layer.speedMasterUuid,
            overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
        )
        // Only a Look can own an effect, so only a Look id belongs in this field.
        // `cookEffects` already skips template layers, which makes this structurally
        // unreachable rather than merely unlikely — stated because the alternative writes a
        // template id into a field named `lookId`.
        instance.lookId = layer.source.id.takeUnless { layer.source.isTemplate }
        instance.cueLayerId = layer.layerId
        instance.cueId = cueId
        instance.cueStackId = cueStackId

        val id = fxEngine.addEffect(instance)
        synchronized(effectIds) { effectIds.add(id) }
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
        val toggleTarget = CueTargetDto(effect.target)
        val presetEffect = LookEffectSpec(
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
            speedMasterUuid = effect.speedMasterUuid,
            rateSpeedMasterUuid = effect.rateSpeedMasterUuid,
        )

        val fxTarget = try {
            resolveTargetForCue(state, toggleTarget, presetEffect)
        } catch (_: Exception) { null } ?: return

        val instance = createInstanceFromPreset(presetEffect, fxTarget, state)
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
        // Outside the `try` so the `finally` can see it.
        val beforeEffects = fxEngine.getActiveEffects().map { it.id }.toSet()
        try {
            // Run as FX_APPLICATION script (lightweight, implicit engine access)
            state.show.runLiteralScript(
                literalScript = scriptBody,
                scriptName = "cue-trigger-$cueId",
                scriptType = ScriptType.FX_APPLICATION,
                scriptId = scriptId,
            )
        } finally {
            fxEngine.currentCueContext = null

            // Track new effects in the `finally`, not after the script returns: a script that
            // throws part-way has still applied everything up to the throw, and this list is the
            // only thing `deactivateTriggersForCue` cleans up by — there is no per-cue sweep, only
            // `removeEffectsForCueStack` when the whole stack is torn down. So a mid-script failure
            // used to leave its already-applied effects running until the stack stopped.
            //
            // The window opened when effects stopped being compiled-in classes (sweep item D7): a
            // typo'd effect name was a *compile* error, which threw before the script had applied
            // anything, whereas `effect("SineWav")` throws at GO time with effects already live.
            val afterEffects = fxEngine.getActiveEffects().map { it.id }.toSet()
            val newEffectIds = afterEffects - beforeEffects
            synchronized(effectIds) { effectIds.addAll(newEffectIds) }
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
