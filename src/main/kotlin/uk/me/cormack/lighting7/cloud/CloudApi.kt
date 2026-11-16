package uk.me.cormack.lighting7.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.preparePut
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollPayload
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollResult
import uk.me.cormack.lighting7.cloud.protocol.AdoptionRequest
import uk.me.cormack.lighting7.cloud.protocol.AdoptionStarted
import uk.me.cormack.lighting7.cloud.protocol.ApiError
import uk.me.cormack.lighting7.cloud.protocol.BackupApiPayload
import uk.me.cormack.lighting7.cloud.protocol.BackupDto
import uk.me.cormack.lighting7.cloud.protocol.BackupKeyDto
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.CloudPaths
import uk.me.cormack.lighting7.cloud.protocol.CreateRepoRequest
import uk.me.cormack.lighting7.cloud.protocol.DeclareBackupRequest
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.RepoApiPayload
import uk.me.cormack.lighting7.cloud.protocol.RepoDto
import uk.me.cormack.lighting7.cloud.protocol.RepoExistsError
import uk.me.cormack.lighting7.cloud.protocol.RepoList
import uk.me.cormack.lighting7.cloud.protocol.SignatureHeaders
import uk.me.cormack.lighting7.cloud.protocol.SignedPayload
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.cloud.protocol.UnadoptPayload
import java.io.Closeable
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

/** An adopted install's id and key, which sign its repo API calls and its git tokens (cloud-p2 plan D1, D3). */
class InstallSigner(val installId: String, val key: PrivateKey) {
    override fun toString(): String = "InstallSigner($installId)"
}

/** Who a repo API read is from: an adopted install, signing, or a developer token, as a bearer (cloud-p2 plan D3, D9). */
sealed interface RepoReader {
    class Install(val signer: InstallSigner) : RepoReader

    class Developer(val token: String) : RepoReader {
        override fun toString(): String = "Developer"
    }
}

/** A create's answer: the repo made, or, when [existed], the one the project already had (`REPO_EXISTS`). */
data class RepoCreated(val repo: RepoDto, val existed: Boolean)

/**
 * The cloud's adoption, repo and backup APIs over HTTP. Talks only to [baseUrl]'s host and follows no
 * redirect, with the JDK's normal certificate checks.
 */
