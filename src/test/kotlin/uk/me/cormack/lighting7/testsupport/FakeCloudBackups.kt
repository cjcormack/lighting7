package uk.me.cormack.lighting7.testsupport

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import uk.me.cormack.lighting7.cloud.protocol.ApiError
import uk.me.cormack.lighting7.cloud.protocol.BACKUP_PART_BYTES
import uk.me.cormack.lighting7.cloud.protocol.BackupApiPayload
import uk.me.cormack.lighting7.cloud.protocol.BackupDto
import uk.me.cormack.lighting7.cloud.protocol.BackupIds
import uk.me.cormack.lighting7.cloud.protocol.BackupKeyDto
import uk.me.cormack.lighting7.cloud.protocol.BackupList
import uk.me.cormack.lighting7.cloud.protocol.BackupStatus
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.CloudPaths
import uk.me.cormack.lighting7.cloud.protocol.DeclareBackupRequest
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.SignatureHeaders
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.cloud.protocol.backupPartCount
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FakeCloud]'s backups (cloud-p3 plan §3): the backup API under [CloudPaths.BACKUPS], every call
 * signed over a [BackupApiPayload] and checked, over an in-memory store with the protocol's rules —
 * the declared part count and sizes, each part's and the whole's SHA-256, unique ids, the org's quota,
 * the size cap and [backupsOff].
 */
class FakeCloudBackups(private val cloud: FakeCloud) {
    class Stored(
        val installId: String,
        val request: DeclareBackupRequest,
        val parts: ConcurrentHashMap<Int, ByteArray> = ConcurrentHashMap(),
        @Volatile var complete: Boolean = false,
        val createdAt: Instant = Instant.now(),
    ) {
        fun bytes(): ByteArray = ByteArrayOutputStream().also { out -> (1..request.parts).forEach { out.write(parts.getValue(it)) } }.toByteArray()
    }

    /** The org's data keys by id, and the current one's id. */
    val keys = ConcurrentHashMap<String, ByteArray>()
    @Volatile var currentKeyId: String = "key-1"

    val backups = ConcurrentHashMap<String, Stored>()

    /** The org has no off-site backups: every call answers 403 `BACKUPS_OFF`. */
    @Volatile var backupsOff: Boolean = false

    /** The org's backup storage, in bytes; a declare past it is 409 `BACKUP_QUOTA_EXCEEDED`. */
    @Volatile var quotaBytes: Long = Long.MAX_VALUE

    /** The largest backup taken whatever the quota, 413 `BACKUP_TOO_LARGE`. */
    @Volatile var maxBytes: Long = Long.MAX_VALUE

    /** Answer the next [failParts] part uploads 503. */
    val failParts = AtomicInteger(0)

    /** Answer the data key 503, as an unreachable cloud fails before anything is staged. */
    @Volatile var failKey: Boolean = false

    /** Answer this many uploads of each part number 503, before taking it. */
    val failPart = ConcurrentHashMap<Int, Int>()

    /** Every call as `METHOD target`, in order. */
    val calls = CopyOnWriteArrayList<String>()

    init {
        keys[currentKeyId] = ByteArray(32).also(SecureRandom()::nextBytes)
    }

    /** The current data key's bytes. */
    val currentKey: ByteArray get() = keys.getValue(currentKeyId)

