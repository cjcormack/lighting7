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
import uk.me.cormack.lighting7.models.EntryPlace
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoLookSceneryRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.models.LayerSourceKind
import uk.me.cormack.lighting7.models.MAX_SCENERY_TRANSITION
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.elementTravelS
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import uk.me.cormack.lighting7.models.entriesOf

private val logger = LoggerFactory.getLogger("SceneryService")

/**
 * The desk's live scenery (stage-view plan session 8): what each element named by a scenery change
 * shows now, and how it got there — published as `scenery.state` (`plugins/ScenerySocket.kt`),
 * `StateFlow`-backed so the subscription is the connect snapshot.
 *
 * **Resolution is [SceneryResolver]'s**; this class feeds it the live desk — the stacks
 * [uk.me.cormack.lighting7.fx.CueStackManager] holds live, the cues the AI's `apply_cue` applied
 * beside them, the programmer's Look layers and its own scenery ([ProgrammerScenery]) — unless the
 * programmer is blind: blind is not on stage, so those two are **staged** instead ([Frame.staged],
 * scenery-programmer plan D12) — and the rows, and turns each recompute into a **transition** per
 * element: the state it is leaving ([Entry.from], as drawn at that moment, so a retarget mid-move
 * starts where the piece is), the state it is going to, a start and a duration ([durationFor]): a
 * cue's own transition when the change belongs to the cue just GO'd, the operator's fade for a
 * programmer move, and otherwise the piece's own `travelS` — a pressed Look, an edit, a stack
 * stopping, GO TO landing an earlier cue's change — which a piece without one snaps (D6). Each entry
 * names what holds it ([Entry.source], D4).
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
        /** What holds the piece there — the source of its highest-tier state (D4). */
        val source: Holder = Holder.BASE,
    )

    /**
     * What holds an element where it is, as the frame names it (D4): [kind] is `base`, `set`, `cue`,
     * `cueLook`, `programmerLook` or `programmer`, with the ids and words that kind has — a set's
     * stack and its name, a cue's stack, id and label, a Look's id and name.
     */
    data class Holder(
        val kind: String,
        val stackId: Int? = null,
        val cueId: Int? = null,
        val label: String? = null,
        val lookId: Int? = null,
        val name: String? = null,
    ) {
        companion object {
            val BASE = Holder("base")
        }
    }

    /**
     * The live scenery, and — while the programmer is blind and holds something that would move a
     * piece — [staged]: each such element as it would be on leaving Blind, moving from where live
     * (or an earlier staged move) has it. Null, never empty, when Blind stages nothing (D12).
     */
    data class Frame(val projectId: Int?, val entries: List<Entry>, val staged: List<Entry>? = null)

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
    private var staged: Map<UUID, Entry> = emptyMap()
    private var seq = 0L
    /** The entry each stack last went to by the stack manager's GOs, its cue, and when. */
    private val goes = HashMap<Int, Go>()
    /**
     * Cues held loose (cue model plan §3.4), and when: each folds in as a one-cue stack in GO order —
     * its own rows, no set, no tracking.
     */
    private val loose = HashMap<Int, Long>()
    /** GOs no recompute has read yet: the cue each track went to, keyed as [liveCues] keys them. */
    private val pendingGos = HashMap<Int, Int>()

    /** One stack's live entry, the cue it names, and the GO's sequence number. */
    private data class Go(val entryId: Int, val cueId: Int, val seq: Long)

    /** What a track is live on — a stack's entry, or a loose cue — and when it went. */
    private sealed interface Track {
        val cueId: Int
        val seq: Long

        data class Stack(val stackId: Int, val entryId: Int, override val cueId: Int, override val seq: Long) : Track
        data class Loose(override val cueId: Int, override val seq: Long) : Track
    }

    private var listenedFixtures: uk.me.cormack.lighting7.show.Fixtures? = null
    private var layersJob: Job? = null
    private var overlayJob: Job? = null

    private val listener = object : FixturesChangeListener {
        override fun cueListChanged() = recompute()
        override fun cueStackListChanged() = recompute()
        override fun lookListChanged() = recompute()
        override fun stageElementListChanged() = recompute()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────────────────

    /**
     * Follow [show]: its list-change events (an edit anywhere moves the stage), its programmer's
     * layer stack and the programmer's own scenery. Called on start and again on every project
     * switch, which also forgets the old show's live cues — they belong to a show that is gone.
     *
     * [reload] is a same-project reload (`ShowCarry`): [show] has GO'd the stacks that were live
     * again, so their GOs stand — every other one is forgotten, as is every cue applied outside a
     * stack, which the reload does not bring back — and what is on stage stays where it is rather
     * than starting again from each element's base.
     */
    fun attach(show: Show, scope: CoroutineScope, reload: Boolean = false) {
        synchronized(lock) {
            listenedFixtures?.unregisterListener(listener)
            show.fixtures.registerListener(listener)
            listenedFixtures = show.fixtures
            if (reload) {
                goes.keys.retainAll(show.cueStackManager.getActiveStackIds())
                loose.keys.retainAll(show.cueStackManager.holds.looseCueIdsInPressOrder().toSet())
                pendingGos.keys.retainAll(goes.keys + loose.keys.map(::looseKey))
            } else {
                goes.clear()
                loose.clear()
                pendingGos.clear()
                entries = emptyMap()
                staged = emptyMap()
            }
        }
        layersJob?.cancel()
        layersJob = show.programmerStore.layersFlow.onEach { recompute() }.launchIn(scope)
        // The overlay is the State's, not the show's, so one subscription would do — but it is
        // renewed with the layers' to keep the two following the same scope.
        overlayJob?.cancel()
        overlayJob = state.programmerScenery.flow.onEach { recompute() }.launchIn(scope)
        recompute()
    }

    fun close() {
        layersJob?.cancel()
        layersJob = null
        overlayJob?.cancel()
        overlayJob = null
        synchronized(lock) {
            listenedFixtures?.unregisterListener(listener)
            listenedFixtures = null
        }
        worker.shutdownNow()
    }

    // ─── Hooks ───────────────────────────────────────────────────────────────────────────────

    /**
     * A stack GO to [entryId] — `CueStackManager.activateEntry`, by every path that fires one. The
     * stack tracks down its entry list to this entry (cue model plan §3.4), even when the GO only
     * moved between two entries of one cue.
     */
    fun onEntryLive(stackId: Int, entryId: Int, cueId: Int) {
        synchronized(lock) {
            goes[stackId] = Go(entryId, cueId, ++seq)
            pendingGos[stackId] = cueId
        }
        recompute()
    }

    /** A cue held loose (`CueHolds.takeLoose`): it joins in GO order, as of this press. */
    fun onLooseHeld(cueId: Int) {
        synchronized(lock) {
            loose[cueId] = ++seq
            pendingGos[looseKey(cueId)] = cueId
        }
        recompute()
    }

    /** A loose hold let go (`CueHolds.dropLoose`): the cue's scenery leaves with it. */
    fun onLooseReleased(cueId: Int) {
        synchronized(lock) {
            loose.remove(cueId)
            pendingGos.remove(looseKey(cueId))
        }
        recompute()
    }

    /** `CueStackManager.deactivateStack`: the stack's set and cues let go. */
    fun onStackStopped(stackId: Int) {
        synchronized(lock) {
            goes.remove(stackId)
            pendingGos.remove(stackId)
        }
        recompute()
    }

    /** The programmer went blind or came back: its Looks and its scenery leave or rejoin the stage. */
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
            val (live, gos, keep) = synchronized(lock) {
                val live = liveCues()
                // A stack stopped, or let go of an applied cue, since its GO has nothing left to move.
                val gos = pendingGos.filter { (key, cueId) -> live[key]?.cueId == cueId }
                pendingGos.clear()
                Triple(live, gos, entries.keys + staged.keys)
            }
            val (overlay, releases) = state.programmerScenery.readForResolve(takeReleases = true)
            val input = load(live, overlay, keep)
            synchronized(lock) {
                if (input == null) {
                    publish(null, emptyMap(), emptyMap())
                    return
                }
                val now = System.currentTimeMillis()
                val released = releases.mapNotNull { (uuid, fade) -> input.idByUuid[uuid]?.let { it to fade } }.toMap()
                val liveResolved = SceneryResolver.resolve(
                    input.elements, input.stacks, input.lookScenery,
                    if (input.blind) emptyList() else input.programmerLookIds,
                    if (input.blind) emptyList() else input.held,
                )
                val sameProject = projectId == input.projectId
                val carried = if (sameProject) entries else emptyMap()
                val next = LinkedHashMap<UUID, Entry>()
                for (r in liveResolved.values.sortedBy { it.element.name.lowercase() }) {
                    val entry = transition(r, carried[r.element.uuid], fallback = null, input, gos, released[r.element.id], now)
                    // An element only the programmer ever held, let go and landed back on its base,
                    // leaves the frame: absent and at its base draw the same.
                    if (r.element.id !in input.controlled && entry.state == r.element.base && landed(entry, now)) continue
                    next[r.element.uuid] = entry
                }
                val nextStaged = LinkedHashMap<UUID, Entry>()
                if (input.blind && (input.programmerLookIds.isNotEmpty() || input.held.isNotEmpty())) {
                    val carriedStaged = if (sameProject) staged else emptyMap()
                    val full = SceneryResolver.resolve(input.elements, input.stacks, input.lookScenery, input.programmerLookIds, input.held)
                    for (r in full.values.sortedBy { it.element.name.lowercase() }) {
                        if (r.state == liveResolved[r.element.id]?.state) continue
                        nextStaged[r.element.uuid] =
                            transition(r, carriedStaged[r.element.uuid], fallback = next[r.element.uuid], input, emptyMap(), released[r.element.id], now)
                    }
                }
                publish(input.projectId, next, nextStaged)
            }
            if (input != null) state.programmerScenery.forget(input.projectId, input.missingHeld)
        } catch (e: Exception) {
            logger.warn("scenery: recompute failed — {}", e.message)
        }
    }

    /**
     * [r] as an entry: [prev] carried when it is already going there (its name and holder
     * refreshed), else a new move from where [prev] — or, with none, [fallback], or the element's
     * base — is drawn now.
     */
    private fun transition(
        r: SceneryResolver.Resolved,
        prev: Entry?,
        fallback: Entry?,
        input: Input,
        gos: Map<Int, Int>,
        releasedFadeMs: Long?,
        now: Long,
    ): Entry {
        val source = input.holder(SceneryResolver.holderOf(r))
        if (prev != null && prev.state == r.state) return prev.copy(elementName = r.element.name, source = source)
        val origin = prev ?: fallback
        val was = origin?.state ?: r.element.base
        return Entry(
            r.element.uuid, r.element.name, r.state,
            from = origin?.let { displayedAt(it, now) } ?: was,
            startedAtMs = now,
            durationMs = if (was == r.state) 0 else durationFor(r, was, gos, releasedFadeMs),
            source = source,
        )
    }

    private fun landed(entry: Entry, nowMs: Long) = nowMs - entry.startedAtMs >= entry.durationMs

    /**
     * What the stage's scenery would be if [cueId] of [stackId] went now, from where it is now —
     * `POST cue-stacks/{id}/preview`'s scenery, which the Next GO vis source animates. Nothing is
     * published or remembered. Read on the caller's thread (a request, outside any transaction),
     * with [lock] held only to snapshot what it compares against.
     */
    fun preview(stackId: Int, entryId: Int, cueId: Int): List<PreviewEntry> =
        previewWith(stackId, cueId) { Track.Stack(stackId, entryId, cueId, it) }

    /**
     * [preview] for a loose press of [cueId] (`GET cues/{id}/preview`): the cue joins as its own
     * one-cue track, as of now, on top of every stack and press.
     */
    fun previewLoose(cueId: Int): List<PreviewEntry> =
        previewWith(looseKey(cueId), cueId) { Track.Loose(cueId, it) }

    private fun previewWith(trackKey: Int, cueId: Int, track: (seq: Long) -> Track): List<PreviewEntry> {
        val (live, current) = synchronized(lock) {
            liveCues().toMutableMap().also { it[trackKey] = track(seq + 1) } to entries
        }
        val input = load(live, state.programmerScenery.readForResolve(takeReleases = false).first, current.keys) ?: return emptyList()
        val now = System.currentTimeMillis()
        // The GO lands under whatever the programmer holds live; blind holds nothing live.
        val resolved = SceneryResolver.resolve(
            input.elements, input.stacks, input.lookScenery,
            if (input.blind) emptyList() else input.programmerLookIds,
            if (input.blind) emptyList() else input.held,
        )
        return resolved.values.sortedBy { it.element.name.lowercase() }.map { r ->
            val prev = current[r.element.uuid]
            val was = prev?.state ?: r.element.base
            val from = prev?.let { displayedAt(it, now) } ?: was
            val duration = if (was == r.state) 0 else durationFor(r, was, mapOf(trackKey to cueId))
            PreviewEntry(r.element.uuid, r.state, from, duration)
        }
    }

    private fun publish(projectId: Int?, next: Map<UUID, Entry>, nextStaged: Map<UUID, Entry>) {
        this.projectId = projectId
        entries = next
        staged = nextStaged
        _frame.value = Frame(projectId, next.values.toList(), nextStaged.values.toList().ifEmpty { null })
    }

    /**
     * What each track is live on, and when it went — every stack's entry ([goes]) and every loose
     * cue ([loose]), keyed by stack id or by [looseKey]. Under [lock]. Not the manager's own table:
     * it changes a stack's entry before the GO's hook fires.
     */
    private fun liveCues(): Map<Int, Track> {
        val out = HashMap<Int, Track>()
        for ((stackId, go) in goes) out[stackId] = Track.Stack(stackId, go.entryId, go.cueId, go.seq)
        for ((cueId, at) in loose) out[looseKey(cueId)] = Track.Loose(cueId, at)
        return out
    }

    private class Input(
        val projectId: Int,
        val elements: List<SceneryResolver.Element>,
        val stacks: List<SceneryResolver.LiveStack>,
        val lookScenery: Map<Int, List<SceneryResolver.Change>>,
        /** The programmer's live Look layers — blind or not; a caller leaves them out for a live resolve under Blind. */
        val programmerLookIds: List<Int>,
        /** The programmer's own scenery, in the order first held — likewise blind or not. */
        val held: List<SceneryResolver.Held>,
        val blind: Boolean,
        /** Elements a stored change names or the programmer holds: the ones whose entry stays at their base. */
        val controlled: Set<Int>,
        val idByUuid: Map<UUID, Int>,
        /** Held elements the project's scene no longer has. */
        val missingHeld: List<UUID>,
        val lookNames: Map<Int, String>,
        val stackNames: Map<Int, String>,
    ) {
        fun holder(source: SceneryResolver.Source): Holder = when (source) {
            SceneryResolver.Source.Base -> Holder.BASE
            is SceneryResolver.Source.StackSet -> Holder("set", stackId = source.stackId, name = stackNames[source.stackId])
            // A loose cue's track key is no stack's id (see [looseKey]): it holds in no stack.
            is SceneryResolver.Source.CueRow -> Holder("cue", stackId = source.stackId.takeIf { it > 0 }, cueId = source.cueId, label = source.cueLabel)
            is SceneryResolver.Source.CueLook -> Holder("cueLook", stackId = source.stackId.takeIf { it > 0 }, lookId = source.lookId, name = lookNames[source.lookId])
            is SceneryResolver.Source.ProgrammerLook -> Holder("programmerLook", lookId = source.lookId, name = lookNames[source.lookId])
            is SceneryResolver.Source.Programmer -> Holder("programmer")
        }
    }

    /**
     * The desk's scenery inputs: [live] stacks, the programmer's [overlay] (when it is this
     * project's), and the rows. Elements are those a change names, the programmer holds, or [keep]
     * — an entry the last frame carried, so a piece the programmer let go flies home rather than
     * vanishing from the frame mid-move.
     */
    private fun load(live: Map<Int, Track>, overlay: ProgrammerScenery.Snapshot, keep: Set<UUID>): Input? {
        val show = state.showOrNull ?: return null
        val project = runCatching { state.projectManager.currentProject }.getOrNull() ?: return null
        val store = show.programmerStore
        val blind = store.blind
        val programmerLooks = programmerLookIds(store.layers)
        val detached = show.cueStackManager.detachedPlaces()
        return transaction(state.database) {
            val dao = DaoProject.findById(project.id) ?: return@transaction null
            val heldByUuid = if (overlay.projectId == dao.id.value) overlay.elements else emptyMap()
            val all = RigScope.of(dao).stageElements().toList()
            val idByUuid = all.associate { it.uuid to it.id.value }
            val missingHeld = heldByUuid.keys.filter { it !in idByUuid }
            if (all.isEmpty()) {
                return@transaction Input(
                    dao.id.value, emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), blind,
                    emptySet(), emptyMap(), missingHeld, emptyMap(), emptyMap(),
                )
            }
            val ids = all.map { it.id }
            // Only elements a scenery change names are under the show's control; the rest are their base.
            val named = (
                DaoCueSceneryRow.find { DaoCueScenery.element inList ids }.map { it.element.id.value } +
                    DaoCueStackSceneryRow.find { DaoCueStackScenery.element inList ids }.map { it.element.id.value } +
                    DaoLookSceneryRow.find { DaoLookScenery.element inList ids }.map { it.element.id.value }
                ).toSet()
            val held = heldByUuid.mapNotNull { (uuid, h) -> idByUuid[uuid]?.let { SceneryResolver.Held(it, h.state, h.fadeMs) } }
            val controlled = named + held.map { it.elementId }
            val elements = all.filter { it.id.value in controlled || it.uuid in keep }.map { it.toSceneryElement() }

            val stacks = live.values.mapNotNull { track ->
                when (track) {
                    is Track.Stack -> {
                        val stack = DaoCueStack.findById(track.stackId)?.takeIf { it.project.id == dao.id } ?: return@mapNotNull null
                        liveStack(stack, track.entryId, track.cueId, track.seq, detached[track.stackId])
                    }
                    is Track.Loose -> looseStack(dao, track.cueId, track.seq)
                }
            }
            val stackNames = stacks.filter { it.stackId > 0 }
                .mapNotNull { s -> DaoCueStack.findById(s.stackId)?.let { s.stackId to it.name } }.toMap()
            val lookIds = (stacks.flatMap { it.layeredLookIds } + programmerLooks).toSet()
            val lookScenery = if (lookIds.isEmpty()) emptyMap() else
                DaoLookSceneryRow.find { DaoLookScenery.look inList lookIds }
                    .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
                    .groupBy({ it.look.id.value }) { SceneryResolver.Change(it.element.id.value, decodeSceneryState(it.stateJson)) }
            val lookNames = if (lookScenery.isEmpty()) emptyMap() else
                DaoLook.find { DaoLooks.id inList lookScenery.keys }.associate { it.id.value to it.name }
            Input(
                dao.id.value, elements, stacks, lookScenery, programmerLooks, held, blind,
                controlled, idByUuid, missingHeld, lookNames, stackNames,
            )
        }
    }

    /**
     * One live stack's set, its cue entries down to the live one, and the live cue's layered Looks.
     * Tracking walks the stack's entries down to the live **entry** (cue model plan §3.4), so a cue
     * twice in one stack tracks from the place the stack is actually at. A live entry removed from
     * the list ([detachedAt], D16) tracks the entries that stood before it, then its cue.
     */
    private fun liveStack(
        stack: DaoCueStack,
        liveEntryId: Int,
        liveCueId: Int,
        goSeq: Long,
        detachedAt: EntryPlace?,
    ): SceneryResolver.LiveStack? {
        val entries = entriesOf(stack).filter { !it.isMarker && it.cueId != null }
        val liveIndex = entries.indexOfFirst { it.id.value == liveEntryId }
        val earlier = when {
            liveIndex >= 0 -> entries.take(liveIndex)
            detachedAt != null -> entries.take(detachedAt.cueIndexIn(entriesOf(stack)))
            else -> return null
        }
        val cueById = DaoCue.find { DaoCues.id inList (earlier.map { it.cueId!! } + liveCueId).distinct() }.associateBy { it.id.value }
        val cues = earlier.mapNotNull { e -> cueById[e.cueId!!]?.let { cue -> cue to (e.cueNumber?.takeIf { it.isNotBlank() } ?: cue.name) } } +
            listOfNotNull(cueById[liveCueId]?.let { cue ->
                val live = entries.getOrNull(liveIndex)
                cue to (live?.cueNumber?.takeIf { it.isNotBlank() } ?: cue.name)
            })
        val set = DaoCueStackSceneryRow.find { DaoCueStackScenery.stack eq stack.id }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .map { SceneryResolver.Change(it.element.id.value, decodeSceneryState(it.stateJson)) }
        val rows = cueSceneryRows(cues.map { it.first })
        val live = cueById[liveCueId] ?: return null
        return SceneryResolver.LiveStack(
            stackId = stack.id.value,
            goSeq = goSeq,
            set = set,
            cues = cues.map { (cue, label) -> sceneryCue(cue, label, rows) },
            layeredLookIds = layeredLookIds(live),
        )
    }

    /**
     * A loose cue as a one-cue stack (§3.4): its own rows, no set, no tracking, in GO order as of its
     * press. Keyed by [looseKey], which no stack id can be.
     */
    private fun looseStack(project: DaoProject, cueId: Int, goSeq: Long): SceneryResolver.LiveStack? {
        val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == project.id } ?: return null
        return SceneryResolver.LiveStack(
            stackId = looseKey(cueId),
            goSeq = goSeq,
            set = emptyList(),
            cues = listOf(sceneryCue(cue, cue.name, cueSceneryRows(listOf(cue)))),
            layeredLookIds = layeredLookIds(cue),
        )
    }

    private fun cueSceneryRows(cues: List<DaoCue>): Map<Int, List<DaoCueSceneryRow>> =
        DaoCueSceneryRow.find { DaoCueScenery.cue inList cues.map { it.id }.distinct() }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .groupBy { it.cue.id.value }

    private fun sceneryCue(cue: DaoCue, label: String, rows: Map<Int, List<DaoCueSceneryRow>>): SceneryResolver.Cue {
        val fade = cue.fadeDuration?.toMillis() ?: 0L
        return SceneryResolver.Cue(
            cueId = cue.id.value,
            label = label,
            changes = rows[cue.id.value].orEmpty().map {
                SceneryResolver.CueChange(it.element.id.value, decodeSceneryState(it.stateJson), it.transition?.toMillis() ?: fade)
            },
        )
    }

    /** The Looks [cue] layers live — enabled, a non-zero amount, untimed — in layer order. */
    private fun layeredLookIds(cue: DaoCue): List<Int> =
        DaoCueLayer.find { DaoCueLayers.cue eq cue.id }
            .filter { it.enabled && it.amount > 0.0 && it.delay == null && it.interval == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
            .mapNotNull { it.look?.id?.value }

    companion object {
        /**
         * A loose cue's track key in the live table and in [SceneryResolver.LiveStack.stackId]: the
         * cue's id negated, which no stack's (always positive) id can collide with. The holder a
         * client reads maps it back to "no stack".
         */
        fun looseKey(cueId: Int): Int = -cueId

        /** The programmer's live Look layers, bottom first: templates carry no scenery (D11). */
        fun programmerLookIds(layers: List<ProgrammerLayer>): List<Int> =
            layers.filter { it.source.kind == LayerSourceKind.LOOK && it.enabled && it.amount > 0.0 }
                .sortedBy { it.sortOrder }
                .map { it.source.id }

        /**
         * How long a move to [r] from [previous] takes (scenery-programmer plan D6), the longest of
         * the states that moved, each on the first clock that applies:
         *
         * 1. a change of a cue just GO'd ([gos], the cue by stack) keeps its own transition;
         * 2. a programmer move takes the operator's fade when above 0;
         * 3. a piece the programmer let go on a fade ([releasedFadeMs], a Clear's) flies home on it;
         * 4. anything else — a programmer move with no fade, a pressed Look, an edit, a stack
         *    stopping, GO TO landing an earlier cue's change — takes the piece's `travelS` scaled by
         *    the share of its travel moved ([travelMs]); a piece with none snaps.
         */
        fun durationFor(
            r: SceneryResolver.Resolved,
            previous: ElementStates,
            gos: Map<Int, Int>,
            releasedFadeMs: Long? = null,
        ): Long {
            val changed = buildSet {
                if (r.state.visible != previous.visible) add("visible")
                if (r.state.open != previous.open) add("open")
                if (r.state.trimM != previous.trimM) add("trimM")
            }
            return changed.maxOfOrNull { key ->
                val src = r.sources[key]
                when {
                    src is SceneryResolver.Source.CueRow && gos[src.stackId] == src.cueId -> src.transitionMs
                    src is SceneryResolver.Source.Programmer ->
                        src.fadeMs?.takeIf { it > 0 } ?: travelMs(r.element, key, previous, r.state)
                    else -> releasedFadeMs?.takeIf { it > 0 } ?: travelMs(r.element, key, previous, r.state)
                }
            } ?: 0L
        }

        /**
         * [element]'s `travelS` for the share of its travel a move of [key] from [from] to [to]
         * covers: `|Δopen|` for a drawn drape, `|ΔtrimM| / |out − in|` for a flown piece (in its
         * Z, out its base trim — a full travel where the two are equal). `visible` never travels,
         * and a piece with no `travelS` snaps.
         */
        fun travelMs(element: SceneryResolver.Element, key: String, from: ElementStates, to: ElementStates): Long {
            val travelS = element.travelS ?: return 0L
            val share = when (key) {
                "open" -> {
                    val a = from.open
                    val b = to.open
                    if (a == null || b == null) 1.0 else abs(b - a)
                }
                "trimM" -> {
                    val a = from.trimM
                    val b = to.trimM
                    val inM = element.inTrimM
                    val outM = element.base.trimM
                    val span = if (inM == null || outM == null) 0.0 else abs(outM - inM)
                    when {
                        a == null || b == null -> 1.0
                        span < 1e-9 -> 1.0
                        else -> abs(b - a) / span
                    }
                }
                else -> return 0L
            }
            return (travelS * 1000.0 * share).roundToLong().coerceIn(0L, MAX_SCENERY_TRANSITION.toMillis())
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
 * shown when unstated, a drawn drape closed (as the Stage view draws one), a flown piece at its Z —
 * with its travel time and, for a flown piece, its *in* (its Z).
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
        travelS = elementTravelS(info.params),
        inTrimM = if ("trimM" in keys) positionZ else null,
    )
}
