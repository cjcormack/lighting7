package uk.me.cormack.lighting7.sync

/**
 * Pure three-way diff over per-record snapshots and last-synced hashes.
 *
 * For every key in `local ∪ remote ∪ syncState`, classify into one of:
 *  * [DiffOutcome.NoOp] — both sides agree (or no record on either side).
 *  * [DiffOutcome.TakeLocal] — local side wins (remote unchanged from base, or absent).
 *  * [DiffOutcome.TakeRemote] — remote side wins (local unchanged from base, or absent).
 *  * [DiffOutcome.Conflict] — both sides changed and disagree; user must resolve.
 *
 * The function is deliberately pure / no-side-effects. It's the testable core of the
 * Phase 5 engine and the logic that the integration tests poke through
 * `RemoteSyncEngine`.
 *
 * **Phase 5 limitations** (documented in `docs/sync-engineering.md`):
 *  * No tombstone awareness. A record present on one side and absent on the other is
 *    treated as "the side that has it wins" — we can't tell "deleted by user" from
 *    "never existed here". Phase 7 will add this distinction.
 *  * Only [ConflictKind.EDIT_EDIT] is produced — [ConflictKind.EDIT_DELETE] /
 *    [ConflictKind.DELETE_EDIT] also wait for tombstones in Phase 7.
 */
object ThreeWayDiff {

    /**
     * @param local       SHA-256 hash by key for the local side.
     * @param remote      SHA-256 hash by key for the remote side.
     * @param lastSynced  SHA-256 hash by key from `sync_state` (the last-known shared base).
     * @return one [DiffOutcome] per key in the union of all three inputs.
     */
    fun compute(
        local: Map<RecordKey, String>,
        remote: Map<RecordKey, String>,
        lastSynced: Map<RecordKey, String>,
    ): Map<RecordKey, DiffOutcome> {
        val keys = local.keys union remote.keys union lastSynced.keys
        val out = mutableMapOf<RecordKey, DiffOutcome>()
        for (key in keys) {
            val l = local[key]
            val r = remote[key]
            val base = lastSynced[key]
            out[key] = classify(l, r, base)
        }
        return out
    }

    private fun classify(local: String?, remote: String?, base: String?): DiffOutcome = when {
        // Neither side has it — left over `sync_state` row. Engine GCs it.
        local == null && remote == null -> DiffOutcome.NoOp

        // Only one side has it. Phase 5 doesn't model tombstones, so the side that
        // has the record wins. Documented gap: this resurrects records the other
        // side deleted since the last sync.
        local != null && remote == null -> DiffOutcome.TakeLocal
        local == null && remote != null -> DiffOutcome.TakeRemote

        // Both sides have it.
        local == remote -> DiffOutcome.NoOp

        // No shared base means we never recorded a sync_state row — neither side can
        // claim "I'm unchanged since the last sync". Surface as a conflict so the user
        // makes the call. This is also the only outcome on the very first Phase 5 sync
        // following a Phase 4 upgrade where local & remote happen to differ.
        base == null -> DiffOutcome.Conflict(ConflictKind.EDIT_EDIT)

        local == base -> DiffOutcome.TakeRemote
        remote == base -> DiffOutcome.TakeLocal

        // Both sides moved away from base, and to different values.
        else -> DiffOutcome.Conflict(ConflictKind.EDIT_EDIT)
    }
}

/** Result of [ThreeWayDiff.compute] for a single record. */
sealed class DiffOutcome {
    object NoOp : DiffOutcome()
    object TakeLocal : DiffOutcome()
    object TakeRemote : DiffOutcome()
    data class Conflict(val kind: ConflictKind) : DiffOutcome()

    /**
     * Phase 6 MANUAL resolution: write [content] to the record's file path verbatim instead
     * of choosing local or remote. Never produced by [ThreeWayDiff.compute] — it's only
     * synthesised in [uk.me.cormack.lighting7.sync.RemoteSyncEngine.applyMergeFromSession]
     * from the user's stored manual edit.
     */
    data class TakeManual(val content: String) : DiffOutcome()
}

/**
 * The kind of conflict the diff detected. Phase 5 only produces `EDIT_EDIT`; the others
 * are reserved for Phase 7 when tombstones land.
 */
enum class ConflictKind { EDIT_EDIT, EDIT_DELETE, DELETE_EDIT }
