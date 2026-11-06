package uk.me.cormack.lighting7.cloud

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.link.LinkIn
import uk.me.cormack.lighting7.link.LinkOut
import uk.me.cormack.lighting7.link.LinkPath
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
 * Every refusal is an `rtc.close` replying to the offer. Whenever a session ends, whoever ended it,
 * the cloud and the sidecar are told, each unless it was the one that ended it.
 */
internal class RemoteBroker(
    private val sessions: RemoteSessions,
    private val remoteAccess: () -> Boolean,
    private val sidecar: () -> SidecarPort?,
    private val toCloud: (Message, String?) -> Boolean,
    private val clock: () -> Instant,
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

    init {
        sessions.addEndListener { session, reason -> ended(session.sid, reason) }
    }

    fun offer(id: String, offer: Message.RtcOffer) {
        val open = LinkIn.Open(offer.sid, offer.sdp, offer.iceServers)
        val refusal = synchronized(lock) { admit() }
            ?: when {
                !remoteAccess() -> ErrorCode.REMOTE_ACCESS_OFF
                sidecar()?.running != true -> ErrorCode.SIDECAR_UNAVAILABLE
                // Within what the sidecar reads: a line it skipped would leave the session unanswered.
                offer.sid.length > MAX_SID_CHARS || offer.sdp.length > MAX_SDP_CHARS || open.encode().length > MAX_OPEN_CHARS ->
                    ErrorCode.MALFORMED
                else -> null
            }
        if (refusal != null) return refuse(offer.sid, id, refusal)
        val opened = when (val result = sessions.open(offer.sid, offer.accountId, offer.email, offer.displayName)) {
            is OpenResult.Refused -> return refuse(offer.sid, id, result.reason)
            is OpenResult.Opened -> result.session
        }
        pending[opened.sid] = id
        if (sidecar()?.send(open) != true) {
            endFor(opened.sid, Side.SIDECAR, RemoteSessionEnd.SIDECAR_STOPPED)
        }
    }

    /** D17's caps, before any sidecar work or database write: null when the offer may go on. */
    private fun admit(): String? {
        val now = clock()
        while (recentOffers.isNotEmpty() && Duration.between(recentOffers.first(), now) >= OFFER_WINDOW) recentOffers.removeFirst()
        if (sessions.open.size >= MAX_OPEN_SESSIONS || recentOffers.size >= MAX_OFFERS_PER_MINUTE) return ErrorCode.REMOTE_SESSION_LIMIT
        recentOffers.addLast(now)
        return null
    }

    private fun refuse(sid: String, replyTo: String, reason: String) {
        logger.info("Refused remote session {}: {}", ChannelConnection.clip(sid), reason)
        toCloud(Message.RtcClose(sid, reason), replyTo)
    }

    fun cloudIce(ice: Message.RtcIce) {
        if (sessions.find(ice.sid) == null) return
        val candidate = ice.candidate
        if (candidate != null && candidate.candidate.length > MAX_CANDIDATE_CHARS) return
        sidecar()?.send(LinkIn.Ice(ice.sid, candidate))
    }

    fun cloudClose(close: Message.RtcClose) {
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

        private val CODE = Regex("[A-Z][A-Z_]{0,63}")
    }
}
