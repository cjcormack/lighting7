package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Single-use password reset tokens (multi-user-auth plan, session 3) — the QR flow's
 * durable half. An admin mints one for a locked-out user; the phone that scans the QR
 * redeems it at the auth-exempt `/api/rest/auth/reset/{token}` endpoint.
 *
 * As with sessions, only the SHA-256 hex of the raw token lands here, so a copied-around
 * SQLite file never contains a redeemable credential. TTL is 15 minutes and minting a new
 * token cancels the user's outstanding ones, so at most one live token exists per account.
 *
 * `used_at_ms` / `cancelled_at_ms` are kept rather than deleting the row: the admin's
 * sheet polls the token's status and must be able to distinguish "the user set their
 * password" from "this token expired" from "unknown token". `AuthService` prunes dead
 * rows at startup.
 *
 * The CASCADE on `user_id` documents intent but is not enforced — SQLite runs with
 * `PRAGMA foreign_keys` OFF here, so `AuthService` deletes a user's tokens explicitly.
 * Machine-local; never synced.
 */
object DaoPasswordResetTokens : IntIdTable("password_reset_tokens") {
    /** SHA-256 hex of the raw token that travels in the QR URL. */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val user = reference("user_id", DaoUsers, onDelete = ReferenceOption.CASCADE)

    /** The admin who minted it, for a future audit log. Nullable so deleting that admin can't take live tokens with it. */
    val createdByUser = optReference("created_by_user_id", DaoUsers, onDelete = ReferenceOption.SET_NULL)
    val createdAtMs = long("created_at_ms")
    val expiresAtMs = long("expires_at_ms")
    val usedAtMs = long("used_at_ms").nullable()
    val cancelledAtMs = long("cancelled_at_ms").nullable()
}

class DaoPasswordResetToken(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPasswordResetToken>(DaoPasswordResetTokens)

    var tokenHash by DaoPasswordResetTokens.tokenHash
    var user by DaoUser referencedOn DaoPasswordResetTokens.user
    var createdByUser by DaoUser optionalReferencedOn DaoPasswordResetTokens.createdByUser
    var createdAtMs by DaoPasswordResetTokens.createdAtMs
    var expiresAtMs by DaoPasswordResetTokens.expiresAtMs
    var usedAtMs by DaoPasswordResetTokens.usedAtMs
    var cancelledAtMs by DaoPasswordResetTokens.cancelledAtMs
}
