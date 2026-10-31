package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.CueMoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("MoveInDarkService")

/**
 * Moves dark heads ahead of the cue that lights them (move-in-dark plan D1, D6–D9, D12, §3 steps
 * 2–3). One per `Show`, started and stopped with it; nothing is stored.
 *
 * Each live stack has a plan ([MoveInDarkPlanner]: which heads, for which entry, with which rows),
 * re-made off the GO path whenever "next" may have moved — a GO (GO TO and GO BACK included), a
 * standby armed or cleared, the stack's settings, and every announcement that a stack's list or a
 * cue's rows changed ([FixturesChangeListener]: `cueListChanged`, `cueStackListChanged`,
 * `cuesRecomposed`, a Look or palette list change, a repatch). A tick every [TICK_MS] (and at once
 * after one of those) watches the planned heads' intensity on the output ([HeadIntensity]):
 *
 * - dark since *t*, a head is [HeadState.WAITING]; at *t* + delay it is [HeadState.MOVING], its rows
 *   joining the stack's contributor and ramping over the move time; after that it is [HeadState.SET];
 * - one that lights while waiting goes back to idle; one that lights while moving or set is
 *   [HeadState.LIT_EARLY] (D8): its rows go, ramping back to what the live cues say over the move
 *   time, and once dark again it waits the delay and moves again;
 * - a re-plan that gives a moving or set head another target moves it there over the move time, and
 *   one that gives it none (or whose live cue now lights it) takes it back the same way.
 *
 * **The contributor** is one Layer 4 entry per stack in [CueAssignmentLayer], under
 * [contributorId] — the stack's id negated, which no cue can take — at the stack's live priority +
 * [PRIORITY_OFFSET], so it beats the stack's own live cue and stays under the next entry's band, a
 * higher stack (D12), the programmer, park and FX. It is published head by head
 * ([CueAssignmentLayer.setContributorAssignments]), each change ramping over its own time — a join,
 * a retarget and a release over the head's move time, a hand-over's end, a catch-up and a stop at
 * once — and since a publish writes only the keys whose value changed, a head already moving keeps
 * its ramp.
 *
 * **At a GO** ([beforeGo], [afterGo]): before the cue arrives, a hand-over still running from an
 * earlier GO ends, the heads dark at that moment are noted, and the contributor is restamped above
 * both the incoming and the outgoing cue, so neither wins a held head back during the crossfade (it
 * settles to the live priority once the fade is over). Once the cue has landed, a head whose target
 * the GO is holds its rows (which equal that cue's) until the crossfade ends, so nothing moves while it
 * comes up; and every head dark at the GO's start that the incoming cue lights but is not yet in
 * place — still waiting, still moving, set for a later cue, or never planned (a GO TO, a standby armed
 * late) — **catches up** (D7): it takes the incoming cue's moving families at once, a ramp in
 * progress ended, and holds them to the end of the fade. Then the stack re-plans from its new entry.
 * Nothing in the GO path waits on a plan.
 *
 * **A reload** ([resume]) carries nothing of the service: it re-plans, and a head dark on the carried
 * frame whose planned rows the frame already shows starts out set, so nothing moves.
 *
 * [state] is the `moveInDark.state` frame's source: every reported head, republished on a change of
 * state at most every [FRAME_MS].
 */
