package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Invites to link a cloud account to a desk user (cloud-p1 plan D12): one live invite per user and
 * per email, kept until it is accepted, refused in the portal, cancelled or past [expiresAt] (at most
 * seven days). Machine-local, never synced.
 *
 * The references are enforced by hand (SQLite runs with `PRAGMA foreign_keys` OFF):
 * `AuthService.deleteUser` deletes a user's invites and clears [invitedBy].
 */
object DaoCloudLinkInvites : IntIdTable("cloud_link_invites") {
    val inviteId = text("invite_id").uniqueIndex()
    val user = reference("user_id", DaoUsers, onDelete = ReferenceOption.CASCADE).index()
    val email = text("email")
    val invitedBy = optReference("invited_by_user_id", DaoUsers, onDelete = ReferenceOption.SET_NULL)
    val createdAt = utcInstant("created_at")
    val expiresAt = utcInstant("expires_at")
}
