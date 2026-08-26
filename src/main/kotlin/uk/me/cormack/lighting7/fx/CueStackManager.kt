@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.CueTargetDto

import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.*
import uk.me.cormack.lighting7.state.State
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("CueStackManager")

/**
 * A cue stack's *run state* — what is live, what the next GO will fire, and how far through a
 * fade the desk is. Broadcast on every transition (see [CueStackManager.runStateFor]) so a
 * prompt book on a tablet agrees with the desk that armed or fired the cue, rather than each
 * session computing "next" for itself.
 *
 * @property nextCueId the cue the next GO fires: the explicitly armed standby when there is
 *   one, else the positional next. Null at the end of a non-looping stack.
 * @property nextIsArmed true when [nextCueId] is an operator-armed standby rather than the
 *   positional next — the difference a "NEXT" pill draws differently.
 * @property transition true when this frame *is* a cue transition — a GO just happened. False
 *   for a standby change, a stack stopping, and the connect-time snapshot. A client can't infer
 *   it: a snapshot of a cue that fired an hour ago looks exactly like the cue firing now, and
 *   guessing means a freshly-opened session replays a fade that is long over.
 * @property autoAdvance whether the stack *will* roll forward on its own — a live auto-advance
 *   timer, not merely a cue configured for one. A paused timer reports false, because a client
 *   drawing the countdown would otherwise show a bar completing into nothing.
 * @property fadeDurationMs the live cue's configured fade, whether or not a fade is running.
 * @property fadeElapsedMs null when no fade is in progress; otherwise how far into the fade
 *   the desk is *at send time*. A session joining mid-fade starts its animation there instead
 *   of replaying from zero. Deliberately a duration and not a wall-clock instant: a tablet
 *   with a skewed clock would otherwise animate a fade that is already over.
 */
data class CueRunState(
    val projectId: Int,
    val stackId: Int,
    val activeCueId: Int?,
    val nextCueId: Int?,
    val nextIsArmed: Boolean,
    val transition: Boolean,
    val fadeDurationMs: Long?,
    val fadeElapsedMs: Long?,
    val autoAdvance: Boolean,
    val autoAdvanceDelayMs: Long?,
)

