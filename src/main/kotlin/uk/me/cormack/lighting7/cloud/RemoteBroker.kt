package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.IceCandidate
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.RevokeTarget
import uk.me.cormack.lighting7.link.LinkIn
import uk.me.cormack.lighting7.link.LinkOut
import uk.me.cormack.lighting7.link.LinkPath
import uk.me.cormack.lighting7.models.toIsoUtc
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** What the broker asks of the sidecar. */
internal interface SidecarPort {
    val running: Boolean

    /** False when the sidecar cannot take [message]: none serves, or it is being restarted. */
    fun send(message: LinkIn): Boolean
}

/**
 * The broker between the control channel and the sidecar (cloud-p1 plan D5, D7, D17): an `rtc.offer`
 * checked against the caps, then the switch and the sidecar, then opened in [sessions] (its account
 * and org recorded, its audit row written) and in the sidecar, whose answer goes back as the offer's
 * reply; candidates and closes both ways; the path the sidecar reports kept for the audit row.
 *
 * With *Approve each new session on this desk* on (D13), an offer past the caps waits in [waitingForApproval]
 * for an admin at the desk to [allow] or [deny] it, or for [approvalTimeout] to pass, before anything
 * is written; the browser's candidates are kept meanwhile. Every refusal is an `rtc.close` replying to
 * the offer. Whenever a session ends, whoever ended it, the cloud and the sidecar are told, each unless
 * it was the one that ended it.
 */
