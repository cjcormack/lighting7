package uk.me.cormack.lighting7.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollPayload
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollResult
import uk.me.cormack.lighting7.cloud.protocol.AdoptionRequest
import uk.me.cormack.lighting7.cloud.protocol.AdoptionStarted
import uk.me.cormack.lighting7.cloud.protocol.ApiError
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.CloudPaths
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.SignatureHeaders
import uk.me.cormack.lighting7.cloud.protocol.SignedPayload
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.cloud.protocol.UnadoptPayload
import java.io.Closeable
import java.security.PrivateKey
import java.time.Duration
import java.time.Instant

/** How one call to the cloud went. */
sealed interface CloudCall<out T> {
    data class Ok<T>(val value: T) : CloudCall<T>

    /** The cloud answered with an error status; [error] is its `ApiError` body when it sent one. */
    data class Refused(val status: Int, val error: ApiError?) : CloudCall<Nothing>

    /** No answer: the cloud is down, the network is, or it took too long. */
    data class Unreachable(val message: String) : CloudCall<Nothing>
}

/**
 * The adoption API (cloud-p0 plan D6, D8) over HTTP. Talks only to [baseUrl]'s host and follows
 * no redirect (D12), with the JDK's normal certificate checks.
 */
class CloudApi(
    private val baseUrl: String,
    requestTimeout: Duration = Duration.ofSeconds(15),
    private val clock: () -> Instant = Instant::now,
) : Closeable {

    private val client = HttpClient(CIO) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeout.toMillis()
            connectTimeoutMillis = requestTimeout.toMillis()
        }
    }

    suspend fun startAdoption(request: AdoptionRequest): CloudCall<AdoptionStarted> = call(AdoptionStarted.serializer()) {
        client.preparePost(baseUrl + CloudPaths.ADOPTIONS) {
            setBody(TextContent(CloudJson.encodeToString(AdoptionRequest.serializer(), request), ContentType.Application.Json))
        }
    }

    suspend fun pollAdoption(adoptionId: String, key: PrivateKey): CloudCall<AdoptionPollResult> =
        call(AdoptionPollResult.serializer()) {
            client.preparePost(baseUrl + CloudPaths.adoptionPoll(adoptionId)) { sign(key) { AdoptionPollPayload(adoptionId, it) } }
        }

    suspend fun unadopt(installId: String, key: PrivateKey): CloudCall<Unit> = callNoBody {
        client.prepareDelete(baseUrl + CloudPaths.install(installId)) { sign(key) { UnadoptPayload(installId, it) } }
    }

    private fun HttpRequestBuilder.sign(key: PrivateKey, payload: (timestamp: String) -> SignedPayload) {
        val timestamp = Timestamps.format(clock())
        header(SignatureHeaders.TIMESTAMP, timestamp)
        header(SignatureHeaders.SIGNATURE, InstallKeys.sign(key, payload(timestamp)))
    }

    private suspend fun <T> call(serializer: KSerializer<T>, send: suspend () -> HttpStatement): CloudCall<T> =
        when (val answer = exchange(send)) {
            is Exchange.Failed -> answer.call
            is Exchange.Answered -> runCatching { CloudCall.Ok(CloudJson.decodeFromString(serializer, answer.body)) }
                .getOrElse { CloudCall.Unreachable("The cloud sent an answer this desk could not read") }
        }

    /** For a call whose 2xx answer has no body the desk reads. */
    private suspend fun callNoBody(send: suspend () -> HttpStatement): CloudCall<Unit> =
        when (val answer = exchange(send)) {
            is Exchange.Failed -> answer.call
            is Exchange.Answered -> CloudCall.Ok(Unit)
        }

    private sealed interface Exchange {
        data class Answered(val body: String) : Exchange
        data class Failed(val call: CloudCall<Nothing>) : Exchange
    }

    /**
     * Send and read the answer, at most [MAX_ANSWER_BYTES] of it: a cloud that sends more is
     * treated as one that did not answer, so it cannot fill the show's heap.
     */
    private suspend fun exchange(send: suspend () -> HttpStatement): Exchange = try {
        send().execute { response ->
            val bytes = response.bodyAsChannel().readBuffer(MAX_ANSWER_BYTES + 1L).readByteArray()
            when {
                bytes.size > MAX_ANSWER_BYTES -> Exchange.Failed(CloudCall.Unreachable("The cloud's answer was too large"))
                !response.status.isSuccess() -> {
                    val error = runCatching { CloudJson.decodeFromString(ApiError.serializer(), bytes.decodeToString()) }.getOrNull()
                    Exchange.Failed(CloudCall.Refused(response.status.value, error))
                }
                else -> Exchange.Answered(bytes.decodeToString())
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Exchange.Failed(CloudCall.Unreachable(e.message ?: e::class.simpleName ?: "no answer"))
    }

    override fun close() {
        client.close()
    }

    companion object {
        /** Far above any adoption answer; the control channel's frames have their own limit. */
        const val MAX_ANSWER_BYTES: Int = 64 * 1024
    }
}
