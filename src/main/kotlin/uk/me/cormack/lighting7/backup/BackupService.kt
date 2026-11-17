package uk.me.cormack.lighting7.backup

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.CloudCall
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.InstallSigner
import uk.me.cormack.lighting7.cloud.protocol.BACKUP_PART_BYTES
import uk.me.cormack.lighting7.cloud.protocol.BackupIds
import uk.me.cormack.lighting7.cloud.protocol.BackupKind
import uk.me.cormack.lighting7.cloud.protocol.BackupStatus
import uk.me.cormack.lighting7.cloud.protocol.DeclareBackupRequest
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.HealthBackup
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.cloud.protocol.backupPartCount
import uk.me.cormack.lighting7.models.DaoBackupSettingsTable
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.telemetry.Tracing
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** The codes a backup's last result and the backup routes answer, beside the protocol's own. */
object BackupErrorCodes {
    const val FAILED = "BACKUP_FAILED"
    const val KEY_INVALID = "BACKUP_KEY_INVALID"
    const val REMOTE_OFF = "BACKUP_REMOTE_OFF"
    const val SETTINGS_INVALID = "BACKUP_SETTINGS_INVALID"
}

/** The machine-local settings and the last result, as the row holds them. */
data class BackupSettings(
    val enabled: Boolean = false,
    val dailyAt: String = BackupSchedule.DEFAULT_DAILY_AT,
    val nightly: Boolean = true,
    val onQuit: Boolean = false,
    val quitPending: Boolean = false,
    val lastOkAt: Instant? = null,
    val lastFailedAt: Instant? = null,
    val lastCode: String? = null,
    val lastMessage: String? = null,
    val lastBackupId: String? = null,
    val lastKind: String? = null,
    val lastSizeBytes: Long? = null,
) {
    /** The last attempt failed: its code and words are the last result. */
    val lastAttemptFailed: Boolean get() = lastFailedAt != null && (lastOkAt == null || lastFailedAt.isAfter(lastOkAt))
}

/** `GET /install/cloud/backups`: the Cloud tab's *Off-site backups* section. */
@Serializable
data class BackupStatusDto(
    val enabled: Boolean,
    /** Local time, `HH:mm`. */
    val dailyAt: String,
    /** Back up each night at [dailyAt]. */
    val nightly: Boolean = true,
    /** Back up when *Quit desk* stops the desk. */
    val onQuit: Boolean = false,
    /** When the next scheduled one is due, while the switch and [nightly] are on. */
    val nextAt: String? = null,
    val running: BackupRunDto? = null,
    val lastOkAt: String? = null,
    val lastFailedAt: String? = null,
    /** The last failure's code and words, while the last attempt failed. */
    val lastCode: String? = null,
    val lastMessage: String? = null,
    val lastBackupId: String? = null,
    /** The kind the cloud lists the last backup under: a resumed one keeps the kind it was declared with. */
    val lastKind: String? = null,
    /** The last backup uploaded, encrypted. */
    val lastSizeBytes: Long? = null,
)

/** A backup running now. */
@Serializable
data class BackupRunDto(
    val kind: String,
    /** `WAITING` (a scheduled one waiting for the show to go quiet), `MAKING` or `UPLOADING`. */
    val phase: String,
    val startedAt: String,
    val parts: Int? = null,
    val partsSent: Int? = null,
)

/** A backup refused or failed, with the code the last result records. */
class BackupFailure(val code: String, message: String, val discard: Boolean = false) : Exception(message)

/** When a scheduled backup runs (cloud-p3 plan D6): pure, so a test drives it on its own clock. */
object BackupSchedule {
    const val DEFAULT_DAILY_AT = "04:00"
    val QUIET_CHECK: Duration = Duration.ofMinutes(10)
    val QUIET_CAP: Duration = Duration.ofHours(6)

    private val SHAPE = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

    fun isValidTime(text: String): Boolean = SHAPE.matches(text)

    /** The next [dailyAt] in [zone] after [now]. */
    fun nextDue(now: Instant, dailyAt: String, zone: ZoneId): Instant {
        val time = LocalTime.parse(dailyAt)
        val local = now.atZone(zone)
        var due = local.toLocalDate().atTime(time).atZone(zone)
        if (!due.toInstant().isAfter(now)) due = local.toLocalDate().plusDays(1).atTime(time).atZone(zone)
        return due.toInstant()
    }

