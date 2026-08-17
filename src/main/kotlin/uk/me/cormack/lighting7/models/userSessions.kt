package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * How a session came to exist. Stored by name via `enumerationByName`, like [UserRole], and
 * `@Serializable` because it also travels to the frontend in `GET /auth/sessions`.
 *
 * Named `createdVia` on the table rather than `origin`, because `request.origin` is a Ktor
 * extension already in scope in the files that write this column.
 */
@Serializable
enum class SessionOrigin {
    /** Someone typed a username and password — `POST /auth/login`, or the setup route. */
    PASSWORD,

    /** A phone or tablet scanned a device-login QR off the desk's screen. */
    QR,
}

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

    /**
     * NOT NULL with a constant default rather than nullable-plus-a-magic-string: SQLite's
     * `ALTER TABLE ADD COLUMN` accepts exactly that, which is what `createMissingTablesAndColumns`
     * emits — so this stays migration-free while still giving callers an exhaustive `when`.
     * Rows predating the column read as [SessionOrigin.PASSWORD], which is what they were.
     */
    val createdVia = enumerationByName<SessionOrigin>("created_via", 16)
        .default(SessionOrigin.PASSWORD)
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
    var createdVia by DaoUserSessions.createdVia
}
