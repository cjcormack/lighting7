package uk.me.cormack.lighting7.cloud

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.cloud.protocol.DeskAccessClaims
import uk.me.cormack.lighting7.cloud.protocol.DeskTokens
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.RevokeTarget
import uk.me.cormack.lighting7.cloud.protocol.TokenCheck
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.cloud.protocol.TokenScope
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoCloudRemoteSessions
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What remote sessions need from the adoption: who tokens are for, and whether the switch is on. */
data class CloudTrust(val installId: String, val orgId: String, val remoteAccess: Boolean)

/** Why a remote session ended, as its audit row records it. */
enum class RemoteSessionEnd {
    /** The desk stopped with the session open, uncleanly: written at the next startup. */
    DESK_RESTARTED,

    /** The desk stopped cleanly with the session open. */
    DESK_STOPPED,

    /** Its token ran out unrefreshed. */
    EXPIRED,

    /** A `revoke` named its session, its account or everything. */
    REVOKED,

    /** A token or refresh for it named another account or org, or no later expiry. */
    MISMATCH,

    /** Its user was disabled or deleted, given a new password, or signed out everywhere. */
    CREDENTIALS_REVOKED,

    /** Its account's link to the local user went. */
    LINK_REMOVED,

    /** *Remote sessions* was turned off. */
    REMOTE_ACCESS_OFF,

    /** The desk is no longer adopted. */
    UNADOPTED,

    /** The cloud closed it: the browser went, or the portal ended it. */
    CLOSED_BY_CLOUD,

    /** The sidecar closed it: its peer went, the desk refused its token, or one of its limits. */
    LINK_CLOSED,

    /** The sidecar carrying it stopped, crashed or was restarted. */
    SIDECAR_STOPPED,

    /** An admin ended it from the Cloud tab. */
    ENDED_BY_ADMIN,

    /** Ended by its user, or by the desk for no other reason. */
    ENDED,
    ;

    /** The `rtc.close` reason that tells the cloud. */
    val rtcReason: String
        get() = when (this) {
            REMOTE_ACCESS_OFF -> ErrorCode.REMOTE_ACCESS_OFF
            MISMATCH -> ErrorCode.REMOTE_SESSION_MISMATCH
            REVOKED -> ErrorCode.TOKEN_REVOKED
            SIDECAR_STOPPED -> ErrorCode.SIDECAR_UNAVAILABLE
            else -> ErrorCode.SESSION_ENDED
        }
}

/** One open remote session (cloud-p1 plan D5). Its account, org and local user are fixed when it opens. */
class RemoteSession internal constructor(
    val sid: String,
    val accountId: String,
    val orgId: String,
    val email: String,
    val displayName: String,
    userId: Int?,
    val startedAt: Instant,
    internal val rowId: Int,
) {
    /**
     * The local user the account is linked to, or null for an unlinked session (D12). Fixed when the
     * session opens, except that an unlinked one takes the link its account gains while it is open.
     */
    @Volatile var userId: Int? = userId
        internal set

    /** Opened unlinked: its audit row names no user until it closes, and then the one it gained, if any. */
    internal val openedUnlinked: Boolean = userId == null

    /** The highest `exp` of any token accepted for it, 0 before the first. */
    @Volatile internal var latestExp: Long = 0

    /** When it ends unless a token with a later `exp` arrives. */
    @Volatile internal var deadline: Instant = startedAt

    @Volatile internal var expiry: ScheduledFuture<*>? = null

    /** The last path the sidecar reported, written to the audit row when the session ends. */
    @Volatile var path: String? = null

    /** Completed with the reason once the session has ended: what its sockets wait on to close 4401. */
    val ended: CompletableDeferred<RemoteSessionEnd> = CompletableDeferred()

    /** What [AuthenticatedUser.sessionTokenHash] is for this session's requests. */
    val tokenHash: String get() = tokenHashOf(sid)

    companion object {
        const val TOKEN_HASH_PREFIX: String = "cloud-session:"

        fun tokenHashOf(sid: String): String = TOKEN_HASH_PREFIX + sid
    }
}

/** What [RemoteSessions.authenticate] made of a bearer request. */
sealed interface BearerCheck {
    /** A session linked to a local user, who is the caller. [previousExp] is the session's latest `exp` before this token. */
    data class Linked(val session: RemoteSession, val user: AuthenticatedUser, val claims: DeskAccessClaims, val previousExp: Long) : BearerCheck

