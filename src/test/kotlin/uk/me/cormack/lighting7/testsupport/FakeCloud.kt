package uk.me.cormack.lighting7.testsupport

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollPayload
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollResult
import uk.me.cormack.lighting7.cloud.protocol.AdoptionRequest
import uk.me.cormack.lighting7.cloud.protocol.AdoptionStarted
import uk.me.cormack.lighting7.cloud.protocol.ApiError
import uk.me.cormack.lighting7.cloud.protocol.CloudAccount
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.CloudOrg
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.SignatureHeaders
import uk.me.cormack.lighting7.cloud.protocol.SignedPayload
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.cloud.protocol.UnadoptPayload
import uk.me.cormack.lighting7.cloud.protocol.UserCode
import java.io.Closeable
import java.security.PublicKey
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for Lighting7 Cloud, built on `cloud-protocol` and served on a loopback port, so desk
 * tests never need the real cloud (cloud design record R5). It implements the adoption API and
 * verifies every signature the desk sends, so a desk that signs the wrong bytes fails here first.
 * Its switches make the cloud approve, deny, expire, slow down, refuse or stall; session 5 adds the
 * control channel and dropping it.
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

    val org = CloudOrg("org-1", "Test Org")
    val account = CloudAccount("acct-1", "admin@example.com", "Test Admin")

    val adoptions = ConcurrentHashMap<String, Adoption>()
    val installs = ConcurrentHashMap<String, Install>()
    val polls = CopyOnWriteArrayList<Poll>()
    val unadopts = CopyOnWriteArrayList<String>()
    private val ids = AtomicInteger()

    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
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
        server.stop(0, 500)
    }
}
