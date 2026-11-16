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
import org.jetbrains.exposed.v1.core.or
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
import uk.me.cormack.lighting7.cloud.protocol.AdoptionRestore
import uk.me.cormack.lighting7.cloud.protocol.AdoptionStarted
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.UserCode
import uk.me.cormack.lighting7.models.CloudConnectionState
import uk.me.cormack.lighting7.models.CloudLinkEventKind
import uk.me.cormack.lighting7.models.CloudLinkEventUser
import uk.me.cormack.lighting7.models.CloudLinkVia
import uk.me.cormack.lighting7.models.CloudUnlinkReason
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.models.clearCloudLinks
import uk.me.cormack.lighting7.models.recordCloudLinkEvent
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.update.BuildInfo
import java.security.PrivateKey
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** The `cloud_connection` row, read whole. */
data class CloudConnectionRow(
    val state: CloudConnectionState,
    val baseUrl: String,
    val installId: String?,
    val orgId: String?,
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
    val remoteAccess: Boolean = false,
    val mcpAccess: Boolean = false,
    val approveSessions: Boolean = false,
    /** A restore the approval asked for, until it is confirmed or declined (cloud-p3 plan D9). */
    val restore: AdoptionRestore? = null,
)

/** What the desk keeps in memory only, beside the row (D11: no write per poll, heartbeat or reconnect). */
internal class CloudMemory {
    @Volatile var notice: String? = null
    @Volatile var noticeAt: Instant? = null
    @Volatile var lastError: CloudErrorDto? = null
    @Volatile var channel: ChannelStatus = ChannelStatus(CloudConnectionStatus.OFF)
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
    /** These desk users' cloud links changed, so Users is told (cloud-p1 plan D12). */
    private val usersChanged: (Collection<Int>) -> Unit = {},
    /** What the desk speaks, sent with the adoption so the portal can offer a restore (cloud-p3 plan D1). */
    private val capabilities: () -> List<String> = { emptyList() },
    /** The adoption is forgotten (unadopt, cancel, revoke): a staged restore goes with it (cloud-p3 plan D9). */
    private val unadopted: () -> Unit = {},
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
                orgId = it[DaoCloudConnections.orgId],
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
                remoteAccess = it[DaoCloudConnections.remoteAccess],
                mcpAccess = it[DaoCloudConnections.mcpAccess],
                approveSessions = it[DaoCloudConnections.approveSessions],
                restore = restoreOf(
                    it[DaoCloudConnections.restoreReplacesInstallId],
                    it[DaoCloudConnections.restoreReplacesName],
                    it[DaoCloudConnections.restoreBackupId],
                ),
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
            val (installUuid, friendlyName) = DeskIdentity.read(database)
            val request = AdoptionRequest(
                publicKey = publicKey,
                deskInstallUuid = installUuid,
                friendlyName = friendlyName,
                platform = DeskPlatform.current,
                deskVersion = BuildInfo.current.version,
                capabilities = capabilities(),
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
            memory.noticeAt = null
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
     * here and say so. With [installId], only if the desk is still adopted as that install, so a
     * channel that outlived its adoption cannot clear a newer one.
     */
    suspend fun revoked(installId: String? = null): Unit = mutex.withLock {
        val row = row() ?: return@withLock
        if (installId != null && (row.state != CloudConnectionState.ADOPTED || row.installId != installId)) return@withLock
        clearLocked(notice = "This desk was removed from ${row.orgName ?: "its organisation"} in the cloud.")
    }

    /**
     * Confirm the replacement the adoption recorded (cloud-p3 plan D9): the signed `POST
     * /v1/installs/{installId}/replace`, then the old install's id as this desk's, which reconnects the
     * channel as it. The restore is cleared, so a backup it named must be checked first. A replacement
     * past its 7 days clears the restore and leaves the desk its own install; so does one the cloud no
     * longer has, unless the old install already answers to this desk's key, when an earlier confirm's
     * answer was lost and the replacement stands. Unreachable leaves the restore pending. Answers the
     * install id the desk now is.
     */
    suspend fun confirmReplacement(): String = mutex.withLock {
        val row = row()
        val installId = row?.installId
        val replaces = row?.restore?.replacesInstallId
        if (row?.state != CloudConnectionState.ADOPTED || installId == null || replaces == null) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NO_RESTORE, "No replacement is waiting on this desk.")
        }
        val key = keys.load() ?: throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.KEY_MISSING, "This desk's install key is missing, so it can't sign the replacement.")
        when (val call = api().replaceInstall(InstallSigner(installId, key))) {
            is CloudCall.Ok -> {
                replacedLocked(call.value.installId)
                call.value.installId
            }
            is CloudCall.Refused -> {
                val code = call.error?.code
                if (code == ErrorCode.REPLACEMENT_EXPIRED) {
                    clearRestoreLocked()
                    throw CloudException(HttpStatusCode.Conflict, code, "The replacement expired: it waited longer than 7 days. This desk stays its own install.")
                }
                if (answersAs(replaces, key)) {
                    replacedLocked(replaces)
                    return@withLock replaces
                }
                if (code == ErrorCode.REPLACEMENT_NOT_FOUND) {
                    clearRestoreLocked()
                    throw CloudException(HttpStatusCode.Conflict, code, "The cloud has no replacement waiting for this desk any more. This desk stays its own install.")
                }
                throw CloudException(
                    HttpStatusCode.BadGateway,
                    code ?: CloudErrorCodes.REFUSED,
                    "The cloud refused the replacement: " + (call.error?.message ?: "HTTP ${call.status}"),
                )
            }
            is CloudCall.Unreachable -> throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.UNREACHABLE, "Couldn't reach the cloud: ${call.message}")
        }
    }

    /**
     * Whether a replacement whose answer was lost has already happened: the old install answers to this
     * desk's key. Takes its id when it has, and answers true.
     */
    suspend fun settleLostReplacement(): Boolean = mutex.withLock {
        val row = row()
        val replaces = row?.restore?.replacesInstallId
        if (row?.state != CloudConnectionState.ADOPTED || replaces == null) return@withLock false
        val key = keys.load() ?: return@withLock false
        if (!answersAs(replaces, key)) return@withLock false
        replacedLocked(replaces)
        true
    }

    /**
     * Decline the restore (cloud-p3 plan D9): a replacement is declined in the cloud too, by the signed
     * `DELETE`; one the cloud no longer has counts as declined. The desk stays its own install, unless an
     * earlier confirm's lost answer had already moved the old install here, which stands.
     */
    suspend fun declineRestore(): Unit = mutex.withLock {
        val row = row()
        val restore = row?.restore
        if (row?.state != CloudConnectionState.ADOPTED || restore == null) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NO_RESTORE, "No restore is waiting on this desk.")
        }
        val installId = row.installId
        val replaces = restore.replacesInstallId
        if (replaces != null && installId != null) {
            val key = keys.load() ?: throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.KEY_MISSING, "This desk's install key is missing, so it can't sign the decline.")
            when (val call = api().declineReplacement(InstallSigner(installId, key))) {
                is CloudCall.Ok -> Unit
                is CloudCall.Refused -> {
                    val code = call.error?.code
                    if (code != ErrorCode.REPLACEMENT_EXPIRED && answersAs(replaces, key)) {
                        logger.info("The replacement of {} had already happened, so only the backup is declined", replaces)
                        replacedLocked(replaces)
                        return@withLock
                    }
                    if (code != ErrorCode.REPLACEMENT_EXPIRED && code != ErrorCode.REPLACEMENT_NOT_FOUND) {
                        throw CloudException(
                            HttpStatusCode.BadGateway,
                            code ?: CloudErrorCodes.REFUSED,
                            "The cloud refused the decline: " + (call.error?.message ?: "HTTP ${call.status}"),
                        )
                    }
                }
                is CloudCall.Unreachable -> throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.UNREACHABLE, "Couldn't reach the cloud: ${call.message}")
            }
        }
        clearRestoreLocked()
    }

    /** A harmless call signed as [installId] with this desk's [key]: the cloud accepts it only once the identity moved here. */
    private suspend fun answersAs(installId: String, key: PrivateKey): Boolean =
        api().backupKey(InstallSigner(installId, key)) is CloudCall.Ok

    /** The replacement stands: [installId] is this desk's, and the restore is no longer pending. Must hold [mutex]. */
    private fun replacedLocked(installId: String) {
        transaction(database) {
            DaoCloudConnections.update {
                it[DaoCloudConnections.installId] = installId
                it[restoreReplacesInstallId] = null
                it[restoreReplacesName] = null
                it[restoreBackupId] = null
            }
        }
        logger.info("This desk now replaces the install {}", installId)
        changed()
    }

    /** Forget the restore here alone: a backup alone, once staged, asks nothing of the cloud. */
    suspend fun clearRestore(): Unit = mutex.withLock { clearRestoreLocked() }

    /** Must hold [mutex]. */
    private fun clearRestoreLocked() {
        transaction(database) {
            DaoCloudConnections.update {
                it[restoreReplacesInstallId] = null
                it[restoreReplacesName] = null
                it[restoreBackupId] = null
            }
        }
        changed()
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
        val linked = transaction(database) {
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
                it[restoreReplacesInstallId] = approved.restore?.replacesInstallId
                it[restoreReplacesName] = approved.restore?.replacesName
                it[restoreBackupId] = approved.restore?.backupId
            }
            // Read here, in the write's transaction, so a user deleted meanwhile gets no link.
            val userId = DaoCloudConnections.selectAll().single()[DaoCloudConnections.adoptedBy]?.value
            val username = userId?.let { id -> DaoUsers.selectAll().where { DaoUsers.id eq id }.firstOrNull()?.get(DaoUsers.username) }
            if (userId != null && username != null) {
                val who = CloudLinkEventUser(userId, username)
                // Unadoption cleared every link, so neither side is linked yet; were one, it gives way to adoption's.
                DaoCloudIdentityLinks.selectAll()
                    .where { (DaoCloudIdentityLinks.user eq userId) or (DaoCloudIdentityLinks.cloudAccountId eq approved.account.id) }
                    .forEach { old ->
                        val oldUser = old[DaoCloudIdentityLinks.user].value
                        val oldName = DaoUsers.selectAll().where { DaoUsers.id eq oldUser }.firstOrNull()?.get(DaoUsers.username)
                        recordCloudLinkEvent(
                            now, CloudLinkEventKind.UNLINKED, user = oldName?.let { CloudLinkEventUser(oldUser, it) },
                            cloudAccountId = old[DaoCloudIdentityLinks.cloudAccountId], email = old[DaoCloudIdentityLinks.email],
                            via = old[DaoCloudIdentityLinks.linkedVia], reason = CloudUnlinkReason.REPLACED.name,
                        )
                    }
                DaoCloudIdentityLinks.deleteWhere { (user eq userId) or (cloudAccountId eq approved.account.id) }
                DaoCloudIdentityLinks.insert {
                    it[user] = userId
                    it[cloudAccountId] = approved.account.id
                    it[email] = approved.account.email
                    it[linkedAt] = now
                    it[linkedVia] = CloudLinkVia.ADOPTION
                }
                recordCloudLinkEvent(
                    now, CloudLinkEventKind.LINKED, user = who, cloudAccountId = approved.account.id,
                    email = approved.account.email, via = CloudLinkVia.ADOPTION,
                )
            }
            userId?.takeIf { username != null }
        }
        logger.info("Adopted by {} in the cloud", approved.org.name)
        memory.lastError = null
        pollJob = null
        polling = null
        changed()
        if (linked != null) usersChanged(listOf(linked))
    }

    /** Forget the adoption: row, links and key. Must hold [mutex]. */
    private fun clearLocked(notice: String?, cancelPoll: Boolean = true) {
        if (cancelPoll) pollJob?.cancel()
        pollJob = null
        polling = null
        val unlinked = transaction(database) {
            clearCloudLinks(clock(), CloudUnlinkReason.UNADOPTED).also { DaoCloudConnections.deleteAll() }
        }
        runCatching { keys.delete() }.onFailure { logger.warn("Couldn't delete the install key", it) }
        memory.notice = notice
        memory.noticeAt = notice?.let { nowUtc() }
        memory.lastError = null
        changed()
        if (unlinked.isNotEmpty()) usersChanged(unlinked)
        runCatching { unadopted() }.onFailure { logger.warn("Couldn't drop the staged restore", it) }
    }

    companion object {
        /** A stored restore, or null when the row holds none or one the protocol would refuse. */
        internal fun restoreOf(replacesInstallId: String?, replacesName: String?, backupId: String?): AdoptionRestore? =
            if (replacesInstallId == null && backupId == null) null
            else runCatching { AdoptionRestore(replacesInstallId, replacesName, backupId) }.getOrNull()

        const val DEFAULT_INTERVAL_S = 5

        /** The longest interval the desk waits between polls, whatever the cloud asks. */
        const val MAX_INTERVAL_S = 60
        const val EXPIRED_NOTICE = "The adoption code expired before anyone approved it."

        val SECONDS: (Int) -> Duration = { it.seconds }
    }
}
