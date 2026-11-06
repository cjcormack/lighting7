package uk.me.cormack.lighting7.cloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.protocol.AuthFailure
import uk.me.cormack.lighting7.cloud.protocol.AuthPayload
import uk.me.cormack.lighting7.cloud.protocol.CloseCode
import uk.me.cormack.lighting7.cloud.protocol.CloudPaths
import uk.me.cormack.lighting7.cloud.protocol.Envelope
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.MAX_FRAME_BYTES
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.PROTOCOL_VERSION
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.update.BuildInfo
import java.lang.management.ManagementFactory
import java.net.URI
import java.security.PrivateKey
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** Where and as whom the channel connects: fixed for one adoption. [key] is read when an attempt needs it. */
internal class ChannelTarget(val baseUrl: String, val installId: String, val key: () -> PrivateKey?) {
    override fun toString(): String = "ChannelTarget($baseUrl, $installId)"
}

/** Who the desk says it is, to the cloud: at adoption and in `hello`. */
data class DeskIdentity(val installUuid: String, val friendlyName: String) {
    companion object {
        fun read(database: Database): DeskIdentity = transaction(database) {
            DaoInstall.all().first().let { DeskIdentity(it.uuid.toString(), it.friendlyName) }
        }
    }
}

/** The channel's live status, which [CloudService] keeps in memory only (D11). */
internal data class ChannelStatus(
    val connection: CloudConnectionStatus,
    val retryAt: Instant? = null,
    val lastError: CloudErrorDto? = null,
    val lastOnlineAt: Instant? = null,
)

/**
 * What a connection asks of the desk, and tells it, while online (cloud-p1 plan D3, D5, D6). Each call
 * runs on the channel's thread and must not block on anything the cloud controls.
 */
internal interface ChannelHandler {
    /** What `hello` and `capabilities` announce now. */
    fun capabilities(): List<String> = emptyList()

    /** [connection] is online, its `hello` queued, or [connection] has gone offline. */
    fun online(connection: ChannelConnection) {}

    fun offline(connection: ChannelConnection) {}

    fun welcomed(welcome: Message.Welcome) {}

    fun jwks(jwks: Message.Jwks) {}

    fun revoke(revoke: Message.Revoke) {}

    /** An `rtc.offer`, [id] its envelope's: what the answer, or a refusal, replies to. */
    fun rtcOffer(id: String, offer: Message.RtcOffer) {}

    fun rtcIce(ice: Message.RtcIce) {}

    fun rtcClose(close: Message.RtcClose) {}

    /** A `link.accepted`, [id] its envelope's: what `link.ack` replies to (D12). */
    fun linkAccepted(id: String, accepted: Message.LinkAccepted) {}

    fun linkRefused(refused: Message.LinkRefused) {}

    object None : ChannelHandler
}

/** How one connection ended. */
internal sealed interface ChannelEnd {
    /** The cloud removed this install: close 4004, or an `unadopted` message. */
    data object Revoked : ChannelEnd

    /** Anything else, after which the channel reconnects; [error] is what the Cloud tab shows meanwhile. */
    data class Closed(val error: CloudErrorDto) : ChannelEnd
}

/**
 * The reconnect delay (D9): exponential from 1 s with full jitter, capped at 30 s, so a desk is
 * back within 30 s of the cloud's return (NFR-PERF-3). [failures] counts the attempts since the
 * channel was last [STABLE] online.
 */
internal object Backoff {
    val MIN: Duration = 1.seconds
    val MAX: Duration = 30.seconds

    /** How long a connection must stay online for the next retry to start from [MIN] again. */
    val STABLE: Duration = MAX

    fun delayFor(failures: Int, random: Random): Duration {
        val ceilingMs = MIN.inWholeMilliseconds shl failures.coerceIn(0, 5)
        val upper = minOf(ceilingMs, MAX.inWholeMilliseconds)
        return random.nextLong(MIN.inWholeMilliseconds, upper + 1).milliseconds
    }
}

/**
 * The control channel (cloud-p0 plan D9, D10): one outbound WebSocket to the cloud, authenticated
 * by the install key, sending a heartbeat, and reconnecting whenever it ends, except when the cloud
 * removed the install. [run] returns only then, after [onRevoked]; cancelling it is how
 * [CloudService] stops it.
 *
 * It runs on [CloudService]'s scope and never touches the database: its status goes to [publish],
 * which keeps it in memory (`docs/cloud-engineering.md` §"Show safety").
 */
