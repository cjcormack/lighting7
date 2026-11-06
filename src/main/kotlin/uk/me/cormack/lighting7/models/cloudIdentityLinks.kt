package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** How a desk account came to be linked to a cloud account (cloud-p1 plan D12). Stored by name. */
enum class CloudLinkVia {
    /** The admin who adopted the desk, linked to the cloud account that approved it. */
    ADOPTION,

    /** An admin invited the account by email from Users, and it accepted in the portal. */
    INVITE,

    /** An unlinked remote session signed in once with the user's desk username and password. */
    PROVED,
}

/**
 * Which cloud account each desk account is (cloud-p0 plan D7, cloud-p1 plan D12): one cloud account
 * to one desk user and the reverse, held by the unique indexes on both. A remote session's local user
 * is the one its account links to (`cloud/RemoteSessions.kt`). Written by adoption, an accepted
 * invite and a proof, removed by an unlink, a user's deletion and unadoption
 * (`cloud/CloudLinks.kt`). Machine-local, never synced.
 *
 * The CASCADE on `user_id` is not enforced (SQLite runs with `PRAGMA foreign_keys` OFF), so
 * `AuthService.deleteUser` deletes a user's link by hand.
 */
object DaoCloudIdentityLinks : IntIdTable("cloud_identity_links") {
    val user = reference("user_id", DaoUsers, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val cloudAccountId = text("cloud_account_id").uniqueIndex()
    val email = text("email")
    val linkedAt = utcInstant("linked_at")
    val linkedVia = enumerationByName<CloudLinkVia>("linked_via", 16)

    /** The invite an [CloudLinkVia.INVITE] link came from, so an acceptance the cloud sends again is answered linked. */
    val inviteId = text("invite_id").nullable()
}

/** What deleting a desk user took from the cloud's side: its link's account, if it had one, and its live invites. */
data class DeletedUserLinks(val userId: Int, val cloudAccountId: String?, val inviteIds: List<String>)

/**
 * Delete [userId]'s cloud link and invites, record each, and clear the user from the link audit and
 * from the invites it sent, as the unenforced foreign keys would. Must be called inside the
 * deletion's transaction.
 */
fun deleteCloudLinksOf(userId: Int, username: String, now: Instant): DeletedUserLinks {
    val who = CloudLinkEventUser(userId, username)
    val link = DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.user eq userId }.firstOrNull()
    if (link != null) {
        DaoCloudIdentityLinks.deleteWhere { DaoCloudIdentityLinks.user eq userId }
        recordCloudLinkEvent(
            now, CloudLinkEventKind.UNLINKED, user = who,
            cloudAccountId = link[DaoCloudIdentityLinks.cloudAccountId], email = link[DaoCloudIdentityLinks.email],
            via = link[DaoCloudIdentityLinks.linkedVia], reason = CloudUnlinkReason.USER_DELETED.name,
        )
    }
    val invites = DaoCloudLinkInvites.selectAll().where { DaoCloudLinkInvites.user eq userId }.toList()
    invites.forEach { invite ->
        recordCloudLinkEvent(
            now, CloudLinkEventKind.INVITE_CANCELLED, user = who, email = invite[DaoCloudLinkInvites.email],
            inviteId = invite[DaoCloudLinkInvites.inviteId], reason = CloudUnlinkReason.USER_DELETED.name,
        )
    }
    DaoCloudLinkInvites.deleteWhere { DaoCloudLinkInvites.user eq userId }
    DaoCloudLinkInvites.update({ DaoCloudLinkInvites.invitedBy eq userId }) { it[invitedBy] = null }
    DaoCloudLinkEvents.update({ DaoCloudLinkEvents.user eq userId }) { it[user] = null }
    DaoCloudLinkEvents.update({ DaoCloudLinkEvents.byUser eq userId }) { it[byUser] = null }
    return DeletedUserLinks(userId, link?.get(DaoCloudIdentityLinks.cloudAccountId), invites.map { it[DaoCloudLinkInvites.inviteId] })
}

/**
 * Delete every link and invite, recording each as [reason]: the desk is no longer adopted. Answers the
 * users who had one. Must be called inside a transaction.
 */
fun clearCloudLinks(now: Instant, reason: CloudUnlinkReason): Set<Int> {
    val names = DaoUsers.selectAll().associate { it[DaoUsers.id].value to it[DaoUsers.username] }
    fun who(userId: Int) = names[userId]?.let { CloudLinkEventUser(userId, it) }
    DaoCloudIdentityLinks.selectAll().forEach { link ->
        recordCloudLinkEvent(
            now, CloudLinkEventKind.UNLINKED, user = who(link[DaoCloudIdentityLinks.user].value),
            cloudAccountId = link[DaoCloudIdentityLinks.cloudAccountId], email = link[DaoCloudIdentityLinks.email],
            via = link[DaoCloudIdentityLinks.linkedVia], reason = reason.name,
        )
    }
    DaoCloudLinkInvites.selectAll().forEach { invite ->
        recordCloudLinkEvent(
            now, CloudLinkEventKind.INVITE_CANCELLED, user = who(invite[DaoCloudLinkInvites.user].value),
            email = invite[DaoCloudLinkInvites.email], inviteId = invite[DaoCloudLinkInvites.inviteId], reason = reason.name,
        )
    }
    val users = DaoCloudIdentityLinks.selectAll().map { it[DaoCloudIdentityLinks.user].value }.toSet() +
        DaoCloudLinkInvites.selectAll().map { it[DaoCloudLinkInvites.user].value }
    DaoCloudIdentityLinks.deleteAll()
    DaoCloudLinkInvites.deleteAll()
    return users
}

/** Why a link went, as its [CloudLinkEventKind.UNLINKED] row records it. */
enum class CloudUnlinkReason {
    /** An admin unlinked it from Users. */
    BY_ADMIN,

    /** Its user unlinked it from their profile. */
    BY_USER,

    /** Its user was deleted. */
    USER_DELETED,

    /** The desk was unadopted, or the cloud removed it. */
    UNADOPTED,

    /** Adoption linked the adopting admin to another account. */
    REPLACED,
}
