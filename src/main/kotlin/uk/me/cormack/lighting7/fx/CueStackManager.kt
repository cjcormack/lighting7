@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.perf.DeskMetrics
import uk.me.cormack.lighting7.telemetry.TraceSampling
import uk.me.cormack.lighting7.telemetry.Tracing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("CueStackManager")

/**
 * Manages runtime state for active cue stacks.
 *
 * Tracks which entry is live in each stack and handles auto-advance timers. The entry is the
 * playback key (cue model plan P2): a stack's live entry, its standby, a GO TO, a preview and an
 * activate all name one, so a cue twice in one stack (D7) is never ambiguous.
 *
 * What a cue's contribution does on stage is **not** this class's call: a stack's live entry is a
 * *hold* on its cue, and [holds] — the hold registry, `fx/CueHolds.kt` — decides when a cue comes on,
 * goes off and at what priority it composes (P3, D3). A GO from entry *a* to entry *b* is
 * `acquire(cue(b), Stack(s, b))` then `release(cue(a), Stack(s, a))`, sharing one crossfade; a cue
 * another hold still keeps live stays on stage while this stack moves on.
 *
 * Supports crossfades: when a cue has `fadeDurationMs` configured, its outgoing and incoming Layer 4
 * assignments crossfade using per-cue fade weights and the configured [EasingCurve], and its effects
 * fade by size over the same time and curve (effects plan D1) — the outgoing cue's shrinking to
 * nothing and removed, the incoming cue's growing from nothing, and an effect the two cues share
 * running on untouched (P4). A 0 s fade, a first GO and a stack stop snap, values and effects alike.
 * Auto-advance and crossfade are per-cue settings.
 *
 * Two halves of cue-stack running live next door rather than here, because neither needs the
 * firing machinery and both were making this class hard to read:
 * - [CueCrossfadeDriver] ticks the Layer 4 fade envelope (`crossfades`).
 * - [CueRunStateTracker] owns standby arming, the definition of "next", and the [CueRunState]
 *   broadcast ([runState]).
 */