    fun routes(route: Route) = with(route) {
        get(CloudPaths.BACKUP_KEY) {
            caller(call, ByteArray(0)) ?: return@get
            if (failKey) return@get call.apiError(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE")
            call.json(BackupKeyDto.serializer(), BackupKeyDto(currentKeyId, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(currentKey)))
        }
        get("${CloudPaths.BACKUP_KEY}/{keyId}") {
            caller(call, ByteArray(0)) ?: return@get
            val keyId = call.parameters["keyId"].orEmpty()
            val key = keys[keyId] ?: return@get call.apiError(HttpStatusCode.NotFound, ErrorCode.BACKUP_KEY_UNKNOWN)
            call.json(BackupKeyDto.serializer(), BackupKeyDto(keyId, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)))
        }
        post(CloudPaths.BACKUPS) {
            val body = call.receive<ByteArray>()
            val installId = caller(call, body) ?: return@post
            val request = runCatching { CloudJson.decodeFromString(DeclareBackupRequest.serializer(), body.decodeToString()) }.getOrNull()
                ?: return@post call.apiError(HttpStatusCode.BadRequest, ErrorCode.MALFORMED)
            if (!BackupIds.isWellFormed(request.backupId) || request.parts != backupPartCount(request.sizeBytes)) {
                return@post call.apiError(HttpStatusCode.BadRequest, ErrorCode.MALFORMED)
            }
            if (!keys.containsKey(request.keyId)) return@post call.apiError(HttpStatusCode.Conflict, ErrorCode.BACKUP_KEY_UNKNOWN)
            if (request.sizeBytes > maxBytes) return@post call.apiError(HttpStatusCode.PayloadTooLarge, ErrorCode.BACKUP_TOO_LARGE)
            if (backups.values.sumOf { it.request.sizeBytes } + request.sizeBytes > quotaBytes) {
                return@post call.apiError(HttpStatusCode.Conflict, ErrorCode.BACKUP_QUOTA_EXCEEDED)
            }
            if (backups.putIfAbsent(request.backupId, Stored(installId, request)) != null) {
                return@post call.apiError(HttpStatusCode.Conflict, ErrorCode.BACKUP_ID_TAKEN)
            }
            call.json(BackupDto.serializer(), dtoOf(backups.getValue(request.backupId)), HttpStatusCode.Created)
        }
        get("${CloudPaths.BACKUPS}/{backupId}") {
            caller(call, ByteArray(0)) ?: return@get
            val stored = backups[call.parameters["backupId"].orEmpty()] ?: return@get call.apiError(HttpStatusCode.NotFound, ErrorCode.BACKUP_NOT_FOUND)
            call.json(BackupDto.serializer(), dtoOf(stored))
        }
        put("${CloudPaths.BACKUPS}/{backupId}/parts/{n}") {
            val body = call.receive<ByteArray>()
            caller(call, body) ?: return@put
            val n = call.parameters["n"]?.toIntOrNull() ?: 0
            val failNamed = failPart.computeIfPresent(n) { _, left -> left - 1 }?.let { it >= 0 } ?: false
            if (failNamed || failParts.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                return@put call.apiError(HttpStatusCode.ServiceUnavailable, "UNAVAILABLE")
            }
            val stored = backups[call.parameters["backupId"].orEmpty()] ?: return@put call.apiError(HttpStatusCode.NotFound, ErrorCode.BACKUP_NOT_FOUND)
            val parts = stored.request.parts
            val expected = if (n < parts) BACKUP_PART_BYTES.toLong() else stored.request.sizeBytes - (parts - 1).toLong() * BACKUP_PART_BYTES
            if (stored.complete || n !in 1..parts || body.size.toLong() != expected) {
                return@put call.apiError(HttpStatusCode.BadRequest, ErrorCode.BACKUP_PART_INVALID)
            }
            stored.parts[n] = body
            call.json(BackupDto.serializer(), dtoOf(stored))
        }
        post("${CloudPaths.BACKUPS}/{backupId}/complete") {
            caller(call, ByteArray(0)) ?: return@post
            val stored = backups[call.parameters["backupId"].orEmpty()] ?: return@post call.apiError(HttpStatusCode.NotFound, ErrorCode.BACKUP_NOT_FOUND)
            if (!stored.complete) {
                if ((1..stored.request.parts).any { !stored.parts.containsKey(it) }) return@post call.apiError(HttpStatusCode.Conflict, ErrorCode.BACKUP_INCOMPLETE)
                if (sha256(stored.bytes()) != stored.request.sha256) return@post call.apiError(HttpStatusCode.Conflict, ErrorCode.BACKUP_PART_INVALID)
                stored.complete = true
            }
            call.json(BackupDto.serializer(), dtoOf(stored))
        }
        get("${CloudPaths.BACKUPS}/{backupId}/file") {
            caller(call, ByteArray(0)) ?: return@get
            val stored = backups[call.parameters["backupId"].orEmpty()] ?: return@get call.apiError(HttpStatusCode.NotFound, ErrorCode.BACKUP_NOT_FOUND)
            if (!stored.complete) return@get call.apiError(HttpStatusCode.Conflict, ErrorCode.BACKUP_INCOMPLETE)
            call.respondBytes(stored.bytes(), ContentType.Application.OctetStream)
        }
        get(CloudPaths.BACKUPS) {
            caller(call, ByteArray(0)) ?: return@get
            val installId = call.request.queryParameters["installId"]
            val list = backups.values.filter { it.complete && (installId == null || it.installId == installId) }
                .sortedByDescending { it.createdAt }.map(::dtoOf)
            call.json(BackupList.serializer(), BackupList(list))
        }
    }

    private fun dtoOf(stored: Stored) = BackupDto(
        backupId = stored.request.backupId,
        installId = stored.installId,
        installName = "Test desk",
        kind = stored.request.kind,
        status = if (stored.complete) BackupStatus.COMPLETE else BackupStatus.UPLOADING,
        sizeBytes = stored.request.sizeBytes,
        parts = stored.request.parts,
        partsReceived = stored.parts.keys.sorted(),
        sha256 = stored.request.sha256,
        keyId = stored.request.keyId,
        manifest = stored.request.manifest,
        createdAt = Timestamps.format(stored.createdAt),
        completedAt = null,
    )

    /** The install that signed this call over [body], or null once refused. */
    private suspend fun caller(call: ApplicationCall, body: ByteArray): String? {
        calls += "${call.request.httpMethod.value} ${call.request.uri}"
        if (backupsOff) return null.also { call.apiError(HttpStatusCode.Forbidden, ErrorCode.BACKUPS_OFF) }
        val installId = call.request.headers[SignatureHeaders.INSTALL]
        val timestamp = call.request.headers[SignatureHeaders.TIMESTAMP]
        val signature = call.request.headers[SignatureHeaders.SIGNATURE]
        val install = installId?.let { cloud.installs[it] }
        if (install == null || timestamp == null || signature == null) {
            return null.also { call.apiError(HttpStatusCode.Unauthorized, ErrorCode.MALFORMED) }
        }
        if (!Timestamps.withinSkew(timestamp, Instant.now())) return null.also { call.apiError(HttpStatusCode.Unauthorized, ErrorCode.CLOCK_SKEW) }
        val payload = BackupApiPayload.of(installId, call.request.httpMethod.value, call.request.uri, timestamp, body)
        if (!InstallKeys.verify(install.key, payload, signature)) return null.also { call.apiError(HttpStatusCode.Unauthorized, ErrorCode.BAD_SIGNATURE) }
        return installId
    }

    private suspend fun <T> ApplicationCall.json(serializer: kotlinx.serialization.KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(CloudJson.encodeToString(serializer, value), ContentType.Application.Json, status)

    private suspend fun ApplicationCall.apiError(status: HttpStatusCode, code: String) =
        respondText(CloudJson.encodeToString(ApiError.serializer(), ApiError(code, "fake cloud: $code")), ContentType.Application.Json, status)

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
