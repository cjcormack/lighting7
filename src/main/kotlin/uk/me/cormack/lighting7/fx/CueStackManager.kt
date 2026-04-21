@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.*
import uk.me.cormack.lighting7.state.State
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages runtime state for active cue stacks.
 *
 * Tracks which cue is active in each stack, maintains stack-level palettes
 * (which persist across cue transitions), and handles auto-advance timers.
 *
 * Supports intensity envelope crossfades: when a cue has `fadeDurationMs`
 * configured, outgoing effects ramp from 1→0 and incoming effects ramp from
 * 0→1 using the configured [EasingCurve]. Auto-advance and crossfade are
 * per-cue settings, allowing different timing for each cue in a stack.
 *
 * The CueStackManager does NOT own the effects — it delegates to [FxEngine]
 * for effect lifecycle. Effects belonging to a stack are tagged with both
 * `cueId` and `cueStackId` on [FxInstance].
 */
class CueStackManager(
    private val fxEngine: FxEngine,
) {
    private data class ActiveStackState(
        val stackId: Int,
        var activeCueId: Int,
        var autoAdvanceJob: Job? = null,
        var crossfadeJob: Job? = null,
        // Cue id being faded out by [crossfadeJob], if any. Tracked so that cancelling a
        // mid-flight crossfade (because a new cue activates) can drop the now-abandoned
        // outgoing's Layer 3 assignments — otherwise they'd linger past the cancellation.
        var crossfadeOutgoingCueId: Int? = null,
    )

    private val activeStacks = ConcurrentHashMap<Int, ActiveStackState>()

    /** Tick interval for crossfade envelope updates (≈60fps). */
    private val CROSSFADE_TICK_MS = 16L

    enum class AdvanceDirection { FORWARD, BACKWARD }

    data class ActivateResult(
        val stackId: Int,
        val cueId: Int,
        val cueName: String,
        val effectCount: Int,
    )

    /**
     * Activate a cue within a stack.
     *
     * 1. Snapshot outgoing effects for crossfade (if configured)
     * 2. Merge the cue's palette into the stack palette
     * 3. Apply the cue's effects tagged with both cueId and cueStackId
     * 4. Start crossfade coroutine or snap-cut old effects
     * 5. Start auto-advance timer if configured
     */
    fun activateCueInStack(
        state: State,
        stackId: Int,
        cueId: Int,
        scope: CoroutineScope = GlobalScope,
        rejectMarkers: Boolean = false,
    ): ActivateResult {
        // Read stack and cue data from DB
        val (stackData, cueData) = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: throw IllegalArgumentException("Cue stack not found: $stackId")
            val cue = DaoCue.findById(cueId)
                ?: throw IllegalArgumentException("Cue not found: $cueId")
            if (cue.cueStack?.id?.value != stackId) {
                throw IllegalArgumentException("Cue $cueId does not belong to stack $stackId")
            }
            if (rejectMarkers && cue.cueType == CueType.MARKER.name) {
                throw IllegalArgumentException("Cannot go-to a MARKER cue")
            }

            val sd = StackData(
                id = stack.id.value,
                palette = stack.palette,
                loop = stack.loop,
            )

            val cd = CueApplyData(
                cueId = cue.id.value,
                cueName = cue.name,
                palette = cue.palette,
                updateGlobalPalette = cue.updateGlobalPalette,
                presetApplications = cue.presetApplications.sortedBy { it.sortOrder }.map { app ->
                    CuePresetApplicationDto(
                        presetId = app.preset.id.value,
                        targets = app.targets,
                        delayMs = app.delayMs,
                        intervalMs = app.intervalMs,
                        randomWindowMs = app.randomWindowMs,
                        sortOrder = app.sortOrder,
                    )
                },
                adHocEffects = cue.adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
                propertyAssignments = cue.propertyAssignments.sortedBy { it.sortOrder }.map { it.toDto() },
                triggers = cue.triggers.sortedBy { it.sortOrder }.map { trigger ->
                    CueTriggerDto(
                        triggerType = trigger.triggerType.name,
                        delayMs = trigger.delayMs,
                        intervalMs = trigger.intervalMs,
                        randomWindowMs = trigger.randomWindowMs,
                        scriptId = trigger.script.id.value,
                        sortOrder = trigger.sortOrder,
                    )
                },
                autoAdvance = cue.autoAdvance,
                autoAdvanceDelayMs = cue.autoAdvanceDelayMs,
                fadeDurationMs = cue.fadeDurationMs,
                fadeCurve = cue.fadeCurve,
                stomp = cue.stomp,
                cueStackId = cue.cueStack?.id?.value,
                sortOrder = cue.sortOrder,
            )

            sd to cd
        }

        // Cancel any in-progress crossfade for this stack. A crossfade cancelled mid-flight
        // still holds onto the previous outgoing cue's Layer 3 assignments (they'd have been
        // removed at end-of-crossfade). Drop those now so the new activation starts clean.
        val existingState = activeStacks[stackId]
        existingState?.crossfadeJob?.cancel()
        existingState?.autoAdvanceJob?.cancel()
        existingState?.crossfadeOutgoingCueId?.let { staleOutgoing ->
            fxEngine.removeCueAssignments(staleOutgoing)
        }
        existingState?.crossfadeOutgoingCueId = null

        val outgoingCueId = existingState?.activeCueId

        // 1. Snapshot outgoing effects (before removing) for crossfade
        val outgoingEffects = fxEngine.getActiveEffects().filter { it.cueStackId == stackId }
        val fadeDurationMs = cueData.fadeDurationMs ?: 0L
        // Layer 3 alone is enough to trigger a crossfade: a pure-property cue with no effects
        // still needs its assignments to fade. `outgoingEffects.isNotEmpty()` was the old
        // Phase-0 check before Layer 3 existed.
        val useCrossfade = fadeDurationMs > 0 && (outgoingEffects.isNotEmpty() || outgoingCueId != null)

        // Deactivate triggers for the outgoing cue (stop recurring triggers, etc.)
        if (outgoingCueId != null) {
            state.cueTriggerManager.deactivateTriggersForCue(outgoingCueId)
            if (!useCrossfade) {
                // Snap-cut path: drop outgoing Layer 3 immediately. Crossfade path keeps them
                // in place and ticks the weight down — removal happens at end-of-crossfade.
                fxEngine.removeCueAssignments(outgoingCueId)
            }
        }

        if (!useCrossfade) {
            // Snap-cut: remove old effects but keep the stack palette (it carries over between cues)
            fxEngine.removeEffectsForCueStackKeepPalette(stackId)
        }
        // If crossfading, we leave old effects in place — they'll be faded out below

        // 2. Merge cue palette into stack palette
        if (cueData.palette.isNotEmpty()) {
            val colours = cueData.palette.map { parseExtendedColour(it) }
            fxEngine.setStackPalette(stackId, colours)
        } else if (fxEngine.getStackPalette(stackId) == null && stackData.palette.isNotEmpty()) {
            val colours = stackData.palette.map { parseExtendedColour(it) }
            fxEngine.setStackPalette(stackId, colours)
        }

        // Also update global palette if the cue requests it
        if (cueData.updateGlobalPalette && cueData.palette.isNotEmpty()) {
            val colours = cueData.palette.map { parseExtendedColour(it) }
            fxEngine.setPalette(colours)
        }

        // 3. Apply cue effects with stack palette resolution
        var effectCount = 0
        val newEffectIds = mutableListOf<Long>()

        // Split preset applications into immediate and timed
        val (immediatePresets, timedPresets) = cueData.presetApplications.partition {
            it.delayMs == null && it.intervalMs == null
        }
        val (immediateAdHoc, timedAdHoc) = cueData.adHocEffects.partition {
            it.delayMs == null && it.intervalMs == null
        }

        // Apply immediate preset effects
        for (presetApp in immediatePresets) {
            val presetEffects = transaction(state.database) {
                DaoFxPreset.findById(presetApp.presetId)?.effects
            } ?: continue

            for (target in presetApp.targets) {
                val toggleTarget = TogglePresetTarget(type = target.type, key = target.key)
                for (presetEffect in presetEffects) {
                    val fxTarget = try {
                        resolveTargetForCue(state, toggleTarget, presetEffect)
                    } catch (_: Exception) { null } ?: continue

                    val instance = createInstanceForStack(
                        presetEffect, fxTarget, presetApp.presetId, state, stackId
                    )
                    instance.cueId = cueData.cueId
                    instance.cueStackId = stackId
                    instance.presetId = presetApp.presetId
                    if (useCrossfade) instance.intensityMultiplier = 0.0
                    val id = fxEngine.addEffect(instance)
                    newEffectIds.add(id)
                    effectCount++
                }
            }
        }

        // Apply immediate ad-hoc effects
        for (adHoc in immediateAdHoc) {
            val target = TogglePresetTarget(type = adHoc.targetType, key = adHoc.targetKey)
            val presetEffectDto = FxPresetEffectDto(
                effectType = adHoc.effectType,
                category = adHoc.category,
                propertyName = adHoc.propertyName,
                beatDivision = adHoc.beatDivision,
                blendMode = adHoc.blendMode,
                distribution = adHoc.distribution,
                phaseOffset = adHoc.phaseOffset,
                elementMode = adHoc.elementMode,
                elementFilter = adHoc.elementFilter,
                stepTiming = adHoc.stepTiming,
                parameters = adHoc.parameters,
            )
            val fxTarget = try {
                resolveTargetForCue(state, target, presetEffectDto)
            } catch (_: Exception) { null } ?: continue

            val instance = createInstanceForStack(
                presetEffectDto, fxTarget, null, state, stackId
            )
            instance.cueId = cueData.cueId
            instance.cueStackId = stackId
            if (useCrossfade) instance.intensityMultiplier = 0.0
            val id = fxEngine.addEffect(instance)
            newEffectIds.add(id)
            effectCount++
        }

        // Apply Layer 3 for the incoming cue. Under crossfade the incoming starts at weight 0
        // atomically with the insert; `runCrossfade` ticks it up from there. Stomp runs off
        // the same assignments so HTP/LTP and stomp overlap agree.
        val layer3Assignments = buildLayer3AssignmentsForCue(state.show.fixtures, cueData)
        val incomingStartWeight = if (useCrossfade) 0.0 else 1.0
        if (layer3Assignments.isNotEmpty()) {
            fxEngine.setCueAssignments(cueData.cueId, layer3Assignments, incomingStartWeight)
        } else {
            fxEngine.removeCueAssignments(cueData.cueId)
        }
        // Restore outgoing to 1.0 in case a prior mid-flight crossfade left it partial.
        if (useCrossfade && layer3Assignments.isNotEmpty() && outgoingCueId != null) {
            fxEngine.updateCueFadeWeights(mapOf(outgoingCueId to 1.0))
        }
        if (cueData.stomp) {
            val overlap = buildStompOverlapFromAssignments(state.show.fixtures, cueData)
            fxEngine.stompForCue(cueData.cueId, overlap)
        }

        // 4. Update active state
        activeStacks[stackId] = ActiveStackState(
            stackId = stackId,
            activeCueId = cueData.cueId,
        )

        // 5. Start crossfade or finalize
        if (useCrossfade) {
            val outgoingIds = outgoingEffects.map { it.id }
            val easingCurve = try {
                EasingCurve.valueOf(cueData.fadeCurve)
            } catch (_: Exception) {
                EasingCurve.LINEAR
            }
            val crossfadeOutgoingCueId = outgoingCueId
            val crossfadeIncomingCueId = if (layer3Assignments.isNotEmpty()) cueData.cueId else null

            val stackState = activeStacks[stackId]
            stackState?.crossfadeOutgoingCueId = crossfadeOutgoingCueId
            stackState?.crossfadeJob = scope.launch {
                runCrossfade(
                    outgoingEffectIds = outgoingIds,
                    incomingEffectIds = newEffectIds,
                    outgoingCueId = crossfadeOutgoingCueId,
                    incomingCueId = crossfadeIncomingCueId,
                    durationMs = fadeDurationMs,
                    easingCurve = easingCurve,
                )
                // Clear the in-flight marker once the fade completes normally — prevents a
                // later activation from double-dropping the already-removed outgoing.
                activeStacks[stackId]?.crossfadeOutgoingCueId = null
            }
        }

        // 6. Activate timed effects (delayed/recurring presets and ad-hoc effects)
        if (timedPresets.isNotEmpty() || timedAdHoc.isNotEmpty()) {
            state.cueTriggerManager.activateTimedEffectsForCue(
                cueId = cueData.cueId,
                cueStackId = stackId,
                timedPresets = timedPresets,
                timedAdHocEffects = timedAdHoc,
                scope = scope,
            )
        }

        // 7. Activate script triggers for the new cue
        if (cueData.triggers.isNotEmpty()) {
            state.cueTriggerManager.activateTriggersForCue(
                cueId = cueData.cueId,
                cueStackId = stackId,
                triggers = cueData.triggers,
                scope = scope,
            )
        }

        // 8. Start auto-advance timer if configured
        if (cueData.autoAdvance && cueData.autoAdvanceDelayMs != null) {
            val delayMs = cueData.autoAdvanceDelayMs
            activeStacks[stackId]?.autoAdvanceJob = scope.launch {
                delay(delayMs)
                try {
                    advanceStack(state, stackId, AdvanceDirection.FORWARD, scope)
                } catch (_: Exception) {
                    // Stack may have been deactivated or cue deleted
                }
            }
        }

        return ActivateResult(
            stackId = stackId,
            cueId = cueData.cueId,
            cueName = cueData.cueName,
            effectCount = effectCount,
        )
    }

    /**
     * Run a crossfade between outgoing and incoming cues.
     *
     * Effects: outgoing [FxInstance.intensityMultiplier] ramps from 1→0, incoming from 0→1,
     * both over [durationMs] using the given [easingCurve].
     *
     * Layer 3 property assignments: if either cue contributed Layer 3 state, its per-cue
     * fade weight is ticked through [FxEngine.updateCueFadeWeights] on the same schedule so
     * sliders / colours / positions blend symmetrically rather than snap-cutting.
     *
     * When complete, outgoing effects are removed from the engine, outgoing Layer 3 is
     * dropped via [FxEngine.removeCueAssignments], and incoming effects + Layer 3 weight are
     * pinned at 1.0.
     */
    private suspend fun runCrossfade(
        outgoingEffectIds: List<Long>,
        incomingEffectIds: List<Long>,
        outgoingCueId: Int?,
        incomingCueId: Int?,
        durationMs: Long,
        easingCurve: EasingCurve,
    ) {
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
            val easedProgress = easingCurve.apply(progress)

            // Outgoing: 1→0
            val outgoingMultiplier = 1.0 - easedProgress
            for (id in outgoingEffectIds) {
                fxEngine.getEffect(id)?.intensityMultiplier = outgoingMultiplier
            }

            // Incoming: 0→1
            val incomingMultiplier = easedProgress
            for (id in incomingEffectIds) {
                fxEngine.getEffect(id)?.intensityMultiplier = incomingMultiplier
            }

            // Single atomic update so the engine only republishes once per tick even when both
            // cues contributed Layer 3.
            if (outgoingCueId != null || incomingCueId != null) {
                val updates = buildMap {
                    if (outgoingCueId != null) put(outgoingCueId, outgoingMultiplier)
                    if (incomingCueId != null) put(incomingCueId, incomingMultiplier)
                }
                fxEngine.updateCueFadeWeights(updates)
            }

            if (progress >= 1.0) break
            delay(CROSSFADE_TICK_MS)
        }

        // Crossfade complete — remove outgoing effects and Layer 3 contributions.
        for (id in outgoingEffectIds) {
            fxEngine.removeEffect(id)
        }
        if (outgoingCueId != null) {
            fxEngine.removeCueAssignments(outgoingCueId)
        }

        for (id in incomingEffectIds) {
            fxEngine.getEffect(id)?.intensityMultiplier = 1.0
        }
        if (incomingCueId != null) {
            fxEngine.updateCueFadeWeights(mapOf(incomingCueId to 1.0))
        }
    }

    /**
     * Advance to the next or previous cue in a stack.
     *
     * Only STANDARD cues are candidates for advancement — MARKERs are skipped.
     * Respects the stack's loop setting. If at the end and not looping,
     * stays on the current cue.
     *
     * @return The result of activating the next cue, or null if at end and not looping
     */
    fun advanceStack(
        state: State,
        stackId: Int,
        direction: AdvanceDirection,
        scope: CoroutineScope = GlobalScope,
    ): ActivateResult? {
        val currentState = activeStacks[stackId]
            ?: throw IllegalStateException("Stack $stackId is not active")

        val nextCueId = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: throw IllegalArgumentException("Cue stack not found: $stackId")

            // Only STANDARD cues are candidates for advancement
            val orderedCues = DaoCue.find {
                (DaoCues.cueStack eq stackId) and (DaoCues.cueType eq CueType.STANDARD.name)
            }.orderBy(DaoCues.sortOrder to SortOrder.ASC)
                .map { it.id.value }

            if (orderedCues.isEmpty()) return@transaction null

            val currentIndex = orderedCues.indexOf(currentState.activeCueId)
            if (currentIndex == -1) return@transaction orderedCues.first() // Fallback to first STANDARD

            val nextIndex = when (direction) {
                AdvanceDirection.FORWARD -> currentIndex + 1
                AdvanceDirection.BACKWARD -> currentIndex - 1
            }

            when {
                nextIndex in orderedCues.indices -> orderedCues[nextIndex]
                stack.loop && direction == AdvanceDirection.FORWARD -> orderedCues.first()
                stack.loop && direction == AdvanceDirection.BACKWARD -> orderedCues.last()
                else -> orderedCues[currentIndex] // At boundary and not looping — stay on current cue
            }
        }

        if (nextCueId == null) {
            // No STANDARD cues in stack
            return null
        }

        // Already on this cue — no-op
        if (nextCueId == currentState.activeCueId) {
            return ActivateResult(
                stackId = stackId,
                cueId = nextCueId,
                cueName = transaction(state.database) {
                    DaoCue.findById(nextCueId)?.name ?: "Unknown"
                },
                effectCount = 0,
            )
        }

        return activateCueInStack(state, stackId, nextCueId, scope)
    }

    /**
     * Go to a specific cue within a stack (arbitrary jump).
     *
     * Returns HTTP 400 (via IllegalArgumentException) if the target cue is a MARKER.
     */
    fun goToCue(
        state: State,
        stackId: Int,
        cueId: Int,
        scope: CoroutineScope = GlobalScope,
    ): ActivateResult {
        return activateCueInStack(state, stackId, cueId, scope, rejectMarkers = true)
    }

    /**
     * Activate a cue stack at its first STANDARD cue.
     * Throws [IllegalArgumentException] if the stack has no standard cues.
     */
    fun activateAtFirstCue(state: State, stackId: Int, scope: CoroutineScope = GlobalScope): ActivateResult {
        val firstCueId = transaction(state.database) {
            DaoCue.find {
                (DaoCues.cueStack eq stackId) and (DaoCues.cueType eq CueType.STANDARD.name)
            }.orderBy(DaoCues.sortOrder to SortOrder.ASC)
                .firstOrNull()?.id?.value
        } ?: throw IllegalArgumentException("Cue stack has no standard cues")

        return activateCueInStack(state, stackId, firstCueId, scope)
    }

    /**
     * Deactivate a stack — remove all its effects and clean up state.
     *
     * @return Number of effects removed
     */
    fun deactivateStack(stackId: Int, appState: State? = null): Int {
        val stackState = activeStacks.remove(stackId)
        stackState?.autoAdvanceJob?.cancel()
        stackState?.crossfadeJob?.cancel()

        // `removeEffectsForCueStack` below wipes effects but not Layer 3 — clear the active
        // cue's assignments here so an assignment-only cue doesn't leave stale state behind.
        // Also drop the mid-flight crossfade's outgoing if one was in-flight.
        stackState?.activeCueId?.let { fxEngine.removeCueAssignments(it) }
        stackState?.crossfadeOutgoingCueId?.let { fxEngine.removeCueAssignments(it) }

        // Deactivate triggers for the active cue in this stack
        appState?.cueTriggerManager?.deactivateTriggersForStack(stackId)

        return fxEngine.removeEffectsForCueStack(stackId)
    }

    /**
     * Get the active cue ID for a stack, or null if the stack is not active.
     */
    fun getActiveCueId(stackId: Int): Int? = activeStacks[stackId]?.activeCueId

    /**
     * Cancel the auto-advance timer on the given stack without otherwise disturbing its
     * state. Used by surface `CueStackPause` bindings — a press stops the stack from
     * rolling forward automatically; the operator can then drive it manually with GO / Back.
     *
     * Returns true if the stack had an active auto-advance timer that was cancelled.
     * Calling this on a stack without auto-advance, or on an inactive stack, is a no-op
     * (returns false).
     */
    fun pauseAutoAdvance(stackId: Int): Boolean {
        val state = activeStacks[stackId] ?: return false
        val job = state.autoAdvanceJob ?: return false
        if (!job.isActive) return false
        job.cancel()
        state.autoAdvanceJob = null
        return true
    }

    /**
     * Fire a specific cue without requiring the caller to know its stack id. Looks up the
     * cue's `cueStack` FK in the DB, then delegates to [activateCueInStack].
     *
     * Throws [IllegalArgumentException] if the cue doesn't exist or isn't attached to a
     * stack.
     */
    fun fireCue(
        state: State,
        cueId: Int,
        scope: CoroutineScope = GlobalScope,
    ): ActivateResult {
        val stackId = transaction(state.database) {
            val cue = DaoCue.findById(cueId)
                ?: throw IllegalArgumentException("Cue not found: $cueId")
            cue.cueStack?.id?.value
                ?: throw IllegalArgumentException("Cue $cueId is not attached to a stack")
        }
        return activateCueInStack(state, stackId, cueId, scope)
    }

    /**
     * Get all currently active stack IDs.
     */
    fun getActiveStackIds(): Set<Int> = activeStacks.keys.toSet()

    /**
     * Check if a stack is active.
     */
    fun isStackActive(stackId: Int): Boolean = activeStacks.containsKey(stackId)

    // ─── Private helpers ─────────────────────────────────────────────────

    private data class StackData(
        val id: Int,
        val palette: List<String>,
        val loop: Boolean,
    )

    /**
     * Create an FxInstance for a stack cue, using the stack palette for resolution.
     */
    private fun createInstanceForStack(
        presetEffect: FxPresetEffectDto,
        fxTarget: FxTarget,
        presetId: Int?,
        state: State,
        stackId: Int,
    ): FxInstance {
        val engine = state.show.fxEngine
        val effect = state.show.fxRegistry.createEffect(
            presetEffect.effectType,
            presetEffect.parameters,
            paletteSupplier = { engine.getStackPalette(stackId) ?: engine.getPalette() },
            paletteVersionSupplier = { engine.getStackPaletteVersion(stackId) + engine.paletteVersion },
        )
        val timing = FxTiming(presetEffect.beatDivision)
        val blendMode = try {
            BlendMode.valueOf(presetEffect.blendMode)
        } catch (_: Exception) {
            BlendMode.OVERRIDE
        }
        val distribution = try {
            uk.me.cormack.lighting7.fx.group.DistributionStrategy.fromName(presetEffect.distribution)
        } catch (_: Exception) {
            uk.me.cormack.lighting7.fx.group.DistributionStrategy.LINEAR
        }
        val elementMode = try {
            presetEffect.elementMode?.let { ElementMode.valueOf(it) } ?: ElementMode.PER_FIXTURE
        } catch (_: Exception) {
            ElementMode.PER_FIXTURE
        }
        val elementFilter = try {
            presetEffect.elementFilter?.let { ElementFilter.fromName(it) } ?: ElementFilter.ALL
        } catch (_: Exception) {
            ElementFilter.ALL
        }

        // Propagate timing source from the effect's registration
        val registration = state.show.fxRegistry.getRegistration(presetEffect.effectType)
        val timingSource = registration?.timingSource ?: TimingSource.BEAT

        return FxInstance(effect, fxTarget, timing, blendMode).apply {
            this.presetId = presetId
            phaseOffset = presetEffect.phaseOffset
            distributionStrategy = distribution
            this.elementMode = elementMode
            this.elementFilter = elementFilter
            this.timingSource = timingSource
            presetEffect.stepTiming?.let { this.stepTiming = it }
        }
    }
}
