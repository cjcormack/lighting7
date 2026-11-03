package uk.me.cormack.lighting7.cloud

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollResult
import uk.me.cormack.lighting7.cloud.protocol.AdoptionRequest
import uk.me.cormack.lighting7.cloud.protocol.AdoptionStarted
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.UserCode
import uk.me.cormack.lighting7.models.CloudConnectionState
import uk.me.cormack.lighting7.models.CloudLinkVia
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.update.BuildInfo
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** The `cloud_connection` row, read whole. */
data class CloudConnectionRow(
    val state: CloudConnectionState,
    val baseUrl: String,
    val installId: String?,
    val orgName: String?,
    val fingerprint: String,
    val adoptedAt: Instant?,
    val adoptedBy: Int?,
    val adoptionId: String?,
    val userCode: String?,
    val verificationUri: String?,
    val verificationUriComplete: String?,
    val pollIntervalS: Int?,
    val adoptionExpiresAt: Instant?,
)

/** What the desk keeps in memory only, beside the row (D11: no write per poll). */
internal class CloudMemory {
    @Volatile var notice: String? = null
    @Volatile var lastError: CloudErrorDto? = null
}

/**
 * Adoption and unadoption (cloud-p0 plan D6, D8): every transition of the `cloud_connection` row,
 * the install key and the identity links, and the backend's poll loop. Transitions are serialised
 * by one mutex; the database is written only on a transition, never per poll.
 */
