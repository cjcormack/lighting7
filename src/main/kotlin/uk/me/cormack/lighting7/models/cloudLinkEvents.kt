package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant

/** What happened to a link or an invite (cloud-p1 plan D12). Stored by name. */
enum class CloudLinkEventKind {
    /** A link was written: [DaoCloudLinkEvents.via] says how. */
    LINKED,

    /** A link went: [DaoCloudLinkEvents.reason] says why. */
    UNLINKED,

    INVITED,
    INVITE_CANCELLED,

    /** An owner or admin of the org declined the invite in the portal. */
    INVITE_REFUSED,
    INVITE_EXPIRED,

    /** The cloud said an invite was accepted and the desk refused it: [DaoCloudLinkEvents.reason] is the `link.ack` reason. */
    ACCEPTANCE_REFUSED,
}

/**
 * The audit of links and invites (cloud-p1 plan D12): a row per change, written in the change's own
 * transaction. Names are kept as text beside the user ids, so a row still says who after the user
 * is deleted; rows older than a year are pruned at startup. Machine-local, never synced.
 *
 * `user_id` and `by_user_id` are SET_NULL in intent only (SQLite runs with `PRAGMA foreign_keys`
 * OFF), so `AuthService.deleteUser` clears them by hand.
 */
object DaoCloudLinkEvents : IntIdTable("cloud_link_events") {
    val at = utcInstant("at")
    val kind = enumerationByName<CloudLinkEventKind>("kind", 24)
    val user = optReference("user_id", DaoUsers, onDelete = ReferenceOption.SET_NULL)
    val username = text("username").nullable()
    val cloudAccountId = text("cloud_account_id").nullable()
    val email = text("email").nullable()
    val inviteId = text("invite_id").nullable()
    val via = enumerationByName<CloudLinkVia>("via", 16).nullable()
    val reason = text("reason").nullable()

    /** The desk user who made the change, when one did. */
    val byUser = optReference("by_user_id", DaoUsers, onDelete = ReferenceOption.SET_NULL)
    val byUsername = text("by_username").nullable()
}

/** Who a [DaoCloudLinkEvents] row is about, or who made the change: an id and the name it had then. */
data class CloudLinkEventUser(val userId: Int, val username: String)

/** Write one [DaoCloudLinkEvents] row. Must be called inside the change's transaction. */
fun recordCloudLinkEvent(
    at: Instant,
    kind: CloudLinkEventKind,
    user: CloudLinkEventUser? = null,
    cloudAccountId: String? = null,
    email: String? = null,
    inviteId: String? = null,
    via: CloudLinkVia? = null,
    reason: String? = null,
    by: CloudLinkEventUser? = null,
) {
    DaoCloudLinkEvents.insert {
        it[DaoCloudLinkEvents.at] = at
        it[DaoCloudLinkEvents.kind] = kind
        it[DaoCloudLinkEvents.user] = user?.userId
        it[username] = user?.username
        it[DaoCloudLinkEvents.cloudAccountId] = cloudAccountId
        it[DaoCloudLinkEvents.email] = email
        it[DaoCloudLinkEvents.inviteId] = inviteId
        it[DaoCloudLinkEvents.via] = via
        it[DaoCloudLinkEvents.reason] = reason
        it[byUser] = by?.userId
        it[byUsername] = by?.username
    }
}
