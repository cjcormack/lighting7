package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Cloud-sync conflict session — created by [uk.me.cormack.lighting7.sync.RemoteSyncEngine]
 * when a `Diverged` history produces at least one EDIT_EDIT conflict that requires user
 * input. Survives across REST calls so the user can resolve conflicts at their own pace.
 *
 * Phase 5 ships only the `CONFLICTS_PENDING → APPLYING → DONE/FAILED/ABORTED` branch of
 * the design's full state machine; `FETCHING` and crash-resume are Phase 6.
 *
 * Machine-local — never serialised.
 */
object DaoSyncSessions : IntIdTable("sync_session") {
    val project = reference("project_id", DaoProjects)
    val startedAtMs = long("started_at_ms")
    /** One of `CONFLICTS_PENDING`, `APPLYING`, `DONE`, `FAILED`, `ABORTED`. */
    val state = varchar("state", 32)
    /** Commit SHA local HEAD pointed at when the session opened. Used to spot stale `apply`s. */
    val localSha = varchar("local_sha", 64).nullable()
    /** Commit SHA `origin/{branch}` resolved to at fetch time. */
    val remoteSha = varchar("remote_sha", 64).nullable()
    /** Merge-base between local and remote at fetch time. */
    val baseSha = varchar("base_sha", 64).nullable()
    val errorMessage = text("error_message").nullable()
}

class DaoSyncSession(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSyncSession>(DaoSyncSessions)

    var project by DaoProject referencedOn DaoSyncSessions.project
    var startedAtMs by DaoSyncSessions.startedAtMs
    var state by DaoSyncSessions.state
    var localSha by DaoSyncSessions.localSha
    var remoteSha by DaoSyncSessions.remoteSha
    var baseSha by DaoSyncSessions.baseSha
    var errorMessage by DaoSyncSessions.errorMessage
    val conflicts by DaoSyncSessionConflict referrersOn DaoSyncSessionConflicts.session
}