    /**
     * Wait while [busy] — a cue stack running, the programmer holding anything — checking every
     * [QUIET_CHECK], for at most [QUIET_CAP] from now; answers whether the show went quiet.
     */
    suspend fun awaitQuiet(busy: () -> Boolean, clock: () -> Instant, sleep: suspend (Duration) -> Unit): Boolean {
        val deadline = clock().plus(QUIET_CAP)
        while (busy()) {
            val now = clock()
            if (!now.isBefore(deadline)) return false
            val left = Duration.between(now, deadline)
            sleep(if (left < QUIET_CHECK) left else QUIET_CHECK)
        }
        return true
    }
}

/**
 * The desk's off-site backups (cloud-p3 plan D5–D8): the machine-local switch, its two schedules — each
 * night at a time, and as *Quit desk* stops the desk — the nightly run and its wait for a quiet show, a
 * backup asked for now (the Cloud tab's button, the cloud's `backup.request`), and the upload in parts
 * with its resume, which a start also runs for a backup left staged.
 *
 * The work runs on one thread of its own, `backup-1`, at the lowest priority, never on the show's or
 * the cloud channel's, and at most one backup runs at a time. It writes the database only when a
 * backup ends and when an admin changes the settings. It needs the cloud only to fetch the data key,
 * before the archive is made, and to upload it, after.
 */
