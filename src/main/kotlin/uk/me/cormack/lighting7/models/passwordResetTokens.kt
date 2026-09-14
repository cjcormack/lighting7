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
 * `used_at` / `cancelled_at` are kept rather than deleting the row: the admin's
 * sheet polls the token's status and must be able to distinguish "the user set their
 * password" from "this token expired" from "unknown token". Spent rows then stay as the
 * account's reset **history** — which is what makes a live link visible and revocable rather
 * than something that silently outlives the sheet that showed it. `AuthService` ages rows out
 * after 30 days (`pruneOldResetTokenRows`) instead of sweeping spent ones at startup, so that
 * history survives a restart.
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
    val createdAt = utcInstant("created_at")
    val expiresAt = utcInstant("expires_at")
    val usedAt = utcInstant("used_at").nullable()
    val cancelledAt = utcInstant("cancelled_at").nullable()
}

class DaoPasswordResetToken(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPasswordResetToken>(DaoPasswordResetTokens)

    var tokenHash by DaoPasswordResetTokens.tokenHash
    var user by DaoUser referencedOn DaoPasswordResetTokens.user
    var createdByUser by DaoUser optionalReferencedOn DaoPasswordResetTokens.createdByUser
    var createdAt by DaoPasswordResetTokens.createdAt
    var expiresAt by DaoPasswordResetTokens.expiresAt
    var usedAt by DaoPasswordResetTokens.usedAt
    var cancelledAt by DaoPasswordResetTokens.cancelledAt
}
