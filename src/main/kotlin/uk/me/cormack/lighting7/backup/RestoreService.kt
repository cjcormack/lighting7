package uk.me.cormack.lighting7.backup

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.CloudCall
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.CloudErrorDto
import uk.me.cormack.lighting7.cloud.CloudException
import uk.me.cormack.lighting7.cloud.CloudRestoreDto
import uk.me.cormack.lighting7.cloud.CloudService
import uk.me.cormack.lighting7.cloud.protocol.BackupCipher
import uk.me.cormack.lighting7.cloud.protocol.BackupDto
import uk.me.cormack.lighting7.cloud.protocol.BackupManifest
import uk.me.cormack.lighting7.cloud.protocol.BackupStatus
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.telemetry.Tracing
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** `GET /install/cloud/restore`: what an approval asked this desk to restore, and how a confirm is going. */
@Serializable
data class RestoreStatusDto(
    val pending: CloudRestoreDto? = null,
    /** The backup [pending] names, as the cloud describes it; absent when it could not be read. */
    val backup: RestoreBackupDto? = null,
    /** What the confirm must be typed as: the replaced install's name, else the backup's install name. */
    val confirmName: String? = null,
    /** `IDLE`, `DOWNLOADING`, `VERIFYING`, `REPLACING`, or `READY` once a backup waits for the restart. */
    val phase: String = RestoreService.PHASE_IDLE,
    /** Why the last confirm stopped, or why a restore failed at the last start; either way the desk is as it was. */
    val lastError: CloudErrorDto? = null,
)

/** A backup as a restoring desk shows it before it downloads anything. */
@Serializable
data class RestoreBackupDto(
    val backupId: String,
    val installName: String,
    val createdAt: String,
    val sizeBytes: Long,
    val deskVersion: String,
    /** Written by a newer desk than this one: it would be refused (`BACKUP_TOO_NEW`). */
    val tooNew: Boolean,
)

/**
 * Restoring onto this desk (cloud-p3 plan D9): an admin's confirm of what the adoption's approval
 * asked for. A backup is downloaded, decrypted under the key its header names and checked file by file
 * against its manifest before anything changes; then a replacement is confirmed in the cloud, which
 * moves the old install's identity here; then a staged backup waits for the next start, which applies
 * it ([RestoreApply]). The work runs on `restore-1`, never on the show's or the cloud channel's threads.
 */