class BackupService(
    private val database: Database,
    private val staging: InstallBackup,
    private val sources: () -> BackupSources,
    /** Who the backup is from, read on the backup thread; null when the desk is not adopted. */
    private val origin: () -> BackupOrigin?,
    private val signer: () -> InstallSigner?,
    private val api: () -> CloudApi?,
    /** A cue stack running, or the programmer holding anything: a scheduled backup waits. */
    private val busy: () -> Boolean = { false },
    private val clock: () -> Instant = ::nowUtc,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val sleep: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    /** The switch moved: what the desk announces changed. */
    private val switchChanged: () -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(BackupService::class.java)

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "backup-1").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher() + CoroutineName("backup"))

    @Volatile private var settings: BackupSettings = runCatching { load() }.getOrElse {
        logger.warn("Couldn't read the backup settings; backups are off", it)
        BackupSettings()
    }
    private val running = AtomicReference<BackupRunDto?>(null)
    private val scheduleLock = Any()
    private var started = false
    private var scheduleJob: Job? = null
    @Volatile private var nextDue: Instant? = null

    private val _status = MutableStateFlow(statusOf())
    val status: StateFlow<BackupStatusDto> = _status.asStateFlow()

    /** The switch, read per heartbeat and per announcement: memory only. */
    val enabled: Boolean get() = settings.enabled

    /** What the heartbeat says of backups (cloud-p3 plan D1): memory only. */
    fun health(): HealthBackup {
        val s = settings
        return HealthBackup(
            enabled = s.enabled,
            lastOkAt = s.lastOkAt?.let(Timestamps::format),
            lastFailedAt = s.lastFailedAt?.let(Timestamps::format),
            code = s.lastCode?.takeIf { s.lastAttemptFailed },
        )
    }

    /**
     * Sweep what a stopped backup left in staging, finish the last quit's backup while the desk backs up
     * on quit, and start the nightly schedule. Idempotent. A staged QUIT backup is resumed, or remade
     * past [RESUME_WINDOW]; a quit that staged nothing leaves [BackupSettings.quitPending] for a fresh one.
     */
    fun start() {
        synchronized(scheduleLock) {
            if (started) return
            started = true
        }
        scope.launch {
            runCatching { staging.sweep() }.onFailure { logger.warn("Couldn't sweep backup staging", it) }
            val left = runCatching { staging.staged() }.getOrNull()
            if (!backsUpOnQuit()) return@launch
            if (left?.kind == BackupKind.QUIT) {
                logger.info("Finishing the backup {} left staged at the last quit", left.backupId)
                claim(BackupKind.QUIT)?.let { execute(it.kind) }
            } else if (settings.quitPending) {
                logger.info("Making the backup the last quit could not")
                claim(BackupKind.QUIT)?.let { execute(it.kind, fresh = true) }
            }
        }
        reschedule()
    }

    private fun backsUpOnQuit(): Boolean = settings.let { it.enabled && it.onQuit } && signer() != null

    /**
     * The backup *Quit desk* waits for, or null when it backs up nothing: always a fresh QUIT archive,
     * made once a backup already making or uploading has ended; a scheduled one only waiting gives way.
     * One that does not finish uploading stays staged for [start] to finish.
     */
    fun startQuitBackup(): Job? {
        if (!backsUpOnQuit()) return null
        markQuitPending()
        claim(BackupKind.QUIT)?.let { claimed -> return scope.launch { execute(claimed.kind, fresh = true) } }
        return scope.launch {
            while (true) {
                status.first { running.get() == null && it.running == null }
                claim(BackupKind.QUIT)?.let { claimed -> return@launch execute(claimed.kind, fresh = true) }
            }
        }
    }

    private fun markQuitPending() {
        synchronized(this) {
            runCatching { write { row -> row[DaoBackupSettingsTable.quitPending] = true } }
                .onFailure { logger.warn("Couldn't record the quit's backup as pending", it) }
            settings = settings.copy(quitPending = true)
        }
    }

    /** Change the switch, the time or the schedules; only the desk's own listener's admin route calls this. */
    fun update(
        enabled: Boolean? = null,
        dailyAt: String? = null,
        nightly: Boolean? = null,
        onQuit: Boolean? = null,
    ): BackupStatusDto {
        if (dailyAt != null && !BackupSchedule.isValidTime(dailyAt)) {
            throw BackupFailure(BackupErrorCodes.SETTINGS_INVALID, "The time must be HH:mm, 00:00 to 23:59.")
        }
        val switched: Boolean
        synchronized(this) {
            val was = settings
            val next = was.copy(
                enabled = enabled ?: was.enabled,
                dailyAt = dailyAt ?: was.dailyAt,
                nightly = nightly ?: was.nightly,
                onQuit = onQuit ?: was.onQuit,
            ).let { it.copy(quitPending = it.quitPending && it.enabled && it.onQuit) }
            write { row ->
                row[DaoBackupSettingsTable.enabled] = next.enabled
                row[DaoBackupSettingsTable.dailyAt] = next.dailyAt
                row[DaoBackupSettingsTable.nightly] = next.nightly
                row[DaoBackupSettingsTable.onQuit] = next.onQuit
                row[DaoBackupSettingsTable.quitPending] = next.quitPending
            }
            settings = next
            switched = was.enabled != next.enabled
        }
        reschedule()
        publish()
        if (switched) switchChanged()
        return status.value
    }

    /**
     * Start a backup of [kind] now unless one runs, answering at once with the run already in [status].
     * A scheduled backup still waiting for a quiet show gives way: the one asked for runs instead.
     * A [BackupKind.REQUESTED] one (the cloud's `backup.request`) is ignored while the switch is off;
     * the desk's own button is not. Answers false when nothing was started for it.
     */
    fun request(kind: String): Boolean {
        if (kind == BackupKind.REQUESTED && !settings.enabled) return false
        val claim = claim(kind) ?: return false
        scope.launch { execute(claim.kind) }
        return true
    }

    /** Make and upload one backup of [kind] (or finish the one staged), unless one runs; answers the status after. */
    suspend fun runOnce(kind: String): BackupStatusDto {
        val claim = claim(kind) ?: return status.value
        execute(claim.kind)
        return status.value
    }

    /** Take [running] for a backup of [kind], from nothing or from a scheduled one only waiting; null when one runs. */
    private fun claim(kind: String): BackupRunDto? {
        val claim = BackupRunDto(kind, PHASE_MAKING, clock().toIsoUtc())
        while (true) {
            val current = running.get()
            if (current != null && current.phase != PHASE_WAITING) return null
            if (running.compareAndSet(current, claim)) break
        }
        publish()
        return claim
    }

    /** Run the backup [running] has been claimed for; it is let go at the end. */
    private suspend fun execute(kind: String, fresh: Boolean = false) {
        try {
            val staged = Tracing.transactionSuspending(OP_MAKE, OP_MAKE) { span ->
                prepare(kind, fresh).also { span?.setData("sizeBytes", it.sizeBytes) }
            }
            setRunning { it.copy(phase = PHASE_UPLOADING, parts = backupPartCount(staged.sizeBytes), partsSent = 0) }
            Tracing.transactionSuspending(OP_UPLOAD, OP_UPLOAD) { span ->
                span?.setData("sizeBytes", staged.sizeBytes)
                span?.setData("parts", backupPartCount(staged.sizeBytes))
                upload(staged)
            }
            runCatching { staging.discard(staged.backupId) }
            recordOk(staged)
            logger.info("Backup {} uploaded: {} bytes in {} parts", staged.backupId, staged.sizeBytes, backupPartCount(staged.sizeBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFailure) {
            logger.warn("Backup failed: {} {}", e.code, e.message)
            if (e.discard) runCatching { staging.staged()?.let { staging.discard(it.backupId) } }
            recordFailure(e.code, e.message ?: e.code)
        } catch (e: Exception) {
            logger.warn("Backup failed", e)
            recordFailure(BackupErrorCodes.FAILED, "The backup failed: ${e.message ?: e::class.simpleName}")
        } finally {
            running.set(null)
            publish()
        }
    }

    /** The staged backup to finish, while it is within [RESUME_WINDOW] and not [fresh]; else a new archive under a fresh key. */
    private suspend fun prepare(kind: String, fresh: Boolean): StagedBackup {
        val signer = signer() ?: throw BackupFailure(CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted, so it has nowhere to back up to.")
        val api = api() ?: throw BackupFailure(CloudErrorCodes.NOT_ADOPTED, "This desk has no cloud configured.")
        staging.staged()?.let { staged ->
            if (!fresh && resumable(staged)) return staged
            staging.discard(staged.backupId)
        }
        val key = api.backupKey(signer).orFail()
        val keyBytes = key.keyBytes() ?: throw BackupFailure(BackupErrorCodes.KEY_INVALID, "The cloud sent a data key this desk can't use.")
        val origin = origin() ?: throw BackupFailure(CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted, so it has nowhere to back up to.")
        val now = clock()
        try {
            return staging.make(sources(), origin, BackupIds.mint(now), kind, keyBytes, key.keyId, now)
        } finally {
            keyBytes.fill(0)
        }
    }

    /** Whether [staged] was declared (or made) within [RESUME_WINDOW], so the cloud still holds its upload. */
    private fun resumable(staged: StagedBackup): Boolean {
        val since = Timestamps.parse(staged.declaredAt ?: staged.madeAt) ?: return false
        return Duration.between(since, clock()) < RESUME_WINDOW
    }

    /** Declare (once), send every part the cloud lacks, then complete; the archive stays staged on a failure. */
    private suspend fun upload(staged: StagedBackup) {
        val signer = signer() ?: throw BackupFailure(CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted, so it has nowhere to back up to.")
        val api = api() ?: throw BackupFailure(CloudErrorCodes.NOT_ADOPTED, "This desk has no cloud configured.")
        val parts = backupPartCount(staged.sizeBytes)
        val received: Set<Int> = if (staged.declaredAt == null) {
            val request = DeclareBackupRequest(staged.backupId, staged.kind, staged.sizeBytes, parts, staged.sha256, staged.keyId, staged.manifest)
            when (val declared = api.declareBackup(signer, request)) {
                is CloudCall.Ok -> declared.value.partsReceived.toSet()
                is CloudCall.Refused if declared.error?.code == ErrorCode.BACKUP_ID_TAKEN -> existingParts(api, signer, staged) ?: return
                else -> declared.orFail().partsReceived.toSet()
            }.also { staging.record(staged.copy(declaredAt = Timestamps.format(clock()))) }
        } else {
            existingParts(api, signer, staged) ?: return
        }
        var sent = received.size
        setRunning { it.copy(partsSent = sent) }
        for (n in 1..parts) {
            if (n in received) continue
            val offset = (n - 1).toLong() * BACKUP_PART_BYTES
            val length = minOf(BACKUP_PART_BYTES.toLong(), staged.sizeBytes - offset).coerceAtLeast(0)
            sendPart(api, signer, staged.backupId, n, staging.archiveOf(staged.backupId), offset, length)
            sent++
            setRunning { it.copy(partsSent = sent) }
        }
        api.completeBackup(signer, staged.backupId).orFail()
    }

    /** The parts the cloud holds of [staged], or null when it already has it whole. */
    private suspend fun existingParts(api: CloudApi, signer: InstallSigner, staged: StagedBackup): Set<Int>? {
        val dto = when (val call = api.backup(signer, staged.backupId)) {
            is CloudCall.Refused if call.error?.code == ErrorCode.BACKUP_NOT_FOUND ->
                throw BackupFailure(ErrorCode.BACKUP_NOT_FOUND, "The cloud no longer has this backup's upload; the next one starts afresh.", discard = true)
            else -> call.orFail()
        }
        if (dto.sha256 != staged.sha256) {
            throw BackupFailure(ErrorCode.BACKUP_ID_TAKEN, "The cloud holds another backup under this one's id; the next one starts afresh.", discard = true)
        }
        return if (dto.status == BackupStatus.COMPLETE) null else dto.partsReceived.toSet()
    }

    /** One part, tried again [PART_RETRIES] times with backoff when the cloud did not answer or failed. */
    private suspend fun sendPart(api: CloudApi, signer: InstallSigner, backupId: String, n: Int, file: Path, offset: Long, length: Long) {
        var backoff = PART_BACKOFF
        repeat(PART_RETRIES + 1) { attempt ->
            val call = api.uploadBackupPart(signer, backupId, n, file, offset, length)
            val retriable = call is CloudCall.Unreachable || (call is CloudCall.Refused && call.status >= 500)
            if (call is CloudCall.Ok) return
            if (!retriable || attempt == PART_RETRIES) call.orFail()
            sleep(backoff)
            backoff = backoff.multipliedBy(2)
        }
    }

    private fun <T> CloudCall<T>.orFail(): T = when (this) {
        is CloudCall.Ok -> value
        is CloudCall.Refused -> {
            val code = error?.code ?: CloudErrorCodes.REFUSED
            throw BackupFailure(code, error?.message ?: "The cloud refused the backup ($status).", discard = code in DISCARDING)
        }
        is CloudCall.Unreachable -> throw BackupFailure(CloudErrorCodes.UNREACHABLE, "The cloud didn't answer: $message")
    }

    /** Replace the schedule loop. A backup it started runs on its own job, so this never cancels one. */
    private fun reschedule() {
        synchronized(scheduleLock) {
            if (!started) return
            scheduleJob?.cancel()
            scheduleJob = scope.launch {
                while (true) {
                    val s = settings
                    if (!s.enabled || !s.nightly) {
                        nextDue = null
                        publish()
                        return@launch
                    }
                    val due = BackupSchedule.nextDue(clock(), s.dailyAt, zone())
                    nextDue = due
                    publish()
                    val wait = Duration.between(clock(), due)
                    if (!wait.isNegative) sleep(wait)
                    scope.launch { runScheduled() }.join()
                }
            }
        }
    }

    /**
     * Tonight's backup, while the switch is on: once the show is quiet, or after [BackupSchedule.QUIET_CAP]
     * anyway. A backup asked for while it waits runs instead, and ends tonight's wait.
     */
    internal suspend fun runScheduled() {
        if (!nightlyOn) return
        if (busy()) {
            val waiting = BackupRunDto(BackupKind.SCHEDULED, PHASE_WAITING, clock().toIsoUtc())
            if (!running.compareAndSet(null, waiting)) return
            publish()
            val quiet = try {
                BackupSchedule.awaitQuiet({ running.get() === waiting && nightlyOn && busy() }, clock, sleep)
            } catch (e: CancellationException) {
                if (running.compareAndSet(waiting, null)) publish()
                throw e
            }
            if (running.get() !== waiting) return
            if (!nightlyOn) {
                if (running.compareAndSet(waiting, null)) publish()
                return
            }
            if (!quiet) logger.info("The show stayed busy for {}; backing up anyway", BackupSchedule.QUIET_CAP)
            val claim = BackupRunDto(BackupKind.SCHEDULED, PHASE_MAKING, clock().toIsoUtc())
            if (!running.compareAndSet(waiting, claim)) return
            publish()
            execute(claim.kind)
        } else {
            val claim = BackupRunDto(BackupKind.SCHEDULED, PHASE_MAKING, clock().toIsoUtc())
            if (!running.compareAndSet(null, claim)) return
            publish()
            execute(claim.kind)
        }
    }

    private val nightlyOn: Boolean get() = settings.let { it.enabled && it.nightly }

    private fun recordOk(staged: StagedBackup) {
        val now = clock()
        writeResult {
            it.copy(
                lastOkAt = now,
                lastCode = null,
                lastMessage = null,
                lastBackupId = staged.backupId,
                lastKind = staged.kind,
                lastSizeBytes = staged.sizeBytes,
                quitPending = it.quitPending && staged.kind != BackupKind.QUIT,
            )
        }
    }

    private fun recordFailure(code: String, message: String) {
        val now = clock()
        writeResult { it.copy(lastFailedAt = now, lastCode = code, lastMessage = message.take(500)) }
    }

    /** Record a result: [change] sets only result fields, applied to the settings as they are now. */
    private fun writeResult(change: (BackupSettings) -> BackupSettings) {
        synchronized(this) {
            val next = change(settings)
            runCatching {
                write { row ->
                    row[DaoBackupSettingsTable.lastOkAt] = next.lastOkAt
                    row[DaoBackupSettingsTable.lastFailedAt] = next.lastFailedAt
                    row[DaoBackupSettingsTable.lastCode] = next.lastCode
                    row[DaoBackupSettingsTable.lastMessage] = next.lastMessage
                    row[DaoBackupSettingsTable.lastBackupId] = next.lastBackupId
                    row[DaoBackupSettingsTable.lastKind] = next.lastKind
                    row[DaoBackupSettingsTable.lastSizeBytes] = next.lastSizeBytes
                    row[DaoBackupSettingsTable.quitPending] = next.quitPending
                }
            }.onFailure { logger.warn("Couldn't record the backup's result", it) }
            settings = next
        }
    }

    private fun write(set: (UpdateBuilder<*>) -> Unit) {
        transaction(database) {
            val exists = DaoBackupSettingsTable.selectAll().limit(1).any()
            if (exists) {
                DaoBackupSettingsTable.update {
                    set(it)
                    it[updatedAt] = nowUtc()
                }
            } else {
                DaoBackupSettingsTable.insert {
                    set(it)
                    it[updatedAt] = nowUtc()
                }
            }
        }
    }

    private fun load(): BackupSettings = transaction(database) {
        DaoBackupSettingsTable.selectAll().limit(1).firstOrNull()?.let {
            BackupSettings(
                enabled = it[DaoBackupSettingsTable.enabled],
                dailyAt = it[DaoBackupSettingsTable.dailyAt],
                nightly = it[DaoBackupSettingsTable.nightly],
                onQuit = it[DaoBackupSettingsTable.onQuit],
                quitPending = it[DaoBackupSettingsTable.quitPending],
                lastOkAt = it[DaoBackupSettingsTable.lastOkAt],
                lastFailedAt = it[DaoBackupSettingsTable.lastFailedAt],
                lastCode = it[DaoBackupSettingsTable.lastCode],
                lastMessage = it[DaoBackupSettingsTable.lastMessage],
                lastBackupId = it[DaoBackupSettingsTable.lastBackupId],
                lastKind = it[DaoBackupSettingsTable.lastKind],
                lastSizeBytes = it[DaoBackupSettingsTable.lastSizeBytes],
            )
        } ?: BackupSettings()
    }

    private fun setRunning(change: (BackupRunDto) -> BackupRunDto) {
        running.updateAndGet { it?.let(change) }
        publish()
    }

    private fun publish() {
        _status.value = statusOf()
    }

    private fun statusOf(): BackupStatusDto {
        val s = settings
        val failing = s.lastAttemptFailed
        return BackupStatusDto(
            enabled = s.enabled,
            dailyAt = s.dailyAt,
            nightly = s.nightly,
            onQuit = s.onQuit,
            nextAt = nextDue?.takeIf { s.enabled && s.nightly }?.toIsoUtc(),
            running = running.get(),
            lastOkAt = s.lastOkAt?.toIsoUtc(),
            lastFailedAt = s.lastFailedAt?.toIsoUtc(),
            lastCode = s.lastCode?.takeIf { failing },
            lastMessage = s.lastMessage?.takeIf { failing },
            lastBackupId = s.lastBackupId,
            lastKind = s.lastKind,
            lastSizeBytes = s.lastSizeBytes,
        )
    }

    fun close() {
        scope.cancel()
        executor.shutdownNow()
    }

    companion object {
        const val PHASE_WAITING = "WAITING"
        const val PHASE_MAKING = "MAKING"
        const val PHASE_UPLOADING = "UPLOADING"

        const val OP_MAKE = "backup.make"
        const val OP_UPLOAD = "backup.upload"

        /** A staged backup is finished within this of its declare, inside the cloud's 24-hour abort (D8). */
        val RESUME_WINDOW: Duration = Duration.ofHours(20)

        const val PART_RETRIES = 3
        val PART_BACKOFF: Duration = Duration.ofSeconds(2)

        /** Refusals no resume can get past: the staged archive goes. */
        private val DISCARDING = setOf(ErrorCode.BACKUP_TOO_LARGE, ErrorCode.BACKUP_NOT_FOUND, ErrorCode.BACKUP_KEY_UNKNOWN)
    }
}
