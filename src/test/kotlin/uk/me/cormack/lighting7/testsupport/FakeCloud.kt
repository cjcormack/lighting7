package uk.me.cormack.lighting7.testsupport

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import uk.me.cormack.lighting7.cloud.protocol.AuthFailure
import uk.me.cormack.lighting7.cloud.protocol.AuthPayload
import uk.me.cormack.lighting7.cloud.protocol.CloseCode
import uk.me.cormack.lighting7.cloud.protocol.CloudPaths
import uk.me.cormack.lighting7.cloud.protocol.Envelope
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollPayload
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollResult
import uk.me.cormack.lighting7.cloud.protocol.AdoptionRequest
import uk.me.cormack.lighting7.cloud.protocol.AdoptionStarted
import uk.me.cormack.lighting7.cloud.protocol.ApiError
import uk.me.cormack.lighting7.cloud.protocol.CloudAccount
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.CloudOrg
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.IceServer
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.SignatureHeaders
import uk.me.cormack.lighting7.cloud.protocol.SignedPayload
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.cloud.protocol.UnadoptPayload
import uk.me.cormack.lighting7.cloud.protocol.UserCode
import java.io.Closeable
import java.security.KeyPair
import java.security.PublicKey
import uk.me.cormack.lighting7.cloud.protocol.DeskAccessClaims
import uk.me.cormack.lighting7.cloud.protocol.DeskTokens
import uk.me.cormack.lighting7.cloud.protocol.Jwk
import uk.me.cormack.lighting7.cloud.protocol.TokenScope
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A stand-in for Lighting7 Cloud, built on `cloud-protocol` and served on a loopback port, so desk
 * tests never need the real cloud (cloud design record R5). It implements the adoption API and
 * verifies every signature the desk sends, so a desk that signs the wrong bytes fails here first.
 * Its switches make the cloud approve, deny, expire, slow down, refuse or stall.
 *
 * It serves the control channel too (`/v1/desk`): the challenge, the signed `auth` checked against
 * the install's key and the fake's clock, `welcome`, one connection per install (a newer one closes
 * the older `4009`), and close `4004` for an install removed in the portal. Its channel switches
 * stall a connection, stop reading one, drop it, refuse or redirect the upgrade, and force an
 * `auth` refusal.
 *
 * For P1 it holds a token-signing key, sends its `jwks` after every `welcome`, mints desk access
 * tokens with [mint], rotates its key ([rotateKeys]) and sends `revoke` ([revoke]).
 */
class FakeCloud : Closeable {

    /** What a poll of a pending adoption answers. */
    enum class Decision { PENDING, APPROVE, DENY, EXPIRE }

    data class Adoption(val id: String, val request: AdoptionRequest, val key: PublicKey, val userCode: String)
    data class Install(val id: String, val adoptionId: String, val key: PublicKey)

    /** One poll as the fake saw it: when, which adoption, and whether its signature verified. */
    data class Poll(val at: Long, val adoptionId: String, val signed: Boolean)

    @Volatile var decision: Decision = Decision.PENDING

    /** When set, the next poll answers `slow_down` with this interval, then it clears. */
    @Volatile var slowDownOnce: Int? = null

    /** Every call answers 503 with an `ApiError`. */
    @Volatile var refuse: Boolean = false

    /** Every answer is padded past what the desk reads. */
    @Volatile var oversize: Boolean = false

    /** Every call waits until [release] or [close]. */
    @Volatile var stall: Boolean = false
    private var released = CompletableDeferred<Unit>()

    @Volatile var intervalS: Int = 1
    @Volatile var expiresInS: Int = 600

    /** The `heartbeatS` a `welcome` gives. */
    @Volatile var heartbeatS: Int = 25

    /** The `remoteOrigin` a `welcome` gives. */
    @Volatile var remoteOrigin: String? = null

    /** The key desk access tokens are signed with, and its `kid`. */
    @Volatile var tokenKey: KeyPair = InstallKeys.generate()
        private set
    @Volatile var tokenKid: String = "key-1"
        private set
    private val kids = AtomicInteger(1)

    /** How far the fake's clock is from the real one, for the challenge's `serverTime` and the skew check. */
    @Volatile var clockOffset: Duration = Duration.ZERO

    /** Refuse every `auth` with this reason, whatever it carries. */
    @Volatile var authFailure: AuthFailure? = null

    /** Accept the upgrade, then send nothing and read nothing until [releaseChannel]. */
    @Volatile var channelStall: Boolean = false

    /** Stop reading every live connection until [releaseChannel]. */
    @Volatile var stopReading: Boolean = false