    /** A session whose account links to no local user (D4, D12). */
    data class Unlinked(val session: RemoteSession, val claims: DeskAccessClaims, val previousExp: Long) : BearerCheck

    data class Refused(val status: HttpStatusCode, val code: String, val message: String) : BearerCheck

    val sessionOrNull: RemoteSession?
        get() = when (this) {
            is Linked -> session
            is Unlinked -> session
            is Refused -> null
        }
}

/** Why [RemoteSessions.open] refused, an `rtc.close` reason. */
sealed interface OpenResult {
    data class Opened(val session: RemoteSession) : OpenResult

    data class Refused(val reason: String) : OpenResult
}

/**
 * The remote sessions the broker opened (cloud-p1 plan D4, D5): an in-memory registry of each `sid`
 * with its account, org and local user, the bearer check every request through the sidecar passes,
 * expiry, revocation and the deny set that keeps an ended or revoked session from coming back, and
 * the `cloud_remote_sessions` audit row written at each session's open and end, never in between.
 *
 * Nothing here runs on the show's threads: a session's expiry runs on one `cloud-sessions` thread,
 * started with the first session and stopped when idle.
 */
class RemoteSessions(
    private val database: Database,
    private val authService: AuthService?,
    /** The adoption as the desk holds it now, or null while it is not adopted through an enabled cloud. */
    private val trust: () -> CloudTrust?,
    /** The keys the cloud's last `jwks` named. */
    private val keys: () -> Map<String, PublicKey>,
    private val clock: () -> Instant = ::nowUtc,
) {
    private val logger = LoggerFactory.getLogger(RemoteSessions::class.java)
    private val sessions = ConcurrentHashMap<String, RemoteSession>()

    /** Sessions that ended or were revoked, by sid, until any token issued for one has expired. */
    private val endedSids = ConcurrentHashMap<String, Instant>()

    /**
     * The latest `revoke` naming each account, and the latest naming everything, each kept until its
     * tokens have expired. One per target is enough, since a later `at` covers whatever an earlier one
     * did, and it bounds what a cloud sending revokes can make the desk hold.
     */
    private val accountRevokes = ConcurrentHashMap<String, Denial>()
    private val allRevoke = AtomicReference<Denial?>(null)

    /**
     * A `revoke`'s cover: every token whose `iat` is at or before [atSecond], `at`'s whole second, or
     * every token when `at` is not a timestamp, as [Message.Revoke.covers] judges one.
     */
    private class Denial(val atSecond: Long, val keepUntil: Instant) {
        fun covers(iat: Long, now: Instant): Boolean = keepUntil > now && iat <= atSecond

        fun merge(other: Denial) = Denial(maxOf(atSecond, other.atSecond), maxOf(keepUntil, other.keepUntil))

        companion object {
            fun of(revoke: Message.Revoke, keepUntil: Instant) =
                Denial(Timestamps.parse(revoke.at)?.epochSecond ?: Long.MAX_VALUE, keepUntil)
        }
    }

    private val endListeners = CopyOnWriteArrayList<(RemoteSession, RemoteSessionEnd) -> Unit>()

    @Volatile private var linkSecret: ByteArray? = null

    private val random = SecureRandom()
    private val executorLock = Any()
    private var executor: ScheduledThreadPoolExecutor? = null

    init {
        closeLeftOpen()
        authService?.addSessionRevocationListener { userId, except ->
            sessions.values.filter { it.userId == userId && it.tokenHash != except }
                .forEach { end(it.sid, RemoteSessionEnd.CREDENTIALS_REVOKED) }
        }
    }

    /** Every session open now. */
    val open: Collection<RemoteSession> get() = sessions.values.toList()

    fun find(sid: String): RemoteSession? = sessions[sid]

    /** Called whenever a session ends, after its audit row is closed: the broker tells the cloud and the sidecar. */
    fun addEndListener(listener: (RemoteSession, RemoteSessionEnd) -> Unit) {
        endListeners += listener
    }

    /**
     * A new secret for a new launch of the sidecar, which sends it as `Lighting7-Link` on every request
     * (D4). Only a request carrying it may use a bearer token, so a valid token that reaches the public
     * listener any other way is refused. The previous launch's stops working.
     */
    fun newLinkSecret(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        linkSecret = secret.toByteArray(Charsets.US_ASCII)
        return secret
    }

    /** No sidecar runs: every bearer request is refused. */
    fun clearLinkSecret() {
        linkSecret = null
    }

    /**
     * Open [sid] for [accountId] (the broker's call, at `rtc.offer`): its link resolved now and fixed
     * for its life, its audit row written. Refused while not adopted, with *Remote sessions* off, or
     * for a sid already open or ended.
     */
    fun open(sid: String, accountId: String, email: String, displayName: String): OpenResult {
        val trust = trust() ?: return OpenResult.Refused(ErrorCode.SESSION_ENDED)
        if (!trust.remoteAccess) return OpenResult.Refused(ErrorCode.REMOTE_ACCESS_OFF)
        val now = clock()
        pruneDenials(now)
        if (sessions.containsKey(sid) || endedSids.containsKey(sid)) return OpenResult.Refused(ErrorCode.SESSION_ENDED)
        // A sid this desk has ever audited is refused too: one ended before a restart, or long enough ago to leave the deny set.
        val opened = runCatching {
            transaction(database) {
                if (DaoCloudRemoteSessions.selectAll().where { DaoCloudRemoteSessions.sid eq sid }.any()) return@transaction null
                val userId = linkedUser(accountId)
                val rowId = DaoCloudRemoteSessions.insert {
                    it[DaoCloudRemoteSessions.sid] = sid
                    it[cloudAccountId] = accountId
                    it[DaoCloudRemoteSessions.email] = email
                    it[user] = userId
                    it[startedAt] = now
                }[DaoCloudRemoteSessions.id].value
                rowId to userId
            }
        }.onFailure { logger.warn("Couldn't open remote session {}", sid, it) }.getOrNull()
            ?: return OpenResult.Refused(ErrorCode.SESSION_ENDED)
        val (rowId, userId) = opened
        val session = RemoteSession(sid, accountId, trust.orgId, email, displayName, userId, now, rowId)
        session.deadline = now + DeskTokens.MAX_LIFETIME + DeskTokens.CLOCK_SKEW
        if (sessions.putIfAbsent(sid, session) != null) {
            closeRow(session, now, RemoteSessionEnd.ENDED)
            return OpenResult.Refused(ErrorCode.SESSION_ENDED)
        }
        scheduleExpiry(session)
        // A link change that committed between the read above and the registration called [linked] or
        // [linkRemoved] before this session was there to hear it, so read the link once more now.
        val current = runCatching { linkedUser(accountId) }.getOrElse { userId }
        if (current != userId) {
            if (userId == null) {
                linked(accountId, current!!)
            } else {
                end(sid, RemoteSessionEnd.LINK_REMOVED)
                return OpenResult.Refused(ErrorCode.SESSION_ENDED)
            }
        }
        logger.info("Remote session {} opened for {}", sid, email)
        return OpenResult.Opened(session)
    }

    /** The desk user [accountId] links to, if any (D13: who an admin allowing it is shown). */
    fun linkedDeskUser(accountId: String): LinkedDeskUser? = transaction(database) {
        (DaoCloudIdentityLinks innerJoin DaoUsers).selectAll().where { DaoCloudIdentityLinks.cloudAccountId eq accountId }
            .firstOrNull()?.let { LinkedDeskUser(it[DaoUsers.id].value, it[DaoUsers.displayName]) }
    }

    /**
     * What the Cloud tab lists: every open session, newest first, and the [limit] most recently
     * ended from the audit, each with the desk user it was (by name, as the user is now).
     */
    fun listing(limit: Int = RECENT_LIMIT): RemoteSessionsDto {
        val now = open.sortedByDescending { it.startedAt }
        val names = now.mapNotNull { it.userId }.associateWith { authService?.findUser(it)?.displayName }
        val recent = transaction(database) {
            (DaoCloudRemoteSessions leftJoin DaoUsers).selectAll().where { DaoCloudRemoteSessions.endedAt.isNotNull() }
                .orderBy(DaoCloudRemoteSessions.endedAt to SortOrder.DESC, DaoCloudRemoteSessions.id to SortOrder.DESC)
                .limit(limit)
                .map {
                    RemoteSessionDto(
                        sid = it[DaoCloudRemoteSessions.sid],
                        email = it[DaoCloudRemoteSessions.email],
                        user = it.getOrNull(DaoUsers.displayName),
                        startedAt = it[DaoCloudRemoteSessions.startedAt].toIsoUtc(),
                        endedAt = it[DaoCloudRemoteSessions.endedAt]?.toIsoUtc(),
                        path = it[DaoCloudRemoteSessions.path],
                        endReason = it[DaoCloudRemoteSessions.endReason],
                    )
                }
        }
        return RemoteSessionsDto(
            open = now.map {
                RemoteSessionDto(
                    sid = it.sid,
                    email = it.email,
                    displayName = it.displayName,
                    user = it.userId?.let(names::get),
                    startedAt = it.startedAt.toIsoUtc(),
                    path = it.path,
                )
            },
            recent = recent,
        )
    }

    private fun linkedUser(accountId: String): Int? = transaction(database) {
        DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.cloudAccountId eq accountId }
            .firstOrNull()?.get(DaoCloudIdentityLinks.user)?.value
    }

    /**
     * Judge a request's bearer [token] (D4): it must carry the sidecar's [linkHeader], verify under the
     * cloud's keys for this install at the remote door, be covered by no `revoke`, and name an open
     * session whose account and org it matches. A token naming another account or org ends that
     * session. A linked session's caller is its local user as [AuthService] holds them now, so a
     * demotion counts at once.
     */
    fun authenticate(token: String, linkHeader: String?): BearerCheck {
        val secret = linkSecret
        if (secret == null || linkHeader == null || !MessageDigest.isEqual(secret, linkHeader.toByteArray(Charsets.US_ASCII))) {
            return unauthenticated()
        }
        val trust = trust() ?: return unauthenticated()
        if (!trust.remoteAccess) {
            return BearerCheck.Refused(HttpStatusCode.Forbidden, ErrorCode.REMOTE_ACCESS_OFF, "Remote sessions are turned off on this desk.")
        }
        val now = clock()
        val claims = when (val check = DeskTokens.verify(token, keys(), trust.installId, TokenScope.REMOTE, now)) {
            is TokenCheck.Refused -> return refused(check.refusal.code, "This desk refused the session's token (${check.refusal.code}).")
            is TokenCheck.Valid -> check.claims
        }
        val sid = claims.sid ?: return refused(ErrorCode.TOKEN_MALFORMED, "A remote token names a session.")
        if (revoked(claims, now)) return refused(ErrorCode.TOKEN_REVOKED, "This session was ended.")
        val session = sessions[sid] ?: return refused(ErrorCode.REMOTE_SESSION_UNKNOWN, "This desk has no such session.")
        if (claims.sub != session.accountId || claims.org != session.orgId) {
            end(sid, RemoteSessionEnd.MISMATCH)
            return refused(ErrorCode.REMOTE_SESSION_MISMATCH, "This token is for another account.")
        }
        val previousExp = accept(session, claims)
        val userId = session.userId ?: return BearerCheck.Unlinked(session, claims, previousExp)
        val user = authService?.findUser(userId)
        if (user == null) {
            end(sid, RemoteSessionEnd.CREDENTIALS_REVOKED)
            return unauthenticated()
        }
        if (user.disabled) return unauthenticated()
        val caller = AuthenticatedUser(user.userId, user.uuid, user.username, user.displayName, user.role, session.tokenHash)
        return BearerCheck.Linked(session, caller, claims, previousExp)
    }

    /**
     * A refresh (D5): [check]'s token, already judged by [authenticate], must not expire before a token
     * already used, which would hand the session back an older one. Anything else ends the session.
     * The same token seen again, on a request that beat the refresh or on a retry, is accepted. Null
     * when the refresh is accepted.
     */
    fun refresh(check: BearerCheck): BearerCheck.Refused? {
        val (session, claims, previousExp) = when (check) {
            is BearerCheck.Linked -> Triple(check.session, check.claims, check.previousExp)
            is BearerCheck.Unlinked -> Triple(check.session, check.claims, check.previousExp)
            is BearerCheck.Refused -> return check
        }
        if (claims.exp >= previousExp) return null
        end(session.sid, RemoteSessionEnd.MISMATCH)
        return refused(ErrorCode.REMOTE_SESSION_MISMATCH, "A refreshed token must not expire before one already used.")
    }

    /** A `revoke` from the cloud: end what it names, and refuse every token it covers until they have all expired. */
    fun revoke(revoke: Message.Revoke) {
        val now = clock()
        pruneDenials(now)
        val keepUntil = now + DENIAL_TTL
        val denial = Denial.of(revoke, keepUntil)
        for (target in revoke.targets) {
            when (target) {
                is RevokeTarget.Session -> if (!end(target.sid, RemoteSessionEnd.REVOKED)) {
                    // Not open yet, or no longer: never let it open. Past the cap, deny everything instead.
                    if (endedSids.size < MAX_DENIALS || endedSids.containsKey(target.sid)) endedSids[target.sid] = keepUntil else denyAll(denial)
                }
                is RevokeTarget.Account -> {
                    if (accountRevokes.size < MAX_DENIALS || accountRevokes.containsKey(target.accountId)) {
                        accountRevokes.merge(target.accountId, denial, Denial::merge)
                    } else {
                        denyAll(denial)
                    }
                    sessions.values.filter { it.accountId == target.accountId }.forEach { end(it.sid, RemoteSessionEnd.REVOKED) }
                }
                RevokeTarget.All -> denyAll(denial)
            }
        }
    }

    /**
     * [accountId] is now linked to [userId] (D12): its unlinked sessions become that user's, so the
     * session that proved the link carries on signed in. A session already linked keeps its user.
     */
    fun linked(accountId: String, userId: Int) {
        sessions.values.filter { it.accountId == accountId }.forEach { session ->
            synchronized(session) { if (session.userId == null) session.userId = userId }
        }
    }

    /** [accountId]'s link went: end its sessions (D5, D12). */
    fun linkRemoved(accountId: String) {
        sessions.values.filter { it.accountId == accountId }.forEach { end(it.sid, RemoteSessionEnd.LINK_REMOVED) }
    }

    fun endAll(reason: RemoteSessionEnd) {
        sessions.keys.toList().forEach { end(it, reason) }
    }

    /** End [sid]: its sockets close 4401, its requests are refused, and it can never be used again. False when it was not open. */
    fun end(sid: String, reason: RemoteSessionEnd): Boolean {
        val session = sessions.remove(sid) ?: return false
        val now = clock()
        endedSids[sid] = now + DENIAL_TTL
        session.expiry?.cancel(false)
        session.ended.complete(reason)
        runCatching { closeRow(session, now, reason) }.onFailure { logger.warn("Couldn't close the audit row of remote session {}", sid, it) }
        logger.info("Remote session {} ended: {}", sid, reason)
        endListeners.forEach { listener -> runCatching { listener(session, reason) }.onFailure { logger.warn("A remote session listener failed", it) } }
        return true
    }

    /** The desk is stopping: end what is open now, so its audit rows say when. */
    fun close() {
        endAll(RemoteSessionEnd.DESK_STOPPED)
        synchronized(executorLock) {
            executor?.shutdownNow()
            executor = null
        }
    }

    private fun denyAll(denial: Denial) {
        allRevoke.updateAndGet { current -> current?.merge(denial) ?: denial }
        endAll(RemoteSessionEnd.REVOKED)
    }

    private fun revoked(claims: DeskAccessClaims, now: Instant): Boolean =
        allRevoke.get()?.covers(claims.iat, now) == true || accountRevokes[claims.sub]?.covers(claims.iat, now) == true

    /** Raise the session's latest `exp` and its deadline to [claims]'; answers the `exp` before. */
    private fun accept(session: RemoteSession, claims: DeskAccessClaims): Long {
        val previous: Long
        synchronized(session) {
            previous = session.latestExp
            if (claims.exp > previous) {
                session.latestExp = claims.exp
                session.deadline = Instant.ofEpochSecond(claims.exp) + DeskTokens.CLOCK_SKEW
            }
        }
        if (claims.exp > previous) scheduleExpiry(session)
        return previous
    }

    private fun scheduleExpiry(session: RemoteSession) {
        val delayMs = Duration.between(clock(), session.deadline).toMillis().coerceAtLeast(0) + 1
        val future = executor().schedule({ checkExpiry(session) }, delayMs, TimeUnit.MILLISECONDS)
        session.expiry?.cancel(false)
        session.expiry = future
        if (sessions[session.sid] !== session) future.cancel(false)
    }

    private fun checkExpiry(session: RemoteSession) {
        if (sessions[session.sid] !== session) return
        if (clock() >= session.deadline) end(session.sid, RemoteSessionEnd.EXPIRED) else scheduleExpiry(session)
    }

    private fun executor(): ScheduledThreadPoolExecutor = synchronized(executorLock) {
        executor ?: ScheduledThreadPoolExecutor(1) { task -> Thread(task, "cloud-sessions").apply { isDaemon = true } }.apply {
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
            removeOnCancelPolicy = true
            executor = this
        }
    }

    /** Forget denials whose tokens have all expired, each removal atomic against a concurrent write of the same key. */
    private fun pruneDenials(now: Instant) {
        endedSids.keys.forEach { sid -> endedSids.computeIfPresent(sid) { _, until -> until.takeIf { it > now } } }
        accountRevokes.keys.forEach { account -> accountRevokes.computeIfPresent(account) { _, d -> d.takeIf { it.keepUntil > now } } }
        allRevoke.updateAndGet { d -> d?.takeIf { it.keepUntil > now } }
    }

    private fun closeRow(session: RemoteSession, now: Instant, reason: RemoteSessionEnd) = transaction(database) {
        DaoCloudRemoteSessions.update({ (DaoCloudRemoteSessions.id eq session.rowId) and DaoCloudRemoteSessions.endedAt.isNull() }) {
            it[endedAt] = now
            it[path] = session.path
            it[endReason] = reason.name
            // A session that gained its link while open names that user, unless they have been deleted since.
            val gained = session.userId?.takeIf { id -> session.openedUnlinked && DaoUsers.selectAll().where { DaoUsers.id eq id }.any() }
            if (gained != null) it[user] = gained
        }
    }

    /** At startup: close what a crash left open, and prune rows older than [RETENTION]. */
    private fun closeLeftOpen() {
        val now = clock()
        runCatching {
            transaction(database) {
                DaoCloudRemoteSessions.update({ DaoCloudRemoteSessions.endedAt.isNull() }) {
                    it[endedAt] = now
                    it[endReason] = RemoteSessionEnd.DESK_RESTARTED.name
                }
                DaoCloudRemoteSessions.deleteWhere { startedAt less (now - RETENTION) }
            }
        }.onFailure { logger.warn("Couldn't close the remote sessions a restart left open", it) }
    }

    private fun unauthenticated() = BearerCheck.Refused(HttpStatusCode.Unauthorized, UNAUTHENTICATED, "Not signed in")

    private fun refused(code: String, message: String) = BearerCheck.Refused(HttpStatusCode.Unauthorized, code, message)

    companion object {
        /** The cookie gate's code for no session, which a refused bearer without a sharper reason shares. */
        const val UNAUTHENTICATED: String = "unauthenticated"

        /** The header the sidecar sets to its launch's secret on every request it forwards (D4, D7). */
        const val LINK_HEADER: String = "Lighting7-Link"

        /** How long an ended session or a `revoke` is remembered: past the expiry of any token issued before it. */
        val DENIAL_TTL: Duration = DeskTokens.MAX_LIFETIME + DeskTokens.CLOCK_SKEW + DeskTokens.CLOCK_SKEW

        /** The most sids, and accounts, the deny set names; a cloud revoking more is treated as revoking everything. */
        const val MAX_DENIALS: Int = 4096

        /** How long an audit row is kept. */
        val RETENTION: Duration = Duration.ofDays(365)

        /** How many ended sessions the Cloud tab lists. */
        const val RECENT_LIMIT: Int = 20
    }
}

/** A desk user a cloud account links to: [userId] says who, [displayName] is what is shown. */
data class LinkedDeskUser(val userId: Int, val displayName: String)

/** The Cloud tab's sessions: `GET /install/cloud/sessions`. */
@Serializable
data class RemoteSessionsDto(val open: List<RemoteSessionDto>, val recent: List<RemoteSessionDto>)

/** One remote session, open or from the audit. [displayName] is known only while it is open. */
@Serializable
data class RemoteSessionDto(
    val sid: String,
    val email: String,
    val displayName: String? = null,
    /** The desk user it was, by their name now; null for an unlinked session or a user since deleted. */
    val user: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    /** `DIRECT` or `RELAY`, the last the sidecar reported. */
    val path: String? = null,
    /** A [RemoteSessionEnd] name, once ended. */
    val endReason: String? = null,
)
