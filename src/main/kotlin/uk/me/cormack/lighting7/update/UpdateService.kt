package uk.me.cormack.lighting7.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

private val logger = LoggerFactory.getLogger("UpdateService")

/** Persisted so a fresh boot can answer `GET /update/status` with no network call at all. */
@Serializable
internal data class UpdateCache(
    val tag: String? = null,
    val etag: String? = null,
    val fetchedAtMs: Long = 0,
    val htmlUrl: String? = null,
    val name: String? = null,
    val notes: String? = null,
    val publishedAtMs: Long? = null,
    val assetName: String? = null,
    val assetSizeBytes: Long? = null,
    val assetUrl: String? = null,
    val checksumUrl: String? = null,
    val rateLimitResetAtMs: Long? = null,
    /** Versions whose install already failed once — don't keep nagging about them. */
    val failedVersions: Set<String> = emptySet(),
)

/**
 * Owns the update state machine: checking GitHub, staging a verified installer, and handing it
 * to the launcher.
 *
 * The *check* lives here rather than in the launcher for one decisive reason: `java.base` has no
 * JSON parser, and hand-rolling one to read GitHub's release payload inside the zero-dependency
 * module is exactly the cost that module exists to avoid. The launcher's job is narrowed to the
 * single thing only it can do — outlive the backend and run `msiexec`.
 */
