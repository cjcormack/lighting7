package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.state.State
import java.util.concurrent.ConcurrentHashMap

/**
 * A cue stack's *run state* — what is live, what the next GO will fire, and how far through a
 * fade the desk is. Broadcast on every transition (see [CueRunStateTracker.runStateFor]) so a
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
 * What the run-state half needs to know about a stack [CueStackManager] has live. A snapshot
 * rather than the manager's mutable entry, so a frame can't describe two different moments.
 */
internal data class LiveStack(
    val activeCueId: Int,
    val fadeDurationMs: Long?,
    val fadeStartedAtMs: Long?,
    val autoAdvanceRunning: Boolean,
    val autoAdvanceDelayMs: Long?,
)

/** The manager's live-stack table, as the run-state half sees it. */
internal interface LiveStacks {
    fun liveStack(stackId: Int): LiveStack?

    fun liveStackIds(): Set<Int>
}

/**
 * The standby and run-state half of cue-stack running: which cue an operator has armed, what
 * "next" therefore means, and the [CueRunState] frame every session animates from.
 *
 * "Next" has one definition here, shared by GO ([CueStackManager.advanceStack]), the run-state
 * broadcast and the stack-details DTO — the point being that a client no longer chooses between
 * `advance` and `go-to` depending on what it thinks is armed.
 *
 * Reached as [CueStackManager.runState]; it holds no cue-firing machinery of its own.
 */
class CueRunStateTracker internal constructor(
    private val liveStacks: LiveStacks,
) {
    /**
     * `stackId → explicitly armed next cue`.
     *
     * Deliberately not part of the manager's active-stack entry: an operator arms a cue
     * *before* the stack is running (pre-show), and [CueStackManager.activateCueInStack]
     * replaces that whole entry. Transient runtime state — never persisted, never synced.
     */
    private val standbyCueIds = ConcurrentHashMap<Int, Int>()

    /** Ordered STANDARD cue ids for [stackId]. Must be called inside a transaction. */
    internal fun orderedStandardCueIds(stackId: Int): List<Int> =
        DaoCue.find {
            (DaoCues.cueStack eq stackId) and (DaoCues.cueType eq CueType.STANDARD.name)
        }.orderBy(DaoCues.sortOrder to SortOrder.ASC)
            .map { it.id.value }

    /**
     * The cue [direction] from [fromCueId] within [ordered], honouring [loop].
     *
     * Null when the walk runs off the end of a non-looping stack — callers decide what that
     * means: [CueStackManager.advanceStack] stays on the current cue, [effectiveNextCueId]
     * reports "nothing on deck". A [fromCueId] that isn't in [ordered] (deleted, or turned into
     * a MARKER) falls back to the first cue.
     */
    internal fun positionalCueId(
        ordered: List<Int>,
        fromCueId: Int?,
        direction: CueStackManager.AdvanceDirection,
        loop: Boolean,
    ): Int? {
        if (ordered.isEmpty()) return null
        if (fromCueId == null) return ordered.first()
        val currentIndex = ordered.indexOf(fromCueId)
        if (currentIndex == -1) return ordered.first()
        val nextIndex = when (direction) {
            CueStackManager.AdvanceDirection.FORWARD -> currentIndex + 1
            CueStackManager.AdvanceDirection.BACKWARD -> currentIndex - 1
        }
        return when {
            nextIndex in ordered.indices -> ordered[nextIndex]
            !loop -> null
            direction == CueStackManager.AdvanceDirection.FORWARD -> ordered.first()
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
    fun effectiveNextCueId(stackId: Int, orderedStandardCueIds: List<Int>, loop: Boolean): Int? =
        nextCueIdFrom(
            activeCueId = liveStacks.liveStack(stackId)?.activeCueId,
            stackId = stackId,
            ordered = orderedStandardCueIds,
            loop = loop,
        )

    /** [effectiveNextCueId] for a stack whose cues aren't already loaded. */
    fun effectiveNextCueId(state: State, stackId: Int): Int? = transaction(state.database) {
        val stack = DaoCueStack.findById(stackId) ?: return@transaction null
        effectiveNextCueId(stackId, orderedStandardCueIds(stackId), stack.loop)
    }

    /**
     * [effectiveNextCueId] against a caller-supplied live cue, for callers that have already
     * read the stack's active cue and must not race a second read of it — a GO, and the
     * run-state frame, which has to describe one moment.
     *
     * `armed in ordered` also covers a standby whose cue was deleted or turned into a MARKER
     * since it was armed — fall through to positional rather than throwing out of a GO.
     */
    internal fun nextCueIdFrom(
        activeCueId: Int?,
        stackId: Int,
        ordered: List<Int>,
        loop: Boolean,
    ): Int? {
        val armed = standbyCueIds[stackId]
        if (armed != null && armed != activeCueId && armed in ordered) return armed
        return positionalCueId(ordered, activeCueId, CueStackManager.AdvanceDirection.FORWARD, loop)
    }

    /**
     * Arm [cueId] as the cue the next GO on [stackId] fires.
     *
     * Rejects a cue from another stack, and a MARKER — the same two guards
     * [CueStackManager.activateCueInStack] and [CueStackManager.goToCue] apply, because arming
     * is a deferred GO.
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
     * Drop [stackId]'s standby without broadcasting — for the manager's own transitions, which
     * publish one frame describing the finished state rather than one per step. A GO consumes
     * the armed standby; a stopped stack has nothing on deck.
     */
    internal fun consumeStandby(stackId: Int) {
        standbyCueIds.remove(stackId)
    }

    /**
     * [stackId]'s run state as the desk sees it right now — see [CueRunState].
     *
     * Also the connect-time snapshot: a session that opens mid-fade reads a non-null
     * `fadeElapsedMs` and animates the remainder.
     */
    fun runStateFor(state: State, stackId: Int, transition: Boolean = false): CueRunState {
        val active = liveStacks.liveStack(stackId)
        // "Next" is derived from the *same* snapshot as `activeCueId`, not from a second read:
        // an activation landing between the two would otherwise produce a frame whose live cue
        // is the outgoing one and whose next cue was computed from the incoming one — a NEXT
        // pill a cue ahead of the LIVE pill beside it.
        val next = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId) ?: return@transaction null
            nextCueIdFrom(
                activeCueId = active?.activeCueId,
                stackId = stackId,
                ordered = orderedStandardCueIds(stackId),
                loop = stack.loop,
            )
        }
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
            autoAdvance = active?.autoAdvanceRunning == true,
            autoAdvanceDelayMs = active?.autoAdvanceDelayMs,
        )
    }

    /**
     * Stacks with run state worth reporting — live, or holding an armed standby. The
     * connect-time snapshot walks these rather than every stack in the project.
     */
    fun stacksWithRunState(): Set<Int> = liveStacks.liveStackIds() + standbyCueIds.keys

    /** Fan [stackId]'s run state out to every connected session. */
    internal fun publishRunState(state: State, stackId: Int, transition: Boolean = false) {
        state.show.fixtures.cueRunStateChanged(runStateFor(state, stackId, transition))
    }
}
