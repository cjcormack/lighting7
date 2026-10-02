package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCueScenery
import uk.me.cormack.lighting7.models.DaoCueSceneryRow
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackScenery
import uk.me.cormack.lighting7.models.DaoCueStackSceneryRow
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoLookSceneryRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.models.LayerSourceKind
import uk.me.cormack.lighting7.fx.ProgrammerLayer
import uk.me.cormack.lighting7.models.decodeSceneryState
import uk.me.cormack.lighting7.models.sceneryInfo
import uk.me.cormack.lighting7.models.sceneryKeysOf
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.show.SceneryResolver
import uk.me.cormack.lighting7.show.Show
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos

private val logger = LoggerFactory.getLogger("SceneryService")

/**
 * The desk's live scenery (stage-view plan session 8): what each element named by a scenery change
 * shows now, and how it got there — published as `scenery.state` (`plugins/ScenerySocket.kt`),
 * `StateFlow`-backed so the subscription is the connect snapshot.
 *
 * **Resolution is [SceneryResolver]'s**; this class feeds it the live desk — the stacks
 * [uk.me.cormack.lighting7.fx.CueStackManager] holds live, the cues the AI's `apply_cue` applied
 * beside them, the programmer's Look layers (unless the programmer is blind: blind is not on stage)
 * and the rows — and turns each recompute into a **transition** per element: the state it is
 * leaving ([Entry.from], as drawn at that moment, so a retarget mid-move starts where the piece
 * is), the state it is going to, a start and a duration. A move takes the transition of the change
 * that made it **when that change belongs to the cue just GO'd**; everything else — a stack
 * stopping, a Look pressed, an edit, GO TO landing a change an earlier cue made — snaps.
 *
 * Every hook returns at once and never throws into its caller: it updates the live table and queues
 * the recompute on one worker thread (§"Resolution" below for why), so a GO can neither fail nor
 * stall because a scenery read did.
 */
class SceneryService(private val state: State) {
    /** One element's live scenery: where it is going, where it was, and the move between. */
    data class Entry(
        val elementUuid: UUID,
        val elementName: String,
        val state: ElementStates,
        val from: ElementStates,
        /** Wall clock, millis — the frame's `startedAt`, and its `elapsedMs` at send. */
        val startedAtMs: Long,
        val durationMs: Long,
    )

    data class Frame(val projectId: Int?, val entries: List<Entry>)

    /** A preview's answer for one element: what the next GO would move it to, from where it is now. */
    data class PreviewEntry(
        val elementUuid: UUID,
        val state: ElementStates,
        val from: ElementStates,
        val durationMs: Long,
    )

    private val lock = Any()
    private val _frame = MutableStateFlow(Frame(null, emptyList()))
    val frame: StateFlow<Frame> = _frame.asStateFlow()

    private var projectId: Int? = null
    private var entries: Map<UUID, Entry> = emptyMap()
    private var seq = 0L
    /** The cue each stack last went to by the stack manager's GOs, and when. */
    private val goes = HashMap<Int, Pair<Int, Long>>()
    /** Cues the AI's `apply_cue` applied outside the stack manager, by stack. */
    private val applied = HashMap<Int, Pair<Int, Long>>()
    /** GOs no recompute has read yet: the cue each stack went to. */
    private val pendingGos = HashMap<Int, Int>()

    private var listenedFixtures: uk.me.cormack.lighting7.show.Fixtures? = null
    private var layersJob: Job? = null

