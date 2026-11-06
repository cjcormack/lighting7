package uk.me.cormack.lighting7.cloud

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * What `GET /install/cloud` answers and the `cloud.state` frame carries (cloud-p0 plan D14). Built
 * from the `cloud_connection` row and the connector's memory; never secret.
 */
@Serializable
data class CloudStateDto(
    /** Whether `cloud.baseUrl` names a cloud this desk accepts. */
    val configured: Boolean,
    /** False when `cloud.enabled = false` turns the cloud off on this desk. */
    val enabled: Boolean,
    /** Present while an adoption waits for approval. */
    val adoption: CloudAdoptionDto? = null,
    /** Present once adopted. */
    val adopted: CloudAdoptedDto? = null,
    val connection: CloudConnectionStatus = CloudConnectionStatus.OFF,
    val retryAt: String? = null,
    val lastError: CloudErrorDto? = null,
    val lastOnlineAt: String? = null,
    /** Something the admin should know that ended an adoption: refused, expired, removed in the cloud. */
    val notice: String? = null,
    /** When [notice] was set: what a dismissal is keyed on, since the same words can come again. */
    val noticeAt: String? = null,
    /** This answer is for a caller on the public listener, where *Adopt* is refused (D5). */
    val remote: Boolean = false,
) {
    /**
     * What a caller on the public listener sees: the pending adoption's code and links left out,
     * since whoever holds them can approve this desk into an organisation of their own (D5).
     */
    fun forRemote(): CloudStateDto = copy(
        adoption = adoption?.copy(userCode = null, verificationUri = null, verificationUriComplete = null),
        remote = true,
    )
}

@Serializable
data class CloudAdoptionDto(
    /** The code to enter in the portal. Absent on the public listener, with the two links. */
    val userCode: String?,
    val verificationUri: String?,
    val verificationUriComplete: String?,
    val expiresAt: String,
    /** The install key's fingerprint, for comparing with what the portal shows. */
    val fingerprint: String,
)

@Serializable
data class CloudAdoptedDto(
    val installId: String,
    val orgName: String,
    val adoptedAt: String,
    /** The display name of the desk account that adopted it; null once that account is deleted. */
    val adoptedBy: String? = null,
    /** The cloud account linked to [adoptedBy]. */
    val linkedEmail: String? = null,
    val fingerprint: String,
)

@Serializable
data class CloudErrorDto(val code: String, val message: String)

/**
 * What may come through the cloud (cloud-p1 plan D6, D13), all off at adoption. `GET` and `PUT
 * /install/cloud/remote` answer it; only the desk's own listener may change it.
 */
@Serializable
data class RemoteSwitches(
    /** *Remote sessions*: the desk's SPA through the sidecar. */
    val remoteAccess: Boolean = false,
    /** *Claude (MCP)*: MCP calls relayed over the control channel. */
    val mcpAccess: Boolean = false,
    /** Each new remote session waits for an admin at the desk. */
    val approveSessions: Boolean = false,
)

/**
 * The control channel's status: [OFF] while the desk is not adopted or the cloud is off on it,
 * [RETRYING] between connections, with `retryAt` the next attempt.
 */
@Serializable
enum class CloudConnectionStatus { OFF, CONNECTING, ONLINE, RETRYING }

/** A refusal from [CloudService], answered by `routes/installCloud.kt` as [status] with [code]. */
class CloudException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

/** The REST error codes of the Cloud tab's routes (D5, D8, D14). */
object CloudErrorCodes {
    const val ADOPT_LOCAL_ONLY = "CLOUD_ADOPT_LOCAL_ONLY"
    const val NEEDS_ACCOUNT = "CLOUD_NEEDS_ACCOUNT"
    const val NOT_CONFIGURED = "CLOUD_NOT_CONFIGURED"
    const val DISABLED = "CLOUD_DISABLED"
    const val ALREADY_ADOPTED = "CLOUD_ALREADY_ADOPTED"
    const val ADOPTION_IN_PROGRESS = "CLOUD_ADOPTION_IN_PROGRESS"
    const val NO_ADOPTION = "CLOUD_NO_ADOPTION"
    const val NOT_ADOPTED = "CLOUD_NOT_ADOPTED"
    const val UNREACHABLE = "CLOUD_UNREACHABLE"
    const val REFUSED = "CLOUD_REFUSED"

    /** A setting that limits what comes through the cloud, changed from the public listener (cloud-p1 plan D6). */
    const val SETTINGS_LOCAL_ONLY = "CLOUD_SETTINGS_LOCAL_ONLY"

    /** `lastError` codes: never a REST refusal. */
    const val POLL_FAILED = "CLOUD_POLL_FAILED"
    const val KEY_MISSING = "CLOUD_KEY_MISSING"
    const val URL_CHANGED = "CLOUD_URL_CHANGED"
    const val URL_INVALID = "CLOUD_URL_INVALID"
    const val STATE_UNREADABLE = "CLOUD_STATE_UNREADABLE"

    /** `lastError` codes of the control channel (D10): why the last connection ended. */
    const val CONNECT_FAILED = "CLOUD_CONNECT_FAILED"
    const val KEY_UNREADABLE = "CLOUD_KEY_UNREADABLE"
    const val AUTH_FAILED = "CLOUD_AUTH_FAILED"
    const val CLOCK_SKEW = "CLOUD_CLOCK_SKEW"
    const val PROTOCOL_UNSUPPORTED = "CLOUD_PROTOCOL_UNSUPPORTED"
    const val PROTOCOL_ERROR = "CLOUD_PROTOCOL_ERROR"
    const val REPLACED = "CLOUD_REPLACED"
    const val DISCONNECTED = "CLOUD_DISCONNECTED"
}

/** The `platform` the desk reports to the cloud: `windows-x64`, `macos-arm64`, `linux-x64`. */
object DeskPlatform {
    val current: String by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val osName = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            else -> os.replace(Regex("[^a-z0-9]+"), "").ifEmpty { "unknown" }
        }
        val arch = when (val a = System.getProperty("os.arch").orEmpty().lowercase()) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> a.ifEmpty { "unknown" }
        }
        "$osName-$arch"
    }
}
