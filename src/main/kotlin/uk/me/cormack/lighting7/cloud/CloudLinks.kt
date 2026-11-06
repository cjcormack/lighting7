package uk.me.cormack.lighting7.cloud

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.auth.UserRecord
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.LinkOutcome
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.models.CloudConnectionState
import uk.me.cormack.lighting7.models.CloudLinkEventKind
import uk.me.cormack.lighting7.models.CloudLinkEventUser
import uk.me.cormack.lighting7.models.CloudLinkVia
import uk.me.cormack.lighting7.models.CloudUnlinkReason
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoCloudLinkEvents
import uk.me.cormack.lighting7.models.DaoCloudLinkInvites
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.models.DeletedUserLinks
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.recordCloudLinkEvent
import uk.me.cormack.lighting7.models.toIsoUtc
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A desk user's cloud link, as Users and the profile show it. */
@Serializable
data class CloudLinkDto(
    val email: String,
    /** ISO-8601 UTC instant. */
    val linkedAt: String,
    val linkedVia: CloudLinkVia,
)

/** A desk user's live invite. */
@Serializable
data class CloudInviteDto(
    val inviteId: String,
    val email: String,
    /** ISO-8601 UTC instant. */
    val createdAt: String,
    /** ISO-8601 UTC instant. */
    val expiresAt: String,
    /** The display name of the admin who sent it; null once they are deleted. */
    val invitedBy: String? = null,
)

/** One desk user's standing with the cloud: their link and their live invite, each when there is one. */
data class UserCloudLink(val link: CloudLinkDto?, val invite: CloudInviteDto?)

/**
 * Links between cloud accounts and desk users, and invites to make them (cloud-p1 plan D12). A link
 * comes two ways and goes one way:
 *
 * - **Invite**: an admin names an email for a user; the desk keeps the invite and sends
 *   `link.invite`, the cloud holds it until an owner or admin of the org approves it, and its
 *   `link.accepted` writes the link only while the invite is live and the email is the invite's,
 *   answered by `link.ack` either way. `link.refused` is the org declining it.
 * - **Prove once**: an unlinked remote session signs in once with a desk username and password
 *   (`routes/cloudLink.kt`); the session then carries on as that user, and the cloud hears
 *   `link.created`.
 * - **Unlink**, by an admin, by the user, or the user's deletion: the account's sessions end and the
 *   cloud hears `link.removed`.
 *
 * Every change is a row in `cloud_link_events`, written in the change's transaction. What the cloud
 * is told while the channel is down waits in memory, at most [MAX_PENDING] messages, and goes at the
 * next connection; an invite is refused while offline instead, so an admin is never told it went
 * when it did not. Nothing here runs on the show's threads or writes on a timer.
 */
