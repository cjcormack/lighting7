package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Live login sessions for this desk — the server-side table backing the
 * `lighting7_session` httpOnly cookie. The cookie carries the raw token; only its
 * SHA-256 hex lands here, so a copied-around SQLite file (support exports, sync
 * working trees) never contains a usable credential. Machine-local; never synced,
 * and deliberately not carried by `ProjectCloner` either — sessions are per-desk
 * runtime state, not project content.
 *
 * The CASCADE on `user_id` is declared for documentation and future-proofing, but
 * SQLite runs with `PRAGMA foreign_keys` OFF here, so it is **not enforced** —
 * `AuthService` deletes session rows explicitly whenever a user goes away rather
 * than relying on the FK.
 */
object DaoUserSessions : IntIdTable("user_sessions") {
    /** SHA-256 hex of the raw cookie token (see `auth/SessionTokens.kt`). */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val user = reference("user_id", DaoUsers, onDelete = ReferenceOption.CASCADE)
    val createdAtMs = long("created_at_ms")
    val lastSeenAtMs = long("last_seen_at_ms")
    val expiresAtMs = long("expires_at_ms")
    val revokedAtMs = long("revoked_at_ms").nullable()
    val userAgent = varchar("user_agent", 200).nullable()
    val clientIp = varchar("client_ip", 45).nullable()
}

class DaoUserSession(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoUserSession>(DaoUserSessions)

    var tokenHash by DaoUserSessions.tokenHash
    var user by DaoUser referencedOn DaoUserSessions.user
    var createdAtMs by DaoUserSessions.createdAtMs
    var lastSeenAtMs by DaoUserSessions.lastSeenAtMs
    var expiresAtMs by DaoUserSessions.expiresAtMs
    var revokedAtMs by DaoUserSessions.revokedAtMs
    var userAgent by DaoUserSessions.userAgent
    var clientIp by DaoUserSessions.clientIp
}