internal class CloudChannel(
    private val clock: () -> Instant,
    private val publish: (ChannelStatus) -> Unit,
    private val onRevoked: suspend () -> Unit,
    private val connectOnce: suspend (onOnline: () -> Unit) -> ChannelEnd,
    private val random: Random = Random.Default,
    /** Waits out a backoff; tests replace it to run the loop on a virtual clock. */
    private val pause: suspend (Duration) -> Unit = { delay(it) },
) {
    private val logger = LoggerFactory.getLogger(CloudChannel::class.java)

    suspend fun run() {
        var failures = 0
        var lastError: CloudErrorDto? = null
        var lastOnlineAt: Instant? = null
        while (true) {
            publish(ChannelStatus(CloudConnectionStatus.CONNECTING, lastError = lastError, lastOnlineAt = lastOnlineAt))
            var onlineSince: Instant? = null
            val end = try {
                connectOnce {
                    val now = clock()
                    onlineSince = now
                    lastError = null
                    lastOnlineAt = now
                    publish(ChannelStatus(CloudConnectionStatus.ONLINE, lastOnlineAt = now))
                }
            } catch (e: CancellationException) {
                // A stop rethrows here; a cancellation from inside the attempt (a dead session's send) is just an end.
                currentCoroutineContext().ensureActive()
                ChannelEnd.Closed(CloudErrorDto(CloudErrorCodes.DISCONNECTED, "The connection to the cloud ended."))
            } catch (e: Exception) {
                logger.warn("A cloud connection failed", e)
                ChannelEnd.Closed(
                    CloudErrorDto(CloudErrorCodes.DISCONNECTED, "The connection to the cloud failed: ${e.message ?: e::class.simpleName}"),
                )
            }
            when (end) {
                ChannelEnd.Revoked -> return onRevoked()
                is ChannelEnd.Closed -> {
                    // Only a connection that held resets the backoff: one closed straight after its welcome keeps it growing.
                    val since = onlineSince
                    if (since != null && java.time.Duration.between(since, clock()).toKotlinDuration() >= Backoff.STABLE) failures = 0
                    lastError = end.error
                    val wait = Backoff.delayFor(failures++, random)
                    publish(
                        ChannelStatus(
                            CloudConnectionStatus.RETRYING,
                            retryAt = clock().plus(wait.toJavaDuration()),
                            lastError = lastError,
                            lastOnlineAt = lastOnlineAt,
                        ),
                    )
                    pause(wait)
                }
            }
        }
    }
}

/**
 * One connection of the control channel over Ktor's WebSocket client: the handshake, `hello`, the
 * heartbeat, and reading until the connection ends. [client] must follow no redirect (D12).
 */