class CloudLinks(
    private val database: Database,
    private val authService: AuthService?,
    private val sessions: RemoteSessions,
    /** Whether the desk is adopted through the configured, enabled cloud. */
    private val adopted: () -> Boolean,
    /** Send to the cloud now, [replyTo] a request's id; false when the channel is not online or will not take it. */
    private val send: (message: Message, replyTo: String?) -> Boolean,
    private val clock: () -> Instant = ::nowUtc,
) {
    private val logger = LoggerFactory.getLogger(CloudLinks::class.java)
    private val lock = Any()

    /** Messages that found the channel down, oldest first; guarded by [lock]. */
    private val pending = ArrayDeque<Message>()

    init {
        pruneAtStartup()
        authService?.addUserDeletedListener(::userDeleted)
    }

    /** Every desk user's link and live invite, by user id. */
    fun forUsers(): Map<Int, UserCloudLink> = transaction(database) {
        val now = clock()
        val names = DaoUsers.selectAll().associate { it[DaoUsers.id].value to it[DaoUsers.displayName] }
        val links = DaoCloudIdentityLinks.selectAll().associate { row ->
            row[DaoCloudIdentityLinks.user].value to CloudLinkDto(
                email = row[DaoCloudIdentityLinks.email],
                linkedAt = row[DaoCloudIdentityLinks.linkedAt].toIsoUtc(),
                linkedVia = row[DaoCloudIdentityLinks.linkedVia],
            )
        }
        val invites = DaoCloudLinkInvites.selectAll().filter { it[DaoCloudLinkInvites.expiresAt] > now }.associate { row ->
            row[DaoCloudLinkInvites.user].value to CloudInviteDto(
                inviteId = row[DaoCloudLinkInvites.inviteId],
                email = row[DaoCloudLinkInvites.email],
                createdAt = row[DaoCloudLinkInvites.createdAt].toIsoUtc(),
                expiresAt = row[DaoCloudLinkInvites.expiresAt].toIsoUtc(),
                invitedBy = row[DaoCloudLinkInvites.invitedBy]?.value?.let(names::get),
            )
        }
        (links.keys + invites.keys).associateWith { UserCloudLink(links[it], invites[it]) }
    }

    fun forUser(userId: Int): UserCloudLink = forUsers()[userId] ?: UserCloudLink(null, null)

    /**
     * Invite [email] to link to desk user [userId] (D12), sent by [by]. Refused while the desk is not
     * adopted or the channel is down, for a user already linked or already invited, and for an email
     * another user's live invite names, since the cloud keeps one live invite per address.
     */
    fun invite(userId: Int, email: String, by: UserRecord?): CloudInviteDto {
        val address = email.trim()
        validEmailProblem(address)?.let { throw CloudException(HttpStatusCode.BadRequest, CloudErrorCodes.EMAIL_INVALID, it) }
        if (!adopted()) throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted.")
        val user = authService?.findUser(userId) ?: throw CloudException(HttpStatusCode.NotFound, CloudErrorCodes.NO_USER, "User not found")
        synchronized(lock) {
            // Older messages first, so an invite never overtakes the cancel of the one before it.
            flushLocked()
            if (pending.isNotEmpty()) throw offline()
            val now = clock()
            val inviteId = UUID.randomUUID().toString()
            val expiresAt = now + INVITE_LIFETIME
            transaction(database) {
                requireStandingLocked(userId)
                pruneExpired(now)
                if (DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.user eq userId }.any()) {
                    throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.LINK_EXISTS, "${user.displayName} is already linked to a cloud account.")
                }
                val live = DaoCloudLinkInvites.selectAll().toList()
                if (live.any { it[DaoCloudLinkInvites.user].value == userId }) {
                    throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.INVITE_PENDING, "${user.displayName} already has an invite waiting: cancel it first.")
                }
                if (live.any { it[DaoCloudLinkInvites.email].equals(address, ignoreCase = true) }) {
                    throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.INVITE_PENDING, "$address already has an invite waiting for another user.")
                }
                DaoCloudLinkInvites.insert {
                    it[DaoCloudLinkInvites.inviteId] = inviteId
                    it[DaoCloudLinkInvites.user] = userId
                    it[DaoCloudLinkInvites.email] = address
                    it[invitedBy] = by?.userId
                    it[createdAt] = now
                    it[DaoCloudLinkInvites.expiresAt] = expiresAt
                }
                recordCloudLinkEvent(now, CloudLinkEventKind.INVITED, user = who(user), email = address, inviteId = inviteId, by = by?.let(::who))
            }
            val message = Message.LinkInvite(
                inviteId = inviteId,
                email = address,
                userName = user.username,
                invitedByName = by?.displayName ?: "An administrator",
                expiresAt = Timestamps.format(expiresAt),
            )
            if (!send(message, null)) {
                // Never sent, so never made: the invite and its audit row go together.
                transaction(database) {
                    DaoCloudLinkInvites.deleteWhere { DaoCloudLinkInvites.inviteId eq inviteId }
                    DaoCloudLinkEvents.deleteWhere { DaoCloudLinkEvents.inviteId eq inviteId }
                }
                throw offline()
            }
            logger.info("Invited {} to link to desk user {}", address, user.username)
            announce(userId)
            return CloudInviteDto(inviteId, address, now.toIsoUtc(), expiresAt.toIsoUtc(), by?.displayName)
        }
    }

    /** Cancel [userId]'s live invite, telling the cloud (`link.cancelled`). False when there was none. */
    fun cancelInvite(userId: Int, by: UserRecord?): Boolean {
        val user = authService?.findUser(userId)
        val cancelled = synchronized(lock) {
            transaction(database) { cancelInvitesLocked(userId, user, clock(), reason = null, by = by) }
                .onEach { tell(Message.LinkCancelled(it)) }
        }
        if (cancelled.isEmpty()) return false
        announce(userId)
        return true
    }

    /**
     * Remove [userId]'s link (D12): the account's remote sessions end and the cloud hears
     * `link.removed`. False when there was none.
     */
    fun unlink(userId: Int, reason: CloudUnlinkReason, by: UserRecord?): Boolean {
        val user = authService?.findUser(userId)
        val accountId = synchronized(lock) {
            transaction(database) {
                val link = DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.user eq userId }.firstOrNull()
                    ?: return@transaction null
                DaoCloudIdentityLinks.deleteWhere { DaoCloudIdentityLinks.user eq userId }
                recordCloudLinkEvent(
                    clock(), CloudLinkEventKind.UNLINKED, user = user?.let(::who),
                    cloudAccountId = link[DaoCloudIdentityLinks.cloudAccountId], email = link[DaoCloudIdentityLinks.email],
                    via = link[DaoCloudIdentityLinks.linkedVia], reason = reason.name, by = by?.let(::who),
                )
                link[DaoCloudIdentityLinks.cloudAccountId]
            }?.also { tell(Message.LinkRemoved(it)) }
        } ?: return false
        sessions.linkRemoved(accountId)
        logger.info("Unlinked desk user {} from its cloud account ({})", user?.username ?: userId, reason)
        announce(userId)
        return true
    }

    /**
     * Link [session]'s account to [user], who has just proved it with their password (D12). Refused
     * when the user is linked to another account, or the account to another user. The user's live
     * invite is cancelled, since it has nothing left to do.
     */
    fun prove(session: RemoteSession, user: UserRecord) {
        if (!adopted()) throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted.")
        synchronized(lock) {
            val now = clock()
            val cancelled = transaction(database) {
                requireStandingLocked(user.userId)
                if (DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.user eq user.userId }.any()) {
                    throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.LINK_EXISTS, "That desk account is already linked to another cloud account.")
                }
                if (DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.cloudAccountId eq session.accountId }.any()) {
                    throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.LINK_EXISTS, "Your cloud account is already linked to another desk account.")
                }
                DaoCloudIdentityLinks.insert {
                    it[DaoCloudIdentityLinks.user] = user.userId
                    it[cloudAccountId] = session.accountId
                    it[email] = session.email
                    it[linkedAt] = now
                    it[linkedVia] = CloudLinkVia.PROVED
                }
                recordCloudLinkEvent(
                    now, CloudLinkEventKind.LINKED, user = who(user), cloudAccountId = session.accountId,
                    email = session.email, via = CloudLinkVia.PROVED, by = who(user),
                )
                cancelInvitesLocked(user.userId, user, now, reason = "LINKED", by = null)
            }
            tell(Message.LinkCreated(accountId = session.accountId, userName = user.username))
            cancelled.forEach { tell(Message.LinkCancelled(it)) }
        }
        sessions.linked(session.accountId, user.userId)
        logger.info("Cloud account {} proved it is desk user {}", session.email, user.username)
        announce(user.userId)
    }

    /** The cloud's `link.accepted` [accepted], envelope [id]: write the link if the invite allows it, and answer `link.ack`. */
    fun accepted(id: String, accepted: Message.LinkAccepted) {
        val outcome = runCatching { synchronized(lock) { transaction(database) { acceptLocked(accepted) } } }
            .getOrElse {
                logger.warn("Couldn't apply the acceptance of invite {}", ChannelConnection.clip(accepted.inviteId), it)
                return
            }
        val ack = when (outcome) {
            is Acceptance.Linked -> Message.LinkAck(accepted.inviteId, LinkOutcome.LINKED)
            is Acceptance.Refused -> Message.LinkAck(accepted.inviteId, LinkOutcome.REFUSED, outcome.reason)
        }
        // A reply, not queued: an acceptance the desk never answered is the cloud's to send again.
        if (!send(ack, id)) logger.warn("Couldn't answer the acceptance of invite {}", ChannelConnection.clip(accepted.inviteId))
        when (outcome) {
            is Acceptance.Linked -> {
                sessions.linked(accepted.accountId, outcome.userId)
                if (outcome.changed) {
                    logger.info("Invite {} accepted: desk user {} is linked", ChannelConnection.clip(accepted.inviteId), outcome.userId)
                    announce(outcome.userId)
                }
            }
            is Acceptance.Refused -> {
                logger.info("Refused the acceptance of invite {}: {}", ChannelConnection.clip(accepted.inviteId), outcome.reason)
                outcome.inviteRemovedFor?.let(::announce)
            }
        }
    }

    private sealed interface Acceptance {
        data class Linked(val userId: Int, val changed: Boolean) : Acceptance

        /** [inviteRemovedFor] is the user whose invite the refusal deleted, if it deleted one. */
        data class Refused(val reason: String, val inviteRemovedFor: Int? = null) : Acceptance
    }

    private fun acceptLocked(accepted: Message.LinkAccepted): Acceptance {
        val now = clock()
        val invite = DaoCloudLinkInvites.selectAll().where { DaoCloudLinkInvites.inviteId eq accepted.inviteId }.firstOrNull()
        val accountLink = DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.cloudAccountId eq accepted.accountId }.firstOrNull()
        fun refuse(reason: String, userId: Int?, inviteRemoved: Boolean): Acceptance.Refused {
            recordCloudLinkEvent(
                now, CloudLinkEventKind.ACCEPTANCE_REFUSED, user = userId?.let(::whoById),
                cloudAccountId = accepted.accountId, email = accepted.email, inviteId = accepted.inviteId, reason = reason,
            )
            return Acceptance.Refused(reason, userId.takeIf { inviteRemoved })
        }
        if (invite == null || invite[DaoCloudLinkInvites.expiresAt] <= now) {
            // The cloud sending again an acceptance whose answer it never heard.
            if (accountLink != null && accountLink[DaoCloudIdentityLinks.inviteId] == accepted.inviteId) {
                return Acceptance.Linked(accountLink[DaoCloudIdentityLinks.user].value, changed = false)
            }
            if (invite != null) expire(invite, now)
            return refuse(ErrorCode.LINK_INVITE_NOT_LIVE, invite?.get(DaoCloudLinkInvites.user)?.value, inviteRemoved = invite != null)
        }
        val userId = invite[DaoCloudLinkInvites.user].value
        if (!invite[DaoCloudLinkInvites.email].trim().equals(accepted.email.trim(), ignoreCase = true)) {
            return refuse(ErrorCode.LINK_EMAIL_MISMATCH, userId, inviteRemoved = false)
        }
        val userLink = DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.user eq userId }.firstOrNull()
        if (userLink != null || accountLink != null) {
            DaoCloudLinkInvites.deleteWhere { DaoCloudLinkInvites.inviteId eq accepted.inviteId }
            return refuse(ErrorCode.LINK_TAKEN, userId, inviteRemoved = true)
        }
        DaoCloudIdentityLinks.insert {
            it[DaoCloudIdentityLinks.user] = userId
            it[cloudAccountId] = accepted.accountId
            it[email] = accepted.email
            it[linkedAt] = now
            it[linkedVia] = CloudLinkVia.INVITE
            it[inviteId] = accepted.inviteId
        }
        DaoCloudLinkInvites.deleteWhere { DaoCloudLinkInvites.inviteId eq accepted.inviteId }
        recordCloudLinkEvent(
            now, CloudLinkEventKind.LINKED, user = whoById(userId), cloudAccountId = accepted.accountId,
            email = accepted.email, inviteId = accepted.inviteId, via = CloudLinkVia.INVITE,
        )
        return Acceptance.Linked(userId, changed = true)
    }

    /** The cloud's `link.refused`: an owner or admin of the org declined the invite. */
    fun refused(refused: Message.LinkRefused) {
        val userId = runCatching {
            synchronized(lock) {
                transaction(database) {
                    val invite = DaoCloudLinkInvites.selectAll().where { DaoCloudLinkInvites.inviteId eq refused.inviteId }.firstOrNull()
                        ?: return@transaction null
                    val userId = invite[DaoCloudLinkInvites.user].value
                    DaoCloudLinkInvites.deleteWhere { DaoCloudLinkInvites.inviteId eq refused.inviteId }
                    recordCloudLinkEvent(
                        clock(), CloudLinkEventKind.INVITE_REFUSED, user = whoById(userId),
                        email = invite[DaoCloudLinkInvites.email], inviteId = refused.inviteId,
                    )
                    userId
                }
            }
        }.onFailure { logger.warn("Couldn't apply the refusal of invite {}", ChannelConnection.clip(refused.inviteId), it) }.getOrNull()
            ?: return
        logger.info("Invite {} was declined in the portal", ChannelConnection.clip(refused.inviteId))
        announce(userId)
    }

    /** The channel is online: send what waited for it, in order. */
    fun online() {
        synchronized(lock) { flushLocked() }
    }

    /** Send what is waiting, oldest first, until the channel will take no more. Must hold [lock]. */
    private fun flushLocked() {
        while (pending.isNotEmpty()) {
            if (!send(pending.first(), null)) return
            pending.removeFirst()
        }
    }

    private fun offline() =
        CloudException(HttpStatusCode.Conflict, CloudErrorCodes.OFFLINE, "This desk isn't online to the cloud right now, so the invite can't be sent.")

    /**
     * Refuse a write for [userId] unless the desk is still adopted and the user still exists, read in
     * the write's own transaction: an unadopt or a user's deletion that committed since the caller's
     * checks must not be followed by a link or invite they would have deleted. Must be called in a transaction.
     */
    private fun requireStandingLocked(userId: Int) {
        if (!DaoCloudConnections.selectAll().where { DaoCloudConnections.state eq CloudConnectionState.ADOPTED }.any()) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted.")
        }
        if (!DaoUsers.selectAll().where { DaoUsers.id eq userId }.any()) {
            throw CloudException(HttpStatusCode.NotFound, CloudErrorCodes.NO_USER, "User not found")
        }
    }

    /** The desk is no longer adopted: nothing waiting is for the cloud that comes next. */
    fun forgetPending() {
        synchronized(lock) { pending.clear() }
    }

    private fun userDeleted(removed: DeletedUserLinks) {
        synchronized(lock) {
            removed.cloudAccountId?.let { tell(Message.LinkRemoved(it)) }
            removed.inviteIds.forEach { tell(Message.LinkCancelled(it)) }
        }
        removed.cloudAccountId?.let(sessions::linkRemoved)
    }

    /** Send [message], or keep it for the next connection. Must hold [lock]. */
    private fun tell(message: Message) {
        if (!adopted()) return
        if (pending.isEmpty() && send(message, null)) return
        if (pending.size >= MAX_PENDING) {
            logger.warn("Dropping '{}' for the cloud: too many messages are waiting for it", pending.first().type)
            pending.removeFirst()
        }
        pending.addLast(message)
    }

    /** Delete [userId]'s invites, recording each as cancelled by [by] for [reason]; answers their ids. Must be called in a transaction. */
    private fun cancelInvitesLocked(userId: Int, user: UserRecord?, now: Instant, reason: String?, by: UserRecord?): List<String> {
        val invites = DaoCloudLinkInvites.selectAll().where { DaoCloudLinkInvites.user eq userId }.toList()
        invites.forEach { invite ->
            recordCloudLinkEvent(
                now, CloudLinkEventKind.INVITE_CANCELLED, user = user?.let(::who),
                email = invite[DaoCloudLinkInvites.email], inviteId = invite[DaoCloudLinkInvites.inviteId], reason = reason, by = by?.let(::who),
            )
        }
        DaoCloudLinkInvites.deleteWhere { DaoCloudLinkInvites.user eq userId }
        return invites.map { it[DaoCloudLinkInvites.inviteId] }
    }

    /** Delete every invite past its expiry, recording each. Must be called in a transaction. */
    private fun pruneExpired(now: Instant) {
        DaoCloudLinkInvites.selectAll().where { DaoCloudLinkInvites.expiresAt lessEq now }.forEach { expire(it, now) }
    }

    private fun expire(invite: ResultRow, now: Instant) {
        recordCloudLinkEvent(
            now, CloudLinkEventKind.INVITE_EXPIRED, user = whoById(invite[DaoCloudLinkInvites.user].value),
            email = invite[DaoCloudLinkInvites.email], inviteId = invite[DaoCloudLinkInvites.inviteId],
        )
        DaoCloudLinkInvites.deleteWhere { DaoCloudLinkInvites.inviteId eq invite[DaoCloudLinkInvites.inviteId] }
    }

    private fun pruneAtStartup() {
        val now = clock()
        runCatching {
            transaction(database) {
                pruneExpired(now)
                DaoCloudLinkEvents.deleteWhere { DaoCloudLinkEvents.at less (now - EVENT_RETENTION) }
            }
        }.onFailure { logger.warn("Couldn't prune expired cloud invites", it) }
    }

    private fun announce(userId: Int) {
        authService?.announceUserChanged(userId)
    }

    private fun who(user: UserRecord) = CloudLinkEventUser(user.userId, user.username)

    /** Must be called in a transaction. */
    private fun whoById(userId: Int): CloudLinkEventUser? =
        DaoUsers.selectAll().where { DaoUsers.id eq userId }.firstOrNull()?.let { CloudLinkEventUser(userId, it[DaoUsers.username]) }

    companion object {
        /** How long an invite waits for its acceptance: the longest the cloud keeps one. */
        val INVITE_LIFETIME: Duration = Duration.ofDays(7)

        /** How long a `cloud_link_events` row is kept. */
        val EVENT_RETENTION: Duration = Duration.ofDays(365)

        /** The most messages kept for the cloud while the channel is down. */
        const val MAX_PENDING: Int = 256

        private const val MAX_EMAIL_LENGTH = 254

        /** Why [email] cannot be invited, or null. Deliberately loose: the cloud checks it by mailing it. */
        fun validEmailProblem(email: String): String? = when {
            email.isEmpty() -> "Enter the email address of their Lighting7 Cloud account."
            email.length > MAX_EMAIL_LENGTH -> "That email address is too long."
            email.any { it.isWhitespace() || it.isISOControl() } -> "An email address has no spaces."
            email.indexOf('@').let { it <= 0 || it == email.lastIndex } -> "That isn't an email address."
            else -> null
        }
    }
}
