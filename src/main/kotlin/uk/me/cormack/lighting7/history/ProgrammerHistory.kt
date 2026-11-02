package uk.me.cormack.lighting7.history

import kotlinx.coroutines.flow.MutableStateFlow
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.show.LocateManager
import uk.me.cormack.lighting7.state.ProgrammerScenery
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything one programmer step keeps (undo plan §3.6): the store, the Local FX, the Locate
 * bookkeeping and the programmer's scenery. Not Blind, which is a view of the programmer rather than
 * its contents.
 */
data class ProgrammerSnapshot(
    val store: ProgrammerStore.StoreSnapshot,
    val localFx: List<FxEngine.LocalFxSpec>,
    val locate: LocateManager.Snapshot,
    val scenery: ProgrammerScenery.Snapshot,
)

/** The programmer's holders, as the show that owns them reads and restores them. */
interface ProgrammerHolders {
    fun snapshot(): ProgrammerSnapshot

    /** Put every holder back as [snapshot] holds it, moving the stage over [fadeMs]. */
    fun restore(snapshot: ProgrammerSnapshot, fadeMs: Long)
}

/**
 * The programmer's history (undo plan §3.6, D1, D2, D10): a ring of the last [capacity] steps, each the
 * programmer as it stood before one gesture, and the steps undone since the last gesture, to redo.
 *
 * The holders call [beforeMutation] at the head of every mutation. It takes a step the first time a
 * [WriteOrigin] writes — so one request, frame, tool call or surface input is one step however many
 * writes it makes, whatever writes between them — and none with no origin, so the clock, a reload's
 * recook and a Look edit's never do. A continuous gesture (a fader, a colour drag) folds into the last
 * step while its source and [WriteOrigin.coalesceKey] match and less than [COALESCE_MS] has passed
 * since its last frame. An undo or redo restores under an origin of its own kind, which takes no step.
 *
 * One programmer, so one history whoever writes: a step is anyone's to undo (D1). Owned by the [Show]
 * and handed to its successor on a same-project reload with the store; a project switch builds a new
 * show and so a new, empty history.
 *
 * The snapshot is taken outside [lock], since it takes the Locate manager's lock, and a holder may call
 * [beforeMutation] holding its own; the history's lock is never held while a holder's is wanted.
 */