internal class CloudAdoptionService(
    private val database: Database,
    private val config: CloudConfig,
    private val keys: InstallKeyStore,
    private val api: () -> CloudApi,
    private val scope: CoroutineScope,
    private val memory: CloudMemory,
    private val clock: () -> Instant,
    private val pollDelay: () -> (Int) -> Duration,
    /** The row changed: rebuild the state from the database. */
    private val changed: () -> Unit,
    /** Only [memory] changed: republish without reading the database. */
    private val memoryChanged: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger(CloudAdoptionService::class.java)
    private val mutex = Mutex()
    private var pollJob: Job? = null

    /** The adoption [pollJob] is polling, so a poll that lands after a transition changes nothing. */
    private var polling: String? = null

    fun row(): CloudConnectionRow? = transaction(database) {
        DaoCloudConnections.selectAll().firstOrNull()?.let {
            CloudConnectionRow(
                state = it[DaoCloudConnections.state],
                baseUrl = it[DaoCloudConnections.baseUrl],
                installId = it[DaoCloudConnections.installId],
                orgName = it[DaoCloudConnections.orgName],
                fingerprint = it[DaoCloudConnections.fingerprint],
                adoptedAt = it[DaoCloudConnections.adoptedAt],
                adoptedBy = it[DaoCloudConnections.adoptedBy]?.value,
                adoptionId = it[DaoCloudConnections.adoptionId],
                userCode = it[DaoCloudConnections.userCode],
                verificationUri = it[DaoCloudConnections.verificationUri],
                verificationUriComplete = it[DaoCloudConnections.verificationUriComplete],
                pollIntervalS = it[DaoCloudConnections.pollIntervalS],
                adoptionExpiresAt = it[DaoCloudConnections.adoptionExpiresAt],
            )
        }
    }

    /**
     * At startup: resume a pending adoption's polling, or settle what a crash left half done. An
     * adoption whose expiry passed while the desk was down is polled once more, since it may have
     * been approved meanwhile.
     */
    suspend fun resume() = mutex.withLock {
        val row = row()
        if (row == null) {
            if (keys.exists()) {
                logger.info("Deleting an install key with no adoption behind it")
                runCatching { keys.delete() }
            }
            return@withLock
        }
        if (row.state != CloudConnectionState.ADOPTING) return@withLock
        val adoptionId = row.adoptionId
        val expiresAt = row.adoptionExpiresAt
        if (adoptionId == null || expiresAt == null) {
            clearLocked(notice = EXPIRED_NOTICE)
            return@withLock
        }
        if (config.enabled && config.baseUrl == row.baseUrl) {
            launchPoll(adoptionId, row.pollIntervalS ?: DEFAULT_INTERVAL_S, expiresAt)
        } else {
            // Not polled (the cloud is off, or another one is configured), but it still ends.
            polling = adoptionId
            pollJob = scope.launch {
                delay(java.time.Duration.between(clock(), expiresAt).toMillis().coerceAtLeast(0))
                finish(adoptionId, null, EXPIRED_NOTICE)
            }
        }
    }

    suspend fun start(userId: Int): Unit = mutex.withLock {
        val baseUrl = config.baseUrl ?: throw CloudException(
            HttpStatusCode.Conflict,
            CloudErrorCodes.NOT_CONFIGURED,
            config.problem ?: "No cloud is configured on this desk: set cloud.baseUrl in local.conf.",
        )
        if (!config.enabled) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.DISABLED, "The cloud is turned off on this desk (cloud.enabled in local.conf).")
        }
        when (row()?.state) {
            CloudConnectionState.ADOPTED -> throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.ALREADY_ADOPTED, "This desk is already adopted.")
            CloudConnectionState.ADOPTING -> throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.ADOPTION_IN_PROGRESS, "An adoption is already waiting for approval.")
            null -> Unit
        }
        val pair = keys.generate()
        try {
            val publicKey = InstallKeys.encodePublicKey(pair.public)
            val (installUuid, friendlyName) = transaction(database) {
                DaoInstall.all().first().let { it.uuid.toString() to it.friendlyName }
            }
            val request = AdoptionRequest(
                publicKey = publicKey,
                deskInstallUuid = installUuid,
                friendlyName = friendlyName,
                platform = DeskPlatform.current,
                deskVersion = BuildInfo.current.version,
            )
            val started = when (val call = api().startAdoption(request)) {
                is CloudCall.Ok -> call.value.also(::checkStarted)
                is CloudCall.Refused -> throw CloudException(
                    HttpStatusCode.BadGateway,
                    CloudErrorCodes.REFUSED,
                    "The cloud refused the adoption: " + (call.error?.let { "${it.code}: ${it.message}" } ?: "HTTP ${call.status}"),
                )
                is CloudCall.Unreachable ->
                    throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.UNREACHABLE, "Couldn't reach the cloud: ${call.message}")
            }
            val expiresAt = clock().plusSeconds(started.expiresInS.toLong())
            val interval = started.intervalS.coerceIn(1, MAX_INTERVAL_S)
            transaction(database) {
                DaoCloudConnections.deleteAll()
                DaoCloudConnections.insert {
                    it[state] = CloudConnectionState.ADOPTING
                    it[DaoCloudConnections.baseUrl] = baseUrl
                    it[DaoCloudConnections.publicKey] = publicKey
                    it[fingerprint] = InstallKeys.fingerprint(pair.public)
                    it[adoptedBy] = userId
                    it[adoptionId] = started.adoptionId
                    it[userCode] = started.userCode
                    it[verificationUri] = started.verificationUri
                    it[verificationUriComplete] = started.verificationUriComplete
                    it[pollIntervalS] = interval
                    it[adoptionExpiresAt] = expiresAt
                }
            }
            memory.notice = null
            memory.lastError = null
            launchPoll(started.adoptionId, interval, expiresAt)
        } catch (e: Throwable) {
            runCatching { keys.delete() }
            throw e
        }
        changed()
    }

    /** A user code of the protocol's shape and an expiry in the future, or the adoption is refused. */
    private fun checkStarted(started: AdoptionStarted) {
        if (!UserCode.isWellFormed(started.userCode) || started.expiresInS <= 0 || started.adoptionId.isBlank()) {
            throw CloudException(HttpStatusCode.BadGateway, CloudErrorCodes.REFUSED, "The cloud sent an adoption this desk could not use.")
        }
    }

    suspend fun cancel(): Unit = mutex.withLock {
        if (row()?.state != CloudConnectionState.ADOPTING) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NO_ADOPTION, "No adoption is waiting for approval.")
        }
        clearLocked(notice = null)
    }

    /**
     * Unadopt from the desk: the signed `DELETE` (D8), then the local state. Without [force] a cloud
     * that cannot be reached, or that refuses, is 409 [CloudErrorCodes.UNREACHABLE]; with it the
     * desk forgets locally whatever the cloud says.
     */
    suspend fun unadopt(force: Boolean): Unit = mutex.withLock {
        val row = row()
        if (row?.state != CloudConnectionState.ADOPTED || row.installId == null) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NOT_ADOPTED, "This desk is not adopted.")
        }
        if (!force) tellCloud(row, row.installId)
        clearLocked(notice = null)
    }

    private suspend fun tellCloud(row: CloudConnectionRow, installId: String) {
        fun unreachable(message: String): Nothing =
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.UNREACHABLE, message)

        if (!config.enabled) unreachable("The cloud is turned off on this desk (cloud.enabled in local.conf), so it can't be told.")
        if (config.baseUrl != row.baseUrl) unreachable("This desk was adopted through ${row.baseUrl}, which cloud.baseUrl no longer names.")
        val key = keys.load() ?: unreachable("This desk's install key is missing, so it can't sign the removal.")
        when (val call = api().unadopt(installId, key)) {
            is CloudCall.Ok -> Unit
            // Already gone in the cloud: the removal has happened, whoever did it.
            is CloudCall.Refused -> if (call.status != HttpStatusCode.NotFound.value) {
                unreachable("The cloud refused to remove this desk: " + (call.error?.let { "${it.code}: ${it.message}" } ?: "HTTP ${call.status}"))
            }
            is CloudCall.Unreachable -> unreachable("Couldn't reach the cloud: ${call.message}")
        }
    }

    /**
     * The cloud removed this install (an `unadopted` message, or close 4004 at connect): forget it
     * here and say so. The control channel's path in (cloud-p0 plan session 5).
     */
    suspend fun revoked(): Unit = mutex.withLock {
        val row = row() ?: return@withLock
        clearLocked(notice = "This desk was removed from ${row.orgName ?: "its organisation"} in the cloud.")
    }

    /** Must hold [mutex]. */
    private fun launchPoll(adoptionId: String, intervalS: Int, expiresAt: Instant) {
        pollJob?.cancel()
        polling = adoptionId
        pollJob = scope.launch { poll(adoptionId, intervalS, expiresAt) }
    }

    /**
     * Poll every interval until the cloud settles the adoption. Once the expiry has passed the desk
     * polls once more, since only a poll can deliver an approval made in the last interval, and
     * ends the adoption unless that poll says it was approved or denied.
     */
    private suspend fun poll(adoptionId: String, startIntervalS: Int, expiresAt: Instant) {
        var intervalS = startIntervalS
        while (true) {
            val untilExpiry = java.time.Duration.between(clock(), expiresAt).toKotlinDuration().coerceAtLeast(Duration.ZERO)
            delay(minOf(pollDelay()(intervalS), untilExpiry))
            val last = !clock().isBefore(expiresAt)
            try {
                val key = keys.load()
                if (key == null) {
                    // An unreadable key may be a locked keychain: try again until the expiry.
                    if (last) return finish(adoptionId, null, "The adoption expired: this desk couldn't read its install key.")
                    pollFailed(adoptionId, "This desk couldn't read its install key.")
                    continue
                }
                when (val call = api().pollAdoption(adoptionId, key)) {
                    is CloudCall.Ok -> when (val result = call.value) {
                        AdoptionPollResult.Pending -> pollSucceeded(adoptionId)
                        is AdoptionPollResult.SlowDown -> {
                            intervalS = result.intervalS.coerceIn(intervalS, MAX_INTERVAL_S)
                            pollSucceeded(adoptionId)
                        }
                        is AdoptionPollResult.Approved -> return finish(adoptionId, result, null)
                        AdoptionPollResult.Denied -> return finish(adoptionId, null, "The adoption was refused in the cloud.")
                        AdoptionPollResult.Expired -> return finish(adoptionId, null, EXPIRED_NOTICE)
                    }
                    is CloudCall.Refused -> if (call.status == HttpStatusCode.NotFound.value) {
                        return finish(adoptionId, null, EXPIRED_NOTICE)
                    } else {
                        pollFailed(adoptionId, "The cloud refused a poll: " + (call.error?.let { "${it.code}: ${it.message}" } ?: "HTTP ${call.status}"))
                    }
                    is CloudCall.Unreachable -> pollFailed(adoptionId, "Couldn't reach the cloud: ${call.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A database or credential-store failure: retried at the interval until the expiry.
                logger.warn("Cloud adoption poll failed", e)
                pollFailed(adoptionId, "The adoption poll failed: ${e.message ?: e::class.simpleName}")
            }
            if (last) return finish(adoptionId, null, EXPIRED_NOTICE)
        }
    }

    private suspend fun pollSucceeded(adoptionId: String) = mutex.withLock {
        if (polling == adoptionId && memory.lastError?.code == CloudErrorCodes.POLL_FAILED) {
            memory.lastError = null
            memoryChanged()
        }
    }

    private suspend fun pollFailed(adoptionId: String, message: String) = mutex.withLock {
        val error = CloudErrorDto(CloudErrorCodes.POLL_FAILED, message)
        if (polling == adoptionId && memory.lastError != error) {
            logger.warn("Cloud adoption poll failed: {}", message)
            memory.lastError = error
            memoryChanged()
        }
    }

    /** End the adoption [adoptionId] if it is still the pending one: [approved], or cleared with [notice]. */
    private suspend fun finish(adoptionId: String, approved: AdoptionPollResult.Approved?, notice: String?) = mutex.withLock {
        if (polling != adoptionId) return@withLock
        val row = row()
        if (row?.state != CloudConnectionState.ADOPTING || row.adoptionId != adoptionId) return@withLock
        if (approved == null) {
            clearLocked(notice, cancelPoll = false)
            return@withLock
        }
        val now = clock()
        transaction(database) {
            DaoCloudConnections.update {
                it[state] = CloudConnectionState.ADOPTED
                it[installId] = approved.installId
                it[orgId] = approved.org.id
                it[orgName] = approved.org.name
                it[adoptedAt] = now
                it[DaoCloudConnections.adoptionId] = null
                it[userCode] = null
                it[verificationUri] = null
                it[verificationUriComplete] = null
                it[pollIntervalS] = null
                it[adoptionExpiresAt] = null
            }
            // Read here, in the write's transaction, so a user deleted meanwhile gets no link.
            val userId = DaoCloudConnections.selectAll().single()[DaoCloudConnections.adoptedBy]?.value
            if (userId != null && DaoUsers.selectAll().where { DaoUsers.id eq userId }.any()) {
                DaoCloudIdentityLinks.deleteWhere { user eq userId }
                DaoCloudIdentityLinks.insert {
                    it[user] = userId
                    it[cloudAccountId] = approved.account.id
                    it[email] = approved.account.email
                    it[linkedAt] = now
                    it[linkedVia] = CloudLinkVia.ADOPTION
                }
            }
        }
        logger.info("Adopted by {} in the cloud", approved.org.name)
        memory.lastError = null
        pollJob = null
        polling = null
        changed()
    }

    /** Forget the adoption: row, links and key. Must hold [mutex]. */
    private fun clearLocked(notice: String?, cancelPoll: Boolean = true) {
        if (cancelPoll) pollJob?.cancel()
        pollJob = null
        polling = null
        transaction(database) {
            DaoCloudIdentityLinks.deleteAll()
            DaoCloudConnections.deleteAll()
        }
        runCatching { keys.delete() }.onFailure { logger.warn("Couldn't delete the install key", it) }
        memory.notice = notice
        memory.lastError = null
        changed()
    }

    companion object {
        const val DEFAULT_INTERVAL_S = 5

        /** The longest interval the desk waits between polls, whatever the cloud asks. */
        const val MAX_INTERVAL_S = 60
        const val EXPIRED_NOTICE = "The adoption code expired before anyone approved it."

        val SECONDS: (Int) -> Duration = { it.seconds }
    }
}
