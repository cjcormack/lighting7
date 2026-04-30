package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncSession
import uk.me.cormack.lighting7.models.DaoSyncSessionConflict
import uk.me.cormack.lighting7.models.DaoSyncSessionConflicts
import uk.me.cormack.lighting7.models.DaoSyncSessions
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * Persistent values for [DaoSyncSession.state]. The DB column is a `varchar` so wire
 * compatibility is preserved across schema changes; callers compare via [name] when
 * reading the string column.
 *
 * `FETCHING` is reserved for a future phase that opens a session row before the
 * fetch step completes so a crash mid-fetch is observable; not currently written —
 * `runSync` is mutex-locked and a crash mid-fetch leaves no row at all.
 */
enum class SessionState {
    FETCHING, CONFLICTS_PENDING, APPLYING, DONE, FAILED, ABORTED;

    companion object {
        /**
         * "Active" sessions block new syncs / snapshots until applied or aborted.
         *
         * `FAILED` is included so the resume/discard UI can surface a crashed-mid-apply
         * session (see [recoverFromCrash]) and so `/sync/abort` can find and clean it up.
         * The `/sync/resolve` and `/sync/apply` routes still gate on `state == CONFLICTS_PENDING`
         * explicitly, so a FAILED session can only be aborted, not resumed.
         */
        val ACTIVE: Set<String> = setOf(CONFLICTS_PENDING.name, APPLYING.name, FAILED.name)
    }
}

/** Resolution choices on a conflict. `MANUAL` carries a user-edited replacement payload. */
enum class ConflictResolution { LOCAL, REMOTE, MANUAL }

/**
 * DAO-facing helpers for cloud-sync conflict sessions. All methods must run inside an
 * existing Exposed transaction — they don't open one. Mirrors the [Overrides] helper's
 * shape.
 *
 * Higher-level orchestration (fetch, classify, push) lives in
 * [RemoteSyncEngine]; this file only manipulates the session/conflict tables.
 */
object ConflictSession {

    /** The single active session for [projectId], or null if none. */
    fun findActive(projectId: Int): DaoSyncSession? =
        DaoSyncSession.find { DaoSyncSessions.project eq projectId }
            .firstOrNull { it.state in SessionState.ACTIVE }

    /**
     * Open a fresh session for [project] with the supplied conflict rows already known.
     * The caller (`RemoteSyncEngine`) computes the conflicts as part of the diff step;
     * persisting them in the same transaction means a crash mid-`runSync` either leaves
     * everything consistent or no row at all.
     */
    fun open(
        project: DaoProject,
        localSha: String,
        remoteSha: String,
        baseSha: String?,
        conflicts: List<ConflictRow>,
    ): DaoSyncSession {
        val session = DaoSyncSession.new {
            this.project = project
            this.startedAtMs = System.currentTimeMillis()
            this.state = SessionState.CONFLICTS_PENDING.name
            this.localSha = localSha
            this.remoteSha = remoteSha
            this.baseSha = baseSha
            this.errorMessage = null
        }
        for (row in conflicts) {
            DaoSyncSessionConflict.new {
                this.session = session
                this.tableName = row.tableName
                this.recordUuid = row.recordUuid
                this.conflictKind = row.conflictKind
                this.resolution = null
                this.localJson = row.localJson
                this.remoteJson = row.remoteJson
                this.baseJson = row.baseJson
            }
        }
        return session
    }

    /** All conflicts for [session], stable order by `(tableName, recordUuid)` for the UI. */
    fun listConflicts(session: DaoSyncSession): List<DaoSyncSessionConflict> =
        DaoSyncSessionConflict.find { DaoSyncSessionConflicts.session eq session.id }
            .sortedWith(compareBy({ it.tableName }, { it.recordUuid.toString() }))

    /**
     * Apply a list of `(tableName, recordUuid, resolution)` triples to a session. Idempotent:
     * setting the same value again is a no-op; setting `null` clears a previous choice
     * (the API doesn't expose this today, but the helper supports it). Returns the count of
     * conflicts touched.
     *
     * `manualValueJson` is recorded only when `resolution == MANUAL`; switching back to
     * `LOCAL` / `REMOTE` clears the previously-stored manual content so a stale draft
     * can't be applied by accident.
     */
    fun resolve(
        session: DaoSyncSession,
        resolutions: List<ResolutionEntry>,
    ): Int {
        var touched = 0
        for (entry in resolutions) {
            val conflict = DaoSyncSessionConflict.find {
                (DaoSyncSessionConflicts.session eq session.id) and
                    (DaoSyncSessionConflicts.targetTable eq entry.tableName) and
                    (DaoSyncSessionConflicts.recordUuid eq entry.recordUuid)
            }.firstOrNull() ?: continue
            conflict.resolution = entry.resolution
            conflict.manualValueJson = if (entry.resolution == ConflictResolution.MANUAL.name) {
                entry.manualValueJson
            } else {
                null
            }
            touched++
        }
        return touched
    }

    /** True if every conflict in [session] has a non-null `resolution`. */
    fun allResolved(session: DaoSyncSession): Boolean =
        DaoSyncSessionConflict.find {
            (DaoSyncSessionConflicts.session eq session.id) and
                DaoSyncSessionConflicts.resolution.isNull()
        }.firstOrNull() == null

    fun markState(session: DaoSyncSession, newState: String, errorMessage: String? = null) {
        session.state = newState
        if (errorMessage != null) session.errorMessage = errorMessage
    }

    /**
     * On startup, demote any session that was mid-apply when the JVM died. The
     * `applyMergeFromSession` pipeline isn't single-transaction (DB importer commits before
     * the git push), so an interrupted apply may have left the local DB partly merged
     * without a corresponding remote push. Marking these `FAILED` parks them so the user
     * can `abort` (which resets the working tree to `localSha`) and re-run sync, where the
     * three-way diff will pick up whatever shape the partial apply produced.
     *
     * `CONFLICTS_PENDING` sessions are left untouched: the DB and working tree are
     * consistent at that point and the user can simply pick up where they left off via the
     * existing /conflicts and /apply endpoints.
     *
     * Idempotent — calling twice is a no-op the second time.
     */
    fun recoverFromCrash(state: State) {
        transaction(state.database) {
            val stuck = DaoSyncSession.find { DaoSyncSessions.state eq SessionState.APPLYING.name }.toList()
            if (stuck.isEmpty()) return@transaction
            for (session in stuck) {
                session.state = SessionState.FAILED.name
                session.errorMessage =
                    "Apply was interrupted by a crash or restart; abort the session and re-run sync to recover."
            }
            logger.warn("Recovered {} APPLYING session(s) by demoting them to FAILED", stuck.size)
        }
    }

    private val logger = LoggerFactory.getLogger(ConflictSession::class.java)
}

/**
 * Wire shape for [ConflictSession.open]. Carries the JSON snapshots so resolution
 * remains stable even if the working tree moves before the user clicks `Apply`.
 */
data class ConflictRow(
    val tableName: String,
    val recordUuid: UUID,
    val conflictKind: String,
    val localJson: String?,
    val remoteJson: String?,
    val baseJson: String?,
)

/**
 * One `(record, choice)` pair for [ConflictSession.resolve]. [manualValueJson] is the
 * user-edited replacement payload — required when `resolution == MANUAL`, ignored
 * otherwise.
 */
data class ResolutionEntry(
    val tableName: String,
    val recordUuid: UUID,
    val resolution: String?,
    val manualValueJson: String? = null,
)