    private val listener = object : FixturesChangeListener {
        override fun cueListChanged() = recompute()
        override fun cueStackListChanged() = recompute()
        override fun lookListChanged() = recompute()
        override fun stageElementListChanged() = recompute()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────────────────

    /**
     * Follow [show]: its list-change events (an edit anywhere moves the stage) and its programmer's
     * layer stack. Called on start and again on every project switch, which also forgets the old
     * show's live cues — they belong to a show that is gone.
     */
    fun attach(show: Show, scope: CoroutineScope) {
        synchronized(lock) {
            listenedFixtures?.unregisterListener(listener)
            show.fixtures.registerListener(listener)
            listenedFixtures = show.fixtures
            goes.clear()
            applied.clear()
            pendingGos.clear()
            entries = emptyMap()
        }
        layersJob?.cancel()
        layersJob = show.programmerStore.layersFlow.onEach { recompute() }.launchIn(scope)
        recompute()
    }

    fun close() {
        layersJob?.cancel()
        layersJob = null
        synchronized(lock) {
            listenedFixtures?.unregisterListener(listener)
            listenedFixtures = null
        }
        worker.shutdownNow()
    }

    // ─── Hooks ───────────────────────────────────────────────────────────────────────────────

    /** A stack GO — `CueStackManager.activateCueInStack`, by every path that fires one. */
    fun onCueLive(stackId: Int, cueId: Int) {
        synchronized(lock) {
            goes[stackId] = cueId to ++seq
            applied.remove(stackId)
            pendingGos[stackId] = cueId
        }
        recompute()
    }

    /**
     * A cue applied outside the stack manager — the AI's `apply_cue` and the REST apply route. It
     * stands in for its stack's live cue until the stack goes or stops; [replaceAll] lets go of
     * every other applied cue, as the apply stops their effects.
     */
    fun onCueApplied(stackId: Int, cueId: Int, replaceAll: Boolean) {
        synchronized(lock) {
            if (replaceAll) applied.clear()
            applied[stackId] = cueId to ++seq
            pendingGos[stackId] = cueId
        }
        recompute()
    }

    /** `CueStackManager.deactivateStack`: the stack's set and cues let go. */
    fun onStackStopped(stackId: Int) {
        synchronized(lock) {
            goes.remove(stackId)
            applied.remove(stackId)
            pendingGos.remove(stackId)
        }
        recompute()
    }

    /** `stop_cue` and the REST stop route: an applied cue lets go. */
    fun onCueStopped(cueId: Int) {
        synchronized(lock) { applied.entries.removeIf { it.value.first == cueId } }
        recompute()
    }

    /** The programmer went blind or came back: its Looks leave or rejoin the stage. */
    fun onBlindChanged() = recompute()

    // ─── Resolution ──────────────────────────────────────────────────────────────────────────
    //
    // **A recompute never runs on the caller's thread, and [lock] is never held across a read.**
    // The hooks are called from places that may be inside a transaction — a stack delete calls
    // `deactivateStack` inside its own — and the pool is one SQLite connection. Reading there under a
    // lock another thread could hold while *it* waited for that connection would stall both until
    // Hikari timed out. So each hook updates the live table under [lock] and queues a recompute on
    // [worker], one thread, which keeps them in order; the worker reads with no lock held and takes
    // [lock] only to fold the result into [entries].
    //
    // **A GO's moves start on the first run that reads it**, whichever hook queued that run: the hook
    // records it in [pendingGos] with the live table, and the run takes both in one step.

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scenery").apply { isDaemon = true }
    }

    /**
     * Whether a recompute is queued and has not started reading yet. A fader riding a layer's amount
     * emits `layersFlow` per move, and every one of those would otherwise queue a read on the
     * one-connection pool; one queued recompute that has not read yet answers them all.
     */
    private val queued = AtomicBoolean(false)

    /** Queue a recompute. Returns at once; the frame follows on the worker. */
    fun recompute() {
        if (!queued.compareAndSet(false, true)) return
        try {
            worker.execute {
                queued.set(false)
                recomputeNow()
            }
        } catch (_: RejectedExecutionException) {
            // Closed: the desk is shutting down.
        }
    }