class ProgrammerHistory(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One step: the programmer as it stood before [origin]'s gesture, or after it on the redo side. */
    class Step internal constructor(val id: String, val origin: WriteOrigin, val at: Long, val snapshot: ProgrammerSnapshot) {
        val label: String get() = origin.label ?: DEFAULT_LABEL

        fun info() = StepInfo(id, label, at, origin.source, origin.window)
    }

    data class StepInfo(val id: String, val label: String, val at: Long, val source: HistorySource, val window: String?)

    /** What `history.state` says: the steps to undo, newest first, and those to redo, next first. */
    data class Frame(val projectId: Int?, val revision: Long, val undo: List<StepInfo>, val redo: List<StepInfo>) {
        companion object {
            val EMPTY = Frame(null, 0, emptyList(), emptyList())
        }
    }

    @Volatile
    private var holders: ProgrammerHolders? = null

    @Volatile
    private var projectId: Int? = null

    @Volatile
    private var sink: MutableStateFlow<Frame>? = null

    private val lock = Any()

    /** Serialises undo and redo against each other; never held by [beforeMutation]. */
    private val restoreLock = Any()

    /** Newest last. */
    private val undo = ArrayDeque<Step>()

    /** Next to redo last. */
    private val redo = ArrayDeque<Step>()
    /** When the top step's gesture last wrote: a drag folds into it within [COALESCE_MS]. */
    private var lastAt = NEVER

    /** How many steps have been taken, so an undo can tell one was taken while it restored. */
    private var taken = 0L
    private var revision = 0L
    private val ids = AtomicLong()

    /** Attach to [projectId]'s show — at its start, or a reload's successor — and say the history again. */
    fun bind(projectId: Int, holders: ProgrammerHolders, sink: MutableStateFlow<Frame>?) {
        this.projectId = projectId
        this.holders = holders
        this.sink = sink
        publish()
    }

    /** The current frame, for a test or a route's answer. */
    fun frame(): Frame = synchronized(lock) { buildFrame() }

    fun beforeMutation() {
        val origin = WriteOrigin.current() ?: return
        if (origin.kind != WriteOrigin.Kind.TRACKED) return
        val holders = holders ?: return
        val now = clock()
        synchronized(lock) {
            val top = undo.lastOrNull()
            val key = origin.coalesceKey
            val ofTop = key != null && top != null &&
                top.origin.source.key == origin.source.key && top.origin.coalesceKey == key
            // Whether this request has its step is the origin's to say, so a request that writes
            // again after another's write is still one step.
            if (origin.stepped) {
                if (ofTop) lastAt = now
                return
            }
            origin.stepped = true
            if (ofTop && now - lastAt < COALESCE_MS) {
                lastAt = now
                return
            }
        }
        val snapshot = try {
            holders.snapshot()
        } catch (e: Exception) {
            log.warn("Programmer history could not take a step", e)
            return
        }
        synchronized(lock) {
            undo.addLast(Step("p-" + ids.incrementAndGet().toString(36), origin, now, snapshot))
            while (undo.size > capacity) undo.removeFirst()
            redo.clear()
            lastAt = now
            taken++
            revision++
            sink?.value = buildFrame()
        }
    }

    /**
     * Undo the newest step, or every step down to and including [throughId] — going back to before it.
     * Answers the steps undone, newest first, or null when there is none or [throughId] names none.
     */
    fun undo(throughId: String? = null, fadeMs: Long = 0, by: HistorySource = HistorySource.DESK): List<StepInfo>? =
        synchronized(restoreLock) {
            val holders = holders ?: return null
            var before = 0L
            val picked = synchronized(lock) {
                val from = if (throughId == null) undo.lastIndex else undo.indexOfLast { it.id == throughId }
                if (from < 0) return null
                val steps = undo.subList(from, undo.size).toList()
                repeat(steps.size) { undo.removeLast() }
                lastAt = NEVER
                before = taken
                steps
            }
            val putBack = { synchronized(lock) { if (taken == before) undo.addAll(picked) } }
            val current = try {
                holders.snapshot()
            } catch (e: Exception) {
                putBack()
                throw e
            }
            // Each step's redo side is the programmer as the next newer step found it.
            val redone = picked.mapIndexed { i, step ->
                Step(step.id, step.origin, step.at, if (i == picked.lastIndex) current else picked[i + 1].snapshot)
            }
            try {
                restoreAs(holders, picked.first().snapshot, fadeMs, WriteOrigin(by, kind = WriteOrigin.Kind.UNDO))
            } catch (e: Exception) {
                putBack()
                publish()
                throw e
            }
            synchronized(lock) {
                // A step taken while this restored dropped redo, as any new step does (D10).
                if (taken == before) for (step in redone.asReversed()) redo.addLast(step)
                revision++
                sink?.value = buildFrame()
            }
            picked.asReversed().map { it.info() }
        }

    /**
     * Redo the next undone step, or every one up to and including [throughId]. Answers the steps redone,
     * in the order redone, or null when there is none or [throughId] names none.
     */
    fun redo(throughId: String? = null, fadeMs: Long = 0, by: HistorySource = HistorySource.DESK): List<StepInfo>? =
        synchronized(restoreLock) {
            val holders = holders ?: return null
            var before = 0L
            val picked = synchronized(lock) {
                val from = if (throughId == null) redo.lastIndex else redo.indexOfLast { it.id == throughId }
                if (from < 0) return null
                val steps = redo.subList(from, redo.size).toList().asReversed()
                repeat(steps.size) { redo.removeLast() }
                lastAt = NEVER
                before = taken
                steps
            }
            // A step taken meanwhile dropped redo (D10), so there is nothing to put back then.
            val putBack = { synchronized(lock) { if (taken == before) redo.addAll(picked.asReversed()) } }
            val current = try {
                holders.snapshot()
            } catch (e: Exception) {
                putBack()
                throw e
            }
            // Each step's undo side is the programmer as the step before it left it.
            val undone = picked.mapIndexed { i, step ->
                Step(step.id, step.origin, step.at, if (i == 0) current else picked[i - 1].snapshot)
            }
            try {
                restoreAs(holders, picked.last().snapshot, fadeMs, WriteOrigin(by, kind = WriteOrigin.Kind.REDO))
            } catch (e: Exception) {
                putBack()
                publish()
                throw e
            }
            synchronized(lock) {
                for (step in undone) undo.addLast(step)
                while (undo.size > capacity) undo.removeFirst()
                revision++
                sink?.value = buildFrame()
            }
            picked.map { it.info() }
        }

    private fun restoreAs(holders: ProgrammerHolders, snapshot: ProgrammerSnapshot, fadeMs: Long, origin: WriteOrigin) {
        WriteOrigin.with(origin) { holders.restore(snapshot, fadeMs) }
    }

    private fun publish() {
        synchronized(lock) { sink?.value = buildFrame() }
    }

    private fun buildFrame() = Frame(
        projectId = projectId,
        revision = revision,
        undo = undo.asReversed().map { it.info() },
        redo = redo.asReversed().map { it.info() },
    )

    companion object {
        const val DEFAULT_CAPACITY = 50
        const val COALESCE_MS = 1000L
        const val DEFAULT_LABEL = "Programmer change"
        private const val NEVER = Long.MIN_VALUE / 2
        private val log = LoggerFactory.getLogger(ProgrammerHistory::class.java)
    }
}
