package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.and
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncSession
import uk.me.cormack.lighting7.models.DaoSyncSessionConflict
import uk.me.cormack.lighting7.models.DaoSyncSessionConflicts
import uk.me.cormack.lighting7.models.DaoSyncSessions
import java.util.UUID

/**
 * Persistent values for [DaoSyncSession.state]. The DB column is a `varchar` so wire
 * compatibility is preserved across schema changes; callers compare via [name] when
 * reading the string column.
 */
enum class SessionState {
    CONFLICTS_PENDING, APPLYING, DONE, FAILED, ABORTED;

    companion object {
        /** "Active" sessions block new syncs / snapshots until applied or aborted. */
        val ACTIVE: Set<String> = setOf(CONFLICTS_PENDING.name, APPLYING.name)
    }
}

/** Two values [DaoSyncSessionConflict.resolution] can hold once the user has chosen. */
enum class ConflictResolution { LOCAL, REMOTE }

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

/** One `(record, choice)` pair for [ConflictSession.resolve]. */
data class ResolutionEntry(
    val tableName: String,
    val recordUuid: UUID,
    val resolution: String?,
)