class CueStackManager(
    private val fxEngine: FxEngine,
    private val projectId: Int? = null,
    /** Where the registry's frames go — `State.cueHoldsFrame`, which outlives this show. */
    holdsSink: MutableStateFlow<CueHolds.Frame> = MutableStateFlow(CueHolds.Frame.EMPTY),
    /** After every hold change: the surfaces' cue-pad LEDs. */
    onHoldsChanged: () -> Unit = {},
    /** How [CueHolds] edits an effect run it hands on in place — `routes.alignFx`. */
    fxAligner: FxAligner,
) {
    private data class ActiveStackState(
        val stackId: Int,
        val activeEntryId: Int,
        val activeCueId: Int,
        var autoAdvanceJob: Job? = null,
        // The live cue's fade / auto-advance configuration, copied at activation so
        // [CueRunStateTracker.runStateFor] can describe the stack without going back to the DB.
        var fadeDurationMs: Long? = null,
        var autoAdvance: Boolean = false,
        var autoAdvanceDelayMs: Long? = null,
        // When the live cue's crossfade started, or null when nothing faded (no outgoing cue, or a
        // GO between two holds of cues that were already on and stay on). Never cleared once set —
        // [CueRunStateTracker.runStateFor] treats an elapsed time past the duration as "settled".
        var fadeStartedAtMs: Long? = null,
        // Where the live entry stood once it has been removed from the list (cue model plan D16): the
        // stack still holds its cue, and its next GO fires the entry that followed it. Set in place,
        // like the fields above, so a timer another thread is attaching lands on the live instance.
        @Volatile var detachedAt: EntryPlace? = null,
    )

    private val activeStacks = ConcurrentHashMap<Int, ActiveStackState>()

    /**
     * Orders rank changes against each other (busk-stack plan P3): a GO taking its place against a
     * release's end giving one up ([starting] are the stacks a GO is bringing on), and every restamp
     * of what the moved stacks have on stage ([applyRankChanges]), which reads the places as they
     * stand inside it so the last restamp is always of the latest ranks.
     */
    private val rankLock = Any()
    private val starting = HashSet<Int>()

    /** The live-stack table as the run-state half reads it — a snapshot per stack. */
    private val liveStacks = object : LiveStacks {
        override fun liveStack(stackId: Int): LiveStack? = activeStacks[stackId]?.let {
            LiveStack(
                activeEntryId = it.activeEntryId,
                activeCueId = it.activeCueId,
                fadeDurationMs = it.fadeDurationMs,
                fadeStartedAtMs = it.fadeStartedAtMs,
                autoAdvanceRunning = it.autoAdvanceJob?.isActive == true,
                autoAdvanceDelayMs = it.autoAdvanceDelayMs,
                detachedAt = it.detachedAt,
            )
        }

        override fun liveStackIds(): Set<Int> = activeStacks.keys
    }

    /**
     * Standby arming, the definition of "next", and the [CueRunState] broadcast. Public because
     * the routes, the WS snapshot and the stack DTO all ask it questions directly.
     */
    val runState: CueRunStateTracker = CueRunStateTracker(liveStacks)

    /**
     * The show's Move in dark ([MoveInDarkService]), told of every GO, standby change and stop. Set
     * by `Show` once both exist; null in a manager built without a show.
     */
    @Volatile var moveInDark: MoveInDarkService? = null
        set(value) {
            field = value
            runState.onNextMayHaveMoved = { stackId -> value?.replan(stackId) }
            holds.onEntriesRestamped = { priorities -> value?.onEntriesRestamped(priorities) }
        }

    /** Layer 4 crossfade envelopes. Owns its own per-stack table — see [CueCrossfadeDriver]. */
    private val crossfades = CueCrossfadeDriver(fxEngine).also { driver ->
        // A stopped stack keeps its rank while its release fades (busk-stack plan §3 "Priority").
        driver.onReleaseEnded = { stackId ->
            // Not while a GO is bringing the stack back: that GO has just taken its place.
            val moved = synchronized(rankLock) {
                if (activeStacks.containsKey(stackId) || stackId in starting) null else holds.ranks.remove(stackId)
            }
            moved?.let(::applyRankChanges)
        }
    }

    /** What holds each cue live — stacks and loose presses alike. See [CueHolds]. */
    val holds: CueHolds = CueHolds(fxEngine, crossfades, projectId, holdsSink, onHoldsChanged, fxAligner)
        .also { fxEngine.layerResolver.stackAt = it.ranks::stackAt }

    /**
     * Each stack's FX amount (effects plan D10): a runtime weight on the effects its cues run,
     * 100 % at load. Per show, so a project switch drops it; a reload carries it (`ShowCarry`).
     */
    val fxAmounts: StackFxAmounts = StackFxAmounts(holds.ranks).also { fxEngine.stackFxAmounts = it }

    /**
     * Set [stackId]'s FX amount, 0–1, and answer it as held. Every change re-sends provenance —
     * coalesced there, and as a cue-fade-only update, since an FX amount moves no programmer value — because
     * any weight can change what is on stage: a setting effect paints from half weight up.
     */
    fun setFxAmount(stackId: Int, amount: Double): Double {
        val (before, after) = fxAmounts.change(stackId, amount)
        if (before != after) fxEngine.provenance.emitUpdate(cueFadeOnly = true)
        return after
    }

    /**
     * Each stack uuid this show has resolved to its id, or to [NO_STACK]. Kept for the show's life:
     * a stack's id is fixed while the show runs, and anything that re-creates the stacks (a sync
     * pull) reloads, which builds a new manager.
     */
    private val stackIdsByUuid = ConcurrentHashMap<String, Int>()

    /**
     * [stackUuid]'s id among this show's stacks, or null — how a stack binding (a MIDI fader, its
     * feedback) finds its stack. A fader moves at control rate, so the lookup is kept.
     */
    fun stackIdForUuid(database: org.jetbrains.exposed.v1.jdbc.Database, stackUuid: String): Int? {
        val projectId = projectId ?: return null
        val id = stackIdsByUuid.getOrPut(stackUuid) {
            val uuid = runCatching { java.util.UUID.fromString(stackUuid) }.getOrNull()
            uuid?.let {
                transaction(database) {
                    DaoCueStack.find { (DaoCueStacks.uuid eq it) and (DaoCueStacks.project eq projectId) }.firstOrNull()?.id?.value
                }
            } ?: NO_STACK
        }
        return id.takeIf { it != NO_STACK }
    }

    /** [stackId] is deleted: its FX amount and its uuid's resolution go with it. */
    fun forgetStack(stackId: Int) {
        fxAmounts.forget(stackId)
        runState.clearReleasedAtEnd(stackId)
        stackIdsByUuid.values.removeIf { it == stackId }
    }

    enum class AdvanceDirection { FORWARD, BACKWARD }

    /**
     * What a GO did. [released] when it went past the last cue entry of a stack set to Release
     * (busk-stack plan D3), stopping it: [entryId] and [cueId] are then where it stood.
     */
    data class ActivateResult(
        val stackId: Int,
        val entryId: Int,
        val cueId: Int,
        val cueName: String,
        val fxCount: Int,
        val released: Boolean = false,
    )

    /**
     * GO to entry [entryId] of stack [stackId] (cue model plan P2).
     *
     * 1. Cancel any in-flight crossfade on the stack and the auto-advance timer
     * 2. Take the incoming entry's hold on its cue — on the cue's first hold that spawns its effects
     *    (growing from nothing if crossfading, the outgoing cue's continuing ones adopted) and Layer 4
     *    (at weight 0 if crossfading), its timers, triggers and events ([CueHolds.acquire])
     * 3. Let the outgoing entry's hold go — on the cue's last hold that removes its effects, or sets
     *    them shrinking to nothing, and either drops its Layer 4 or leaves it for the crossfade
     *    ([CueHolds.release])
     * 4. Start the crossfade over whichever side really moved
     * 5. Start the incoming entry's auto-advance timer if configured
     *
     * When both entries name the same cue (D7) nothing on stage moves, and the follow timer is the
     * incoming entry's. [fireEvents] false (GO BACK, a reload) fires no events (D15). [setTempo]
     * false (a reload) sets none of the cue's speed masters, which every other GO sets (S16, D20).
     *
     * [operator] false — a follow, a reload's re-GO — keeps the stack's rank in its band; every other
     * GO is an operator's, and takes the top of it (busk-stack plan D5). Refused with
     * [CueStackLimitException] when the band is full.
     */
    fun activateEntry(
        state: State,
        stackId: Int,
        entryId: Int,
        scope: CoroutineScope = GlobalScope,
        fireEvents: Boolean = true,
        setTempo: Boolean = true,
        operator: Boolean = true,
    ): ActivateResult {
        val started = System.nanoTime()
        return Tracing.transaction(TraceSampling.OP_CUE_GO, "cue.go") { span ->
            span?.setData("cue.stack_id", stackId)
            span?.setData("cue.entry_id", entryId)
            activate(state, stackId, entryId, scope, fireEvents, setTempo, operator)
        }.also { DeskMetrics.global.cueGo.record((System.nanoTime() - started) / 1_000_000.0) }
    }

    /** What a GO reads of its stack beside the cue: its band, and whether it replaces the show. */
    private data class StackGo(val band: StackRanks.Band, val replaces: Boolean)

    private fun activate(
        state: State,
        stackId: Int,
        entryId: Int,
        scope: CoroutineScope,
        fireEvents: Boolean,
        setTempo: Boolean,
        operator: Boolean,
    ): ActivateResult {
        val (cueData, moveInDarkGo, stackGo) = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: throw IllegalArgumentException("Cue stack not found: $stackId")
            if (stack.type == CueStackType.SEPARATOR.name) {
                throw IllegalArgumentException("Cannot activate a separator")
            }
            // The caller names the stack and the entry (cue model plan P2, P7): its position, its
            // number and its follow are where the GO lands. A marker names no cue and is never gone to.
            val entry = DaoCueStackEntry.findById(entryId)?.takeIf { it.stackId == stackId }
                ?: throw IllegalArgumentException("Entry $entryId is not in stack $stackId")
            val cue = entry.cue
                ?: throw IllegalArgumentException("Entry $entryId is a marker, which cannot be gone to")
            // The one builder — see `buildCueApplyData`.
            val settings = stack.moveInDarkSettings
            val active = settings.on || anyCueMovesOnItsOwn(runState.orderedCueEntries(stackId).map { it.cueId })
            Triple(
                buildCueGoData(cue, entry),
                MoveInDarkService.Go(settings, cue.moveInDarkSettings, active),
                StackGo(StackRanks.bandOf(stack.inRunningOrder), stack.replacesShow),
            )
        }

        // Its place first, so a full band refuses the GO before anything moves.
        val moved = synchronized(rankLock) {
            (if (operator) holds.ranks.takeTop(stackId, stackGo.band) else holds.ranks.keep(stackId, stackGo.band))
                .also { starting += stackId }
        }
        try {
            return activatePlaced(state, stackId, entryId, scope, fireEvents, setTempo, cueData, moveInDarkGo, stackGo, moved)
        } catch (e: Exception) {
            // A GO that failed before the stack was live leaves it nowhere.
            if (!activeStacks.containsKey(stackId) && !crossfades.isReleasing(stackId)) applyRankChanges(holds.ranks.remove(stackId))
            throw e
        } finally {
            synchronized(rankLock) { starting -= stackId }
        }
    }

    private fun activatePlaced(
        state: State,
        stackId: Int,
        entryId: Int,
        scope: CoroutineScope,
        fireEvents: Boolean,
        setTempo: Boolean,
        cueData: CueApplyData,
        moveInDarkGo: MoveInDarkService.Go,
        stackGo: StackGo,
        moved: Map<Int, StackRanks.Place>,
    ): ActivateResult {
        // Cancel any in-progress crossfade for this stack — the driver drops the abandoned
        // outgoing cue's Layer 4 rows unless a hold has it again, so the new activation starts clean.
        crossfades.cancel(stackId, holds::isHeld, returning = cueData.cueId)
        // Then every stack the GO moved, this one's live entry among them, composes at its new place.
        applyRankChanges(moved)
        val existingState = activeStacks[stackId]
        existingState?.autoAdvanceJob?.cancel()

        val fadeDurationMs = cueData.fadeDurationMs ?: 0L
        // A GO onto the entry the stack is already on is a deliberate re-fire (the activate route's
        // word). Its outgoing and incoming holds are the same key, so the order below would restamp
        // the hold and then release it — taking the cue off while the stack reports it live. So the
        // re-fire lets its hold go first and takes it again, snapping: the cue re-fires when this
        // was its only hold, and only restamps while another stack or a press still holds it.
        val refire = existingState?.activeEntryId == entryId
        if (refire) {
            holds.release(state, existingState.activeCueId, CueHold.Stack(stackId, entryId), CueHolds.Departure.SNAP)
        }
        // Layer 4 rows and effects alike: the effects' envelopes ramp over the same time and curve.
        val useCrossfade = fadeDurationMs > 0 && existingState != null && !refire
        val easingCurve = try {
            EasingCurve.valueOf(cueData.fadeCurve)
        } catch (_: Exception) {
            EasingCurve.LINEAR
        }

        // The cue this GO takes off, when it does: its last hold is this stack's live entry. Its
        // continuing effects are the incoming cue's to adopt (effects plan P4) — handed over before
        // it is released, since the incoming hold is taken first.
        val leavingCueId = existingState?.takeIf { !refire && it.activeCueId != cueData.cueId }
            ?.takeIf { holds.holdsOf(it.activeCueId) == listOf(CueHold.Stack(stackId, it.activeEntryId)) }
            ?.activeCueId

        // Move in dark's contributor goes above the incoming cue before it arrives.
        val goPriority = stackPriority(holds.ranks.placeOf(stackId), cueData.sortOrder)
        moveInDark?.beforeGo(stackId, entryId, goPriority, moveInDarkGo)
        // The incoming entry's hold first, then the outgoing's: a GO between two entries of one cue
        // then never lets it go, and nothing on stage moves.
        val incoming = holds.acquire(
            state, cueData, CueHold.Stack(stackId, entryId), goPriority,
            arrival = if (useCrossfade) CueHolds.Arrival.FADE_IN else CueHolds.Arrival.SNAP,
            scope = scope, fireEvents = fireEvents, stackTag = stackId,
            fadeMs = fadeDurationMs, curve = easingCurve, adoptFrom = leavingCueId,
            replaces = stackGo.replaces,
        )
        // Tempo is state the cue re-asserts on every GO into it, held elsewhere or not (D20).
        if (setTempo) fxEngine.speedMasters.setCueTempos(cueData.cueLabel, cueData.goSpeedMasters)
        val outgoing = existingState?.takeIf { !refire }?.let {
            holds.release(
                state, it.activeCueId, CueHold.Stack(stackId, it.activeEntryId),
                if (useCrossfade) CueHolds.Departure.FADE_OUT else CueHolds.Departure.SNAP,
                fadeMs = fadeDurationMs, curve = easingCurve,
            )
        }
        // A press took the outgoing cue between the two holds, so it stays on: what the incoming cue
        // took over goes back to it, and the incoming cue runs its own.
        if (outgoing?.last != true) holds.giveBack(state, incoming.adopted, respawnFor = cueData.cueId)
        // Only the side that really moved fades: an incoming cue already held elsewhere is at full,
        // an outgoing cue still held elsewhere stays at full. Either side's rows or effects make it a
        // fade, so an effect-only cue fades too (and a cancel can find what it was fading).
        val incomingFades = useCrossfade && incoming.first && (incoming.hasRows || incoming.fadingFx > 0)
        val outgoingFades = useCrossfade && outgoing != null && (outgoing.fadingRows || outgoing.fadingFx > 0)

        // Update active state. The GO consumed any armed standby — the next one is positional again
        // until an operator arms another.
        activeStacks[stackId] = ActiveStackState(
            stackId = stackId,
            activeEntryId = entryId,
            activeCueId = cueData.cueId,
            fadeDurationMs = cueData.fadeDurationMs,
            autoAdvance = cueData.autoAdvance,
            autoAdvanceDelayMs = cueData.autoAdvanceDelayMs,
            // Only a real crossfade has elapsed time to report. On the snap path the cue is
            // already at full level, and a non-null start would have every client animate a
            // fade that never happened.
            fadeStartedAtMs = if (incomingFades || outgoingFades) System.currentTimeMillis() else null,
        )
        runState.consumeStandby(stackId)
        runState.clearReleasedAtEnd(stackId)
        moveInDark?.afterGo(stackId, entryId, cueData.cueId, if (incomingFades || outgoingFades) fadeDurationMs else 0L, cueData.cueLabel)

        if (incomingFades || outgoingFades) {
            crossfades.start(
                stackId = stackId,
                outgoingCueId = existingState.activeCueId.takeIf { outgoingFades },
                incomingCueId = cueData.cueId.takeIf { incomingFades },
                durationMs = fadeDurationMs,
                easingCurve = easingCurve,
                scope = scope,
            )
        }

        // The incoming entry's follow, even when it names the cue that was already live (D7).
        if (cueData.autoAdvance && cueData.autoAdvanceDelayMs != null) {
            scheduleAutoAdvance(state, stackId, cueData.autoAdvanceDelayMs, scope)
        }

        // Every activation path lands here — REST, the MIDI surface and the auto-advance timer —
        // so this is the one place that has to tell the other sessions.
        runState.publishRunState(state, stackId, transition = true)
        // And the scenery: the stack's set and its entries down to this one, tracked from the top
        // of the list, with this cue's own changes moving on their clocks (stage-view plan session 8).
        Tracing.span("scenery.resolve") { state.sceneryService.onEntryLive(stackId, entryId, cueData.cueId) }

        return ActivateResult(
            stackId = stackId,
            entryId = entryId,
            cueId = cueData.cueId,
            cueName = cueData.cueName,
            fxCount = incoming.fxCount,
        )
    }

    /**
     * Advance to the next or previous entry in a stack.
     *
     * FORWARD fires whatever [CueRunStateTracker.effectiveNext] names — the armed standby when
     * there is one, else the positional next. That's the point of the standby living here: a client
     * no longer has to choose between `advance` and `go-to` depending on what it thinks is armed,
     * and the MIDI surface's GO fires the same entry the tablet has on deck.
     *
     * Only cue entries are candidates — markers are skipped. Respects the stack's loop setting. If
     * at the end and not looping, stays on the current entry, or — FORWARD on a stack set to Release
     * (busk-stack plan D3) — stops it as [stopStack] does, answering [ActivateResult.released]. BACKWARD
     * fires no events (D15).
     *
     * @return The result of activating the next entry, or null if the stack has no cue entries
     */
    fun advanceStack(
        state: State,
        stackId: Int,
        direction: AdvanceDirection,
        scope: CoroutineScope = GlobalScope,
        /** False for a follow, which keeps the stack's rank ([activateEntry]). */
        operator: Boolean = true,
    ): ActivateResult? {
        val currentState = activeStacks[stackId]
            ?: throw IllegalStateException("Stack $stackId is not active")

        val (target, release) = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId)
                ?: throw IllegalArgumentException("Cue stack not found: $stackId")

            val ordered = runState.orderedCueEntries(stackId)
            if (ordered.isEmpty()) return@transaction null

            // FORWARD is the shared "next" rule — armed standby, else positional — so a GO
            // fires exactly the entry every session has on deck. At a boundary of a non-looping
            // stack, stay on the current entry, or release a stack set to.
            val found = when (direction) {
                AdvanceDirection.FORWARD -> runState.nextFrom(
                    activeEntryId = currentState.activeEntryId,
                    stackId = stackId,
                    ordered = ordered,
                    loop = stack.loop,
                    detachedAt = currentState.detachedAt,
                )
                AdvanceDirection.BACKWARD -> runState.positional(
                    ordered, currentState.activeEntryId, direction, stack.loop,
                    runState.detachedIndex(stackId, currentState.detachedAt),
                )
            }
            found to (found == null && direction == AdvanceDirection.FORWARD && stack.releaseAtEnd)
        } ?: return null

        if (release) {
            val cueName = transaction(state.database) { DaoCue.findById(currentState.activeCueId)?.name ?: "Unknown" }
            stop(state, stackId, scope, atEnd = true)
            return ActivateResult(
                stackId = stackId,
                entryId = currentState.activeEntryId,
                cueId = currentState.activeCueId,
                cueName = cueName,
                fxCount = 0,
                released = true,
            )
        }
        val next = target ?: CueEntryRef(currentState.activeEntryId, currentState.activeCueId)

        // Already on this entry — no-op
        if (next.entryId == currentState.activeEntryId) {
            return ActivateResult(
                stackId = stackId,
                entryId = next.entryId,
                cueId = next.cueId,
                cueName = transaction(state.database) {
                    DaoCue.findById(next.cueId)?.name ?: "Unknown"
                },
                fxCount = 0,
            )
        }

        return activateEntry(state, stackId, next.entryId, scope, fireEvents = direction == AdvanceDirection.FORWARD, operator = operator)
    }

    /**
     * Go to a specific entry within a stack (arbitrary jump).
     *
     * Throws IllegalArgumentException (HTTP 400 at the route) when the entry is not in [stackId], or
     * is a marker.
     */
    fun goToEntry(
        state: State,
        stackId: Int,
        entryId: Int,
        scope: CoroutineScope = GlobalScope,
    ): ActivateResult = activateEntry(state, stackId, entryId, scope)

    /**
     * Press GO: fire whatever [stackId] has on deck, starting the stack if it is stopped.
     *
     * The whole of GO's meaning, in one place. Both halves already honour the armed standby, so
     * what this adds is the branch between them — and that branch is the thing every surface has
     * to agree on: a MIDI GO button, a tablet, and the AI's `go_cue_stack` all press the same
     * button, and a second copy of "active ? advance : activate" is a show where they disagree.
     *
     * @return null only when the stack holds no cue entries — at the end of a non-looping stack
     *   [advanceStack] stays on the live entry and returns it, or releases a stack set to Release.
     * @throws IllegalArgumentException if a stopped stack has no cue entries to start at.
     */
    fun go(state: State, stackId: Int, scope: CoroutineScope = GlobalScope): ActivateResult? =
        if (isStackActive(stackId)) {
            advanceStack(state, stackId, AdvanceDirection.FORWARD, scope)
        } else {
            activateAtFirstCue(state, stackId, scope)
        }

    /**
     * Start a stack: fire the armed standby if an operator has one on deck, else the stack's
     * first cue entry.
     *
     * The standby check is what makes the first GO of a show behave like every later one —
     * arming Q5 pre-show and pressing GO fires Q5, not Q1. Callers no longer have to pass the
     * armed entry back in for that to work.
     *
     * Throws [IllegalArgumentException] if the stack has no cue entries.
     */
    fun activateAtFirstCue(state: State, stackId: Int, scope: CoroutineScope = GlobalScope): ActivateResult {
        val startEntryId = transaction(state.database) {
            val ordered = runState.orderedCueEntries(stackId)
            val armed = runState.getStandbyEntryId(stackId)
            if (armed != null && ordered.any { it.entryId == armed }) armed else ordered.firstOrNull()?.entryId
        } ?: throw IllegalArgumentException("Cue stack has no standard cues")

        return activateEntry(state, stackId, startEntryId, scope)
    }

    /**
     * Deactivate a stack at once — let its live entry's hold go and clean up state. The cue comes off
     * only if no other hold keeps it live (D3). A release in flight is cut short. The stop an operator
     * makes is [stopStack]; this is a delete's, an undo's, and the show's advance and go-to.
     *
     * @return Number of effects removed
     */
    fun deactivateStack(stackId: Int, appState: State): Int {
        val stackState = activeStacks.remove(stackId)
        stackState?.autoAdvanceJob?.cancel()
        // Cancelling also drops the mid-flight crossfade's outgoing assignments, if no hold has them.
        crossfades.cancel(stackId, holds::isHeld)

        val removed = stackState?.let {
            holds.release(appState, it.activeCueId, CueHold.Stack(stackId, it.activeEntryId), CueHolds.Departure.SNAP).fxRemoved
        } ?: 0
        applyRankChanges(holds.ranks.remove(stackId))
        return removed + afterStop(appState, stackId)
    }

    /**
     * Stop [stackId] as an operator does (busk-stack plan D6): it reads stopped at once, and the cues
     * it had on stage — its live cue and, mid-crossfade, the cue that fade was taking out — fade from
     * the weight they stood at to 0 over its release time, or stay at full where another hold keeps
     * them. A stack already stopped and still releasing is left to finish. A release time of 0 is
     * [deactivateStack]'s snap.
     *
     * @return Number of effects removed
     */
    fun stopStack(state: State, stackId: Int, scope: CoroutineScope = GlobalScope): Int = stop(state, stackId, scope, atEnd = false)

    private fun stop(state: State, stackId: Int, scope: CoroutineScope, atEnd: Boolean): Int {
        if (!activeStacks.containsKey(stackId)) {
            return if (crossfades.isReleasing(stackId)) 0 else deactivateStack(stackId, state)
        }
        val releaseMs = transaction(state.database) { DaoCueStack.findById(stackId)?.releaseTime?.toMillis() } ?: 0L
        if (atEnd) runState.markReleasedAtEnd(stackId)
        if (releaseMs <= 0) return deactivateStack(stackId, state)
        val stackState = activeStacks.remove(stackId) ?: return 0
        // A follow releasing the stack runs in this job, past its last suspension: cancelling it is harmless.
        stackState.autoAdvanceJob?.cancel()
        val inFlight = crossfades.detach(stackId)
        val curve = EasingCurve.LINEAR
        val cueLayer = fxEngine.cueLayer

        val fromWeights = HashMap<Int, Double>()
        val liveCueId = stackState.activeCueId
        val liveFrom = cueLayer.fadeWeightOf(liveCueId)
        val released = holds.release(
            state, liveCueId, CueHold.Stack(stackId, stackState.activeEntryId), CueHolds.Departure.RELEASE,
            fadeMs = releaseMs, curve = curve,
        )
        // An effects-only cue is held in the release too, though it has no rows to ramp: its effects
        // shrink over the same time, and the stack must keep its rank until they have.
        if (released.last) {
            if (released.fadingRows || released.fadingFx > 0) fromWeights[liveCueId] = liveFrom
        } else {
            pinAtFull(liveCueId)
        }
        // What the GO fade was taking out keeps going out from where it stands, its leaving FX re-ramped
        // to the release time so rows and FX go together; a hold that has it again keeps it at full.
        inFlight?.outgoingCueId?.let { cueId ->
            when {
                holds.isHeld(cueId) -> pinAtFull(cueId)
                cueLayer.hasAssignments(cueId) || fxEngine.leavingCueFx(cueId).isNotEmpty() ->
                    fromWeights[cueId] = cueLayer.fadeWeightOf(cueId)
            }
            if (!holds.isHeld(cueId)) {
                fxEngine.fadeOutFx(fxEngine.leavingCueFx(cueId), releaseMs, curve)
            }
        }
        // The stack keeps its rank while its release fades, and gives it up when the release ends.
        if (fromWeights.isNotEmpty()) {
            crossfades.startRelease(stackId, fromWeights, releaseMs, curve, scope)
        } else {
            applyRankChanges(holds.ranks.remove(stackId))
        }
        return released.fxRemoved + afterStop(state, stackId)
    }

    /**
     * [stackId] has joined the running order ([inRunningOrder]) or left it: while it runs or releases
     * it takes the top of its new band (busk-stack plan P3). A full band leaves it where it is, logged.
     */
    fun onBandChanged(stackId: Int, inRunningOrder: Boolean) {
        val place = holds.ranks.placeOf(stackId) ?: return
        val band = StackRanks.bandOf(inRunningOrder)
        if (place.band == band) return
        try {
            applyRankChanges(holds.ranks.takeTop(stackId, band))
        } catch (e: CueStackLimitException) {
            logger.warn("stack {}: {}", stackId, e.message)
        }
    }

    /**
     * The stacks in [moved] compose at their new places (busk-stack plan P3): their holds, then what a
     * crossfade or a release of theirs still has fading — rows and leaving effects alike, at the same
     * entry position in the new rank — so a fading cue never lands in a rank another stack now holds;
     * then Move in dark's contributors.
     */
    internal fun applyRankChanges(changed: Map<Int, StackRanks.Place>) {
        if (changed.isEmpty()) return
        synchronized(rankLock) { restampAtPlaces(changed.keys) }
    }

    /** [applyRankChanges]'s body, at each stack's place now, under [rankLock]. A stack with none has gone. */
    private fun restampAtPlaces(stackIds: Set<Int>) {
        val moved = stackIds.mapNotNull { id -> holds.ranks.placeOf(id)?.let { id to it } }.toMap()
        if (moved.isEmpty()) return
        holds.restampStacks(moved)
        val fading = HashMap<Int, Int>()
        for ((stackId, place) in moved) {
            for (cueId in crossfades.fadingCuesOf(stackId)) {
                if (holds.isHeld(cueId)) continue
                val priority = fxEngine.cueLayer.publishedPriorityOf(cueId)
                    ?: fxEngine.leavingCueFx(cueId).firstOrNull()?.priority
                    ?: continue
                fading[cueId] = rebasedPriority(priority, place)
            }
        }
        fxEngine.repriorityCues(fading)
        moveInDark?.onStacksMoved(moved)
    }

    private fun pinAtFull(cueId: Int) {
        fxEngine.cueLayer.updateFadeWeights(mapOf(cueId to 1.0))
        fxEngine.settleFx(fxEngine.cueFx(cueId))
    }

    /** What every stop does once the stack's own cues are let go: its cue-less FX, standby, scenery and the arm. */
    private fun afterStop(appState: State, stackId: Int): Int {
        val removed = fxEngine.removeFxForCueStack(stackId)
        moveInDark?.onStackStopped(stackId)
        // A stopped stack has nothing on deck: the standby went with it. Published after the
        // teardown so the frame describes the stack as it now is.
        runState.consumeStandby(stackId)
        runState.publishRunState(appState, stackId)
        // The stack's set and its cues' scenery let go.
        appState.sceneryService.onStackStopped(stackId)
        // And the desk's arm: a stack stopping is a stop (stage-view plan session 9, D16).
        appState.effectsService.onStackStopped(stackId)
        return removed
    }

    /** The entry live in [stackId], or null if the stack is not active. */
    fun getActiveEntryId(stackId: Int): Int? = activeStacks[stackId]?.activeEntryId

    /**
     * Entry [entryId] is being removed from [stackId]'s list at [place] (cue model plan D16). If it is
     * the stack's live entry, the stack keeps holding its cue — nothing on stage moves — and plays on
     * from [place]: the next GO fires the entry that followed it and lets the detached hold go.
     * Harmless before the removal commits: [place] is read only once the entry is gone.
     */
    fun detachEntry(stackId: Int, entryId: Int, place: EntryPlace) {
        activeStacks[stackId]?.takeIf { it.activeEntryId == entryId }?.detachedAt = place
    }

    /** Every live stack whose live entry was removed, and where it stood ([detachEntry]). */
    fun detachedPlaces(): Map<Int, EntryPlace> =
        activeStacks.values.mapNotNull { live -> live.detachedAt?.let { live.stackId to it } }.toMap()

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
        runState.publishRunState(appState, stackId)
        return true
    }

    /**
     * Reschedule auto-advance on an active stack, re-reading the live entry's follow.
     *
     * The counterpart to [pauseAutoAdvance], which a surface PAUSE binding still calls. Nothing in
     * production resumes a paused stack today; it is kept because a surface RESUME binding is the
     * obvious next caller and deleting half a pause/resume pair is worse than an unused half. No-op if:
     * - the stack isn't active
     * - the live entry has no `autoAdvance` or no `autoAdvanceDelayMs`
     * - an auto-advance timer is already running (we don't stack timers)
     *
     * The fresh delay starts from *now* — we don't track remaining time from the original
     * schedule. Acceptable because the operator paused it deliberately; re-starting the
     * countdown is more predictable than racing a stale deadline.
     */
    fun resumeAutoAdvance(state: State, stackId: Int, scope: CoroutineScope = GlobalScope): Boolean {
        val stackState = activeStacks[stackId] ?: return false
        if (stackState.autoAdvanceJob?.isActive == true) return false

        val cueConfig = transaction(state.database) {
            val entry = DaoCueStackEntry.findById(stackState.activeEntryId) ?: return@transaction null
            if (!entry.autoAdvance) return@transaction null
            entry.autoAdvanceDelay?.toMillis()
        } ?: return false

        scheduleAutoAdvance(state, stackId, cueConfig, scope)
        runState.publishRunState(state, stackId)
        return true
    }

    /** Launch and record a fresh auto-advance timer for [stackId]. */
    private fun scheduleAutoAdvance(state: State, stackId: Int, delayMs: Long, scope: CoroutineScope) {
        activeStacks[stackId]?.autoAdvanceJob = scope.launch {
            delay(delayMs)
            try {
                advanceStack(state, stackId, AdvanceDirection.FORWARD, scope, operator = false)
            } catch (e: Exception) {
                // Stack may have been deactivated or cue deleted
                logger.warn("stack {}: auto-advance failed — {}", stackId, e.message)
            }
        }
    }

    /**
     * Get all currently active stack IDs.
     */
    fun getActiveStackIds(): Set<Int> = activeStacks.keys.toSet()

    /**
     * Check if a stack is active.
     */
    fun isStackActive(stackId: Int): Boolean = activeStacks.containsKey(stackId)

    /** Whether [stackId] is stopped with its release still fading ([stopStack]). */
    fun isReleasing(stackId: Int): Boolean = !isStackActive(stackId) && crossfades.isReleasing(stackId)

    // ─── Private helpers ─────────────────────────────────────────────────

    // `createInstanceForStack` stood here. It was `createFxInstance` with a `stackId`
    // parameter, which existed only to resolve `P1` against the stack's positional colour list.
    // With that list gone the two bodies were identical — and this copy had lost the
    // `prewarmPaletteColours` call the shared one makes, so a stack GO left the first `pal:`
    // resolve to the 50 Hz tick loop, where it opens a transaction.
}

/** [CueStackManager.stackIdForUuid]'s kept miss. */
private const val NO_STACK = -1