class UpdateService(
    private val dataDir: Path,
    private val buildInfo: BuildInfo,
    private val installKind: InstallKind,
    private val repo: String,
    apiBase: String,
    private val enabled: Boolean,
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win"),
    private val autoCheckEnabledProvider: () -> Boolean = { true },
    private val onStateChanged: (UpdateService) -> Unit = {},
    private val liveHintProvider: () -> LiveHintDto = { LiveHintDto(showReady = false) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val releaseClientFactory: (String, String) -> GitHubReleaseClient = ::GitHubReleaseClient,
) : Closeable {

    // Lazy so a desk that never checks (dev build, non-Windows) never builds a CIO engine.
    // `close()` therefore has to consult the delegate rather than the property, or shutting down
    // such a desk would construct an HttpClient purely in order to close it.
    private val clientDelegate = lazy { releaseClientFactory(repo, apiBase) }
    private val client by clientDelegate
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Serialises check / download / apply. Every mutation of [phase] happens under it. */
    private val mutex = Mutex()

    private val phase = AtomicReference(UpdatePhase.IDLE)
    private val error = AtomicReference<UpdateErrorDto?>(null)
    private val downloaded = AtomicReference(0L)
    private val total = AtomicReference<Long?>(null)

    // `@Volatile`, not plain vars: all four are written under [mutex] but read from `status()`,
    // which every route calls without taking it (including the frontend's own polling GET, on a
    // different worker thread). Without volatile there is no happens-before edge for those reads,
    // so a reader could observe a stale `downloadJob` — and a `cancelDownload` that saw null there
    // would silently leave the download running and later "resurrect" a cancelled update.
    @Volatile
    private var cache: UpdateCache = UpdateCache()

    @Volatile
    private var lastManualCheckMs: Long = 0

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var applyOutcome: ApplyOutcomeDto? = null

    /**
     * Eligibility. **Both** gates, always.
     *
     * Without the channel gate a locally built `./gradlew packageWindows` MSI is a genuinely
     * packaged install and would immediately offer to msiexec over itself; without the install
     * gate, a `./gradlew run` session against a release-channel resource would do the same.
     */
    val channelKind: UpdateChannelKind = when {
        buildInfo.channel != BuildInfo.Channel.RELEASE -> UpdateChannelKind.DEV
        installKind != InstallKind.PACKAGED -> UpdateChannelKind.DEV
        !isWindows -> UpdateChannelKind.UNSUPPORTED_PLATFORM
        else -> UpdateChannelKind.PACKAGED_WINDOWS
    }

    private val updatable: Boolean get() = enabled && channelKind == UpdateChannelKind.PACKAGED_WINDOWS

    init {
        runCatching { loadCache() }.onFailure { logger.warn("Could not load the update cache", it) }
        runCatching { reconcilePreviousApply() }.onFailure { logger.warn("Could not reconcile the last apply", it) }
        runCatching { sweepStagedFiles() }.onFailure { logger.warn("Could not sweep staged installers", it) }
    }

    // ─── Status ─────────────────────────────────────────────────────────

    /**
     * [throttled] is a parameter rather than consumable shared state. It was briefly an
     * AtomicReference that `status()` cleared with `getAndSet(false)`, but `status()` is called
     * unsynchronised from every route — including the panel's own polling GET on another worker
     * thread — so a concurrent poll could consume the flag and the manual check that set it would
     * report `throttled = false`. It is a property of one response, so it travels with it.
     */
    fun status(throttled: Boolean = false): UpdateStatusDto {
        val staged = stagedInstaller()
        return UpdateStatusDto(
            channel = channelKind,
            currentVersion = buildInfo.version,
            currentCommit = buildInfo.shortSha,
            phase = phase.get(),
            availability = availability(),
            latest = cache.tag?.let { tag ->
                UpdateReleaseDto(
                    tag = tag,
                    version = SemVer.parse(tag)?.toString() ?: tag,
                    name = cache.name,
                    notes = cache.notes,
                    publishedAtMs = cache.publishedAtMs,
                    htmlUrl = cache.htmlUrl ?: "https://github.com/$repo/releases",
                    assetName = cache.assetName,
                    assetSizeBytes = cache.assetSizeBytes,
                )
            },
            lastCheckedAtMs = cache.fetchedAtMs.takeIf { it > 0 },
            lastCheckError = error.get()?.takeIf { it.code == UpdateErrorCode.NETWORK || it.code == UpdateErrorCode.RATE_LIMITED }?.message,
            autoCheckEnabled = autoCheckEnabledProvider(),
            downloadedBytes = downloaded.get(),
            totalBytes = total.get(),
            stagedVersion = staged?.second,
            error = error.get(),
            lastApplyOutcome = applyOutcome,
            live = runCatching { liveHintProvider() }.getOrElse { LiveHintDto(showReady = false) },
            throttled = throttled,
        )
    }

    private fun availability(): UpdateAvailability = when {
        !updatable -> UpdateAvailability.UNKNOWN
        cache.tag == null -> UpdateAvailability.UNKNOWN
        // A version whose install already failed is not offered again automatically. The user can
        // still retry explicitly; what this prevents is a permanent banner for a broken release.
        cache.tag in cache.failedVersions -> UpdateAvailability.UP_TO_DATE
        else -> compareVersions(buildInfo.version, cache.tag)
    }

    // ─── Checking ───────────────────────────────────────────────────────

    /** Automatic check, some time after boot. Silent about everything the user didn't ask for. */
    fun scheduleAutomaticChecks() {
        if (!updatable) {
            logger.info("Update checks disabled: channel=$channelKind, enabled=$enabled")
            return
        }
        scope.launch {
            // Never during boot. The desk is compiling FX scripts and loading the patch; the last
            // thing it needs is to also be pulling on the network.
            kotlinx.coroutines.delay(INITIAL_CHECK_DELAY_MS)
            while (true) {
                if (autoCheckEnabledProvider()) {
                    runCatching { check(manual = false) }
                        .onFailure { if (it is CancellationException) throw it }
                }
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun check(manual: Boolean): UpdateStatusDto {
        if (!updatable) return status()

        mutex.withLock {
            val now = clock()

            if (manual) {
                // A throttled manual check returns the cached answer with a flag, not a 429: the
                // user pressed a button and got an answer, which is not an error condition and
                // must not fire the frontend's error-toast middleware.
                if (now - lastManualCheckMs < MANUAL_CHECK_FLOOR_MS) {
                    return status(throttled = true)
                }
                lastManualCheckMs = now
            } else {
                // Respect a rate-limit reset we were told about, rather than re-learning it.
                cache.rateLimitResetAtMs?.let { if (now < it) return status() }
            }

            phase.set(UpdatePhase.CHECKING)
            error.set(null)
            notifyChanged()

            when (val result = client.fetchLatestRelease(cache.etag)) {
                is ReleaseCheckResult.Found -> {
                    val release = result.release
                    cache = cache.copy(
                        tag = release.tag,
                        etag = result.etag,
                        fetchedAtMs = now,
                        htmlUrl = release.htmlUrl,
                        name = release.name,
                        notes = release.notes,
                        publishedAtMs = release.publishedAtMs,
                        assetName = release.installer?.name,
                        assetSizeBytes = release.installer?.sizeBytes,
                        assetUrl = release.installer?.downloadUrl,
                        checksumUrl = release.checksum?.downloadUrl,
                        rateLimitResetAtMs = null,
                    )
                    persistCache()
                    phase.set(phaseAfterCheck())
                }
                ReleaseCheckResult.NotModified -> {
                    cache = cache.copy(fetchedAtMs = now, rateLimitResetAtMs = null)
                    persistCache()
                    phase.set(phaseAfterCheck())
                }
                ReleaseCheckResult.NoRelease -> {
                    // No published release yet is a perfectly ordinary state, not a failure.
                    cache = cache.copy(fetchedAtMs = now, tag = null, rateLimitResetAtMs = null)
                    persistCache()
                    phase.set(UpdatePhase.IDLE)
                }
                is ReleaseCheckResult.Failed -> {
                    error.set(UpdateErrorDto(result.code, result.message))
                    if (result.code == UpdateErrorCode.RATE_LIMITED) {
                        cache = cache.copy(
                            rateLimitResetAtMs = result.retryAfterMs?.let { now + it }
                                ?: (now + DEFAULT_RATE_LIMIT_BACKOFF_MS),
                        )
                        persistCache()
                    }
                    phase.set(phaseAfterCheck())
                }
            }
            notifyChanged()
            return status()
        }
    }

    private fun phaseAfterCheck(): UpdatePhase = when {
        stagedInstaller() != null -> UpdatePhase.READY_TO_APPLY
        availability() == UpdateAvailability.UPDATE_AVAILABLE -> UpdatePhase.UPDATE_AVAILABLE
        else -> UpdatePhase.IDLE
    }

    // ─── Downloading ────────────────────────────────────────────────────

    sealed interface StartResult {
        data object Started : StartResult
        data class Conflict(val code: String, val message: String) : StartResult
    }

    suspend fun startDownload(): StartResult = mutex.withLock {
        if (!updatable) return StartResult.Conflict("NOT_UPDATABLE", "This build cannot update itself.")
        if (phase.get() == UpdatePhase.DOWNLOADING) {
            return StartResult.Conflict("DOWNLOAD_IN_PROGRESS", "A download is already running.")
        }
        if (phase.get() == UpdatePhase.APPLY_REQUESTED) {
            return StartResult.Conflict("APPLY_ALREADY_REQUESTED", "An update is already being installed.")
        }
        if (availability() != UpdateAvailability.UPDATE_AVAILABLE) {
            return StartResult.Conflict("NOTHING_TO_DOWNLOAD", "There is no newer release to download.")
        }
        val assetUrl = cache.assetUrl
            ?: return StartResult.Conflict("NOTHING_TO_DOWNLOAD", "That release has no Windows installer attached.")

        phase.set(UpdatePhase.DOWNLOADING)
        error.set(null)
        downloaded.set(0)
        total.set(cache.assetSizeBytes)
        notifyChanged()

        downloadJob = scope.launch { runDownload(assetUrl) }
        StartResult.Started
    }

    suspend fun cancelDownload() {
        downloadJob?.let { runCatching { it.cancelAndJoin() } }
        downloadJob = null
        mutex.withLock {
            if (phase.get() == UpdatePhase.DOWNLOADING) {
                phase.set(phaseAfterCheck())
                downloaded.set(0)
                notifyChanged()
            }
        }
    }

    private suspend fun runDownload(assetUrl: String) {
        val assetName = cache.assetName ?: "lighting7-update.msi"
        val staged = UpdateMarkerWriter.stagedDir(dataDir)
        val partFile = staged.resolve("$assetName.part")
        val finalFile = staged.resolve(assetName)

        try {
            Files.createDirectories(staged)

            // Windows Installer caches the package under C:\Windows\Installer during the install,
            // so the peak requirement is roughly twice the download.
            val required = (cache.assetSizeBytes ?: 0) * 2
            val usable = runCatching { Files.getFileStore(staged).usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (required > 0 && usable < required) {
                fail(UpdateErrorCode.INSUFFICIENT_DISK, "Not enough free disk space: the update needs about ${required / 1_000_000} MB.")
                return
            }

            // Fetch the checksum FIRST. Downloading hundreds of megabytes only to discover there
            // is nothing to verify it against would be the wrong order.
            val checksumUrl = cache.checksumUrl
            if (checksumUrl == null) {
                fail(UpdateErrorCode.NO_CHECKSUM, "That release has no checksum, so the download can't be verified.")
                return
            }
            val expectedDigest = client.fetchTextAsset(checksumUrl)?.let(::parseSha256Asset)
            if (expectedDigest == null) {
                fail(UpdateErrorCode.NO_CHECKSUM, "Could not read the release checksum.")
                return
            }

            runCatching { Files.deleteIfExists(partFile) }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            val outcome = Files.newOutputStream(partFile).use { out ->
                client.streamAsset(assetUrl) { buffer, length ->
                    out.write(buffer, 0, length)
                    // One pass: hash as the bytes go by rather than re-reading the finished file.
                    digest.update(buffer, 0, length)
                    written += length
                    downloaded.set(written)
                    maybeNotifyProgress(written)
                }
            }

            if (outcome is DownloadOutcome.Failed) {
                runCatching { Files.deleteIfExists(partFile) }
                fail(outcome.code, outcome.message)
                return
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedDigest, ignoreCase = true)) {
                // Nothing is ever renamed on a mismatch, which is what makes "a non-.part file in
                // staged/ is verified" true by construction rather than by convention.
                runCatching { Files.deleteIfExists(partFile) }
                fail(UpdateErrorCode.CHECKSUM_MISMATCH, "The downloaded installer failed its checksum and was discarded.")
                return
            }

            Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            downloaded.set(written)
            phase.set(UpdatePhase.READY_TO_APPLY)
            error.set(null)
            notifyChanged()
            logger.info("Staged verified installer at $finalFile")
        } catch (e: CancellationException) {
            runCatching { Files.deleteIfExists(partFile) }
            throw e
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(partFile) }
            logger.warn("Update download failed", e)
            fail(UpdateErrorCode.DOWNLOAD_FAILED, "Download failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun fail(code: UpdateErrorCode, message: String) {
        error.set(UpdateErrorDto(code, message))
        phase.set(UpdatePhase.FAILED)
        notifyChanged()
    }

    // ─── Applying ───────────────────────────────────────────────────────

    suspend fun requestApply(confirmVersion: String): StartResult = mutex.withLock {
        if (!updatable) return StartResult.Conflict("NOT_UPDATABLE", "This build cannot update itself.")
        if (phase.get() == UpdatePhase.APPLY_REQUESTED) {
            return StartResult.Conflict("APPLY_ALREADY_REQUESTED", "An update is already being installed.")
        }
        val staged = stagedInstaller()
            ?: return StartResult.Conflict("NOTHING_STAGED", "No verified installer is ready to apply.")
        val (msiPath, stagedVersion) = staged

        // The version the user actually read the notes for. A tab left open across a newer check
        // must not be able to install something its owner never saw.
        if (!confirmVersion.equals(stagedVersion, ignoreCase = true)) {
            return StartResult.Conflict(
                "VERSION_MISMATCH",
                "This page is showing $confirmVersion but $stagedVersion is what's staged. Reload and try again.",
            )
        }

        return try {
            val bytes = Files.size(msiPath)
            val sha = sha256Of(msiPath)
            UpdateMarkerWriter.write(
                dataDir = dataDir,
                targetVersion = stagedVersion,
                tag = cache.tag ?: "v$stagedVersion",
                msiPath = msiPath,
                sha256 = sha,
                sizeBytes = bytes,
                nowMs = clock(),
            )
            phase.set(UpdatePhase.APPLY_REQUESTED)
            error.set(null)
            notifyChanged()
            logger.info("Requested apply of $stagedVersion; the launcher will take over within a second.")
            StartResult.Started
        } catch (e: Exception) {
            logger.error("Could not write the update marker", e)
            fail(UpdateErrorCode.WRITE_FAILED, "Could not stage the update request: ${e.message}")
            StartResult.Conflict("WRITE_FAILED", "Could not stage the update request.")
        }
    }

    // ─── Boot-time reconciliation ───────────────────────────────────────

    /**
     * Work out what happened to the apply that ran before this process started.
     *
     * The NO_VERSION_CHANGE branch is the backstop against the worst failure this feature can
     * have: if the installed version doesn't actually change, an "update available" banner would
     * otherwise reappear on every boot forever. Converting that into a single legible failure is
     * the difference between a bug and an unfixable loop.
     */
    private fun reconcilePreviousApply() {
        val result = UpdateMarkerWriter.readResult(dataDir) ?: return

        applyOutcome = when {
            result.installerSucceeded && result.targetVersion == buildInfo.version -> {
                sweepStagedFiles(force = true)
                ApplyOutcomeDto(
                    targetVersion = result.targetVersion,
                    succeeded = true,
                    msiExitCode = result.exitCode,
                    finishedAtMs = result.finishedAtMs,
                    message = if (result.exitCode == UpdateMarkerWriter.ApplyResult.REBOOT_REQUIRED) {
                        "Updated to ${result.targetVersion}. Windows would like a restart when convenient."
                    } else {
                        "Updated to ${result.targetVersion}."
                    },
                )
            }
            result.installerSucceeded -> {
                rememberFailedVersion(result.targetVersion)
                ApplyOutcomeDto(
                    targetVersion = result.targetVersion,
                    succeeded = false,
                    msiExitCode = result.exitCode,
                    finishedAtMs = result.finishedAtMs,
                    message = "The installer reported success but this is still ${buildInfo.version}. " +
                        "The ${result.targetVersion} update did not take effect.",
                )
            }
            else -> {
                rememberFailedVersion(result.targetVersion)
                ApplyOutcomeDto(
                    targetVersion = result.targetVersion,
                    succeeded = false,
                    msiExitCode = result.exitCode,
                    finishedAtMs = result.finishedAtMs,
                    message = "The ${result.targetVersion} update did not install " +
                        "(msiexec exit ${result.exitCode}). You're still on ${buildInfo.version}.",
                )
            }
        }

        UpdateMarkerWriter.clearResult(dataDir)
    }

    private fun rememberFailedVersion(version: String) {
        if (version.isBlank()) return
        // Keep the staged MSI: a retry shouldn't re-download hundreds of megabytes.
        cache = cache.copy(failedVersions = cache.failedVersions + version + "v$version")
        runCatching { persistCache() }
    }

    // ─── Staging helpers ────────────────────────────────────────────────

    /** The staged installer and its version, or null. Only ever a *verified* file — see the download. */
    private fun stagedInstaller(): Pair<Path, String>? {
        val dir = UpdateMarkerWriter.stagedDir(dataDir)
        if (!Files.isDirectory(dir)) return null
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".msi", ignoreCase = true) }
                .map { path -> versionOfStagedFile(path)?.let { path to it } }
                .filter { it != null }
                .findFirst()
                .orElse(null)
        }
    }

    private fun versionOfStagedFile(path: Path): String? =
        Regex("""lighting7-(\d+\.\d+\.\d+)""").find(path.fileName.toString())?.groupValues?.get(1)

    private fun sweepStagedFiles(force: Boolean = false) {
        val dir = UpdateMarkerWriter.stagedDir(dataDir)
        if (!Files.isDirectory(dir)) return
        val current = SemVer.parse(buildInfo.version)
        val cutoff = clock() - STAGED_RETENTION_MS

        Files.list(dir).use { stream ->
            stream.forEach { path ->
                val name = path.fileName.toString()
                val staleAge = runCatching { Files.getLastModifiedTime(path).toMillis() < cutoff }.getOrDefault(false)
                // A .part file is by definition an interrupted download — never resumable, since
                // the digest was computed over a stream we no longer have.
                val superseded = versionOfStagedFile(path)
                    ?.let { SemVer.parse(it) }
                    ?.let { staged -> current != null && staged <= current }
                    ?: false

                if (force || name.endsWith(".part") || staleAge || superseded) {
                    runCatching { Files.deleteIfExists(path) }
                        .onSuccess { if (it) logger.info("Swept staged update file $name") }
                }
            }
        }
    }

    private fun sha256Of(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ─── Cache persistence ──────────────────────────────────────────────

    private fun cachePath(): Path = UpdateMarkerWriter.updatesDir(dataDir).resolve("last-check.json")

    private fun loadCache() {
        val path = cachePath()
        if (!Files.exists(path)) return
        cache = runCatching { json.decodeFromString<UpdateCache>(Files.readString(path)) }
            .getOrElse {
                logger.warn("Discarding an unreadable update cache at $path")
                UpdateCache()
            }
        if (cache.tag != null) phase.set(phaseAfterCheck())
    }

    private fun persistCache() {
        runCatching {
            Files.createDirectories(UpdateMarkerWriter.updatesDir(dataDir))
            Files.writeString(cachePath(), json.encodeToString(cache))
        }.onFailure { logger.warn("Could not persist the update cache", it) }
    }

    // ─── Progress notification ──────────────────────────────────────────

    private var lastProgressNotifyMs = 0L
    private var lastProgressPercent = -1

    /**
     * Throttled so a fast download can't spam the machine socket. A dropped progress tick is
     * harmless — the next one supersedes it — which is exactly why phase transitions notify
     * unconditionally and only progress goes through here.
     */
    private fun maybeNotifyProgress(written: Long) {
        val now = clock()
        val totalBytes = total.get()
        val percent = totalBytes?.takeIf { it > 0 }?.let { ((written * 100) / it).toInt() } ?: -1
        if (now - lastProgressNotifyMs < PROGRESS_INTERVAL_MS && percent == lastProgressPercent) return
        lastProgressNotifyMs = now
        lastProgressPercent = percent
        notifyChanged()
    }

    private fun notifyChanged() {
        runCatching { onStateChanged(this) }
            .onFailure { logger.warn("Update state listener threw", it) }
    }

    val currentPhase: UpdatePhase get() = phase.get()
    val currentAvailability: UpdateAvailability get() = availability()
    val latestVersion: String? get() = cache.tag
    val downloadedBytes: Long get() = downloaded.get()
    val totalBytes: Long? get() = total.get()

    override fun close() {
        runCatching { scope.cancel() }
        if (clientDelegate.isInitialized()) runCatching { client.close() }
    }

    companion object {
        /** Long enough to be clear of boot: the desk is compiling scripts and loading the patch. */
        internal const val INITIAL_CHECK_DELAY_MS = 60_000L
        internal const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        internal const val MANUAL_CHECK_FLOOR_MS = 30_000L
        internal const val DEFAULT_RATE_LIMIT_BACKOFF_MS = 60 * 60 * 1000L
        internal const val STAGED_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        internal const val PROGRESS_INTERVAL_MS = 500L
    }
}
