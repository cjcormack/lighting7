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
        scope: CoroutineScope? = null,
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
                presetApplications = cue.presetApplications.map { app ->
                    CuePresetApplicationDto(
                        presetId = app.preset.id.value,
                        targets = app.targets,
                    )
                },
                adHocEffects = cue.adHocEffects.map { it.toDto() },
                autoAdvance = cue.autoAdvance,
                autoAdvanceDelayMs = cue.autoAdvanceDelayMs,
                fadeDurationMs = cue.fadeDurationMs,
                fadeCurve = cue.fadeCurve,
            )

            sd to cd
        }

        // Cancel any in-progress crossfade for this stack
        val existingState = activeStacks[stackId]
        existingState?.crossfadeJob?.cancel()
        existingState?.autoAdvanceJob?.cancel()

        // 1. Snapshot outgoing effects (before removing) for crossfade
        val outgoingEffects = fxEngine.getActiveEffects().filter { it.cueStackId == stackId }
        val fadeDurationMs = cueData.fadeDurationMs ?: 0L
        val useCrossfade = fadeDurationMs > 0 && outgoingEffects.isNotEmpty() && scope != null

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

        // Apply preset effects
        for (presetApp in cueData.presetApplications) {
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

        // Apply ad-hoc effects
        for (adHoc in cueData.adHocEffects) {
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

            // scope is guaranteed non-null when useCrossfade is true
            activeStacks[stackId]?.crossfadeJob = scope!!.launch {
                runCrossfade(outgoingIds, newEffectIds, fadeDurationMs, easingCurve)
            }
        }

        // 6. Start auto-advance timer if configured
        if (cueData.autoAdvance && cueData.autoAdvanceDelayMs != null && scope != null) {
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
     * Run a crossfade between outgoing and incoming effects.
     *
     * Outgoing effects ramp from 1→0, incoming effects ramp from 0→1,
     * both over [durationMs] using the given [easingCurve].
     * When complete, outgoing effects are removed from the engine.
     */
    private suspend fun runCrossfade(
        outgoingIds: List<Long>,
        incomingIds: List<Long>,
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
            for (id in outgoingIds) {
                fxEngine.getEffect(id)?.intensityMultiplier = outgoingMultiplier
            }

            // Incoming: 0→1
            val incomingMultiplier = easedProgress
            for (id in incomingIds) {
                fxEngine.getEffect(id)?.intensityMultiplier = incomingMultiplier
            }

            if (progress >= 1.0) break
            delay(CROSSFADE_TICK_MS)
        }

        // Crossfade complete — remove outgoing effects
        for (id in outgoingIds) {
            fxEngine.removeEffect(id)
        }

        // Ensure incoming effects are at full intensity
        for (id in incomingIds) {
            fxEngine.getEffect(id)?.intensityMultiplier = 1.0
        }
    }

    /**
     * Advance to the next or previous cue in a stack.
     *
     * Respects the stack's loop setting. If at the end and not looping,
     * the stack is deactivated.
     *
     * @return The result of activating the next cue, or null if at end and not looping
     */
    fun advanceStack(
        state: State,
        stackId: Int,
        direction: AdvanceDirection,
        scope: CoroutineScope? = null,
    ): ActivateResult? {
        val currentState = activeStacks[stackId]
            ?: throw IllegalStateException("Stack $stackId is not active")

        val nextCueId = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: throw IllegalArgumentException("Cue stack not found: $stackId")

            val orderedCues = DaoCue.find {
                DaoCues.cueStack eq stackId
            }.orderBy(DaoCues.sortOrder to SortOrder.ASC)
                .map { it.id.value }

            if (orderedCues.isEmpty()) return@transaction null

            val currentIndex = orderedCues.indexOf(currentState.activeCueId)
            if (currentIndex == -1) return@transaction orderedCues.first() // Fallback to first

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
            // No cues in stack
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
     */
    fun goToCue(
        state: State,
        stackId: Int,
        cueId: Int,
        scope: CoroutineScope? = null,
    ): ActivateResult {
        return activateCueInStack(state, stackId, cueId, scope)
    }

    /**
     * Deactivate a stack — remove all its effects and clean up state.
     *
     * @return Number of effects removed
     */
    fun deactivateStack(stackId: Int): Int {
        val state = activeStacks.remove(stackId)
        state?.autoAdvanceJob?.cancel()
        state?.crossfadeJob?.cancel()
        return fxEngine.removeEffectsForCueStack(stackId)
    }

    /**
     * Get the active cue ID for a stack, or null if the stack is not active.
     */
    fun getActiveCueId(stackId: Int): Int? = activeStacks[stackId]?.activeCueId

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

        return FxInstance(effect, fxTarget, timing, blendMode).apply {
            this.presetId = presetId
            phaseOffset = presetEffect.phaseOffset
            distributionStrategy = distribution
            this.elementMode = elementMode
            this.elementFilter = elementFilter
            presetEffect.stepTiming?.let { this.stepTiming = it }
        }
    }
}