class RestoreService(
    private val cloud: () -> CloudService,
    private val staging: RestoreStaging,
    /** The highest database `user_version` and sync `formatVersion` this build reads. */
    private val schemaVersion: Int,
    private val syncFormatVersion: Int,
) {
    private val logger = LoggerFactory.getLogger(RestoreService::class.java)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "restore-1").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher() + CoroutineName("restore"))
    private val phase = AtomicReference(PHASE_IDLE)
    @Volatile private var lastError: CloudErrorDto? = null
    @Volatile private var described: BackupDto? = null

    /**
     * At startup: what a stopped confirm left goes, a staged restore asks for its restart again, and a
     * restore that failed while the desk started is told to the admin.
     */
    fun start() {
        runCatching { staging.sweepPartial() }.onFailure { logger.warn("Couldn't sweep the restore's staging", it) }
        staging.readFailure()?.let { failure ->
            lastError = CloudErrorDto(CloudErrorCodes.RESTORE_FAILED, failure.message)
            val at = runCatching { Instant.parse(failure.at) }.getOrElse { Instant.now() }
            runCatching { cloud().restoreFailed(failure.message, at) }.onFailure { logger.warn("Couldn't say the restore failed", it) }
        }
        if (staging.readyMarker() != null) {
            phase.set(PHASE_READY)
            cloud().restartToRestore = true
        }
    }

    suspend fun status(): RestoreStatusDto {
        val pending = cloud().pendingRestore()
        val backup = pending?.backupId?.let { describe(it) }
        return RestoreStatusDto(
            pending = pending,
            backup = backup?.let(::summaryOf),
            confirmName = pending?.let { confirmNameOf(it, backup) },
            phase = phase.get(),
            lastError = lastError,
        )
    }

    /**
     * Check the confirm and start it: the restore pending, nothing already running, and [typed] the old
     * install's name (or, for a backup alone, the backup's install name), never blank. The work goes on
     * after this answers; [status] follows it.
     */
    suspend fun begin(typed: String): Job {
        val pending = cloud().pendingRestore()
            ?: throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.NO_RESTORE, "No restore is waiting on this desk.")
        val backup = pending.backupId?.let { describe(it, fresh = true) }
        val expected = confirmNameOf(pending, backup)
            ?: throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.UNREACHABLE, "Couldn't read the backup from the cloud, so it can't be restored now.")
        if (expected.isBlank()) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.RESTORE_NAME_MISMATCH, "This restore names nothing to type, so it can't be confirmed. Decline it.")
        }
        if (typed.trim() != expected.trim()) {
            throw CloudException(HttpStatusCode.BadRequest, CloudErrorCodes.RESTORE_NAME_MISMATCH, "Type \"$expected\" to confirm: it replaces everything on this desk.")
        }
        if (!phase.compareAndSet(PHASE_IDLE, PHASE_DOWNLOADING.takeIf { pending.backupId != null } ?: PHASE_REPLACING)) {
            throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.RESTORE_BUSY, "A restore is already under way on this desk.")
        }
        lastError = null
        runCatching { staging.clearFailure() }
        return scope.launch { run(pending) }
    }

    /** [begin], then wait for it to finish; for tests. */
    suspend fun confirm(typed: String): RestoreStatusDto {
        begin(typed).join()
        return status()
    }

    /**
     * Decline the restore: the replacement in the cloud too, and anything downloaded for it. A backup
     * staged for the restart is dropped instead, before it applies; a replacement already made stays.
     */
    suspend fun decline() {
        when (phase.get()) {
            PHASE_READY -> {
                dropStaged()
                lastError = null
                return
            }
            PHASE_IDLE -> Unit
            else -> throw CloudException(HttpStatusCode.Conflict, CloudErrorCodes.RESTORE_BUSY, "A restore is under way on this desk.")
        }
        cloud().declineRestore()
        runCatching { staging.clear() }
        runCatching { staging.clearFailure() }
        lastError = null
    }

    /** Forget a staged restore before the restart applies it: on a decline, and when the desk is unadopted. */
    fun dropStaged() {
        runCatching { staging.clear() }.onFailure { logger.warn("Couldn't clear the restore's staging", it) }
        if (phase.compareAndSet(PHASE_READY, PHASE_IDLE) || cloud().restartToRestore) cloud().restartToRestore = false
    }

    private suspend fun run(pending: CloudRestoreDto) {
        val replacing = pending.replacesInstallId != null
        try {
            Tracing.transactionSuspending(OP_RESTORE, OP_RESTORE) { span ->
                pending.backupId?.let { backupId ->
                    val marker = staging.verifiedMarker()?.takeIf { it.backupId == backupId && it.keepLinks == replacing }
                        ?: stageSettling(backupId, replacing)
                    span?.setData("files", marker.manifest.files.size)
                }
                if (replacing && cloud().pendingRestore()?.replacesInstallId != null) {
                    phase.set(PHASE_REPLACING)
                    cloud().confirmReplacement()
                }
                if (pending.backupId != null) {
                    if (cloud().installSigner() == null) {
                        throw RestoreRefused(CloudErrorCodes.NOT_ADOPTED, "This desk is no longer adopted, so the backup isn't restored.")
                    }
                    staging.promote()
                    if (!replacing) {
                        runCatching { cloud().clearRestore() }.onFailure { logger.warn("Couldn't forget the restore once its backup was staged", it) }
                    }
                }
            }
            if (pending.backupId != null) {
                phase.set(PHASE_READY)
                cloud().restartToRestore = true
                logger.info("Backup {} is ready: it is restored when the desk next starts", pending.backupId)
            } else {
                phase.set(PHASE_IDLE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = when (e) {
                is RestoreRefused -> CloudErrorDto(e.code, e.message ?: e.code)
                is CloudException -> CloudErrorDto(e.code, e.message ?: e.code)
                else -> CloudErrorDto(CloudErrorCodes.REFUSED, "The restore failed: ${e.message ?: e::class.simpleName}")
            }
            logger.warn("Restore stopped: {} {}", error.code, error.message)
            // An unanswered replacement may have happened: the checked tree waits for the next confirm.
            val unanswered = e is CloudException && e.code == CloudErrorCodes.UNREACHABLE && phase.get() == PHASE_REPLACING
            if (!unanswered) runCatching { staging.clear() }
            lastError = error
            phase.set(PHASE_IDLE)
        }
    }

    /**
     * [stage], and once more when a replacement's lost answer had already moved the old install here:
     * the cloud no longer knows this desk's own install id, so the first try is refused.
     */
    private suspend fun stageSettling(backupId: String, replacing: Boolean): RestoreMarker =
        try {
            stage(backupId, replacing)
        } catch (e: RestoreRefused) {
            if (!replacing || e.code in FINAL || !cloud().settleLostReplacement()) throw e
            logger.info("A replacement whose answer was lost had already happened; staging the backup as the old install")
            stage(backupId, replacing)
        }

    /** Download, decrypt and check the backup into `verified/`; refused with nothing on the desk changed. */
    private suspend fun stage(backupId: String, keepLinks: Boolean): RestoreMarker {
        val service = cloud()
        val signer = service.installSigner() ?: throw RestoreRefused(CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted, so it can't reach the backup.")
        val api = service.api() ?: throw RestoreRefused(CloudErrorCodes.NOT_ADOPTED, "This desk has no cloud configured.")
        val dto = api.backup(signer, backupId).orRefuse()
        if (dto.status != BackupStatus.COMPLETE) throw RestoreRefused(ErrorCode.BACKUP_INCOMPLETE, "That backup never finished uploading, so it can't be restored.")
        tooNew(dto.manifest)?.let { throw RestoreRefused(ErrorCode.BACKUP_TOO_NEW, it) }
        staging.prepare()
        staging.sweepPartial()
        val downloaded = api.downloadBackup(signer, backupId, staging.download, dto.sizeBytes).orRefuse()
        if (downloaded.sizeBytes != dto.sizeBytes || downloaded.sha256 != dto.sha256) {
            Files.deleteIfExists(staging.download)
            throw RestoreRefused(CloudErrorCodes.BACKUP_CORRUPT, "The backup downloaded is not the one the cloud described, so nothing was restored.")
        }
        val keyId = Files.newInputStream(staging.download).use { runCatching { BackupCipher.readHeader(it).keyId }.getOrNull() }
        if (keyId == null) {
            Files.deleteIfExists(staging.download)
            throw RestoreRefused(CloudErrorCodes.BACKUP_CORRUPT, "The backup's file is not a backup, so nothing was restored.")
        }
        val key = api.backupKey(signer, keyId).orRefuse()
        val keyBytes = key.keyBytes()?.takeIf { key.keyId == keyId }
            ?: throw RestoreRefused(BackupErrorCodes.KEY_INVALID, "The cloud sent a data key this desk can't use.")
        phase.set(PHASE_VERIFYING)
        try {
            return staging.extract(backupId, keyBytes, keepLinks, ::tooNew)
        } finally {
            keyBytes.fill(0)
        }
    }

    /** Why this desk can't read a backup with [manifest], or null when it can. */
    internal fun tooNew(manifest: BackupManifest): String? = when {
        manifest.schemaVersion > schemaVersion || manifest.syncFormatVersion > syncFormatVersion ->
            "That backup was made by a newer desk (${manifest.deskVersion}). Update this desk first, then restore it."
        else -> null
    }

    private suspend fun describe(backupId: String, fresh: Boolean = false): BackupDto? {
        described?.takeIf { it.backupId == backupId && !fresh }?.let { return it }
        val service = cloud()
        val signer = service.installSigner() ?: return null
        val api = service.api() ?: return null
        return (api.backup(signer, backupId) as? CloudCall.Ok)?.value?.also { described = it }
    }

    private fun confirmNameOf(pending: CloudRestoreDto, backup: BackupDto?): String? =
        if (pending.replacesInstallId != null) pending.replacesName else backup?.installName

    private fun summaryOf(dto: BackupDto) = RestoreBackupDto(
        backupId = dto.backupId,
        installName = dto.installName,
        createdAt = dto.createdAt,
        sizeBytes = dto.sizeBytes,
        deskVersion = dto.manifest.deskVersion,
        tooNew = tooNew(dto.manifest) != null,
    )

    private fun <T> CloudCall<T>.orRefuse(): T = when (this) {
        is CloudCall.Ok -> value
        is CloudCall.Refused -> throw RestoreRefused(error?.code ?: CloudErrorCodes.REFUSED, error?.message ?: "The cloud refused the restore ($status).")
        is CloudCall.Unreachable -> throw RestoreRefused(CloudErrorCodes.UNREACHABLE, "Couldn't reach the cloud: $message")
    }

    fun close() {
        scope.cancel()
        executor.shutdownNow()
    }

    companion object {
        const val PHASE_IDLE = "IDLE"
        const val PHASE_DOWNLOADING = "DOWNLOADING"
        const val PHASE_VERIFYING = "VERIFYING"
        const val PHASE_REPLACING = "REPLACING"
        const val PHASE_READY = "READY"

        const val OP_RESTORE = "backup.restore"

        /** Refusals about the backup itself, which no settled replacement would change. */
        private val FINAL = setOf(ErrorCode.BACKUP_INCOMPLETE, ErrorCode.BACKUP_TOO_NEW, CloudErrorCodes.BACKUP_CORRUPT, BackupErrorCodes.KEY_INVALID)
    }
}
