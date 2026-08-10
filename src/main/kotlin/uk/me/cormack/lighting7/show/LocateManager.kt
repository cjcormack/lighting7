package uk.me.cormack.lighting7.show

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.me.cormack.lighting7.models.TargetRef

/**
 * In-memory bookkeeping for active Locate toggles.
 *
 * Records, per located target, the Layer-4 property writes it asserted so toggle-off knows
 * exactly what to clear. Deliberately in-memory only: the writes themselves live in the
 * equally in-memory `DirectWriteStore`, so a restart drops the state and the output together
 * — same contract as the preset toggle bookkeeping in `routes/projectFxPresets.kt`.
 *
 * A plain lock (not per-key compute) because toggle-off must also *re-assert* other active
 * locates whose writes overlap the cleared ones — e.g. releasing a group locate while one of
 * its members is still individually located must leave that member in locate state, not
 * cascade it back to the show. Locates number in single digits, so cross-key work under one
 * lock is simple and plenty fast.
 */
class LocateManager {
    /** One recorded Layer-4 assertion: which target key and property name were written. */
    data class LocateWrite(val targetKey: String, val propertyName: String)

    data class ToggleOutcome(val active: Boolean, val writeCount: Int)

    private val lock = Any()
    private val active = LinkedHashMap<TargetRef, List<LocateWrite>>()

    private val _activeTargets = MutableStateFlow<Set<TargetRef>>(emptySet())

    /** Currently-located targets. REST reads [kotlinx.coroutines.flow.StateFlow.value]; a future locate WebSocket can collect it. */
    val activeTargets: StateFlow<Set<TargetRef>> = _activeTargets.asStateFlow()

    /**
     * Toggle [target]'s locate state.
     *
     * Inactive → run [assert] and record its writes; a target that resolves to zero writes
     * (e.g. a fixture with no DMX-backed properties) is reported inactive rather than stuck
     * as an un-releasable entry. Active → hand the recorded writes to [clear] as one batch,
     * then re-run [assert] for every remaining entry whose writes overlap the cleared set so
     * overlapping locates stay asserted; a re-assert that comes back empty (stale target)
     * drops its entry.
     *
     * Both callbacks run with the manager's lock held and MUST NOT throw — a throw here
     * would leave Layer-4 channels asserted with no bookkeeping row to release them. The
     * route-layer callbacks swallow and degrade instead.
     */
    fun toggle(
        target: TargetRef,
        assert: (TargetRef) -> List<LocateWrite>,
        clear: (List<LocateWrite>) -> Unit,
    ): ToggleOutcome = synchronized(lock) {
        val existing = active.remove(target)
        val outcome = if (existing != null) {
            clear(existing)
            val cleared = existing.toHashSet()
            for ((other, writes) in active.entries.toList()) {
                if (writes.none { it in cleared }) continue
                val next = assert(other)
                if (next.isEmpty()) active.remove(other) else active[other] = next
            }
            ToggleOutcome(active = false, writeCount = existing.size)
        } else {
            val writes = assert(target)
            if (writes.isNotEmpty()) active[target] = writes
            ToggleOutcome(active = writes.isNotEmpty(), writeCount = writes.size)
        }
        _activeTargets.value = active.keys.toSet()
        outcome
    }
}
