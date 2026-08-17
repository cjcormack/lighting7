package uk.me.cormack.lighting7.update

import kotlinx.serialization.Serializable

/** Whether this desk is even eligible to self-update, and why not when it isn't. */
@Serializable
enum class UpdateChannelKind {
    /** A released build, running from a jpackage installer, on Windows. The only updatable case. */
    PACKAGED_WINDOWS,

    /** A `./gradlew run` session or a hand-built installer. Never offers an update. */
    DEV,

    /** A release install on macOS or Linux: the version is reported, but apply is Windows-only. */
    UNSUPPORTED_PLATFORM,
}

@Serializable
enum class UpdatePhase {
    IDLE,
    CHECKING,
    UPDATE_AVAILABLE,
    DOWNLOADING,

    /** A verified installer is on disk. Nothing has been handed to the launcher yet. */
    READY_TO_APPLY,

    /** The marker is written; the launcher is about to stop everything. Terminal for this process. */
    APPLY_REQUESTED,

    FAILED,
}

@Serializable
enum class UpdateErrorCode {
    NETWORK,
    RATE_LIMITED,
    NO_ASSET,
    NO_CHECKSUM,
    CHECKSUM_MISMATCH,
    INSUFFICIENT_DISK,
    DOWNLOAD_FAILED,
    WRITE_FAILED,

    /** The launcher refused the marker. The desk is still on the old version, intact. */
    APPLY_REJECTED,

    /**
     * The installer reported success but the version did not change.
     *
     * The backstop against a desk that reinstalls the same update forever — the failure mode
     * a version-identity mismatch would otherwise produce, silently and on every boot.
     */
    NO_VERSION_CHANGE,

    UNSUPPORTED_PLATFORM,
    DEV_BUILD,
}

@Serializable
data class UpdateErrorDto(val code: UpdateErrorCode, val message: String)

@Serializable
data class UpdateReleaseDto(
    val tag: String,
    val version: String,
    val name: String? = null,
    /** Rendered as plain text by the frontend — this is untrusted text from the internet. */
    val notes: String? = null,
    val publishedAtMs: Long? = null,
    val htmlUrl: String,
    val assetName: String? = null,
    val assetSizeBytes: Long? = null,
)

/**
 * What the desk is doing right now, so the confirm dialog can say what stopping it would cost.
 *
 * Deliberately coarse: a count and a name, never the cue contents. This rides on a response an
 * operator can read.
 */
@Serializable
data class LiveHintDto(
    val showReady: Boolean,
    val activeStackName: String? = null,
    val activeEffectCount: Int = 0,
)

@Serializable
data class ApplyOutcomeDto(
    val targetVersion: String,
    val succeeded: Boolean,
    val msiExitCode: Int? = null,
    val finishedAtMs: Long? = null,
    val message: String,
)

@Serializable
data class UpdateStatusDto(
    val channel: UpdateChannelKind,
    val currentVersion: String,
    val currentCommit: String? = null,
    val phase: UpdatePhase,
    val availability: UpdateAvailability,
    val latest: UpdateReleaseDto? = null,
    val lastCheckedAtMs: Long? = null,
    val lastCheckError: String? = null,
    val autoCheckEnabled: Boolean,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    /** Non-null only when a *verified* installer is staged on disk. */
    val stagedVersion: String? = null,
    val error: UpdateErrorDto? = null,
    val lastApplyOutcome: ApplyOutcomeDto? = null,
    val live: LiveHintDto,
    /** True when a manual check was collapsed into the cached result. Not an error. */
    val throttled: Boolean = false,
)

@Serializable
data class ApplyUpdateRequest(
    /**
     * The version the user actually saw and agreed to. A tab left open across a newer check must
     * not be able to apply something its owner never read the notes for.
     */
    val confirmVersion: String,
)

@Serializable
data class UpdateSettingsRequest(val autoCheckEnabled: Boolean)
