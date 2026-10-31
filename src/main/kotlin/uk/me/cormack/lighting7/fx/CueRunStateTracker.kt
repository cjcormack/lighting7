package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.EntryPlace
import uk.me.cormack.lighting7.models.entriesOfStack
import uk.me.cormack.lighting7.state.State
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A cue stack's *run state* — what is live, what the next GO will fire, and how far through a
 * fade the desk is. Broadcast on every transition (see [CueRunStateTracker.runStateFor]) so a
 * prompt book on a tablet agrees with the desk that armed or fired the cue, rather than each
 * session computing "next" for itself.
 *
 * Every cue id is paired with the **entry** it plays from (cue model plan P2): an entry is the
 * playback key, and a cue twice in one stack (D7) is told apart only by it.
 *
 * @property activeEntryId the stack's live entry; [activeCueId] the cue it names.
 * @property nextEntryId the entry the next GO fires: the explicitly armed standby when there is
 *   one, else the positional next. Null at the end of a non-looping stack. [nextCueId] its cue.
 * @property standbyEntryId the entry an operator has armed, whether or not it is [nextEntryId] (an
 *   armed entry that is live is not next).
 * @property nextIsArmed true when [nextEntryId] is an operator-armed standby rather than the
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
 * @property seq where this frame sits among every run-state frame the desk has produced — see
 *   [CueRunStateTracker.runStateFor]. Server-side only: a socket uses it to drop a frame older
 *   than one it has already sent, and it is not on the wire.
 */
data class CueRunState(
    val projectId: Int,
    val stackId: Int,
    val activeCueId: Int?,
    val nextCueId: Int?,
    val activeEntryId: Int?,
    val nextEntryId: Int?,
    val standbyEntryId: Int?,
    val nextIsArmed: Boolean,
    val transition: Boolean,
    val fadeDurationMs: Long?,
    val fadeElapsedMs: Long?,
    val autoAdvance: Boolean,
    val autoAdvanceDelayMs: Long?,
    val seq: Long,
)

/**
 * What the run-state half needs to know about a stack [CueStackManager] has live. A snapshot
 * rather than the manager's mutable entry, so a frame can't describe two different moments.
 */
internal data class LiveStack(
    val activeEntryId: Int,
    val activeCueId: Int,
    val fadeDurationMs: Long?,
    val fadeStartedAtMs: Long?,
    val autoAdvanceRunning: Boolean,
    val autoAdvanceDelayMs: Long?,
    /** Where [activeEntryId] stood, once it has been removed from the list (cue model plan D16). */
    val detachedAt: EntryPlace? = null,
)

/** The manager's live-stack table, as the run-state half sees it. */
internal interface LiveStacks {
    fun liveStack(stackId: Int): LiveStack?

    fun liveStackIds(): Set<Int>
}

/**
 * One cue entry of a stack's list: the entry and the cue it names. A marker names no cue and never
 * appears as one — it is never held, armed or gone to.
 */
data class CueEntryRef(val entryId: Int, val cueId: Int)

/**
 * The standby and run-state half of cue-stack running: which entry an operator has armed, what
 * "next" therefore means, and the [CueRunState] frame every session animates from.
 *
 * "Next" has one definition here, shared by GO ([CueStackManager.advanceStack]), the run-state
 * broadcast and the stack-details DTO — the point being that a client no longer chooses between
 * `advance` and `go-to` depending on what it thinks is armed. Positions are entry positions (cue
 * model plan P2), so a cue twice in one stack (D7) is never ambiguous.
 *
 * Reached as [CueStackManager.runState]; it holds no cue-firing machinery of its own.
 */