class CloudApi(
    private val baseUrl: String,
    requestTimeout: Duration = Duration.ofSeconds(15),
    private val clock: () -> Instant = Instant::now,
    /** How long one backup part's upload may take: 64 MiB on a venue's uplink. */
    private val partTimeout: Duration = Duration.ofMinutes(15),
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

    /** Make the repo for a project (`POST /v1/repos`); a project that has one answers it, `existed`. */
    suspend fun createRepo(signer: InstallSigner, request: CreateRepoRequest): CloudCall<RepoCreated> {
        val body = CloudJson.encodeToString(CreateRepoRequest.serializer(), request)
        val answer = exchange(MAX_ANSWER_BYTES) {
            client.preparePost(baseUrl + CloudPaths.REPOS) {
                signRepoCall(RepoReader.Install(signer), "POST", CloudPaths.REPOS, body.encodeToByteArray())
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        if (answer is Exchange.Refused && answer.status == 409) {
            val existing = runCatching { CloudJson.decodeFromString(RepoExistsError.serializer(), answer.body) }.getOrNull()
            if (existing?.code == ErrorCode.REPO_EXISTS) return CloudCall.Ok(RepoCreated(existing.repo, existed = true))
        }
        return when (val made = decode(answer, RepoDto.serializer())) {
            is CloudCall.Ok -> CloudCall.Ok(RepoCreated(made.value, existed = false))
            is CloudCall.Refused -> made
            is CloudCall.Unreachable -> made
        }
    }

    /** The org's repos (`GET /v1/repos`), newest push first. */
    suspend fun listRepos(reader: RepoReader): CloudCall<RepoList> =
        decode(exchange(MAX_LIST_BYTES) { client.prepareGet(baseUrl + CloudPaths.REPOS) { signRepoCall(reader, "GET", CloudPaths.REPOS, ByteArray(0)) } }, RepoList.serializer())

    /** One repo (`GET /v1/repos/{repoId}`), or a refusal saying why there is none (`REPO_NOT_FOUND`, `SYNC_OFF`). */
    suspend fun getRepo(reader: RepoReader, repoId: String): CloudCall<RepoDto> {
        val path = CloudPaths.repo(repoId)
        return decode(exchange(MAX_ANSWER_BYTES) { client.prepareGet(baseUrl + path) { signRepoCall(reader, "GET", path, ByteArray(0)) } }, RepoDto.serializer())
    }

    /** The org's current data key (`GET /v1/backups/key`), or the one [keyId] names. Held in memory by the caller, never stored. */
    suspend fun backupKey(signer: InstallSigner, keyId: String? = null): CloudCall<BackupKeyDto> {
        val path = keyId?.let(CloudPaths::backupKey) ?: CloudPaths.BACKUP_KEY
        return decode(exchange(MAX_ANSWER_BYTES) { client.prepareGet(baseUrl + path) { signBackupCall(signer, "GET", path, ByteArray(0)) } }, BackupKeyDto.serializer())
    }

    /** Declare a backup about to be uploaded (`POST /v1/backups`). */
    suspend fun declareBackup(signer: InstallSigner, request: DeclareBackupRequest): CloudCall<BackupDto> {
        val body = CloudJson.encodeToString(DeclareBackupRequest.serializer(), request).encodeToByteArray()
        return decode(
            exchange(MAX_ANSWER_BYTES) {
                client.preparePost(baseUrl + CloudPaths.BACKUPS) {
                    signBackupCall(signer, "POST", CloudPaths.BACKUPS, body)
                    setBody(ByteArrayContent(body, ContentType.Application.Json))
                }
            },
            BackupDto.serializer(),
        )
    }

    /** A backup as the cloud holds it (`GET /v1/backups/{id}`): which parts it has, for a resume. */
    suspend fun backup(signer: InstallSigner, backupId: String): CloudCall<BackupDto> {
        val path = CloudPaths.backup(backupId)
        return decode(exchange(MAX_ANSWER_BYTES) { client.prepareGet(baseUrl + path) { signBackupCall(signer, "GET", path, ByteArray(0)) } }, BackupDto.serializer())
    }

    /**
     * Part [n] of [backupId]: [length] bytes of [file] from [offset], streamed and signed over their own
     * hash, never held in memory. Its timeout is [partTimeout], not the API's.
     */
    suspend fun uploadBackupPart(signer: InstallSigner, backupId: String, n: Int, file: Path, offset: Long, length: Long): CloudCall<BackupDto> {
        val path = CloudPaths.backupPart(backupId, n)
        val timestamp = Timestamps.format(clock())
        val payload = regionOf(file, offset, length).use { BackupApiPayload.of(signer.installId, "PUT", path, timestamp, it) }
        val content = object : OutgoingContent.ReadChannelContent() {
            override val contentLength: Long = length
            override val contentType: ContentType = ContentType.Application.OctetStream
            override fun readFrom(): ByteReadChannel = regionOf(file, offset, length).toByteReadChannel()
        }
        return decode(
            exchange(MAX_ANSWER_BYTES) {
                client.preparePut(baseUrl + path) {
                    timeout { requestTimeoutMillis = partTimeout.toMillis() }
                    header(SignatureHeaders.INSTALL, signer.installId)
                    header(SignatureHeaders.TIMESTAMP, timestamp)
                    header(SignatureHeaders.SIGNATURE, InstallKeys.sign(signer.key, payload))
                    setBody(content)
                }
            },
            BackupDto.serializer(),
        )
    }

    /** Every part is in (`POST /v1/backups/{id}/complete`). */
    suspend fun completeBackup(signer: InstallSigner, backupId: String): CloudCall<BackupDto> {
        val path = CloudPaths.backupComplete(backupId)
        return decode(exchange(MAX_ANSWER_BYTES) { client.preparePost(baseUrl + path) { signBackupCall(signer, "POST", path, ByteArray(0)) } }, BackupDto.serializer())
    }

    private fun HttpRequestBuilder.signBackupCall(signer: InstallSigner, method: String, path: String, body: ByteArray) {
        val timestamp = Timestamps.format(clock())
        header(SignatureHeaders.INSTALL, signer.installId)
        header(SignatureHeaders.TIMESTAMP, timestamp)
        header(SignatureHeaders.SIGNATURE, InstallKeys.sign(signer.key, BackupApiPayload.of(signer.installId, method, path, timestamp, body)))
    }

    private fun HttpRequestBuilder.signRepoCall(reader: RepoReader, method: String, path: String, body: ByteArray) {
        when (reader) {
            is RepoReader.Developer -> header("Authorization", "Bearer ${reader.token}")
            is RepoReader.Install -> {
                val timestamp = Timestamps.format(clock())
                header(SignatureHeaders.INSTALL, reader.signer.installId)
                header(SignatureHeaders.TIMESTAMP, timestamp)
                header(
                    SignatureHeaders.SIGNATURE,
                    InstallKeys.sign(reader.signer.key, RepoApiPayload.of(reader.signer.installId, method, path, timestamp, body)),
                )
            }
        }
    }

    private fun HttpRequestBuilder.sign(key: PrivateKey, payload: (timestamp: String) -> SignedPayload) {
        val timestamp = Timestamps.format(clock())
        header(SignatureHeaders.TIMESTAMP, timestamp)
        header(SignatureHeaders.SIGNATURE, InstallKeys.sign(key, payload(timestamp)))
    }

    private suspend fun <T> call(serializer: KSerializer<T>, send: suspend () -> HttpStatement): CloudCall<T> =
        decode(exchange(MAX_ANSWER_BYTES, send), serializer)

    /** For a call whose 2xx answer has no body the desk reads. */
    private suspend fun callNoBody(send: suspend () -> HttpStatement): CloudCall<Unit> =
        when (val answer = exchange(MAX_ANSWER_BYTES, send)) {
            is Exchange.Failed -> answer.call
            is Exchange.Refused -> answer.call
            is Exchange.Answered -> CloudCall.Ok(Unit)
        }

    private fun <T> decode(answer: Exchange, serializer: KSerializer<T>): CloudCall<T> = when (answer) {
        is Exchange.Failed -> answer.call
        is Exchange.Refused -> answer.call
        is Exchange.Answered -> runCatching { CloudCall.Ok(CloudJson.decodeFromString(serializer, answer.body)) }
            .getOrElse { CloudCall.Unreachable("The cloud sent an answer this desk could not read") }
    }

    private sealed interface Exchange {
        data class Answered(val body: String) : Exchange
        data class Refused(val status: Int, val body: String, val call: CloudCall.Refused) : Exchange
        data class Failed(val call: CloudCall<Nothing>) : Exchange
    }

    /**
     * Send and read the answer, at most [maxBytes] of it: a cloud that sends more is treated as one
     * that did not answer, so it cannot fill the show's heap.
     */
    private suspend fun exchange(maxBytes: Int, send: suspend () -> HttpStatement): Exchange = try {
        send().execute { response ->
            val bytes = response.bodyAsChannel().readBuffer(maxBytes + 1L).readByteArray()
            when {
                bytes.size > maxBytes -> Exchange.Failed(CloudCall.Unreachable("The cloud's answer was too large"))
                !response.status.isSuccess() -> {
                    val body = bytes.decodeToString()
                    val error = runCatching { CloudJson.decodeFromString(ApiError.serializer(), body) }.getOrNull()
                    Exchange.Refused(response.status.value, body, CloudCall.Refused(response.status.value, error))
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
        /** [length] bytes of [file] from [offset]. */
        private fun regionOf(file: Path, offset: Long, length: Long): InputStream {
            val channel = FileChannel.open(file, StandardOpenOption.READ).position(offset)
            val whole = Channels.newInputStream(channel)
            return object : InputStream() {
                private var left = length

                override fun read(): Int {
                    if (left <= 0) return -1
                    return whole.read().also { if (it >= 0) left-- }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    return whole.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
                }

                override fun close() = whole.close()
            }
        }

        /** Far above any adoption or repo answer; the control channel's frames have their own limit. */
        const val MAX_ANSWER_BYTES: Int = 64 * 1024

        /** An org's repo list: thousands of repos. */
        const val MAX_LIST_BYTES: Int = 4 * 1024 * 1024
    }
}