internal class ChannelConnection(
    private val client: HttpClient,
    private val target: ChannelTarget,
    private val identity: DeskIdentity,
    private val clock: () -> Instant,
    private val handshakeTimeout: Duration,
    private val unexpected: OnceLog,
    /** Called on every step of the connection's work, on the thread it runs on (the safety test's hook). */
    private val noteThread: () -> Unit = {},
    private val handler: ChannelHandler = ChannelHandler.None,
) {
    private val logger = LoggerFactory.getLogger(CloudChannel::class.java)

    private class UpgradeTimeout : Exception()

    /** Held from reading the capabilities to queueing them, so `hello` and each `capabilities` go out in order. */
    private val capabilityLock = Any()
    private var outbox: ChannelOutbox? = null
    private var announced: List<String> = emptyList()

    /**
     * Queue [message] behind any earlier one, never displacing it (D16). False when the connection is
     * not online or the queue has no room, logged: the cloud's request then times out.
     */
    fun send(message: Message, replyTo: String? = null): Boolean {
        val queued = synchronized(capabilityLock) { outbox }?.offerReliable(encode(message, replyTo)) ?: return false
        if (!queued) logger.warn("Dropping '{}' to the cloud: {}", message.type, refusalOf(message, replyTo))
        return queued
    }

    /** Tell the cloud the capabilities if they differ from what it was last told on this connection. */
    fun refreshCapabilities() {
        synchronized(capabilityLock) {
            val box = outbox ?: return
            val now = handler.capabilities()
            if (now == announced) return
            announced = now
            if (!box.offerReliable(encode(Message.Capabilities(now)))) logger.warn("Dropping 'capabilities' to the cloud: the send queue is full")
        }
    }

    private fun refusalOf(message: Message, replyTo: String?): String =
        if (ChannelOutbox.utf8Size(encode(message, replyTo)) > MAX_FRAME_BYTES) "it is over the frame limit" else "the send queue is full"

    private fun encode(message: Message, replyTo: String? = null): String =
        Envelope(id = UUID.randomUUID().toString(), body = message, replyTo = replyTo).encode()

    suspend fun connect(onOnline: () -> Unit): ChannelEnd {
        noteThread()
        val key = target.key()
            ?: return closed(CloudErrorCodes.KEY_UNREADABLE, "This desk couldn't read its install key.")
        val opened = AtomicBoolean(false)
        var end: ChannelEnd? = null
        try {
            // The block form, so the upgrade belongs to this coroutine: a timeout cancels it, and no late session outlives it.
            coroutineScope {
                val watchdog = launch {
                    delay(handshakeTimeout)
                    if (!opened.get()) throw UpgradeTimeout()
                }
                client.webSocket(channelUrl(target.baseUrl)) {
                    opened.set(true)
                    watchdog.cancel()
                    noteThread()
                    end = converse(this, key, onOnline)
                }
                watchdog.cancel()
            }
        } catch (e: UpgradeTimeout) {
            return closed(CloudErrorCodes.CONNECT_FAILED, "The cloud didn't answer within ${handshakeTimeout.inWholeSeconds} s.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!opened.get()) return closed(CloudErrorCodes.CONNECT_FAILED, "Couldn't reach the cloud: ${clip(e.message ?: e::class.simpleName.orEmpty())}")
            throw e
        }
        return end ?: closed(CloudErrorCodes.DISCONNECTED, "The connection to the cloud dropped.")
    }

    private suspend fun converse(session: DefaultClientWebSocketSession, key: PrivateKey, onOnline: () -> Unit): ChannelEnd {
        val challenge = when (val first = withTimeoutOrNull(handshakeTimeout) { session.next() }) {
            null -> return session.protocolError("No challenge within ${handshakeTimeout.inWholeSeconds} s")
            is Inbound.Ended -> return ended(session)
            is Inbound.Bad -> return session.protocolError(first.reason)
            is Inbound.Received -> first.envelope.body as? Message.Challenge
                ?: return session.protocolError("Expected a v$PROTOCOL_VERSION challenge, got ${describe(first.envelope)}")
        }
        val timestamp = Timestamps.format(clock())
        val signature = InstallKeys.sign(key, AuthPayload(target.installId, challenge.nonce, timestamp))
        session.sendEnvelope(Message.Auth(target.installId, timestamp, signature))

        val welcome = when (val next = withTimeoutOrNull(handshakeTimeout) { awaitWelcome(session) }) {
            null -> return session.protocolError("No welcome within ${handshakeTimeout.inWholeSeconds} s")
            is Inbound.Ended -> return ended(session, challenge.serverTime)
            is Inbound.Bad -> return session.protocolError(next.reason)
            is Inbound.Received -> next.envelope.body as Message.Welcome
        }
        onOnline()
        logger.info("Online to Lighting7 Cloud as {} ({})", target.installId, clip(welcome.orgName))
        handler.welcomed(welcome)
        return online(session, welcome)
    }

    /** The next frame that is a `welcome`, or how the connection ended; anything else is ignored. */
    private suspend fun awaitWelcome(session: DefaultClientWebSocketSession): Inbound {
        while (true) {
            val inbound = session.next()
            if (inbound !is Inbound.Received || inbound.envelope.body is Message.Welcome) return inbound
            ignore(inbound.envelope)
        }
    }

    /** Online: send `hello`, the heartbeat and whatever else is queued through [ChannelOutbox], and read until the connection ends. */
    private suspend fun online(session: DefaultClientWebSocketSession, welcome: Message.Welcome): ChannelEnd = coroutineScope {
        val heartbeat = welcome.heartbeatS.coerceIn(MIN_HEARTBEAT_S, MAX_HEARTBEAT_S).seconds
        val box = ChannelOutbox(heartbeat) { text -> session.send(Frame.Text(text)) }
        synchronized(capabilityLock) {
            announced = handler.capabilities()
            box.offerReliable(
                encode(
                    Message.Hello(
                        protocol = PROTOCOL_VERSION,
                        deskVersion = BuildInfo.current.version,
                        platform = DeskPlatform.current,
                        deskInstallUuid = identity.installUuid,
                        friendlyName = identity.friendlyName,
                        capabilities = announced,
                    ),
                ),
            )
            outbox = box
        }
        handler.online(this@ChannelConnection)
        launch {
            if (box.run(noteThread) == ChannelOutbox.Stop.STALLED) {
                logger.warn("The cloud stopped taking frames; reconnecting")
                session.cancel()
            }
        }
        launch {
            while (true) {
                delay(heartbeat)
                noteThread()
                box.offerHealth(encode(Message.Health(uptimeS = uptimeS(), deskVersion = BuildInfo.current.version)))
            }
        }
        try {
            read(session)
        } finally {
            synchronized(capabilityLock) { outbox = null }
            handler.offline(this@ChannelConnection)
            coroutineContext.cancelChildren()
        }
    }

    private suspend fun read(session: DefaultClientWebSocketSession): ChannelEnd {
        while (true) {
            when (val inbound = session.next()) {
                is Inbound.Ended -> return ended(session)
                is Inbound.Bad -> return session.protocolError(inbound.reason)
                is Inbound.Received -> when (val body = inbound.envelope.body) {
                    Message.Unadopted -> {
                        withContext(NonCancellable) { runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "unadopted")) } }
                        return ChannelEnd.Revoked
                    }
                    is Message.Error -> unexpected.once("error:${clip(body.code)}") {
                        logger.warn("The cloud reported an error: {}: {}", clip(body.code), clip(body.message))
                    }
                    is Message.Jwks -> handler.jwks(body)
                    is Message.Revoke -> handler.revoke(body)
                    is Message.RtcOffer -> handler.rtcOffer(inbound.envelope.id, body)
                    is Message.RtcIce -> handler.rtcIce(body)
                    is Message.RtcClose -> handler.rtcClose(body)
                    is Message.LinkAccepted -> handler.linkAccepted(inbound.envelope.id, body)
                    is Message.LinkRefused -> handler.linkRefused(body)
                    else -> ignore(inbound.envelope)
                }
            }
        }
    }

    /** An unknown type, one of another version, or one the cloud should not send: ignored, and logged once per type (D9). */
    private fun ignore(envelope: Envelope) {
        val type = clip(envelope.type)
        unexpected.once("v${envelope.v}:$type") { logger.info("Ignoring cloud message {}", describe(envelope)) }
    }

    private fun describe(envelope: Envelope): String {
        val what = if (envelope.body is Message.Unknown) "unknown" else "unexpected"
        return "'${clip(envelope.type)}' (v${envelope.v}, $what)"
    }

    private sealed interface Inbound {
        data class Received(val envelope: Envelope) : Inbound
        data object Ended : Inbound
        data class Bad(val reason: String) : Inbound
    }

    private suspend fun DefaultClientWebSocketSession.next(): Inbound {
        val frame = incoming.receiveCatching().getOrNull() ?: return Inbound.Ended
        noteThread()
        return when (frame) {
            is Frame.Text -> when {
                frame.data.size > MAX_FRAME_BYTES -> Inbound.Bad("A frame of ${frame.data.size} bytes is over the limit")
                else -> runCatching { Envelope.decode(frame.readText()) }
                    .map { Inbound.Received(it) }
                    .getOrElse { Inbound.Bad("A frame that is not an envelope") }
            }
            else -> Inbound.Bad("A frame that is not JSON text")
        }
    }

    private suspend fun DefaultClientWebSocketSession.sendEnvelope(message: Message) {
        send(Frame.Text(encode(message)))
    }

    /** Close 4000 and report it. */
    private suspend fun DefaultClientWebSocketSession.protocolError(reason: String): ChannelEnd {
        logger.warn("Closing the cloud connection: {}", reason)
        withContext(NonCancellable) {
            runCatching { close(CloseReason(CloseCode.PROTOCOL_ERROR.code, utf8Prefix(reason, MAX_CLOSE_REASON_BYTES))) }
        }
        return closed(CloudErrorCodes.PROTOCOL_ERROR, "This desk closed the connection: $reason.")
    }

    /** The connection ended from the cloud's side, or the network's: what its close code says (D10). */
    private suspend fun ended(session: DefaultClientWebSocketSession, serverTime: String? = null): ChannelEnd {
        val reason = withTimeoutOrNull(CLOSE_REASON_WAIT) { runCatching { session.closeReason.await() }.getOrNull() }
        val detail = clip(reason?.message.orEmpty())
        val code = reason?.code?.toInt()
        return when (CloseCode.of(code ?: -1)) {
            CloseCode.INSTALL_REVOKED -> ChannelEnd.Revoked
            CloseCode.AUTH_FAILED -> when (AuthFailure.parse(detail)) {
                AuthFailure.CLOCK_SKEW -> closed(CloudErrorCodes.CLOCK_SKEW, clockSkewMessage(serverTime))
                else -> closed(CloudErrorCodes.AUTH_FAILED, "The cloud refused this desk's credentials (${detail.ifEmpty { "no reason" }}).")
            }
            CloseCode.PROTOCOL_UNSUPPORTED ->
                closed(CloudErrorCodes.PROTOCOL_UNSUPPORTED, "The cloud no longer serves this desk's version: update to $detail or later.")
            CloseCode.REPLACED -> closed(CloudErrorCodes.REPLACED, "Another connection from this desk took this one's place.")
            CloseCode.PROTOCOL_ERROR -> closed(CloudErrorCodes.PROTOCOL_ERROR, "The cloud closed the connection: ${detail.ifEmpty { "protocol error" }}.")
            null -> if (code == null || code == CLOSED_ABNORMALLY) {
                closed(CloudErrorCodes.DISCONNECTED, "The connection to the cloud dropped.")
            } else {
                closed(CloudErrorCodes.DISCONNECTED, "The connection to the cloud closed ($code${if (detail.isEmpty()) "" else ": $detail"}).")
            }
        }
    }

    private fun clockSkewMessage(serverTime: String?): String {
        val offset = serverTime?.let(Timestamps::parse)?.let { java.time.Duration.between(it, clock()).seconds }
        val by = offset?.let { " (it is ${kotlin.math.abs(it)} s ${if (it > 0) "ahead of" else "behind"} the cloud)" }.orEmpty()
        return "The cloud refused this desk's clock$by: check this machine's clock."
    }

    private fun closed(code: String, message: String): ChannelEnd = ChannelEnd.Closed(CloudErrorDto(code, message))

    companion object {
        const val MIN_HEARTBEAT_S = 1
        const val MAX_HEARTBEAT_S = 300

        /** RFC 6455 §5.5: a control frame's payload is at most 125 bytes, two of them the code. */
        private const val MAX_CLOSE_REASON_BYTES = 123
        private const val MAX_TEXT_FROM_CLOUD = 64

        /** RFC 6455's "closed with no close frame", which Ktor records itself when a connection just ends. */
        private const val CLOSED_ABNORMALLY = 1006
        private val CLOSE_REASON_WAIT = 2.seconds

        /** The channel's URL: the base URL's scheme as its WebSocket twin, then [CloudPaths.DESK_CHANNEL]. */
        fun channelUrl(baseUrl: String): String {
            val scheme = URI(baseUrl).scheme ?: throw IllegalArgumentException("Not an http(s) base URL")
            val ws = when (scheme.lowercase()) {
                "https" -> "wss"
                "http" -> "ws"
                else -> throw IllegalArgumentException("Not an http(s) base URL")
            }
            return ws + baseUrl.substring(scheme.length) + CloudPaths.DESK_CHANNEL
        }

        /** Text the cloud chose, cut short and stripped of control characters before the desk logs or repeats it. */
        fun clip(text: String): String {
            val clean = text.filterNot { it.isISOControl() }
            if (clean.codePointCount(0, clean.length) <= MAX_TEXT_FROM_CLOUD) return clean
            return clean.substring(0, clean.offsetByCodePoints(0, MAX_TEXT_FROM_CLOUD)) + "…"
        }

        /** The longest prefix of [text], by whole code points, that is at most [maxBytes] in UTF-8. */
        fun utf8Prefix(text: String, maxBytes: Int): String {
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + size > maxBytes) break
                bytes += size
                end += Character.charCount(cp)
            }
            return text.substring(0, end)
        }

        private fun uptimeS(): Long = ManagementFactory.getRuntimeMXBean().uptime / 1000
    }
}

/** Logs each key once, up to [limit] keys, so a cloud cannot fill the log or the heap with new ones. Keys must be bounded. */
internal class OnceLog(private val limit: Int = 100) {
    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun once(key: String, log: () -> Unit) {
        if (seen.size < limit && seen.add(key)) log()
    }
}