    /** Send the challenge, then close without waiting for `auth`. */
    @Volatile var closeAfterChallenge: Boolean = false

    /** Answer the upgrade 503. */
    @Volatile var refuseChannel: Boolean = false

    /** Answer the upgrade with a redirect to this base URL's channel. */
    @Volatile var redirectChannelTo: String? = null
    private var channelReleased = CompletableDeferred<Unit>()

    /** Installs removed in the portal: their next `auth` is closed 4004. */
    val revoked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every request to the channel's path, refused and redirected ones included. */
    val channelAttempts = AtomicInteger()

    /** Every envelope a desk sent once authenticated, by install id. */
    val received = CopyOnWriteArrayList<Pair<String, Envelope>>()

    /** How each connection was closed, as the fake saw it. */
    val closes = CopyOnWriteArrayList<CloseReason>()

    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    val org = CloudOrg("org-1", "Test Org")
    val account = CloudAccount("acct-1", "admin@example.com", "Test Admin")

    val adoptions = ConcurrentHashMap<String, Adoption>()
    val installs = ConcurrentHashMap<String, Install>()
    val polls = CopyOnWriteArrayList<Poll>()
    val unadopts = CopyOnWriteArrayList<String>()
    private val ids = AtomicInteger()

    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(WebSockets) { maxFrameSize = 64L * 1024 * 1024 }
            intercept(ApplicationCallPipeline.Plugins) {
                if (context.request.path() != CloudPaths.DESK_CHANNEL) return@intercept
                channelAttempts.incrementAndGet()
                val redirect = redirectChannelTo
                if (redirect != null) {
                    context.respondRedirect(redirect + CloudPaths.DESK_CHANNEL, permanent = false)
                    finish()
                } else if (refuseChannel) {
                    context.respond(HttpStatusCode.ServiceUnavailable)
                    finish()
                }
            }
            routing {
                webSocket(CloudPaths.DESK_CHANNEL) { channel(this) }
                post("/v1/adoptions") {
                    if (gate(call)) return@post
                    val request = runCatching { CloudJson.decodeFromString(AdoptionRequest.serializer(), call.receiveText()) }.getOrNull()
                    val key = request?.let { InstallKeys.decodePublicKey(it.publicKey) }
                    if (request == null || key == null) return@post call.error(HttpStatusCode.BadRequest, ErrorCode.MALFORMED)
                    val id = "adp-${ids.incrementAndGet()}"
                    val code = userCode()
                    adoptions[id] = Adoption(id, request, key, code)
                    call.json(
                        AdoptionStarted.serializer(),
                        AdoptionStarted(id, code, "$baseUrl/adopt", "$baseUrl/adopt?code=$code", intervalS, expiresInS),
                    )
                }
                post("/v1/adoptions/{id}/poll") {
                    if (gate(call)) return@post
                    val id = call.parameters["id"].orEmpty()
                    val adoption = adoptions[id] ?: return@post call.error(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND)
                    val refusal = verify(call, adoption.key) { AdoptionPollPayload(id, it) }
                    polls += Poll(System.nanoTime(), id, signed = refusal == null)
                    if (refusal != null) return@post call.error(HttpStatusCode.Unauthorized, refusal)
                    val result = slowDownOnce?.let { slowDownOnce = null; AdoptionPollResult.SlowDown(it) } ?: when (decision) {
                        Decision.PENDING -> AdoptionPollResult.Pending
                        Decision.APPROVE -> {
                            val install = installs.values.firstOrNull { it.adoptionId == id }
                                ?: Install("inst-${ids.incrementAndGet()}", id, adoption.key).also { installs[it.id] = it }
                            AdoptionPollResult.Approved(install.id, org, account)
                        }
                        Decision.DENY -> AdoptionPollResult.Denied
                        Decision.EXPIRE -> AdoptionPollResult.Expired
                    }
                    call.json(AdoptionPollResult.serializer(), result)
                }
                delete("/v1/installs/{id}") {
                    if (gate(call)) return@delete
                    val id = call.parameters["id"].orEmpty()
                    val install = installs[id] ?: return@delete call.error(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND)
                    verify(call, install.key) { UnadoptPayload(id, it) }?.let {
                        return@delete call.error(HttpStatusCode.Unauthorized, it)
                    }
                    installs.remove(id)
                    unadopts += id
                    call.respondText("", status = HttpStatusCode.NoContent)
                }
            }
        }.start(wait = false)

    val port: Int = runBlocking { server.engine.resolvedConnectors().first().port }
    val baseUrl: String = "http://127.0.0.1:$port"

    /** The one adoption started so far. */
    val adoption: Adoption get() = adoptions.values.single()

    fun release() {
        stall = false
        released.complete(Unit)
        released = CompletableDeferred()
    }

    /** End [channelStall] and [stopReading]. */
    fun releaseChannel() {
        channelStall = false
        stopReading = false
        channelReleased.complete(Unit)
        channelReleased = CompletableDeferred()
    }

    /** The installs with a live, authenticated connection. */
    val online: Set<String> get() = sessions.keys.toSet()

    /** Send [message] down [installId]'s connection; answers its envelope's id, which a reply names. */
    fun send(installId: String, message: Message, v: Int = uk.me.cormack.lighting7.cloud.protocol.PROTOCOL_VERSION): String {
        val id = UUID.randomUUID().toString()
        sendText(installId, Envelope(id = id, body = message, v = v).encode())
        return id
    }

    /** What [installId] sent replying to [id], once it has. */
    fun replyTo(installId: String, id: String): Envelope? = received.firstOrNull { it.first == installId && it.second.replyTo == id }?.second

    /** The messages of type [T] [installId] has sent. */
    inline fun <reified T : Message> sent(installId: String): List<T> =
        received.filter { it.first == installId }.mapNotNull { it.second.body as? T }

    fun sendText(installId: String, text: String) = runBlocking {
        val session = sessions[installId] ?: error("$installId is not connected")
        session.send(Frame.Text(text))
    }

    /** Remove the install in the portal: an `unadopted` message when [tell] and it is connected, and 4004 at its next connect. */
    fun removeInPortal(installId: String, tell: Boolean) {
        installs.remove(installId)
        revoked += installId
        if (tell) sessions[installId]?.let { runBlocking { it.send(Frame.Text(Envelope(UUID.randomUUID().toString(), Message.Unadopted).encode())) } }
    }

    /** End every connection without a close frame, as a network or a crashed cloud would. */
    fun dropChannel() {
        sessions.values.forEach { it.cancel() }
    }

    /** Close [installId]'s connection with [code]. */
    fun closeChannel(installId: String, code: CloseCode, reason: String = code.name) = runBlocking {
        sessions[installId]?.close(CloseReason(code.code, reason))
    }

    private fun now(): Instant = Instant.now().plus(clockOffset)

    private fun jwks() = Message.Jwks(listOf(Jwk.of(tokenKid, tokenKey.public)))

    /**
     * A desk access token for [installId], signed as the cloud signs one: a remote session's by
     * default, [iat] the fake's clock, living [ttlS] seconds. [key] and [kid] sign it with something else.
     */
    fun mint(
        installId: String,
        sid: String? = "sid-1",
        sub: String = account.id,
        orgId: String = org.id,
        scope: TokenScope = TokenScope.REMOTE,
        iat: Long = now().epochSecond,
        ttlS: Long = 300,
        key: java.security.PrivateKey = tokenKey.private,
        kid: String = tokenKid,
    ): String = DeskTokens.sign(
        key,
        kid,
        DeskAccessClaims(
            iss = baseUrl,
            aud = installId,
            sub = sub,
            org = orgId,
            scope = scope,
            sid = sid.takeIf { scope == TokenScope.REMOTE },
            gid = "grant-1".takeIf { scope == TokenScope.MCP },
            azp = "client-1".takeIf { scope == TokenScope.MCP },
            iat = iat,
            exp = iat + ttlS,
            jti = UUID.randomUUID().toString(),
        ),
    )

    /** A new signing key under a new `kid`, sent to every connected desk. Tokens signed with the old one no longer verify. */
    fun rotateKeys() {
        tokenKey = InstallKeys.generate()
        tokenKid = "key-${kids.incrementAndGet()}"
        sessions.keys.forEach { send(it, jwks()) }
    }

    /** Send `revoke` to [installId], stamped [at] (the fake's clock by default). */
    fun revoke(installId: String, sid: String? = null, accountId: String? = null, all: Boolean = false, at: Instant = now()) {
        send(installId, Message.Revoke(Timestamps.format(at), sid, accountId, all))
    }

    /** [accountId], signed in as [email], accepts [inviteId] in the portal; answers the message's id, which `link.ack` names. */
    fun acceptInvite(installId: String, inviteId: String, accountId: String, email: String): String =
        send(installId, Message.LinkAccepted(inviteId, accountId, email))

    /** An owner or admin of the org declines [inviteId] in the portal. */
    fun declineInvite(installId: String, inviteId: String): String = send(installId, Message.LinkRefused(inviteId))

    /** Ask [installId] for remote session [sid] as [accountId], as the shell's offer would; answers the offer's id. */
    fun offer(installId: String, sid: String, accountId: String = account.id, sdp: String = "v=0 offer for $sid"): String =
        send(installId, Message.RtcOffer(sid, accountId, "$accountId@example.com", "Someone", sdp, listOf(IceServer(listOf("stun:stun.example:3478")))))

    private suspend fun channel(session: DefaultWebSocketServerSession): Unit = with(session) {
        if (channelStall) channelReleased.await()
        val nonce = UUID.randomUUID().toString()
        send(Frame.Text(Envelope(UUID.randomUUID().toString(), Message.Challenge(nonce, Timestamps.format(now()))).encode()))
        if (closeAfterChallenge) return close(CloseReason(CloseReason.Codes.GOING_AWAY, "restarting"))
        val auth = withTimeoutOrNull(5.seconds) { (incoming.receiveCatching().getOrNull() as? Frame.Text)?.readText() }
            ?.let { runCatching { Envelope.decode(it).body }.getOrNull() }
        suspend fun refuse(code: CloseCode, reason: String) {
            close(CloseReason(code.code, reason))
            closes += CloseReason(code.code, reason)
        }
        if (auth == null) return refuse(CloseCode.AUTH_FAILED, AuthFailure.TIMEOUT.name)
        if (auth !is Message.Auth) return refuse(CloseCode.PROTOCOL_ERROR, "expected auth")
        authFailure?.let { return refuse(CloseCode.AUTH_FAILED, it.name) }
        if (auth.installId in revoked) return refuse(CloseCode.INSTALL_REVOKED, "")
        val install = installs[auth.installId] ?: return refuse(CloseCode.AUTH_FAILED, AuthFailure.MALFORMED.name)
        if (!Timestamps.withinSkew(auth.timestamp, now())) return refuse(CloseCode.AUTH_FAILED, AuthFailure.CLOCK_SKEW.name)
        if (!InstallKeys.verify(install.key, AuthPayload(auth.installId, nonce, auth.timestamp), auth.signature)) {
            return refuse(CloseCode.AUTH_FAILED, AuthFailure.BAD_SIGNATURE.name)
        }
        sessions.put(auth.installId, this)?.close(CloseReason(CloseCode.REPLACED.code, CloseCode.REPLACED.name))
        send(Frame.Text(Envelope(UUID.randomUUID().toString(), Message.Welcome(org.name, heartbeatS, remoteOrigin)).encode()))
        send(Frame.Text(Envelope(UUID.randomUUID().toString(), jwks()).encode()))
        try {
            while (true) {
                if (stopReading) channelReleased.await()
                val frame = incoming.receiveCatching().getOrNull() ?: break
                if (frame is Frame.Text) received += auth.installId to Envelope.decode(frame.readText())
            }
        } finally {
            sessions.remove(auth.installId, this)
            withTimeoutOrNull(2.seconds) { runCatching { closeReason.await() }.getOrNull() }?.let { closes += it }
        }
    }

    /** True when the call was answered by a switch rather than the API. */
    private suspend fun gate(call: ApplicationCall): Boolean {
        if (stall) released.await()
        if (refuse) {
            call.error(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE")
            return true
        }
        return false
    }

    /** The refusal's code, or null when the request is signed by [key] over [payload] now. */
    private fun verify(call: ApplicationCall, key: PublicKey, payload: (String) -> SignedPayload): String? {
        val timestamp = call.request.headers[SignatureHeaders.TIMESTAMP] ?: return ErrorCode.MALFORMED
        val signature = call.request.headers[SignatureHeaders.SIGNATURE] ?: return ErrorCode.MALFORMED
        if (!Timestamps.withinSkew(timestamp, Instant.now())) return ErrorCode.CLOCK_SKEW
        if (!InstallKeys.verify(key, payload(timestamp), signature)) return ErrorCode.BAD_SIGNATURE
        return null
    }

    private fun userCode(): String {
        val letters = (1..8).map { UserCode.ALPHABET.random() }.joinToString("")
        return letters.take(4) + "-" + letters.drop(4)
    }

    private suspend fun <T> ApplicationCall.json(serializer: KSerializer<T>, value: T) =
        respondText(CloudJson.encodeToString(serializer, value) + if (oversize) " ".repeat(128 * 1024) else "", ContentType.Application.Json)

    private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String) =
        respondText(CloudJson.encodeToString(ApiError.serializer(), ApiError(code, "fake cloud: $code")), ContentType.Application.Json, status)

    override fun close() {
        released.complete(Unit)
        channelReleased.complete(Unit)
        server.stop(0, 500)
    }
}