/**
 * Manages runtime state for active cue stacks.
 *
 * Tracks which cue is active in each stack and handles auto-advance timers.
 *
 * Supports Layer 4 property-assignment crossfades: when a cue has `fadeDurationMs`
 * configured, its outgoing and incoming Layer 4 assignments crossfade using per-cue
 * fade weights and the configured [EasingCurve]. Effects always snap on cue transition
 * (outgoing removed, incoming start at full intensity) — matching Eos / grandMA / Hog 4.
 * Auto-advance and crossfade are per-cue settings.
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
        // outgoing's Layer 4 assignments — otherwise they'd linger past the cancellation.
        var crossfadeOutgoingCueId: Int? = null,
        // The live cue's fade / auto-advance configuration, copied at activation so
        // [runStateFor] can describe the stack without going back to the DB.
        var fadeDurationMs: Long? = null,
        var autoAdvance: Boolean = false,
        var autoAdvanceDelayMs: Long? = null,
        // When the live cue's crossfade started, or null when the cue snapped (no outgoing
        // cue to fade out of, so the configured fade time never ran). Never cleared once set —
        // [runStateFor] treats an elapsed time past the duration as "settled".
        var fadeStartedAtMs: Long? = null,
    )

    private val activeStacks = ConcurrentHashMap<Int, ActiveStackState>()

    /**
     * `stackId → explicitly armed next cue`.
     *
     * Deliberately not a field of [ActiveStackState]: an operator arms a cue *before* the
     * stack is running (pre-show), and [activateCueInStack] replaces the whole
     * [ActiveStackState] entry. Transient runtime state — never persisted, never synced.
     */
    private val standbyCueIds = ConcurrentHashMap<Int, Int>()

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
     * 1. Cancel any in-flight crossfade and remove outgoing effects (effects always snap)
     * 2. Apply the cue's effects (tagged with both cueId and cueStackId) and Layer 4
     *    assignments (at weight 0 if crossfading, 1 otherwise)
     * 3. Start Layer 4 crossfade coroutine if configured
     * 4. Start auto-advance timer if configured
     */
    fun activateCueInStack(
        state: State,
        stackId: Int,
        cueId: Int,
        scope: CoroutineScope = GlobalScope,
        rejectMarkers: Boolean = false,
    ): ActivateResult {
        // Read cue data from DB
        val cueData = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: throw IllegalArgumentException("Cue stack not found: $stackId")
            if (stack.type == CueStackType.SEPARATOR.name) {
                throw IllegalArgumentException("Cannot activate a separator")
            }
            val cue = DaoCue.findById(cueId)
                ?: throw IllegalArgumentException("Cue not found: $cueId")
            if (cue.cueStack.id.value != stackId) {
                throw IllegalArgumentException("Cue $cueId does not belong to stack $stackId")
            }
            if (rejectMarkers && cue.cueType == CueType.MARKER.name) {
                throw IllegalArgumentException("Cannot go-to a MARKER cue")
            }

            // The one builder. This used to be a hand-rolled second construction, and `layers`
            // — added later, to the other one — was inert on this path, the primary firing path,
            // for a whole session. See `buildCueApplyData`.
            buildCueApplyData(cue)
        }

        // Cancel any in-progress crossfade for this stack. A crossfade cancelled mid-flight
        // still holds onto the previous outgoing cue's Layer 4 assignments (they'd have been
        // removed at end-of-crossfade). Drop those now so the new activation starts clean.
        val existingState = activeStacks[stackId]
        existingState?.crossfadeJob?.cancel()
        existingState?.autoAdvanceJob?.cancel()
        existingState?.crossfadeOutgoingCueId?.let { staleOutgoing ->
            fxEngine.removeCueAssignments(staleOutgoing)
        }
        existingState?.crossfadeOutgoingCueId = null

        val outgoingCueId = existingState?.activeCueId

        val fadeDurationMs = cueData.fadeDurationMs ?: 0L
        // Governs Layer 4 assignments only — effects always snap (see
        // `removeEffectsForCueStack` below).
        val useCrossfade = fadeDurationMs > 0 && outgoingCueId != null

        // Deactivate triggers for the outgoing cue (stop recurring triggers, etc.)
        if (outgoingCueId != null) {
            state.cueTriggerManager.deactivateTriggersForCue(outgoingCueId)
            if (!useCrossfade) {
                // Snap path: drop outgoing Layer 4 immediately. Crossfade path keeps
                // assignments live and ticks the weight down — removal happens at end-of-crossfade.
                fxEngine.removeCueAssignments(outgoingCueId)
            }
        }

        // Outgoing effects always snap off, regardless of crossfade.
        fxEngine.removeEffectsForCueStack(stackId)

        // 2. Apply cue effects
        //
        // Instances are collected and handed to `addEffects` in one go: adding them one at a
        // time rebuilt the sorted snapshots and re-broadcast the whole active-effect list per
        // effect, which is O(N²) for a cue of any size (sweep item C7). List order is still
        // spawn order, so layer order still decides composition.
        val spawning = mutableListOf<FxInstance>()

        val (immediateAdHoc, timedAdHoc) = cueData.adHocEffects.partition {
            it.delayMs == null && it.intervalMs == null
        }

        // Spawn the layers' effects, in layer order — see [CueComposer.cookEffects] for why
        // spawn order alone is enough to make layer order the composition order.
        for ((layer, lookEffect, target) in CueComposer.cookEffects(
            state.show.fixtures, cueData.cueId, cueData.layers, state.show.lookRegistry,
        )) {
            val effectSpec = lookEffect.toEffectSpec()
            val fxTarget = try {
                resolveTargetForCue(state, CueTargetDto(target), effectSpec)
            } catch (e: Exception) {
                logger.warn(
                    "cue {}: layer on '{}' — target '{}' unresolvable — skipping effect: {}",
                    cueData.cueId, layer.source.name, target.key, e.message,
                )
                null
            } ?: continue

            val instance = createInstanceFromPreset(
                effectSpec, fxTarget, state = state,
                overrideSpeedMasterUuid = layer.speedMasterUuid,
                overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
            )
            instance.cueId = cueData.cueId
            instance.cueStackId = stackId
            // Only a Look can own an effect, so only a Look id belongs in this field.
            // `cookEffects` already skips template layers, which makes this structurally
            // unreachable rather than merely unlikely — stated because the alternative writes
            // a template id into a field named `lookId`.
            instance.lookId = layer.source.id.takeUnless { layer.source.isTemplate }
            instance.cueLayerId = layer.layerId
            spawning += instance
        }

        // Apply immediate ad-hoc effects
        for (adHoc in immediateAdHoc) {
            val target = CueTargetDto(adHoc.target)
            val presetEffectDto = LookEffectSpec(
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
                speedMasterUuid = adHoc.speedMasterUuid,
                rateSpeedMasterUuid = adHoc.rateSpeedMasterUuid,
            )
            val fxTarget = try {
                resolveTargetForCue(state, target, presetEffectDto)
            } catch (e: Exception) {
                logger.warn(
                    "cue {}: ad-hoc effect on '{}' — target unresolvable — skipping: {}",
                    cueData.cueId, adHoc.targetKey, e.message,
                )
                null
            } ?: continue

            val instance = createInstanceFromPreset(presetEffectDto, fxTarget, state)
            instance.cueId = cueData.cueId
            instance.cueStackId = stackId
            spawning += instance
        }

        fxEngine.addEffects(spawning)
        val effectCount = spawning.size

        // Apply Layer 4 for the incoming cue. Under crossfade the incoming starts at weight 0
        // atomically with the insert; `runCrossfade` ticks it up from there. Stomp runs off
        // the same assignments so HTP/LTP and stomp overlap agree.
        // Cook the layer stack with the cue's local rows on top. Note this path previously called
        // `buildCueAssignmentsForCue` *alone*, so an immediate preset's property assignments never
        // reached Layer 4 on a stack GO — unlike `applyCue`, which concatenated both. Routing both
        // paths through `cook` fixes that asymmetry.
        val localRows = buildCueAssignmentsForCue(state.show.fixtures, cueData)
        val cooked = CueComposer.cook(
            fixtures = state.show.fixtures,
            cueId = cueData.cueId,
            priority = cueDerivedPriority(cueData),
            layers = cueData.layers,
            localRows = localRows,
            lookRegistry = state.show.lookRegistry,
            templateRegistry = state.show.templateRegistry,
        )
        val incomingStartWeight = if (useCrossfade) 0.0 else 1.0
        if (cooked.rows.isNotEmpty()) {
            fxEngine.setCueAssignments(
                cueData.cueId, cooked.rows, incomingStartWeight, cueStackId = stackId,
                stompSuppression = cooked.stompSuppression,
            )
        } else {
            fxEngine.removeCueAssignments(cueData.cueId)
        }
        // Restore outgoing to 1.0 in case a prior mid-flight crossfade left it partial.
        // `useCrossfade` already implies `outgoingCueId != null`.
        if (useCrossfade && cooked.rows.isNotEmpty()) {
            fxEngine.updateCueFadeWeights(mapOf(outgoingCueId!! to 1.0))
        }
        if (cueData.stomp) {
            fxEngine.stompForCue(
                cueData.cueId,
                buildStompOverlap(state.show.fixtures, cueData, cooked),
            )
        }

        // 4. Update active state. The GO consumed any armed standby — the next one is
        // positional again until an operator arms another.
        activeStacks[stackId] = ActiveStackState(
            stackId = stackId,
            activeCueId = cueData.cueId,
            fadeDurationMs = cueData.fadeDurationMs,
            autoAdvance = cueData.autoAdvance,
            autoAdvanceDelayMs = cueData.autoAdvanceDelayMs,
            // Only a real crossfade has elapsed time to report. On the snap path the cue is
            // already at full level, and a non-null start would have every client animate a
            // fade that never happened.
            fadeStartedAtMs = if (useCrossfade) System.currentTimeMillis() else null,
        )
        standbyCueIds.remove(stackId)

        // 5. Start crossfade or finalize
        if (useCrossfade) {
            val easingCurve = try {
                EasingCurve.valueOf(cueData.fadeCurve)
            } catch (_: Exception) {
                EasingCurve.LINEAR
            }
            val incomingCueId = if (cooked.rows.isNotEmpty()) cueData.cueId else null

            val stackState = activeStacks[stackId]
            stackState?.crossfadeOutgoingCueId = outgoingCueId
            stackState?.crossfadeJob = scope.launch {
                runCrossfade(
                    outgoingCueId = outgoingCueId,
                    incomingCueId = incomingCueId,
                    durationMs = fadeDurationMs,
                    easingCurve = easingCurve,
                )
                // Clear the in-flight marker once the fade completes normally — prevents a
                // later activation from double-dropping the already-removed outgoing.
                activeStacks[stackId]?.crossfadeOutgoingCueId = null
            }
        }

        // 6. Activate timed effects (delayed/recurring presets and ad-hoc effects)
        if (cueData.layers.any { it.enabled && it.isTimed } || timedAdHoc.isNotEmpty()) {
            state.cueTriggerManager.activateTimedEffectsForCue(
                cueId = cueData.cueId,
                cueStackId = stackId,
                priority = cueDerivedPriority(cueData),
                cueData = cueData,
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
            scheduleAutoAdvance(state, stackId, cueData.autoAdvanceDelayMs, scope)
        }

        // Every activation path lands here — REST, the MIDI surface, a cue-edit live apply and
        // the auto-advance timer — so this is the one place that has to tell the other sessions.
        publishRunState(state, stackId, transition = true)

        return ActivateResult(
            stackId = stackId,
            cueId = cueData.cueId,
            cueName = cueData.cueName,
            effectCount = effectCount,
        )
    }

    /**
     * Crossfade Layer 4 property assignments between outgoing and incoming cues.
     *
     * Each tick, the per-cue fade weight is ticked through [FxEngine.updateCueFadeWeights]
     * — outgoing from 1→0, incoming from 0→1 — so sliders / colours / positions blend
     * symmetrically rather than snap-cutting.
     *
     * Effects are NOT faded here: they are removed at the start of the cue transition
     * (see [activateCueInStack]) and incoming effects start at full intensity. This matches
     * Eos / grandMA / Hog 4 and avoids the drop-to-0 bug that came from scaling
     * OVERRIDE-blend effect outputs by `intensityMultiplier`.
     *
     * When complete, outgoing Layer 4 is dropped via [FxEngine.removeCueAssignments] and
     * incoming Layer 4 weight is pinned at 1.0.
     */
    private suspend fun runCrossfade(
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

            val outgoingWeight = 1.0 - easedProgress
            val incomingWeight = easedProgress

            // Single atomic update so the engine only republishes once per tick even when both
            // cues contributed Layer 4.
            if (outgoingCueId != null || incomingCueId != null) {
                val updates = buildMap {
                    if (outgoingCueId != null) put(outgoingCueId, outgoingWeight)
                    if (incomingCueId != null) put(incomingCueId, incomingWeight)
                }
                fxEngine.updateCueFadeWeights(updates)
            }

            if (progress >= 1.0) break
            delay(CROSSFADE_TICK_MS)
        }

        // Crossfade complete — drop outgoing Layer 4 contributions and pin incoming to 1.0.
        if (outgoingCueId != null) {
            fxEngine.removeCueAssignments(outgoingCueId)
        }
        if (incomingCueId != null) {
            fxEngine.updateCueFadeWeights(mapOf(incomingCueId to 1.0))
        }
    }

    /**
     * Advance to the next or previous cue in a stack.
     *
     * FORWARD fires whatever [effectiveNextCueId] names — the armed standby when there is one,
     * else the positional next. That's the point of the standby living here: a client no longer
     * has to choose between `advance` and `go-to` depending on what it thinks is armed, and the
     * MIDI surface's GO fires the same cue the tablet has on deck.
     *
     * Only STANDARD cues are candidates for advancement — MARKERs are skipped.
     * Respects the stack's loop setting. If at the end and not looping,
     * stays on the current cue.
     *
     * @return The result of activating the next cue, or null if the stack has no STANDARD cues
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

            val orderedCues = orderedStandardCueIds(stackId)
            if (orderedCues.isEmpty()) return@transaction null

            if (direction == AdvanceDirection.FORWARD) {
                val armed = standbyCueIds[stackId]
                // `armed in orderedCues` also covers a standby whose cue was deleted or turned
                // into a MARKER since it was armed — fall through to positional rather than
                // throwing out of a GO.
                if (armed != null && armed != currentState.activeCueId && armed in orderedCues) {
                    return@transaction armed
                }
            }

            // At a boundary of a non-looping stack, stay on the current cue.
            positionalCueId(orderedCues, currentState.activeCueId, direction, stack.loop)
                ?: currentState.activeCueId
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

    // ─── "Next" — one definition, shared by GO, the run-state broadcast and the DTO ───────

    /** Ordered STANDARD cue ids for [stackId]. Must be called inside a transaction. */
    private fun orderedStandardCueIds(stackId: Int): List<Int> =
        DaoCue.find {
            (DaoCues.cueStack eq stackId) and (DaoCues.cueType eq CueType.STANDARD.name)
        }.orderBy(DaoCues.sortOrder to SortOrder.ASC)
            .map { it.id.value }

    /**
     * The cue [direction] from [fromCueId] within [ordered], honouring [loop].
     *
     * Null when the walk runs off the end of a non-looping stack — callers decide what that
     * means: [advanceStack] stays on the current cue, [effectiveNextCueId] reports "nothing on
     * deck". A [fromCueId] that isn't in [ordered] (deleted, or turned into a MARKER) falls
     * back to the first cue.
     */
    private fun positionalCueId(
        ordered: List<Int>,
        fromCueId: Int?,
        direction: AdvanceDirection,
        loop: Boolean,
    ): Int? {
        if (ordered.isEmpty()) return null
        if (fromCueId == null) return ordered.first()
        val currentIndex = ordered.indexOf(fromCueId)
        if (currentIndex == -1) return ordered.first()
        val nextIndex = when (direction) {
            AdvanceDirection.FORWARD -> currentIndex + 1
            AdvanceDirection.BACKWARD -> currentIndex - 1
        }
        return when {
            nextIndex in ordered.indices -> ordered[nextIndex]
            !loop -> null
            direction == AdvanceDirection.FORWARD -> ordered.first()
            else -> ordered.last()
        }
    }

    /** The cue an operator has explicitly armed on [stackId], if any. */
    fun getStandbyCueId(stackId: Int): Int? = standbyCueIds[stackId]

    /**
     * The cue the next GO on [stackId] will fire: the armed standby when one is set and isn't
     * already live, else the positional next. Null at the end of a non-looping stack.
     *
     * Overload taking an already-loaded cue list, for callers that are inside a transaction and
     * hold the stack's cues (the details DTO). The rules live here, not in the caller.
     */
    fun effectiveNextCueId(stackId: Int, orderedStandardCueIds: List<Int>, loop: Boolean): Int? {
        val active = activeStacks[stackId]?.activeCueId
        val armed = standbyCueIds[stackId]
        if (armed != null && armed != active && armed in orderedStandardCueIds) return armed
        return positionalCueId(orderedStandardCueIds, active, AdvanceDirection.FORWARD, loop)
    }

    /** [effectiveNextCueId] for a stack whose cues aren't already loaded. */
    fun effectiveNextCueId(state: State, stackId: Int): Int? = transaction(state.database) {
        val stack = DaoCueStack.findById(stackId) ?: return@transaction null
        effectiveNextCueId(stackId, orderedStandardCueIds(stackId), stack.loop)
    }

    /**
     * Arm [cueId] as the cue the next GO on [stackId] fires.
     *
     * Rejects a cue from another stack, and a MARKER — the same two guards
     * [activateCueInStack] and [goToCue] apply, because arming is a deferred GO.
     */
    fun setStandby(state: State, stackId: Int, cueId: Int) {
        transaction(state.database) {
            val cue = DaoCue.findById(cueId)
                ?: throw IllegalArgumentException("Cue not found: $cueId")
            if (cue.cueStack.id.value != stackId) {
                throw IllegalArgumentException("Cue $cueId does not belong to stack $stackId")
            }
            if (cue.cueType != CueType.STANDARD.name) {
                throw IllegalArgumentException("Cannot arm a ${cue.cueType} cue")
            }
        }
        standbyCueIds[stackId] = cueId
        publishRunState(state, stackId)
    }

    /** Disarm [stackId]'s standby, leaving the positional next on deck. */
    fun clearStandby(state: State, stackId: Int) {
        if (standbyCueIds.remove(stackId) == null) return
        publishRunState(state, stackId)
    }

    /**
     * Drop any standby pointing at [cueId]. Called when a cue is deleted: [effectiveNextCueId]
     * already ignores an armed cue that is no longer a STANDARD cue of the stack, but the
     * entry would otherwise linger and `standbyCueId` would keep reporting a cue that is gone.
     */
    fun clearStandbyForCue(state: State, cueId: Int) {
        for ((stackId, armed) in standbyCueIds) {
            if (armed == cueId) clearStandby(state, stackId)
        }
    }

    /**
     * [stackId]'s run state as the desk sees it right now — see [CueRunState].
     *
     * Also the connect-time snapshot: a session that opens mid-fade reads a non-null
     * `fadeElapsedMs` and animates the remainder.
     */
    fun runStateFor(state: State, stackId: Int, transition: Boolean = false): CueRunState {
        val active = activeStacks[stackId]
        val next = effectiveNextCueId(state, stackId)
        val armed = standbyCueIds[stackId]
        val fadeElapsed = run {
            val startedAt = active?.fadeStartedAtMs ?: return@run null
            val duration = active.fadeDurationMs ?: return@run null
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= duration) null else elapsed
        }
        return CueRunState(
            projectId = state.show.project.id.value,
            stackId = stackId,
            activeCueId = active?.activeCueId,
            nextCueId = next,
            nextIsArmed = armed != null && armed == next,
            transition = transition,
            fadeDurationMs = active?.fadeDurationMs,
            fadeElapsedMs = fadeElapsed,
            // Whether the stack *will* advance, not merely whether the cue is configured to:
            // that's the question a client drawing a countdown is really asking. A paused timer
            // (a cue-edit Live session, the surface's Pause binding) or a configured cue with no
            // delay both report false.
            autoAdvance = active?.autoAdvanceJob?.isActive == true,
            autoAdvanceDelayMs = active?.autoAdvanceDelayMs,
        )
    }

    /**
     * Stacks with run state worth reporting — live, or holding an armed standby. The
     * connect-time snapshot walks these rather than every stack in the project.
     */
    fun stacksWithRunState(): Set<Int> = activeStacks.keys + standbyCueIds.keys

    /** Fan [stackId]'s run state out to every connected session. */
    private fun publishRunState(state: State, stackId: Int, transition: Boolean = false) {
        state.show.fixtures.cueRunStateChanged(runStateFor(state, stackId, transition))
    }

    /**
     * Start a stack: fire the armed standby if an operator has one on deck, else the stack's
     * first STANDARD cue.
     *
     * The standby check is what makes the first GO of a show behave like every later one —
     * arming cue 5 pre-show and pressing GO fires cue 5, not cue 1. Callers no longer have to
     * pass the armed cue back in for that to work.
     *
     * Throws [IllegalArgumentException] if the stack has no standard cues.
     */
    fun activateAtFirstCue(state: State, stackId: Int, scope: CoroutineScope = GlobalScope): ActivateResult {
        val startCueId = transaction(state.database) {
            val ordered = orderedStandardCueIds(stackId)
            val armed = standbyCueIds[stackId]
            if (armed != null && armed in ordered) armed else ordered.firstOrNull()
        } ?: throw IllegalArgumentException("Cue stack has no standard cues")

        return activateCueInStack(state, stackId, startCueId, scope)
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

        // `removeEffectsForCueStack` below wipes effects but not Layer 4 — clear the active
        // cue's assignments here so an assignment-only cue doesn't leave stale state behind.
        // Also drop the mid-flight crossfade's outgoing if one was in-flight.
        stackState?.activeCueId?.let { fxEngine.removeCueAssignments(it) }
        stackState?.crossfadeOutgoingCueId?.let { fxEngine.removeCueAssignments(it) }

        // Deactivate triggers for the active cue in this stack
        appState?.cueTriggerManager?.deactivateTriggersForStack(stackId)

        val removed = fxEngine.removeEffectsForCueStack(stackId)
        // A stopped stack has nothing on deck: the standby went with it. Published after the
        // teardown so the frame describes the stack as it now is.
        standbyCueIds.remove(stackId)
        appState?.let { publishRunState(it, stackId) }
        return removed
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
    fun pauseAutoAdvance(appState: State, stackId: Int): Boolean {
        val stackState = activeStacks[stackId] ?: return false
        val job = stackState.autoAdvanceJob ?: return false
        if (!job.isActive) return false
        job.cancel()
        stackState.autoAdvanceJob = null
        // Clients draw the countdown but no longer drive it, so a paused timer they weren't told
        // about is a bar counting down to a step that never comes.
        publishRunState(appState, stackId)
        return true
    }

    /**
     * Reschedule auto-advance on an active stack, re-reading the active cue's configuration.
     *
     * The counterpart to [pauseAutoAdvance], which a surface PAUSE binding still calls. Its own
     * caller was `cueEdit.endEdit`, retired by sweep item D1 — so nothing in production resumes a
     * paused stack today. Kept because a surface RESUME binding is the obvious next caller and
     * deleting half a pause/resume pair is worse than an unused half. No-op if:
     * - the stack isn't active
     * - the active cue has no `autoAdvance` or no `autoAdvanceDelayMs`
     * - an auto-advance timer is already running (we don't stack timers)
     *
     * The fresh delay starts from *now* — we don't track remaining time from the original
     * schedule. Acceptable because the operator just spent time editing; re-starting the
     * countdown is more predictable than racing a stale deadline.
     */
    fun resumeAutoAdvance(state: State, stackId: Int, scope: CoroutineScope = GlobalScope): Boolean {
        val stackState = activeStacks[stackId] ?: return false
        if (stackState.autoAdvanceJob?.isActive == true) return false

        val cueConfig = transaction(state.database) {
            val cue = DaoCue.findById(stackState.activeCueId) ?: return@transaction null
            if (!cue.autoAdvance) return@transaction null
            val delay = cue.autoAdvanceDelayMs ?: return@transaction null
            delay
        } ?: return false

        scheduleAutoAdvance(state, stackId, cueConfig, scope)
        publishRunState(state, stackId)
        return true
    }

    /** Launch and record a fresh auto-advance timer for [stackId]. */
    private fun scheduleAutoAdvance(state: State, stackId: Int, delayMs: Long, scope: CoroutineScope) {
        activeStacks[stackId]?.autoAdvanceJob = scope.launch {
            delay(delayMs)
            try {
                advanceStack(state, stackId, AdvanceDirection.FORWARD, scope)
            } catch (e: Exception) {
                // Stack may have been deactivated or cue deleted
                logger.warn("stack {}: auto-advance failed — {}", stackId, e.message)
            }
        }
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
            cue.cueStack.id.value
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

    // `createInstanceForStack` stood here. It was `createInstanceFromPreset` with a `stackId`
    // parameter, which existed only to resolve `P1` against the stack's positional colour list.
    // With that list gone the two bodies were identical — and this copy had lost the
    // `prewarmTemplateColours` call the shared one makes, so a stack GO left the first `tmpl:`
    // resolve to the 50 Hz tick loop, where it opens a transaction.
}
