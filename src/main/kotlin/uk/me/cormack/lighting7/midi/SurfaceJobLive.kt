package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/**
 * The live state a job follows (surface-setup plan §3 "Jobs"): the desk selection's heads in rig
 * order and the pads of the busk page showing. [ControlSurfaceBindingService.resolve] reads [state]
 * on every event, lock-free; [refresh] reads the desk off the input path and announces on [changes]
 * the signals whose answer moved, which the feedback publisher re-derives the regions of without
 * re-arming pickup.
 *
 * A signal no job follows is not read at all ([follows]), so a show with no live job pays nothing on a
 * selection change.
 */
class SurfaceJobLive(
    /** Whether the current project holds a job following [signal]. */
    private val follows: (signal: JobSignal) -> Boolean,
    /** The selection's heads, by key, in rig order. */
    private val heads: () -> List<String>,
    /** The pads of the busk page showing, in layout order. */
    private val pads: () -> List<UUID>,
) {
    @Volatile
    var state: JobLive = JobLive.EMPTY
        private set

    private val _changes = MutableSharedFlow<Set<JobSignal>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Set<JobSignal>> = _changes.asSharedFlow()

    private val lock = Any()

    /** Read [signals] again; answers the ones whose state moved, which are also emitted. Refreshes run one at a time. */
    fun refresh(signals: Set<JobSignal> = JobSignal.entries.toSet()): Set<JobSignal> = synchronized(lock) {
        val before = state
        var next = before
        if (JobSignal.SELECTION in signals) {
            next = next.copy(heads = if (follows(JobSignal.SELECTION)) runCatching(heads).getOrDefault(emptyList()) else emptyList())
        }
        if (JobSignal.BUSK_PAGE in signals) {
            next = next.copy(pads = if (follows(JobSignal.BUSK_PAGE)) runCatching(pads).getOrDefault(emptyList()) else emptyList())
        }
        if (next == before) return emptySet()
        state = next
        val moved = buildSet {
            if (next.heads != before.heads) add(JobSignal.SELECTION)
            if (next.pads != before.pads) add(JobSignal.BUSK_PAGE)
        }
        _changes.tryEmit(moved)
        moved
    }
}