class MoveInDarkService internal constructor(
    private val fixtures: Fixtures,
    private val cueLayer: CueAssignmentLayer,
    /** `fixtureKey to attribute` for every running FX — `FxEngine.runningFxCoverage`. */
    private val runningFx: () -> Set<Pair<String, String>>,
    /** [stackId]'s plan from its live entry, or null when it is not live. */
    private val planFor: (stackId: Int, heads: Map<String, HeadIntensity>) -> MoveInDarkPlanner.Plan?,
    private val clock: () -> Long = System::currentTimeMillis,
) : FixturesChangeListener {
    /** What a planned head is doing. A head not reported is idle: lit, or not yet dark. */
    enum class HeadState { WAITING, MOVING, SET, LIT_EARLY }

    /** One head as [statusOf] reports it. */
    data class HeadStatus(
        val head: String,
        val targetEntryId: Int,
        val targetCueId: Int,
        val state: HeadState,
        val remainingMs: Long?,
        /** When the delay or move ends, on [clock]: what a restarted timer changes, which [remainingMs] alone hides. */
        val endsAtMs: Long?,
        /** The families its move carries. */
        val families: Set<AttributeFamily>,
        /** The target's pan and tilt, when the move carries Position. */
        val position: CueAssignmentResolver.AttributeValue.Position?,
        val label: String,
    )

    /** The whole of what the service reports, by stack: the `moveInDark.state` frame's body. */
    data class Snapshot(val stacks: Map<Int, List<HeadStatus>>) {
        companion object {
            val EMPTY = Snapshot(emptyMap())
        }
    }

    /**
     * What a GO reads of Move in dark, in its own transaction: the stack's [settings], the incoming
     * [cue]'s own, and whether the stack moves any head at all ([active]: it is on, or a cue of its
     * list sets *Early* or *Late* of its own).
     */
    data class Go(val settings: MoveInDarkSettings, val cue: CueMoveInDarkSettings, val active: Boolean)

    /** The move a contributor key carries, for the key stack's *Move in dark · for Q12* row (D11). */
    data class Holding(val stackId: Int, val targetEntryId: Int, val targetCueId: Int, val label: String)

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
        /** Each head's rows as last published, unstamped. */
        val publishedByHead = LinkedHashMap<String, List<CueAssignmentResolver.Assignment>>()
        /** The fade a head's next published change takes, where it is not its move time (a join) or 0 (a release). */
        val pendingFade = HashMap<String, Long>()
        /** Keys to write afresh after the next publish: a catch-up ending a ramp in progress. */
        val snapKeys = HashSet<CueAssignmentResolver.Key>()
        @Volatile var dirty = true
        /** Bumped by every GO and re-plan request: a plan made across one is stale and made again. */
        var generation = 0L
        /** Nothing in it moves a head: a GO or a standby re-plans nothing until a setting changes. */
        var off = false
        /** How the GO in progress catches up its incoming cue's heads ([beforeGo]); read and cleared by [afterGo]. */
        var catchUp: MoveInDarkSettings? = null
        var catchUpCue: CueMoveInDarkSettings = CueMoveInDarkSettings.DEFAULT
        /** The heads the live cue lights, as the last plan read them. */
        var litByLive: Set<String> = emptySet()
        /** The live entry's own priority; [priority] rises above it during a GO back, then settles. */
        var livePriority = priority
        var settleAt = 0L
        /** The heads dark as the GO in progress began ([beforeGo]); read and cleared by [afterGo]. */
        var darkAtGo: Set<String> = emptySet()

        val published: Boolean get() = publishedByHead.isNotEmpty()
    }

    private val lock = Any()
    private val stacks = ConcurrentHashMap<Int, StackRun>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    private var classified: Map<String, HeadIntensity> = emptyMap()
    private var classifiedAt = -1L

    private val _state = MutableStateFlow(Snapshot.EMPTY)

    /** Every reported head of every stack, republished on a change of state at most every [FRAME_MS]. */
    val state: StateFlow<Snapshot> = _state.asStateFlow()
    private var lastFrameAt = 0L

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
            for (run in stacks.values) if (run.published) cueLayer.removeAssignments(contributorId(run.stackId))
            stacks.clear()
        }
        _state.value = Snapshot.EMPTY
    }

    // ─── What changes "next" ─────────────────────────────────────────────────────────────────

    override fun cueListChanged() = replanAll()
    override fun cueStackListChanged() = replanAll()
    override fun cuesRecomposed(cueIds: List<Int>) = replanAll()
    override fun lookListChanged() = replanAll()
    override fun paletteListChanged() = replanAll()
    override fun fixturesChanged() = replanAll()

    /**
     * Every live stack re-plans: a stack's list, a cue's rows or the patch changed (D9). Marked, not
     * invalidated: a plan already being made is still applied and the stack plans once more after it,
     * so the announcement every GO route makes does not throw away the plan the GO itself asked for.
     */
    fun replanAll() {
        synchronized(lock) {
            for (run in stacks.values) run.dirty = true
        }
        wake.trySend(Unit)
    }

    /**
     * A GO on [stackId] is about to bring [entryId]'s cue on at [priority]: an earlier GO's hand-over
     * ends, the heads dark now are noted for [afterGo]'s catch-up, and the contributor is restamped
     * above the incoming cue — and above the outgoing one, which outranks it on a GO back — so neither
     * wins a held head back for a frame.
     */
    fun beforeGo(stackId: Int, entryId: Int, priority: Int, go: Go) = guarded("before a GO on stack $stackId") {
        synchronized(lock) {
            val run = stacks.getOrPut(stackId) { StackRun(stackId, priority, entryId) }
            val stamped = maxOf(run.priority, priority)
            run.livePriority = priority
            run.liveEntryId = entryId
            run.generation++
            // The GO read the settings, so its catch-up never waits on a first plan.
            val catchUp = go.cue.effective(go.settings)
            run.catchUp = catchUp
            run.catchUpCue = go.cue
            run.off = !go.active
            run.heads.entries.removeIf { it.value.handOverUntil != null }
            // A GO that moves nothing still lets go of a head held for another cue that it lights.
            run.darkAtGo = if (catchUp != null || run.heads.values.any { it.held }) darkHeads() else emptySet()
            restamp(run, stamped)
            publish(run)
        }
    }

    /**
     * [stackId]'s GO onto [entryId] (its cue [cueId], labelled [label]) has landed, its crossfade
     * [fadeMs] long: every head moved for that entry hands over, every head dark as the GO began that
     * the cue lights catches up (D7), and the stack re-plans, off this thread.
     */
    fun afterGo(stackId: Int, entryId: Int, cueId: Int, fadeMs: Long, label: String = "") = guarded("after a GO on stack $stackId") {
        synchronized(lock) {
            val run = stacks[stackId] ?: return@guarded
            val now = clock()
            val until = now + fadeMs
            run.settleAt = until
            for (head in run.heads.values) {
                if (head.held && head.move.targetEntryId == entryId) head.handOverUntil = until
            }
            val settings = run.catchUp
            val cueSetting = run.catchUpCue
            val dark = run.darkAtGo
            run.catchUp = null
            run.catchUpCue = CueMoveInDarkSettings.DEFAULT
            run.darkAtGo = emptySet()
            if (dark.isNotEmpty()) catchUp(run, entryId, cueId, label, settings, cueSetting, dark, until)
            publish(run)
            run.generation++
            if (run.off) return@guarded
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
            run.livePriority = priority
            // Still above a GO back's outgoing cue, which no restamp moves, until its fade is over.
            restamp(run, if (clock() < run.settleAt) maxOf(run.priority, priority) else priority)
        }
    }

    /** [stackId] stopped: its moves go (D9). */
    fun onStackStopped(stackId: Int) = synchronized(lock) {
        val run = stacks.remove(stackId) ?: return@synchronized
        if (run.published) cueLayer.removeAssignments(contributorId(stackId))
        emitFrame(force = true)
    }

    /**
     * A same-project reload has brought the stacks back (`Show.resume`), the outgoing show's last frame
     * [carried]: each live stack is planned now, and a head that frame shows dark and already where its
     * plan would move it starts out [HeadState.SET], so nothing moves on the reload. Any other head
     * starts idle and moves as usual.
     */
    fun resume(carried: Map<Universe, Map<Int, UByte>>) {
        val heads = synchronized(lock) { heads() }
        val covered = runningFx()
        for (stackId in stacks.keys.toList()) {
            val plan = planOf(stackId, heads) ?: continue
            synchronized(lock) {
                val run = stacks[stackId] ?: return@synchronized
                apply(run, plan)
                val candidates = run.heads.filter { (key, head) ->
                    val intensity = heads[key] ?: return@filter false
                    head.handOverUntil == null && !head.held && intensity.darkIn(carried) && !intensity.hasIntensityFx(covered, heads)
                }
                if (candidates.isEmpty()) return@synchronized
                for ((key, head) in candidates) {
                    head.state = HeadState.SET
                    run.pendingFade[key] = 0L
                }
                publish(run)
                val misplaced = candidates.filter { (_, head) -> !shownIn(head.move.rows, carried) }
                if (misplaced.isEmpty()) return@synchronized
                for ((key, head) in misplaced) {
                    head.state = null
                    run.pendingFade[key] = 0L
                }
                publish(run)
            }
        }
        synchronized(lock) { emitFrame(force = true) }
    }

    /** Every reported head of [stackId], by key. */
    fun statusOf(stackId: Int): Map<String, HeadStatus> = synchronized(lock) {
        val run = stacks[stackId] ?: return@synchronized emptyMap()
        statusesOf(run, clock()).associateBy { it.head }
    }

    /**
     * Every key a held head's rows cover, `(stackId, targetKey, attribute)`, and the move it is held
     * for — [holdingOf] for a whole provenance pass, under the lock once.
     */
    fun holdings(): Map<Triple<Int, String, String>, Holding> = synchronized(lock) {
        val out = HashMap<Triple<Int, String, String>, Holding>()
        for ((stackId, run) in stacks) {
            for (head in run.heads.values) {
                if (!head.held) continue
                val holding = Holding(stackId, head.move.targetEntryId, head.move.targetCueId, head.move.label)
                for (row in head.move.rows) out.putIfAbsent(Triple(stackId, row.targetKey, row.attribute), holding)
            }
        }
        out
    }

    /** The move [targetKey]'s [attribute] is held for by [contributorCueId], or null. */
    fun holdingOf(contributorCueId: Int, targetKey: String, attribute: String): Holding? = synchronized(lock) {
        val stackId = stackOfContributor(contributorCueId) ?: return@synchronized null
        val run = stacks[stackId] ?: return@synchronized null
        val head = run.heads.values.firstOrNull { head ->
            head.held && head.move.rows.any { it.targetKey == targetKey && it.attribute == attribute }
        } ?: return@synchronized null
        Holding(stackId, head.move.targetEntryId, head.move.targetCueId, head.move.label)
    }

    /**
     * One pass: re-plan what is marked, watch every planned head, publish what changed. The plans are
     * made outside the lock, so a GO's [beforeGo] never waits on one; a plan made across a GO or a
     * re-plan request is dropped and made again on the next pass.
     */
    internal fun tick() {
        val (heads, toPlan) = synchronized(lock) {
            if (stacks.isEmpty()) {
                emitFrame(force = false)
                return
            }
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
            val covered = runningFx()
            val now = clock()
            for (run in stacks.values) {
                if (run.heads.isEmpty() && !run.published) continue
                for ((key, head) in run.heads) watch(run, key, head, heads[key], heads, covered, now)
                endHandOvers(run, now)
                if (run.priority != run.livePriority && now >= run.settleAt) restamp(run, run.livePriority)
                publish(run)
            }
            emitFrame(force = false)
        }
    }

    /** A GO-path hook's work: a failure is logged and the GO goes on without the move, as a failed plan is. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Move in dark failed {}: {}", what, e.message, e)
        }
    }

    // ─── Under the lock ──────────────────────────────────────────────────────────────────────

    private fun heads(): Map<String, HeadIntensity> {
        val version = fixtures.structureVersion
        if (version != classifiedAt) {
            classified = HeadIntensity.classify(fixtures)
            classifiedAt = version
        }
        return classified
    }

    /** Every head dark on the output now, with no Intensity FX on it (D4). */
    private fun darkHeads(): Set<String> {
        val heads = heads()
        val covered = runningFx()
        return heads.filterValues { it.isDark(fixtures.controllerOrNull(it.dmxUniverse)) && !it.hasIntensityFx(covered, heads) }.keys
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
     * [plan] over [run]'s heads (D9). A head handing over keeps going and takes its new move after;
     * one heading for the same target keeps its state; a held head given another target moves there
     * over its move time; a held head given none, or that the live cue now lights, goes back the same
     * way. A head not held simply takes its new move.
     */
    private fun apply(run: StackRun, plan: MoveInDarkPlanner.Plan) {
        run.off = plan.off
        run.litByLive = plan.litByLive
        val now = clock()
        val kept = LinkedHashMap<String, HeadRun>()
        for ((key, head) in run.heads) {
            val next = plan.moves[key]
            when {
                head.handOverUntil != null -> { head.following = next; kept[key] = head }
                key in plan.litByLive -> {
                    if (head.held) run.pendingFade[key] = head.move.time.toMillis()
                    if (next != null) {
                        head.move = next
                        head.state = null
                        kept[key] = head
                    }
                }
                next == null -> if (head.held) run.pendingFade[key] = head.move.time.toMillis()
                next.targetEntryId == head.move.targetEntryId && next.rows == head.move.rows -> {
                    head.move = next
                    kept[key] = head
                }
                else -> {
                    head.move = next
                    if (head.held) {
                        head.state = HeadState.MOVING
                        head.since = now
                    }
                    kept[key] = head
                }
            }
        }
        for ((key, move) in plan.moves) if (key !in kept) kept[key] = HeadRun(move)
        run.heads.clear()
        run.heads.putAll(kept)
    }

    private fun watch(
        run: StackRun,
        key: String,
        head: HeadRun,
        intensity: HeadIntensity?,
        heads: Map<String, HeadIntensity>,
        covered: Set<Pair<String, String>>,
        now: Long,
    ) {
        if (head.handOverUntil != null || intensity == null) return
        val dark = intensity.isDark(fixtures.controllerOrNull(intensity.dmxUniverse)) && !intensity.hasIntensityFx(covered, heads)
        when (head.state) {
            // The live cue lights it: dark only because something above holds it out, so nothing moves.
            null -> if (dark && key !in run.litByLive) { head.state = HeadState.WAITING; head.since = now }
            HeadState.WAITING -> when {
                !dark -> head.state = null
                now - head.since >= head.move.delay.toMillis() -> { head.state = HeadState.MOVING; head.since = now }
            }
            HeadState.MOVING, HeadState.SET -> when {
                !dark -> {
                    // Lit before its GO (D8): back to what the live cues say, over the move time.
                    head.state = if (key in run.litByLive) null else HeadState.LIT_EARLY
                    head.since = now
                    run.pendingFade[key] = head.move.time.toMillis()
                }
                head.state == HeadState.MOVING && now - head.since >= head.move.time.toMillis() -> head.state = HeadState.SET
            }
            HeadState.LIT_EARLY -> if (dark) { head.state = HeadState.WAITING; head.since = now }
        }
    }

    /**
     * D7's catch-up: every head in [dark] that [cueId]'s published rows light and that is not already
     * set for this entry takes those rows' moving families at once, held to [until] — but a head the
     * cue keeps live ([cueSetting]), which flies in lit. With no [settings] (the cue moves nothing in
     * the dark) nothing catches up, and a head held for another cue that it lights is let go at once.
     */
    private fun catchUp(
        run: StackRun,
        entryId: Int,
        cueId: Int,
        label: String,
        settings: MoveInDarkSettings?,
        cueSetting: CueMoveInDarkSettings,
        dark: Set<String>,
        until: Long,
    ) {
        val heads = heads()
        val rows = effectiveRows(cueLayer.assignmentsOf(cueId))
        val covered = runningFx()
        val fxHeads = heads.filterValues { it.hasIntensityFx(covered, heads) }.keys
        val lit = MoveInDarkPlanner.litHeads(MoveInDarkPlanner.CueContent(rows, fxHeads), heads)
        val byHead = rows.groupBy { it.targetKey }
        for (key in dark) {
            if (key !in lit) continue
            val existing = run.heads[key]
            val head = heads[key] ?: continue
            // Handing over for this entry: its rows are the cue's own.
            if (settings == null && existing?.handOverUntil != null && existing.move.targetEntryId == entryId) continue
            val moving = if (settings == null || cueSetting.keepsLive(key, head.parentKey)) emptyList()
            else byHead[key].orEmpty().filter { MoveInDarkPlanner.moves(it, head, settings) }
            // Set for this entry with what it now publishes: the plain hand-over. An edit not yet
            // re-planned leaves it holding old rows, which the cue's own replace at once.
            if (existing?.state == HeadState.SET && existing.move.targetEntryId == entryId && sameValues(existing.move.rows, moving)) continue
            if (moving.isEmpty()) {
                // Coming up with nothing to move, or kept live: a move held for another cue goes now, while it is dark.
                if (existing?.held == true) {
                    run.heads.remove(key)
                    run.pendingFade[key] = 0L
                }
                continue
            }
            val timing = settings ?: continue
            val move = MoveInDarkPlanner.HeadMove(key, entryId, cueId, moving, timing.delay, timing.time, label)
            run.heads[key] = HeadRun(move).also {
                it.state = HeadState.SET
                it.handOverUntil = until
            }
            run.pendingFade[key] = 0L
            for (row in moving) run.snapKeys += CueAssignmentResolver.Key.fixture(row.targetKey, row.attribute)
        }
    }

    /**
     * A hand-over ends with its GO's crossfade: once its time is up **and** the target cue's rows are
     * at full weight, since the crossfade's frames can run behind the clock and a release a frame
     * early lets the cue blend the head from the outgoing cue's place. [HAND_OVER_GRACE_MS] past its
     * time it ends regardless, so a weight a cancelled fade left behind cannot hold it for ever.
     */
    private fun endHandOvers(run: StackRun, now: Long) {
        val ended = run.heads.filterValues { head ->
            val until = head.handOverUntil ?: return@filterValues false
            now >= until && (cueLayer.fadeWeightOf(head.move.targetCueId) >= 1.0 || now >= until + HAND_OVER_GRACE_MS)
        }
        for ((key, head) in ended) {
            val next = head.following
            if (next == null) run.heads.remove(key) else run.heads[key] = HeadRun(next)
        }
    }

    private fun restamp(run: StackRun, priority: Int) {
        if (priority == run.priority) return
        run.priority = priority
        if (run.published) cueLayer.repriorityAssignments(mapOf(contributorId(run.stackId) to priority + PRIORITY_OFFSET))
    }

    /**
     * Bring [run]'s contributor to its held heads' rows, one fade at a time: each head whose rows
     * changed takes its [StackRun.pendingFade], else its move time for a join or a retarget and 0 for
     * a release. Then the keys a catch-up named are written afresh.
     */
    private fun publish(run: StackRun) {
        val id = contributorId(run.stackId)
        val desired = LinkedHashMap<String, List<CueAssignmentResolver.Assignment>>()
        for ((key, head) in run.heads) if (head.held && head.move.rows.isNotEmpty()) desired[key] = head.move.rows
        val changed = (desired.keys + run.publishedByHead.keys).filter { desired[it] != run.publishedByHead[it] }
        // `clearAll` (an operator's clear-all-FX) can take the rows without telling us.
        val lost = changed.isEmpty() && run.published && !cueLayer.hasAssignments(id)
        if (changed.isNotEmpty() || lost) {
            val groups = changed.groupBy { key ->
                run.pendingFade.remove(key) ?: desired[key]?.let { run.heads[key]?.move?.time?.toMillis() } ?: 0L
            }.toMutableMap()
            if (lost) groups[0L] = groups[0L].orEmpty()
            val stamped = run.priority + PRIORITY_OFFSET
            val current = LinkedHashMap(run.publishedByHead)
            for ((fade, keys) in groups.entries.sortedBy { it.key }) {
                for (key in keys) {
                    val rows = desired[key]
                    if (rows == null) current.remove(key) else current[key] = rows
                }
                val flat = current.values.flatMap { rows -> rows.map { it.asContributorRow(id, stamped) } }
                cueLayer.setContributorAssignments(id, flat, TransmitFade(fade, EasingCurve.LINEAR))
            }
            run.publishedByHead.clear()
            run.publishedByHead.putAll(desired)
        }
        run.pendingFade.keys.retainAll(run.heads.keys + run.publishedByHead.keys)
        if (run.snapKeys.isNotEmpty()) {
            val keys = HashSet(run.snapKeys)
            run.snapKeys.clear()
            cueLayer.snapKeys(keys)
        }
    }

    private fun statusesOf(run: StackRun, now: Long): List<HeadStatus> = run.heads.values.mapNotNull { head ->
        val state = head.state ?: return@mapNotNull null
        val endsAt = when (state) {
            HeadState.WAITING -> head.since + head.move.delay.toMillis()
            HeadState.MOVING -> head.since + head.move.time.toMillis()
            HeadState.SET, HeadState.LIT_EARLY -> null
        }
        HeadStatus(
            head = head.move.head,
            targetEntryId = head.move.targetEntryId,
            targetCueId = head.move.targetCueId,
            state = state,
            remainingMs = endsAt?.let { (it - now).coerceAtLeast(0) },
            endsAtMs = endsAt,
            families = head.move.rows.mapTo(LinkedHashSet()) { MoveInDarkPlanner.familyOf(it) },
            position = head.move.rows.firstNotNullOfOrNull { it.value as? CueAssignmentResolver.AttributeValue.Position },
            label = head.move.label,
        )
    }

    /**
     * Republish [state] when what it says has changed — a head's state, its target, what it moves or
     * when its timer ends, not the time left, which a client counts down — at most every [FRAME_MS],
     * unless [force]d.
     */
    private fun emitFrame(force: Boolean) {
        val now = clock()
        if (!force && now - lastFrameAt < FRAME_MS) return
        val next = Snapshot(
            stacks.values.mapNotNull { run -> statusesOf(run, now).takeIf { it.isNotEmpty() }?.let { run.stackId to it } }
                .sortedBy { it.first }.toMap(),
        )
        if (!force && next.withoutTimes() == _state.value.withoutTimes()) return
        lastFrameAt = now
        _state.value = next
    }

    private fun Snapshot.withoutTimes(): Snapshot = Snapshot(stacks.mapValues { (_, heads) -> heads.map { it.copy(remainingMs = null) } })

    /** Whether every channel [rows] write reads in [carried] what the controllers hold now. */
    private fun shownIn(rows: List<CueAssignmentResolver.Assignment>, carried: Map<Universe, Map<Int, UByte>>): Boolean = rows.all { row ->
        val fixture = runCatching { fixtures.untypedGroupableFixture(row.targetKey) }.getOrNull() ?: return@all false
        AttributeChannelWriter.channelsFor(fixture, row.attribute).all { write ->
            val controller = fixtures.controllerOrNull(write.universe) ?: return@all false
            carried[write.universe]?.get(write.channel) == controller.currentValues[write.channel]
        }
    }

    companion object {
        /** How often planned heads are watched. */
        const val TICK_MS = 50L

        /** The `moveInDark.state` frame's floor. */
        const val FRAME_MS = 100L

        /** How long past its fade a hand-over waits for the target cue to reach full weight. */
        const val HAND_OVER_GRACE_MS = 2_000L

        /** Above the live cue (`+1`), below the next entry's (`+1 000`). */
        const val PRIORITY_OFFSET = 500

        /** [stackId]'s contributor in [CueAssignmentLayer]: negative, so never a cue's id. */
        fun contributorId(stackId: Int): Int = -stackId

        /** The stack whose contributor [cueId] is, or null for a cue. */
        fun stackOfContributor(cueId: Int): Int? = if (cueId < 0) -cueId else null

        /** Whether [a] and [b] set the same values on the same keys, whoever's rows they are. */
        private fun sameValues(a: List<CueAssignmentResolver.Assignment>, b: List<CueAssignmentResolver.Assignment>): Boolean =
            a.associate { (it.targetKey to it.attribute) to it.value } == b.associate { (it.targetKey to it.attribute) to it.value }

        /**
         * A cue's published rows with one per (head, attribute): a direct row over the group row it
         * beats ([CueAssignmentResolver]'s specificity).
         */
        private fun effectiveRows(rows: List<CueAssignmentResolver.Assignment>): List<CueAssignmentResolver.Assignment> =
            rows.groupBy { it.targetKey to it.attribute }.values.map { same -> same.firstOrNull { !it.targetIsGroup } ?: same.first() }

        /** A row of its own: the target's group origin, which specificity would drop beside any direct row, stays behind. */
        private fun CueAssignmentResolver.Assignment.asContributorRow(id: Int, priority: Int) = copy(
            cueId = id, priority = priority, fadeWeight = 1.0, fadeDurationMs = null,
            targetIsGroup = false, layerWinner = null,
        )
    }
}

/** Whether every level channel of this head reads 0 in [frame] (a reload's carried frame). */
private fun HeadIntensity.darkIn(frame: Map<Universe, Map<Int, UByte>>): Boolean {
    if (levels.isEmpty()) return false
    val values = frame[dmxUniverse] ?: return false
    return levels.all { (values[it]?.toInt() ?: 0) == 0 }
}