class CueRunStateTracker internal constructor(
    private val liveStacks: LiveStacks,
) {
    /**
     * `stackId → explicitly armed next entry` (`standbyEntryIds`, §3.4), with the cue it names.
     *
     * Deliberately not part of the manager's active-stack entry: an operator arms a cue
     * *before* the stack is running (pre-show), and [CueStackManager.activateEntry]
     * replaces that whole entry. Transient runtime state — never persisted, never synced.
     */
    private val standbys = ConcurrentHashMap<Int, CueEntryRef>()

    /** [stackId]'s cue entries in playback order, markers left out. Must be called inside a transaction. */
    internal fun orderedCueEntries(stackId: Int): List<CueEntryRef> =
        entriesOfStack(EntityID(stackId, DaoCueStacks)).mapNotNull { entry -> entry.cueId?.let { CueEntryRef(entry.id.value, it) } }

    /**
     * How many of [stackId]'s cue entries play before [place] — where a removed live entry would sit
     * in [orderedCueEntries] — or null for no place. Must be called inside a transaction.
     */
    internal fun detachedIndex(stackId: Int, place: EntryPlace?): Int? =
        place?.cueIndexIn(entriesOfStack(EntityID(stackId, DaoCueStacks)))

    /**
     * The entry [direction] from [fromEntryId] within [ordered], honouring [loop].
     *
     * Null when the walk runs off the end of a non-looping stack — callers decide what that
     * means: [CueStackManager.advanceStack] stays on the current entry, [effectiveNext]
     * reports "nothing on deck". A [fromEntryId] that isn't in [ordered] walks from
     * [detachedIndex] — where it stood before it was removed (D16) — when known, else falls back to
     * the first entry.
     */
    internal fun positional(
        ordered: List<CueEntryRef>,
        fromEntryId: Int?,
        direction: CueStackManager.AdvanceDirection,
        loop: Boolean,
        detachedIndex: Int? = null,
    ): CueEntryRef? {
        if (ordered.isEmpty()) return null
        if (fromEntryId == null) return ordered.first()
        val currentIndex = ordered.indexOfFirst { it.entryId == fromEntryId }
        val nextIndex = when {
            currentIndex == -1 && detachedIndex == null -> return ordered.first()
            // Between two entries: forward is the one now at its index, back the one before.
            currentIndex == -1 -> when (direction) {
                CueStackManager.AdvanceDirection.FORWARD -> detachedIndex!!
                CueStackManager.AdvanceDirection.BACKWARD -> detachedIndex!! - 1
            }
            direction == CueStackManager.AdvanceDirection.FORWARD -> currentIndex + 1
            else -> currentIndex - 1
        }
        return when {
            nextIndex in ordered.indices -> ordered[nextIndex]
            !loop -> null
            direction == CueStackManager.AdvanceDirection.FORWARD -> ordered.first()
            else -> ordered.last()
        }
    }

    /** Told when an armed standby changes what a stack's next GO fires — Move in dark re-plans. */
    @Volatile internal var onNextMayHaveMoved: (stackId: Int) -> Unit = {}

    /** The entry an operator has explicitly armed on [stackId], if any. */
    fun getStandbyEntryId(stackId: Int): Int? = standbys[stackId]?.entryId

    /** The cue the armed entry on [stackId] names, if any. */
    fun getStandbyCueId(stackId: Int): Int? = standbys[stackId]?.cueId

    /** Every armed standby, stack → entry: what a carried reload (`ShowCarry`) re-arms. */
    fun armedStandbys(): Map<Int, Int> = standbys.mapValues { it.value.entryId }

    /**
     * The entry the next GO on [stackId] will fire: the armed standby when one is set and isn't
     * already live, else the positional next. Null at the end of a non-looping stack.
     *
     * Overload taking an already-loaded entry list, for callers that are inside a transaction and
     * hold the stack's entries (the details DTO). The rules live here, not in the caller.
     */
    fun effectiveNext(stackId: Int, ordered: List<CueEntryRef>, loop: Boolean): CueEntryRef? {
        val live = liveStacks.liveStack(stackId)
        return nextFrom(
            activeEntryId = live?.activeEntryId,
            stackId = stackId,
            ordered = ordered,
            loop = loop,
            detachedAt = live?.detachedAt,
        )
    }

    /** [effectiveNext] for a stack whose entries aren't already loaded. */
    fun effectiveNext(state: State, stackId: Int): CueEntryRef? = transaction(state.database) {
        val stack = DaoCueStack.findById(stackId) ?: return@transaction null
        effectiveNext(stackId, orderedCueEntries(stackId), stack.loop)
    }

    /**
     * [effectiveNext] against a caller-supplied live entry, for callers that have already read the
     * stack's active entry and must not race a second read of it — a GO, and the run-state frame,
     * which has to describe one moment.
     *
     * `armed in ordered` also covers a standby whose entry was removed, or whose cue was deleted,
     * since it was armed — fall through to positional rather than throwing out of a GO.
     */
    internal fun nextFrom(
        activeEntryId: Int?,
        stackId: Int,
        ordered: List<CueEntryRef>,
        loop: Boolean,
        detachedAt: EntryPlace? = null,
    ): CueEntryRef? {
        val armed = standbys[stackId]
        if (armed != null && armed.entryId != activeEntryId && armed in ordered) return armed
        val detachedIndex = if (detachedAt != null && ordered.none { it.entryId == activeEntryId }) detachedIndex(stackId, detachedAt) else null
        return positional(ordered, activeEntryId, CueStackManager.AdvanceDirection.FORWARD, loop, detachedIndex)
    }

    /**
     * Arm [entryId] as the entry the next GO on [stackId] fires.
     *
     * Rejects an entry of another stack, and a marker — the same two guards
     * [CueStackManager.activateEntry] applies, because arming is a deferred GO.
     */
    fun setStandby(state: State, stackId: Int, entryId: Int) {
        val ref = transaction(state.database) {
            val entry = DaoCueStackEntry.findById(entryId)?.takeIf { it.stackId == stackId }
                ?: throw IllegalArgumentException("Entry $entryId is not in stack $stackId")
            CueEntryRef(entryId, entry.cueId ?: throw IllegalArgumentException("Entry $entryId is a marker, which cannot be armed"))
        }
        standbys[stackId] = ref
        onNextMayHaveMoved(stackId)
        publishRunState(state, stackId)
    }

    /** Disarm [stackId]'s standby, leaving the positional next on deck. */
    fun clearStandby(state: State, stackId: Int) {
        if (standbys.remove(stackId) == null) return
        onNextMayHaveMoved(stackId)
        publishRunState(state, stackId)
    }

    /** Drop any standby armed on one of [entryIds], which were removed from their stacks. */
    fun clearStandbyForEntries(state: State, entryIds: Set<Int>) {
        for ((stackId, armed) in standbys) {
            if (armed.entryId in entryIds) clearStandby(state, stackId)
        }
    }

    /**
     * Drop [stackId]'s standby without broadcasting — for the manager's own transitions, which
     * publish one frame describing the finished state rather than one per step. A GO consumes
     * the armed standby; a stopped stack has nothing on deck.
     */
    internal fun consumeStandby(stackId: Int) {
        standbys.remove(stackId)
    }

    /**
     * [stackId]'s run state as the desk sees it right now — see [CueRunState].
     *
     * Also the connect-time snapshot: a session that opens mid-fade reads a non-null
     * `fadeElapsedMs` and animates the remainder.
     *
     * [seq] is read *before* the state, and [publishRunState] takes a fresh one before its
     * read, so a frame stamped `n` describes the desk no earlier than every publish stamped
     * `n` or below. That is what lets a socket keep the highest-stamped frame and drop the rest.
     */
    fun runStateFor(
        state: State,
        stackId: Int,
        transition: Boolean = false,
        seq: Long = runStateSeq.get(),
    ): CueRunState {
        val active = liveStacks.liveStack(stackId)
        // "Next" is derived from the *same* snapshot as the live entry, not from a second read:
        // an activation landing between the two would otherwise produce a frame whose live cue
        // is the outgoing one and whose next cue was computed from the incoming one — a NEXT
        // pill a cue ahead of the LIVE pill beside it.
        val next = transaction(state.database) {
            val stack = DaoCueStack.findById(stackId) ?: return@transaction null
            nextFrom(
                activeEntryId = active?.activeEntryId,
                stackId = stackId,
                ordered = orderedCueEntries(stackId),
                loop = stack.loop,
                detachedAt = active?.detachedAt,
            )
        }
        val armed = standbys[stackId]
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
            nextCueId = next?.cueId,
            activeEntryId = active?.activeEntryId,
            nextEntryId = next?.entryId,
            standbyEntryId = armed?.entryId,
            nextIsArmed = armed != null && armed == next,
            transition = transition,
            fadeDurationMs = active?.fadeDurationMs,
            fadeElapsedMs = fadeElapsed,
            // Whether the stack *will* advance, not merely whether the cue is configured to:
            // that's the question a client drawing a countdown is really asking. A paused timer
            // (the surface's Pause binding) or a configured cue with no delay both report false.
            autoAdvance = active?.autoAdvanceRunning == true,
            autoAdvanceDelayMs = active?.autoAdvanceDelayMs,
            seq = seq,
        )
    }

    /**
     * Stacks with run state worth reporting — live, or holding an armed standby. The
     * connect-time snapshot walks these rather than every stack in the project.
     */
    fun stacksWithRunState(): Set<Int> = liveStacks.liveStackIds() + standbys.keys

    /** Fan [stackId]'s run state out to every connected session. */
    internal fun publishRunState(state: State, stackId: Int, transition: Boolean = false) {
        val seq = runStateSeq.incrementAndGet()
        state.show.fixtures.cueRunStateChanged(runStateFor(state, stackId, transition, seq))
    }

    private companion object {
        /** Process-wide, not per tracker: a project reload builds a new tracker, and a socket
         *  that outlives it must not see the count start again below what it has sent. */
        val runStateSeq = AtomicLong()
    }
}