    /**
     * Park the worker until the returned call releases it — for tests, which need two hooks to queue
     * behind each other the way a burst of GOs does on a busy desk.
     */
    internal fun holdWorker(): () -> Unit {
        val release = java.util.concurrent.CountDownLatch(1)
        val parked = java.util.concurrent.CountDownLatch(1)
        worker.execute {
            parked.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        parked.await(10, TimeUnit.SECONDS)
        return { release.countDown() }
    }

    /** Wait for every recompute queued so far — for tests, which read the frame straight after a GO. */
    internal fun awaitIdle() {
        runCatching { worker.submit {}.get(10, TimeUnit.SECONDS) }
    }

    private fun recomputeNow() {
        try {
            val (live, gos) = synchronized(lock) {
                val live = liveCues()
                // A stack stopped, or let go of an applied cue, since its GO has nothing left to move.
                val gos = pendingGos.filter { (stackId, cueId) -> live[stackId]?.first == cueId }
                pendingGos.clear()
                live to gos
            }
            val input = load(live)
            synchronized(lock) {
                if (input == null) {
                    publish(null, emptyMap())
                    return
                }
                val now = System.currentTimeMillis()
                val resolved = SceneryResolver.resolve(input.elements, input.stacks, input.lookScenery, input.programmerLookIds)
                val next = LinkedHashMap<UUID, Entry>()
                val carried = if (projectId == input.projectId) entries else emptyMap()
                for (r in resolved.values.sortedBy { it.element.name.lowercase() }) {
                    // An element no change named until now is drawn at its base.
                    val prev = carried[r.element.uuid]
                    val was = prev?.state ?: r.element.base
                    next[r.element.uuid] = when {
                        prev != null && prev.state == r.state -> prev.copy(elementName = r.element.name)
                        else -> Entry(
                            r.element.uuid, r.element.name, r.state,
                            from = prev?.let { displayedAt(it, now) } ?: was,
                            startedAtMs = now,
                            durationMs = if (was == r.state) 0 else durationFor(r, was, gos),
                        )
                    }
                }
                publish(input.projectId, next)
            }
        } catch (e: Exception) {
            logger.warn("scenery: recompute failed — {}", e.message)
        }
    }

    /**
     * What the stage's scenery would be if [cueId] of [stackId] went now, from where it is now —
     * `POST cue-stacks/{id}/preview`'s scenery, which the Next GO vis source animates. Nothing is
     * published or remembered. Read on the caller's thread (a request, outside any transaction),
     * with [lock] held only to snapshot what it compares against.
     */
    fun preview(stackId: Int, cueId: Int): List<PreviewEntry> {
        val (live, current) = synchronized(lock) {
            liveCues().toMutableMap().also { it[stackId] = cueId to (seq + 1) } to entries
        }
        val input = load(live) ?: return emptyList()
        val now = System.currentTimeMillis()
        val resolved = SceneryResolver.resolve(input.elements, input.stacks, input.lookScenery, input.programmerLookIds)
        return resolved.values.sortedBy { it.element.name.lowercase() }.map { r ->
            val prev = current[r.element.uuid]
            val was = prev?.state ?: r.element.base
            val from = prev?.let { displayedAt(it, now) } ?: was
            val duration = if (was == r.state) 0 else durationFor(r, was, mapOf(stackId to cueId))
            PreviewEntry(r.element.uuid, r.state, from, duration)
        }
    }

    private fun publish(projectId: Int?, next: Map<UUID, Entry>) {
        this.projectId = projectId
        entries = next
        _frame.value = Frame(projectId, next.values.toList())
    }

    /**
     * The cue each live stack is on, and when it went — [goes], with `apply_cue`'s over it. Under
     * [lock]. Not the manager's own table: it changes a stack's cue before the GO's hook fires.
     */
    private fun liveCues(): Map<Int, Pair<Int, Long>> {
        val out = HashMap(goes)
        for ((stackId, cue) in applied) {
            val existing = out[stackId]
            if (existing == null || cue.second > existing.second) out[stackId] = cue
        }
        return out
    }

    private class Input(
        val projectId: Int,
        val elements: List<SceneryResolver.Element>,
        val stacks: List<SceneryResolver.LiveStack>,
        val lookScenery: Map<Int, List<SceneryResolver.Change>>,
        val programmerLookIds: List<Int>,
    )

    private fun load(live: Map<Int, Pair<Int, Long>>): Input? {
        val show = state.showOrNull ?: return null
        val project = runCatching { state.projectManager.currentProject }.getOrNull() ?: return null
        val programmerLooks = if (show.programmerStore.blind) emptyList() else programmerLookIds(show.programmerStore.layers)
        return transaction(state.database) {
            val dao = DaoProject.findById(project.id) ?: return@transaction null
            val all = DaoStageElement.find { DaoStageElements.project eq dao.id }.toList()
            if (all.isEmpty()) return@transaction Input(dao.id.value, emptyList(), emptyList(), emptyMap(), emptyList())
            val ids = all.map { it.id }
            // Only elements a scenery change names are under the show's control; the rest are their base.
            val named = (
                DaoCueSceneryRow.find { DaoCueScenery.element inList ids }.map { it.element.id.value } +
                    DaoCueStackSceneryRow.find { DaoCueStackScenery.element inList ids }.map { it.element.id.value } +
                    DaoLookSceneryRow.find { DaoLookScenery.element inList ids }.map { it.element.id.value }
                ).toSet()
            val elements = all.filter { it.id.value in named }.map { it.toSceneryElement() }

            val stacks = live.mapNotNull { (stackId, cue) ->
                val stack = DaoCueStack.findById(stackId)?.takeIf { it.project.id == dao.id } ?: return@mapNotNull null
                liveStack(stack, cue.first, cue.second)
            }
            val lookIds = (stacks.flatMap { it.layeredLookIds } + programmerLooks).toSet()
            val lookScenery = if (lookIds.isEmpty()) emptyMap() else
                DaoLookSceneryRow.find { DaoLookScenery.look inList lookIds }
                    .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
                    .groupBy({ it.look.id.value }) { SceneryResolver.Change(it.element.id.value, decodeSceneryState(it.stateJson)) }
            Input(dao.id.value, elements, stacks, lookScenery, programmerLooks)
        }
    }

    /** One live stack's set, its standard cues down to the live one, and the live cue's layered Looks. */
    private fun liveStack(stack: DaoCueStack, liveCueId: Int, goSeq: Long): SceneryResolver.LiveStack? {
        val all = DaoCue.find { DaoCues.cueStack eq stack.id }.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        val liveIndex = all.indexOfFirst { it.id.value == liveCueId }.takeIf { it >= 0 } ?: return null
        val cues = all.take(liveIndex + 1).filter { it.cueType == CueType.STANDARD.name || it.id.value == liveCueId }
        val set = DaoCueStackSceneryRow.find { DaoCueStackScenery.stack eq stack.id }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .map { SceneryResolver.Change(it.element.id.value, decodeSceneryState(it.stateJson)) }
        val rows = DaoCueSceneryRow.find { DaoCueScenery.cue inList cues.map { it.id } }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .groupBy { it.cue.id.value }
        val live = all[liveIndex]
        val layered = DaoCueLayer.find { DaoCueLayers.cue eq live.id }
            .filter { it.enabled && it.amount > 0.0 && it.delay == null && it.interval == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .mapNotNull { it.look?.id?.value }
        return SceneryResolver.LiveStack(
            stackId = stack.id.value,
            goSeq = goSeq,
            set = set,
            cues = cues.map { cue ->
                val fade = cue.fadeDuration?.toMillis() ?: 0L
                SceneryResolver.Cue(
                    cueId = cue.id.value,
                    label = cue.cueNumber?.takeIf { it.isNotBlank() } ?: cue.name,
                    changes = rows[cue.id.value].orEmpty().map {
                        SceneryResolver.CueChange(it.element.id.value, decodeSceneryState(it.stateJson), it.transition?.toMillis() ?: fade)
                    },
                )
            },
            layeredLookIds = layered,
        )
    }

    companion object {
        /** The programmer's live Look layers, bottom first: templates carry no scenery (D11). */
        fun programmerLookIds(layers: List<ProgrammerLayer>): List<Int> =
            layers.filter { it.source.kind == LayerSourceKind.LOOK && it.enabled && it.amount > 0.0 }
                .sortedBy { it.sortOrder }
                .map { it.source.id }

        /**
         * How long a move to [r] from [previous] takes: the transition of a change of a cue just GO'd
         * ([gos], the cue by stack) that decided a state that moved, the longest if several did; else
         * a snap.
         */
        fun durationFor(r: SceneryResolver.Resolved, previous: ElementStates, gos: Map<Int, Int>): Long {
            val changed = buildSet {
                if (r.state.visible != previous.visible) add("visible")
                if (r.state.open != previous.open) add("open")
                if (r.state.trimM != previous.trimM) add("trimM")
            }
            return changed.maxOfOrNull { key ->
                val src = r.sources[key]
                if (src is SceneryResolver.Source.CueRow && gos[src.stackId] == src.cueId) src.transitionMs else 0L
            } ?: 0L
        }

        /** Sine in-out, the curve the Stage view moves scenery on — the two must agree. */
        fun ease(t: Double): Double = 0.5 - 0.5 * cos(PI * t.coerceIn(0.0, 1.0))

        /**
         * Where [entry] is drawn at [nowMs]: the numeric states eased from `from` to `state`; a
         * piece appearing shows at once and one disappearing goes at the end, as the Stage view
         * draws it.
         */
        fun displayedAt(entry: Entry, nowMs: Long): ElementStates {
            if (entry.durationMs <= 0) return entry.state
            val t = (nowMs - entry.startedAtMs).toDouble() / entry.durationMs
            if (t >= 1.0) return entry.state
            val e = ease(t)
            fun lerp(a: Double?, b: Double?) = if (a == null || b == null) b else a + (b - a) * e
            val visible = if (entry.state.visible == true) true else entry.from.visible ?: entry.state.visible
            return ElementStates(visible, lerp(entry.from.open, entry.state.open), lerp(entry.from.trimM, entry.state.trimM))
        }
    }
}

/**
 * [this] element as [SceneryResolver] reads it: the states its kind takes, and its base for each —
 * shown when unstated, a drawn drape closed (as the Stage view draws one), a flown piece at its Z.
 * Must run inside a transaction.
 */
fun DaoStageElement.toSceneryElement(): SceneryResolver.Element {
    val info = sceneryInfo()
    val keys = sceneryKeysOf(info.kind, info.params)
    val own = info.params?.states
    return SceneryResolver.Element(
        id = id.value,
        uuid = uuid,
        name = name,
        keys = keys,
        base = ElementStates(
            visible = own?.visible ?: true,
            open = if ("open" in keys) own?.open ?: 0.0 else null,
            trimM = if ("trimM" in keys) own?.trimM ?: positionZ else null,
        ),
    )
}
