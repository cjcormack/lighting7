package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * The audit of remote sessions through Lighting7 Cloud (cloud-p1 plan D5): a row when a session
 * opens, completed when it ends, never written in between. A row a crash left open is closed at
 * startup as `DESK_RESTARTED`, and rows older than a year are pruned then (`cloud/RemoteSessions.kt`).
 * Machine-local, never synced.
 *
 * `user_id` is SET_NULL in intent only (SQLite runs with `PRAGMA foreign_keys` OFF), so
 * `AuthService.deleteUser` clears it by hand; the email and account id say who it was.
 */
object DaoCloudRemoteSessions : IntIdTable("cloud_remote_sessions") {
    val sid = text("sid").uniqueIndex()
    val cloudAccountId = text("cloud_account_id")
    val email = text("email")
    /** The local user the session's link resolved to, or null for an unlinked session. */
    val user = optReference("user_id", DaoUsers, onDelete = ReferenceOption.SET_NULL)
    val startedAt = utcInstant("started_at")
    val endedAt = utcInstant("ended_at").nullable()
    /** The last path the sidecar reported for the session's connection (`DIRECT` or `RELAY`). */
    val path = text("path").nullable()
    val endReason = text("end_reason").nullable()
}
