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
 * equally in-memory `ProgrammerStore`, so a restart drops the state and the output together
 * — same contract as the preset toggle bookkeeping in `routes/projectFxPresets.kt`.
 *
 * A plain lock (not per-key compute) because toggle-off must also *re-assert* other active
 * locates whose writes overlap the cleared ones — e.g. releasing a group locate while one of
 * its members is still individually located must leave that member in locate state, not
 * cascade it back to the show. Every locate shares the single `ProgrammerOwner.LOCATE`
 * owner in the `ProgrammerStore`, so the store's per-owner fallback cannot arbitrate
 * between two locates — this re-assert loop is what does, and it additionally re-resolves
 * values and drops stale targets, which a stored-value fallback could not. Locates number
 * in single digits, so cross-key work under one lock is simple and plenty fast.
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
     * (e.g. a fixture with no DMX-backed properties, or one whose every channel is parked) is
     * reported inactive rather than stuck as an un-releasable entry. Active → hand the
     * recorded writes to [clear] as one batch, then re-run [assert] for every remaining entry
     * whose writes overlap the cleared set so overlapping locates stay asserted.
     *
     * [assert] returns null for a *stale* target (rekeyed fixture, rebuilt group), which is
     * the only thing that drops an existing entry on the re-assert path. An empty list is not
     * staleness: a still-present target whose properties have since become unassertable — the
     * operator parked it while it was located — keeps its locate state and simply holds no
     * writes, rather than silently un-locating itself because an unrelated overlapping locate
     * was released.
     *
     * Both callbacks run with the manager's lock held and MUST NOT throw — a throw here
     * would leave Layer-4 channels asserted with no bookkeeping row to release them. The
     * route-layer callbacks swallow and degrade instead.
     */
    fun toggle(
        target: TargetRef,
        assert: (TargetRef) -> List<LocateWrite>?,
        clear: (List<LocateWrite>) -> Unit,
    ): ToggleOutcome = synchronized(lock) {
        val existing = active.remove(target)
        val outcome = if (existing != null) {
            clear(existing)
            val cleared = existing.toHashSet()
            for ((other, writes) in active.entries.toList()) {
                if (writes.none { it in cleared }) continue
                val next = assert(other)
                if (next == null) active.remove(other) else active[other] = next
            }
            ToggleOutcome(active = false, writeCount = existing.size)
        } else {
            val writes = assert(target).orEmpty()
            if (writes.isNotEmpty()) active[target] = writes
            ToggleOutcome(active = writes.isNotEmpty(), writeCount = writes.size)
        }
        _activeTargets.value = active.keys.toSet()
        outcome
    }

    /**
     * Drop all locate bookkeeping without running any release callbacks. Used by
     * programmer clear-all, which sweeps every `LOCATE` store entry itself — running the
     * per-target clears afterwards would double-release, and leaving the bookkeeping would
     * desync the toggles (a "located" fixture whose writes are long gone).
     */
    fun reset() = synchronized(lock) {
        active.clear()
        _activeTargets.value = emptySet()
    }

    /**
     * Drop the bookkeeping rows matching one released (targetKey, propertyName) write —
     * used when a programmer entry clear takes a `LOCATE` slot out from under the manager,
     * so a target whose every write has been cleared stops reporting itself located.
     * Targets with other writes remaining keep their locate state (consistent with the
     * write-less-is-not-stale rule in [toggle]).
     */
    fun pruneWrite(targetKey: String, propertyName: String) = synchronized(lock) {
        val write = LocateWrite(targetKey, propertyName)
        var changed = false
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (write !in entry.value) continue
            changed = true
            val remaining = entry.value.filter { it != write }
            if (remaining.isEmpty()) iterator.remove() else entry.setValue(remaining)
        }
        if (changed) _activeTargets.value = active.keys.toSet()
    }
}
