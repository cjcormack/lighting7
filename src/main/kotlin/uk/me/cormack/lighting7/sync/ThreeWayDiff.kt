package uk.me.cormack.lighting7.sync

import org.slf4j.LoggerFactory

/**
 * Pure three-way diff over per-record snapshots and last-synced metadata.
 *
 * For every key in `local ∪ remote ∪ syncState`, classify into one of:
 *  * [DiffOutcome.NoOp] — both sides agree (or no record on either side).
 *  * [DiffOutcome.TakeLocal] — local side wins (remote unchanged from base, or absent).
 *  * [DiffOutcome.TakeRemote] — remote side wins (local unchanged from base, or absent).
 *  * [DiffOutcome.Conflict] — both sides changed and disagree; user must resolve.
 *
 * The function is deliberately pure / no-side-effects. It's the testable core of the
 * sync engine and the logic that the integration tests poke through [RemoteSyncEngine].
 *
 * Each side carries an `isDeleted` bit alongside the content hash so deletions propagate
 * explicitly instead of looking like "absent." The sync_state row likewise carries
 * `lastSyncedIsDeleted` so a tombstone on both sides is `NoOp` rather than ambiguous.
 */
object ThreeWayDiff {

    private val logger = LoggerFactory.getLogger(ThreeWayDiff::class.java)

    /**
     * @param local       per-record `(hash, isDeleted)` for the local side.
     * @param remote      per-record `(hash, isDeleted)` for the remote side.
     * @param lastSynced  per-record `(hash, isDeleted)` from `sync_state` (the last-known shared base).
     * @return one [DiffOutcome] per key in the union of all three inputs.
     */
    fun compute(
        local: Map<RecordKey, SnapshotMeta>,
        remote: Map<RecordKey, SnapshotMeta>,
        lastSynced: Map<RecordKey, SnapshotMeta>,
    ): Map<RecordKey, DiffOutcome> {
        val keys = local.keys union remote.keys union lastSynced.keys
        val out = mutableMapOf<RecordKey, DiffOutcome>()
        for (key in keys) {
            out[key] = classify(key, local[key], remote[key], lastSynced[key])
        }
        return out
    }

    private fun classify(
        key: RecordKey,
        local: SnapshotMeta?,
        remote: SnapshotMeta?,
        base: SnapshotMeta?,
    ): DiffOutcome = when {
        // Neither side has any trace — left over `sync_state` row. Engine GCs it.
        local == null && remote == null -> DiffOutcome.NoOp

        // One side absent on disk entirely. With snapshot-time tombstone derivation a
        // deletion always lands as a tombstone, so "absent on local" is either "we never
        // had it" or "remote dropped it without a tombstone" (history rewrite, manual
        // `rm`, peer that doesn't write tombstones). Resurrect with a logged warning when
        // sync_state says the base was a live record.
        local != null && remote == null -> {
            if (base != null && !base.isDeleted) {
                logger.warn(
                    "Record {} present locally but absent from remote with no tombstone; " +
                        "treating as TakeLocal (history rewrite or pre-Phase-7 peer).",
                    key,
                )
            }
            if (local.isDeleted) DiffOutcome.NoOp else DiffOutcome.TakeLocal
        }
        local == null && remote != null -> {
            if (remote.isDeleted) DiffOutcome.NoOp else DiffOutcome.TakeRemote
        }

        // Both sides have something (live record or tombstone).
        // Same hash + same kind → no-op. Covers concurrent identical edits AND concurrent
        // identical deletions (both tombstones share the same canonical body hash).
        local!!.hash == remote!!.hash && local.isDeleted == remote.isDeleted -> DiffOutcome.NoOp

        // No shared base (first sync after Phase-4 → Phase-5 upgrade, or both sides
        // independently created the same UUID). Surface as a conflict so the user picks.
        base == null -> DiffOutcome.Conflict(conflictKindFor(local, remote))

        else -> {
            val localChanged = local.hash != base.hash || local.isDeleted != base.isDeleted
            val remoteChanged = remote.hash != base.hash || remote.isDeleted != base.isDeleted
            when {
                !localChanged && !remoteChanged -> DiffOutcome.NoOp
                !localChanged -> DiffOutcome.TakeRemote
                !remoteChanged -> DiffOutcome.TakeLocal
                else -> DiffOutcome.Conflict(conflictKindFor(local, remote))
            }
        }
    }

    /**
     * Pick the right [ConflictKind] when both sides moved away from base to different
     * values — purely a function of whether each side is currently a tombstone.
     */
    private fun conflictKindFor(local: SnapshotMeta, remote: SnapshotMeta): ConflictKind = when {
        local.isDeleted && !remote.isDeleted -> ConflictKind.DELETE_EDIT
        !local.isDeleted && remote.isDeleted -> ConflictKind.EDIT_DELETE
        else -> ConflictKind.EDIT_EDIT
    }
}

/** Result of [ThreeWayDiff.compute] for a single record. */
sealed class DiffOutcome {
    object NoOp : DiffOutcome()
    object TakeLocal : DiffOutcome()
    object TakeRemote : DiffOutcome()
    data class Conflict(val kind: ConflictKind) : DiffOutcome()

    /**
     * MANUAL resolution: write [content] to the record's file path verbatim instead of
     * choosing local or remote. Never produced by [ThreeWayDiff.compute] — it's only
     * synthesised in [uk.me.cormack.lighting7.sync.RemoteSyncEngine.applyMergeFromSession]
     * from the user's stored manual edit.
     */
    data class TakeManual(val content: String) : DiffOutcome()
}

/**
 * The kind of conflict the diff detected.
 *
 *  * `EDIT_EDIT` — both sides edited the record to different live values.
 *  * `DELETE_EDIT` — local deleted, remote edited.
 *  * `EDIT_DELETE` — local edited, remote deleted.
 */
enum class ConflictKind { EDIT_EDIT, EDIT_DELETE, DELETE_EDIT }
