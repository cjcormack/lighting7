package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.show.Fixtures
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("MoveInDarkService")

/**
 * Moves dark heads ahead of the cue that lights them (move-in-dark plan D1, D6, D7, §3 steps 2–3).
 * One per `Show`, started and stopped with it; nothing is stored.
 *
 * Each live stack has a plan ([MoveInDarkPlanner]: which heads, for which entry, with which rows),
 * re-made off the GO path whenever "next" may have moved — a GO, a standby armed or cleared, the
 * stack's settings. A tick every [TICK_MS] (and at once after a GO) watches the planned heads'
 * intensity on the output ([HeadIntensity]): a head dark since *t* is **waiting**; at *t* + delay it
 * is **moving** and its rows join the stack's contributor, fading in over the move time; after that
 * it is **set**. A head that lights while waiting goes back to idle; one that lights while moving or
 * set — or whose stack's live cue lights it, after a GO back — is let go at once and moves again once
 * dark (session 2 makes that release fade, D8).
 *
 * **The contributor** is one Layer 4 entry per stack in [CueAssignmentLayer], under
 * [contributorId] — the stack's id negated, which no cue can take (cue ids are row ids, positive) —
 * at the stack's live priority + [PRIORITY_OFFSET], so it beats the stack's own live cue and stays
 * under the next entry's band, a higher stack, the programmer, park and FX. Its rows carry the move
 * time as their row fade and are published with `honourRowFades`, so the head that just joined ramps
 * over its own time: the publish only touches keys whose value changed, and a head that is already
 * moving or set keeps its ramp. One contributor serves every head of the stack for that reason, and
 * releasing a head's rows snaps them to what lies underneath. Its priority follows the live entry's,
 * through a GO ([beforeGo]) and a restamp of the stack's entries ([onEntriesRestamped]).
 *
 * **At a GO** ([beforeGo], [afterGo]): before the GO's cue arrives, a hand-over still running from an
 * earlier GO ends and the contributor is restamped to the new live priority, so a cue walked through
 * on the way to a head's target never wins it back; once it has landed, a head whose target the GO is
 * holds its rows (which equal that cue's) until the GO's crossfade ends, then lets go, so nothing
 * moves while it comes up. Then the stack is re-planned from its new live entry. Nothing in the GO
 * path waits on a plan.
 */