internal class RemoteBroker(
    private val sessions: RemoteSessions,
    private val remoteAccess: () -> Boolean,
    private val sidecar: () -> SidecarPort?,
    private val toCloud: (Message, String?) -> Boolean,
    private val clock: () -> Instant,
    private val approveSessions: () -> Boolean = { false },
    /** The desk user [accountId] links to, if any: who the admin is shown. */
    private val linkedDeskUser: (accountId: String) -> LinkedDeskUser? = { null },
    /** Run the block after the delay, off the caller's thread; answers its cancel. */
    private val after: (Duration, () -> Unit) -> () -> Unit = { _, _ -> {} },
    private val approvalTimeout: () -> Duration = { APPROVAL_TIMEOUT },
) {
    private val logger = LoggerFactory.getLogger(RemoteBroker::class.java)
    private val lock = Any()

    /** The offers this minute that got past the caps, oldest first: never more than [MAX_OFFERS_PER_MINUTE]. */
    private val recentOffers = ArrayDeque<Instant>()

    /** Each open session's offer still to be answered, by sid: the envelope id its reply names. */
    private val pending = ConcurrentHashMap<String, String>()

    /** Sessions ending at the request of one side, which is then not told; and the reason to give the cloud. */
    private val endedBy = ConcurrentHashMap<String, Side>()
    private val cloudReasons = ConcurrentHashMap<String, String>()

    private enum class Side { CLOUD, SIDECAR }

    /**
     * An offer waiting for an admin (D13), by sid; guarded by [lock]. Once allowed it stays listed,
     * [opening], until its `session.open` has reached the sidecar: it still counts against the cap and
     * still keeps the browser's candidates, but no one can answer it again.
     */
    private class Waiting(val replyTo: String, val offer: Message.RtcOffer, var linkedUser: LinkedDeskUser?, val expiresAt: Instant) {
        val ice = kotlin.collections.ArrayDeque<IceCandidate?>()
        var cancelTimeout: () -> Unit = {}
        var opening = false

        /** Withdrawn while it was opening: the session it opens is ended at once, told to all but [Withdrawal.side]. */
        var withdrawn: Withdrawal? = null
    }

    private class Withdrawal(val side: Side?, val reason: RemoteSessionEnd)

    private val waiting = LinkedHashMap<String, Waiting>()

    private val _pending = MutableStateFlow<List<PendingRemoteSession>>(emptyList())

    /** The offers waiting for an admin at the desk, oldest first: what `remoteSession.pending` carries. */
    val waitingForApproval: StateFlow<List<PendingRemoteSession>> = _pending.asStateFlow()

    init {
        sessions.addEndListener { session, reason -> ended(session.sid, reason) }
    }

    fun offer(id: String, offer: Message.RtcOffer) {
        val open = LinkIn.Open(offer.sid, offer.sdp, offer.iceServers)
        val refusal = synchronized(lock) { admit(offer.sid) }
            ?: when {
                // Within what the sidecar reads: a line it skipped would leave the session unanswered.
                offer.sid.length > MAX_SID_CHARS || offer.sdp.length > MAX_SDP_CHARS || open.encode().length > MAX_OPEN_CHARS ->
                    ErrorCode.MALFORMED
                else -> unavailable()
            }
        if (refusal != null) return refuse(offer.sid, id, refusal)
        if (approveSessions()) return await(id, offer)
        if (!open(id, offer)) return
        if (sidecar()?.send(open) != true) endFor(offer.sid, Side.SIDECAR, RemoteSessionEnd.SIDECAR_STOPPED)
    }

    /** Why no session can open now, or null when one can. */
    private fun unavailable(): String? = when {
        !remoteAccess() -> ErrorCode.REMOTE_ACCESS_OFF
        sidecar()?.running != true -> ErrorCode.SIDECAR_UNAVAILABLE
        else -> null
    }

    /** Open the session in the registry, its offer's reply kept for the sidecar's answer: false, and refused, when it cannot. */
    private fun open(id: String, offer: Message.RtcOffer): Boolean {
        when (val result = sessions.open(offer.sid, offer.accountId, offer.email, offer.displayName)) {
            is OpenResult.Refused -> {
                refuse(offer.sid, id, result.reason)
                return false
            }
            is OpenResult.Opened -> pending[result.session.sid] = id
        }
        return true
    }

    /** D13: hold the offer for an admin, with nothing written, until [allow], [deny] or the timeout. */
    private fun await(id: String, offer: Message.RtcOffer) {
        val linkedUser = readLinkedUser(offer.accountId)
        val timeout = approvalTimeout()
        val entry = Waiting(id, offer, linkedUser, clock() + timeout)
        synchronized(lock) {
            if (offer.sid in waiting) return@synchronized null
            waiting[offer.sid] = entry
            publishPending()
            entry
        } ?: return refuse(offer.sid, id, ErrorCode.SESSION_ENDED)
        logger.info("Remote session {} for {} is waiting for an admin at the desk", ChannelConnection.clip(offer.sid), offer.email)
        val cancel = after(timeout) {
            if (settle(offer.sid, entry) != null) {
                logger.info("Remote session {} was not allowed in time", ChannelConnection.clip(offer.sid))
                refuse(offer.sid, id, ErrorCode.REMOTE_SESSION_DENIED)
            }
        }
        synchronized(lock) {
            if (waiting[offer.sid] === entry && !entry.opening) entry.cancelTimeout = cancel else cancel()
        }
    }

    private fun readLinkedUser(accountId: String): LinkedDeskUser? = runCatching { linkedDeskUser(accountId) }
        .onFailure { logger.warn("Couldn't read the link of a remote session waiting for approval", it) }
        .getOrNull()

    /**
     * Take [sid] off the waiting list if it is still waiting to be answered (as [expected], when named):
     * its entry, or null. An entry already allowed and opening is not taken.
     */
    private fun settle(sid: String, expected: Waiting? = null): Waiting? {
        val entry = synchronized(lock) {
            val entry = waiting[sid]?.takeIf { (expected == null || it === expected) && !it.opening } ?: return null
            waiting.remove(sid)
            publishPending()
            entry
        }
        entry.cancelTimeout()
        return entry
    }

    /**
     * Something other than an admin ended [sid]'s wait: take it off the list (its entry, for a reply),
     * or, when it is already opening, mark it so the session it opens is ended as [reason].
     */
    private fun withdraw(sid: String, side: Side?, reason: RemoteSessionEnd): Waiting? {
        val entry = synchronized(lock) {
            val entry = waiting[sid] ?: return null
            if (entry.opening) {
                entry.withdrawn = Withdrawal(side, reason)
                return null
            }
            waiting.remove(sid)
            publishPending()
            entry
        }
        entry.cancelTimeout()
        return entry
    }

    /**
     * An admin at the desk allowed [sid] (D13). Refused when it is not waiting, and when the desk user
     * its account links to has changed since it was shown: the admin is shown the new one and asked again.
     */
    fun allow(sid: String, by: String): AllowResult {
        val shown = synchronized(lock) { waiting[sid]?.takeIf { !it.opening } } ?: return AllowResult.NotWaiting
        val linkedNow = readLinkedUser(shown.offer.accountId)
        val entry = synchronized(lock) {
            val entry = waiting[sid]?.takeIf { it === shown && !it.opening } ?: return AllowResult.NotWaiting
            // By id: a display name is not unique, and the session opens as whoever the link names.
            if (linkedNow?.userId != entry.linkedUser?.userId) {
                entry.linkedUser = linkedNow
                publishPending()
                return AllowResult.LinkChanged(linkedNow?.displayName)
            }
            entry.opening = true
            publishPending()
            entry
        }
        entry.cancelTimeout()
        logger.info("Remote session {} for {} allowed by {}", ChannelConnection.clip(sid), entry.offer.email, by)
        val refusal = unavailable()
        if (refusal != null || !open(entry.replyTo, entry.offer)) {
            synchronized(lock) { if (waiting[sid] === entry) waiting.remove(sid) }
            refusal?.let { refuse(sid, entry.replyTo, it) }
            return AllowResult.Allowed
        }
        if (sidecar()?.send(LinkIn.Open(sid, entry.offer.sdp, entry.offer.iceServers)) != true) {
            synchronized(lock) { if (waiting[sid] === entry) waiting.remove(sid) }
            endFor(sid, Side.SIDECAR, RemoteSessionEnd.SIDECAR_STOPPED)
            return AllowResult.Allowed
        }
        // The candidates kept, then the entry gone, under the lock that [cloudIce] takes: none overtakes the open or is lost.
        val withdrawn = synchronized(lock) {
            entry.ice.forEach { sidecar()?.send(LinkIn.Ice(sid, it)) }
            if (waiting[sid] === entry) waiting.remove(sid)
            entry.withdrawn
        }
        if (withdrawn != null) {
            if (withdrawn.side != null) endFor(sid, withdrawn.side, withdrawn.reason) else sessions.end(sid, withdrawn.reason)
        }
        return AllowResult.Allowed
    }

    /** An admin at the desk denied [sid] (D13): false when it is not waiting. */
    fun deny(sid: String, by: String): Boolean {
        val entry = settle(sid) ?: return false
        logger.info("Remote session {} for {} denied by {}", ChannelConnection.clip(sid), entry.offer.email, by)
        refuse(sid, entry.replyTo, ErrorCode.REMOTE_SESSION_DENIED)
        return true
    }

    /** *Remote sessions* went off, or the desk was unadopted: nothing waiting can be allowed. */
    fun remoteAccessOff() {
        waitingSids().forEach { sid ->
            withdraw(sid, null, RemoteSessionEnd.REMOTE_ACCESS_OFF)?.let { refuse(sid, it.replyTo, ErrorCode.REMOTE_ACCESS_OFF) }
        }
    }

    /**
     * The control channel dropped: an offer's reply names the connection it came on, so nothing waiting
     * can be answered on the next one, and the cloud has already failed it.
     */
    fun connectionLost() {
        waitingSids().forEach { sid ->
            if (withdraw(sid, Side.CLOUD, RemoteSessionEnd.CLOSED_BY_CLOUD) != null) {
                logger.info("Remote session {} stopped waiting: the connection to the cloud dropped", ChannelConnection.clip(sid))
            }
        }
    }

    /** A `revoke`: what it names stops waiting, refused as revoked; one already opening ends once open. */
    fun revoked(revoke: Message.Revoke) {
        val named = synchronized(lock) {
            waiting.values.filter { entry ->
                revoke.targets.any { target ->
                    when (target) {
                        is RevokeTarget.Session -> target.sid == entry.offer.sid
                        is RevokeTarget.Account -> target.accountId == entry.offer.accountId
                        RevokeTarget.All -> true
                    }
                }
            }.map { it.offer.sid }
        }
        named.forEach { sid -> withdraw(sid, null, RemoteSessionEnd.REVOKED)?.let { refuse(sid, it.replyTo, ErrorCode.TOKEN_REVOKED) } }
    }

    private fun waitingSids(): List<String> = synchronized(lock) { waiting.keys.toList() }

    /** Must hold [lock]. */
    private fun publishPending() {
        _pending.value = waiting.values.filter { !it.opening }.map {
            PendingRemoteSession(it.offer.sid, it.offer.email, it.offer.displayName, it.linkedUser?.displayName, it.expiresAt.toIsoUtc())
        }
    }

    /**
     * D17's caps, before any sidecar work or database write: null when the offer may go on. An offer
     * waiting for an admin counts as open, and a second offer for a sid waiting or open is refused.
     */
    private fun admit(sid: String): String? {
        if (sid in waiting || sessions.find(sid) != null) return ErrorCode.SESSION_ENDED
        val now = clock()
        while (recentOffers.isNotEmpty() && Duration.between(recentOffers.first(), now) >= OFFER_WINDOW) recentOffers.removeFirst()
        if (sessions.open.size + waiting.size >= MAX_OPEN_SESSIONS || recentOffers.size >= MAX_OFFERS_PER_MINUTE) {
            return ErrorCode.REMOTE_SESSION_LIMIT
        }
        recentOffers.addLast(now)
        return null
    }

    private fun refuse(sid: String, replyTo: String, reason: String) {
        logger.info("Refused remote session {}: {}", ChannelConnection.clip(sid), reason)
        toCloud(Message.RtcClose(sid, reason), replyTo)
    }

    fun cloudIce(ice: Message.RtcIce) {
        val candidate = ice.candidate
        if (candidate != null && candidate.candidate.length > MAX_CANDIDATE_CHARS) return
        synchronized(lock) {
            // Kept for an offer waiting for an admin, or allowed and still opening, to follow its session.open.
            val entry = waiting[ice.sid]
            if (entry != null) {
                if (entry.ice.size < MAX_WAITING_CANDIDATES) entry.ice.addLast(candidate)
                return
            }
        }
        if (sessions.find(ice.sid) == null) return
        sidecar()?.send(LinkIn.Ice(ice.sid, candidate))
    }

    fun cloudClose(close: Message.RtcClose) {
        if (withdraw(close.sid, Side.CLOUD, RemoteSessionEnd.CLOSED_BY_CLOUD) != null) {
            logger.info("Remote session {} was closed by the cloud while waiting for approval", ChannelConnection.clip(close.sid))
        }
        endFor(close.sid, Side.CLOUD, RemoteSessionEnd.CLOSED_BY_CLOUD)
    }

    fun fromSidecar(message: LinkOut) {
        when (message) {
            is LinkOut.Answer -> {
                val replyTo = pending.remove(message.sid)
                if (replyTo == null || sessions.find(message.sid) == null) {
                    sidecar()?.send(LinkIn.Close(message.sid, ErrorCode.SESSION_ENDED))
                    return
                }
                toCloud(Message.RtcAnswer(message.sid, message.sdp), replyTo)
            }
            is LinkOut.Ice -> if (sessions.find(message.sid) != null) toCloud(Message.RtcIce(message.sid, message.candidate), null)
            is LinkOut.State -> if (message.path in LinkPath.ALL) sessions.find(message.sid)?.path = message.path
            is LinkOut.Closed -> {
                if (sessions.find(message.sid) == null) return
                if (CODE.matches(message.reason)) cloudReasons[message.sid] = message.reason
                endFor(message.sid, Side.SIDECAR, RemoteSessionEnd.LINK_CLOSED)
            }
            is LinkOut.Started -> Unit
        }
    }

    /** The sidecar stopped: every session it carried is over. */
    fun sidecarStopped() {
        sessions.open.forEach { endFor(it.sid, Side.SIDECAR, RemoteSessionEnd.SIDECAR_STOPPED) }
    }

    private fun endFor(sid: String, side: Side, reason: RemoteSessionEnd) {
        endedBy[sid] = side
        try {
            if (!sessions.end(sid, reason)) pending.remove(sid)
        } finally {
            endedBy.remove(sid)
            cloudReasons.remove(sid)
        }
    }

    private fun ended(sid: String, reason: RemoteSessionEnd) {
        val replyTo = pending.remove(sid)
        val side = endedBy[sid]
        if (side != Side.CLOUD) toCloud(Message.RtcClose(sid, cloudReasons[sid] ?: reason.rtcReason), replyTo)
        if (side != Side.SIDECAR) sidecar()?.send(LinkIn.Close(sid, reason.rtcReason))
    }

    companion object {
        /** D17: the most remote sessions open at once, and offers a minute. */
        const val MAX_OPEN_SESSIONS = 8
        const val MAX_OFFERS_PER_MINUTE = 20
        val OFFER_WINDOW: Duration = Duration.ofMinutes(1)

        /** A browser's offer for data channels is a few KiB, and its whole `session.open` far under the sidecar's 1 MiB line. */
        const val MAX_SDP_CHARS = 64 * 1024
        const val MAX_OPEN_CHARS = 128 * 1024
        const val MAX_SID_CHARS = 128
        const val MAX_CANDIDATE_CHARS = 4 * 1024

        /** D13: how long an offer waits for an admin at the desk; the cloud waits 75 s for the answer. */
        val APPROVAL_TIMEOUT: Duration = Duration.ofSeconds(60)

        /** The browser's candidates kept for an offer waiting for approval, as the sidecar keeps per session. */
        const val MAX_WAITING_CANDIDATES = 64

        private val CODE = Regex("[A-Z][A-Z_]{0,63}")
    }
}

/** What an admin's *Allow* came to (D13). */
sealed interface AllowResult {
    data object Allowed : AllowResult

    /** Not waiting: answered already, timed out, closed, or never offered. */
    data object NotWaiting : AllowResult

    /** The desk user its account links to changed while it waited, to [linkedUser]; it waits on, to be answered again. */
    data class LinkChanged(val linkedUser: String?) : AllowResult
}

/** A remote session waiting for an admin at the desk (D13), as `remoteSession.pending` names it. */
@Serializable
data class PendingRemoteSession(
    val sid: String,
    val email: String,
    val displayName: String,
    /** The display name of the desk user the account links to; null for an unlinked one. */
    val linkedUser: String? = null,
    /** When it is denied unless answered. */
    val expiresAt: String,
)