class MoveInDarkService internal constructor(
    private val fixtures: Fixtures,
    private val cueLayer: CueAssignmentLayer,
    /** `fixtureKey to attribute` for every running FX — `FxEngine.runningFxCoverage`. */
    private val runningFx: () -> Set<Pair<String, String>>,
    /** [stackId]'s plan from its live entry, or null when it is not live. */
    private val planFor: (stackId: Int, heads: Map<String, HeadIntensity>) -> MoveInDarkPlanner.Plan?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What a planned head is doing. A head not reported is idle: lit, or not yet dark. */
    enum class HeadState { WAITING, MOVING, SET }

    /** One head as [statusOf] reports it. */
    data class HeadStatus(val head: String, val targetEntryId: Int, val targetCueId: Int, val state: HeadState, val remainingMs: Long?)

    private class HeadRun(var move: MoveInDarkPlanner.HeadMove) {
        var state: HeadState? = null
        var since = 0L
        /** Set by a GO onto [move]'s target: the rows are held until this time, then let go. */
        var handOverUntil: Long? = null
        /** The plan's next move for this head, taken once its hand-over ends. */
        var following: MoveInDarkPlanner.HeadMove? = null

        val held: Boolean get() = handOverUntil != null || state == HeadState.MOVING || state == HeadState.SET
    }

    private class StackRun(val stackId: Int, var priority: Int, var liveEntryId: Int) {
        val heads = LinkedHashMap<String, HeadRun>()
        var published: List<CueAssignmentResolver.Assignment> = emptyList()
        @Volatile var dirty = true
        /** Bumped by every GO and re-plan request: a plan made across one is stale and made again. */
        var generation = 0L
        /** Its Move in dark is off: a GO or a standby re-plans nothing until the settings change. */
        var off = false
    }

    private val lock = Any()
    private val stacks = ConcurrentHashMap<Int, StackRun>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    private var classified: Map<String, HeadIntensity> = emptyMap()
    private var classifiedAt = -1L

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Move in dark tick failed: {}", e.message, e)
                }
                withTimeoutOrNull(TICK_MS) { wake.receive() }
            }
        }
    }

    /** Stop watching and let every move go. */
    fun stop() {
        job?.cancel()
        job = null
        synchronized(lock) {
            for (run in stacks.values) if (run.published.isNotEmpty()) cueLayer.removeAssignments(contributorId(run.stackId))
            stacks.clear()
        }
    }

    /**
     * A GO on [stackId] is about to bring [entryId]'s cue on at [priority]: an earlier GO's hand-over
     * ends, and the contributor is restamped above the incoming cue, so it never wins a held head back
     * for a frame.
     */
    fun beforeGo(stackId: Int, entryId: Int, priority: Int) = synchronized(lock) {
        val run = stacks.getOrPut(stackId) { StackRun(stackId, priority, entryId) }
        run.priority = priority
        run.liveEntryId = entryId
        run.generation++
        run.heads.entries.removeIf { it.value.handOverUntil != null }
        publish(run)
        if (run.published.isEmpty()) return@synchronized
        val stamped = priority + PRIORITY_OFFSET
        cueLayer.repriorityAssignments(mapOf(contributorId(stackId) to stamped))
        run.published = run.published.map { it.copy(priority = stamped) }
    }

    /**
     * [stackId]'s GO onto [entryId] has landed, its crossfade [fadeMs] long: every head moved for
     * that entry hands over (D7), and the stack re-plans at once, off this thread.
     */
    fun afterGo(stackId: Int, entryId: Int, fadeMs: Long) {
        synchronized(lock) {
            val run = stacks[stackId] ?: return
            val until = clock() + fadeMs
            for (head in run.heads.values) {
                if (head.held && head.move.targetEntryId == entryId) head.handOverUntil = until
            }
            run.generation++
            if (run.off) return
            run.dirty = true
        }
        wake.trySend(Unit)
    }

    /** "Next" on [stackId] may have moved: a standby armed or cleared. */
    fun replan(stackId: Int) {
        synchronized(lock) {
            val run = stacks[stackId] ?: return
            run.generation++
            if (run.off) return
            run.dirty = true
        }
        wake.trySend(Unit)
    }

    /** The settings of [stackId] changed; applied at once, without releasing the stack (D2). */
    fun onStackSettingsChanged(stackId: Int) {
        synchronized(lock) { stacks[stackId]?.off = false }
        replan(stackId)
    }

    /**
     * Entries were restamped (`CueHolds.restampEntries`, `entryId → priority`) after the order of a
     * stack's list changed: a contributor whose live entry moved within its stack follows it. A live
     * entry moved to another stack is still held in this one until its next GO, so its contributor stays.
     */
    fun onEntriesRestamped(entryPriorities: Map<Int, Int>) = synchronized(lock) {
        for (run in stacks.values) {
            val priority = entryPriorities[run.liveEntryId] ?: continue
            if (stackOfDerivedPriority(priority) != run.stackId) continue
            if (priority == run.priority) continue
            run.priority = priority
            if (run.published.isEmpty()) continue
            val stamped = priority + PRIORITY_OFFSET
            cueLayer.repriorityAssignments(mapOf(contributorId(run.stackId) to stamped))
            run.published = run.published.map { it.copy(priority = stamped) }
        }
    }

    /** [stackId] stopped: its moves go (D9). */
    fun onStackStopped(stackId: Int) = synchronized(lock) {
        val run = stacks.remove(stackId) ?: return@synchronized
        if (run.published.isNotEmpty()) cueLayer.removeAssignments(contributorId(stackId))
    }

    /** Every reported head of [stackId], by key. */
    fun statusOf(stackId: Int): Map<String, HeadStatus> = synchronized(lock) {
        val run = stacks[stackId] ?: return@synchronized emptyMap()
        val now = clock()
        run.heads.values.mapNotNull { head ->
            val state = head.state ?: return@mapNotNull null
            val remaining = when (state) {
                HeadState.WAITING -> head.move.delay.toMillis() - (now - head.since)
                HeadState.MOVING -> head.move.time.toMillis() - (now - head.since)
                HeadState.SET -> null
            }?.coerceAtLeast(0)
            HeadStatus(head.move.head, head.move.targetEntryId, head.move.targetCueId, state, remaining)
        }.associateBy { it.head }
    }

    /**
     * One pass: re-plan what is marked, watch every planned head, publish what changed. The plans are
     * made outside the lock, so a GO's [beforeGo] never waits on one; a plan made across a GO or a
     * re-plan request is dropped and made again on the next pass.
     */
    internal fun tick() {
        val (heads, toPlan) = synchronized(lock) {
            if (stacks.isEmpty()) return
            heads() to stacks.values.filter { it.dirty }.map { run -> run.dirty = false; run to run.generation }
        }
        val plans = toPlan.map { (run, generation) -> Triple(run, generation, planOf(run.stackId, heads)) }
        synchronized(lock) {
            for ((run, generation, plan) in plans) {
                if (stacks[run.stackId] !== run) continue
                when {
                    run.generation != generation -> run.dirty = true
                    plan != null -> apply(run, plan)
                }
            }
            val active = stacks.values.filter { it.heads.isNotEmpty() || it.published.isNotEmpty() }
            if (active.isEmpty()) return
            val covered = runningFx()
            val now = clock()
            for (run in active) {
                for ((key, head) in run.heads) watch(head, heads[key], heads, covered, now)
                endHandOvers(run, now)
                publish(run)
            }
        }
    }

    /** Called under [lock]. */
    private fun heads(): Map<String, HeadIntensity> {
        val version = fixtures.structureVersion
        if (version != classifiedAt) {
            classified = HeadIntensity.classify(fixtures)
            classifiedAt = version
        }
        return classified
    }

    /** [stackId]'s plan, empty when it is not live, or null when it could not be made (logged; the old one stands). */
    private fun planOf(stackId: Int, heads: Map<String, HeadIntensity>): MoveInDarkPlanner.Plan? = try {
        planFor(stackId, heads) ?: MoveInDarkPlanner.Plan(emptyMap(), emptySet())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Move in dark: could not plan stack {}: {}", stackId, e.message, e)
        null
    }

    /**
     * [plan] over [run]'s heads: a head handing over, or heading for the same target, keeps its state;
     * a held head the live cue lights is let go.
     */
    private fun apply(run: StackRun, plan: MoveInDarkPlanner.Plan) {
        run.off = plan.off
        val kept = LinkedHashMap<String, HeadRun>()
        for ((key, head) in run.heads) {
            val next = plan.moves[key]
            when {
                head.handOverUntil != null -> { head.following = next; kept[key] = head }
                next != null && next.targetEntryId == head.move.targetEntryId && next.rows == head.move.rows -> {
                    head.move = next
                    if (key in plan.litByLive) head.state = null
                    kept[key] = head
                }
            }
        }
        for ((key, move) in plan.moves) if (key !in kept) kept[key] = HeadRun(move)
        run.heads.clear()
        run.heads.putAll(kept)
    }

    private fun watch(
        head: HeadRun,
        intensity: HeadIntensity?,
        heads: Map<String, HeadIntensity>,
        covered: Set<Pair<String, String>>,
        now: Long,
    ) {
        if (head.handOverUntil != null || intensity == null) return
        val dark = intensity.isDark(fixtures.controllerOrNull(intensity.dmxUniverse)) && !intensity.hasIntensityFx(covered, heads)
        when (head.state) {
            null -> if (dark) { head.state = HeadState.WAITING; head.since = now }
            HeadState.WAITING -> when {
                !dark -> head.state = null
                now - head.since >= head.move.delay.toMillis() -> { head.state = HeadState.MOVING; head.since = now }
            }
            HeadState.MOVING, HeadState.SET -> when {
                !dark -> head.state = null
                head.state == HeadState.MOVING && now - head.since >= head.move.time.toMillis() -> head.state = HeadState.SET
            }
        }
    }

    private fun endHandOvers(run: StackRun, now: Long) {
        val ended = run.heads.filterValues { head -> head.handOverUntil?.let { now >= it } == true }
        for ((key, head) in ended) {
            val next = head.following
            if (next == null) run.heads.remove(key) else run.heads[key] = HeadRun(next)
        }
    }

    private fun publish(run: StackRun) {
        val priority = run.priority + PRIORITY_OFFSET
        val id = contributorId(run.stackId)
        val rows = run.heads.values
            .filter { it.held }
            .flatMap { head ->
                val fade = head.move.time.toMillis().takeIf { it > 0 }
                head.move.rows.map { row ->
                    // A row of its own: neither the target's group origin (which specificity would drop
                    // beside any direct row) nor its legacy row flag (which would arm the head over a
                    // higher stack) comes with it.
                    row.copy(
                        cueId = id, priority = priority, fadeWeight = 1.0, fadeDurationMs = fade,
                        targetIsGroup = false, moveInDark = false, layerWinner = null,
                    )
                }
            }
        // `clearAll` (an operator's clear-all-FX) can take the rows without telling us.
        if (rows == run.published && (rows.isEmpty() || cueLayer.hasAssignments(id))) return
        run.published = rows
        cueLayer.setAssignments(id, rows, honourRowFades = true)
    }

    companion object {
        /** How often planned heads are watched. */
        const val TICK_MS = 50L

        /** Above the live cue (`+1`), below the next entry's (`+1 000`). */
        const val PRIORITY_OFFSET = 500

        /** [stackId]'s contributor in [CueAssignmentLayer]: negative, so never a cue's id. */
        fun contributorId(stackId: Int): Int = -stackId

        /** The stack whose contributor [cueId] is, or null for a cue. */
        fun stackOfContributor(cueId: Int): Int? = if (cueId < 0) -cueId else null
    }
}
