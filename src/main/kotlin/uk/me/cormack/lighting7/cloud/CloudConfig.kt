package uk.me.cormack.lighting7.cloud

import io.ktor.server.config.ApplicationConfig
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString
import java.net.InetAddress
import java.net.URI

/**
 * The `cloud { }` block of `local.conf` (cloud-p0 plan D13). [enabled] false is the hard kill
 * switch the UI cannot override. An empty `baseUrl` means no cloud is configured; so does one the
 * desk refuses (D12), whose reason is [problem].
 */
data class CloudConfig(
    /** The cloud's base URL, no trailing slash, or null when none is configured or it was refused. */
    val baseUrl: String?,
    val enabled: Boolean,
    /** Why a non-empty `cloud.baseUrl` was refused. */
    val problem: String? = null,
) {
    val configured: Boolean get() = baseUrl != null

    companion object {
        fun from(config: ApplicationConfig): CloudConfig {
            val enabled = config.optionalBoolean("cloud.enabled", default = true)
            val raw = config.optionalString("cloud.baseUrl")?.trim().orEmpty()
            if (raw.isEmpty()) return CloudConfig(null, enabled)
            return when (val checked = checkBaseUrl(raw)) {
                is BaseUrlCheck.Ok -> CloudConfig(checked.url, enabled)
                is BaseUrlCheck.Refused -> CloudConfig(null, enabled, checked.reason)
            }
        }

        /**
         * D12: `https` to any host, plain `http` only to a loopback one (a cloud on the same
         * machine), no credentials, query or fragment.
         */
        fun checkBaseUrl(raw: String): BaseUrlCheck {
            val uri = runCatching { URI(raw) }.getOrNull()
                ?: return BaseUrlCheck.Refused("cloud.baseUrl is not a URL")
            val host = uri.host ?: return BaseUrlCheck.Refused("cloud.baseUrl has no host")
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
                return BaseUrlCheck.Refused("cloud.baseUrl must be a plain base URL, with no credentials, query or fragment")
            }
            when (uri.scheme?.lowercase()) {
                "https" -> Unit
                "http" -> if (!isLoopback(host)) {
                    return BaseUrlCheck.Refused("cloud.baseUrl must use https unless the cloud runs on this machine")
                }
                else -> return BaseUrlCheck.Refused("cloud.baseUrl must be an https URL")
            }
            return BaseUrlCheck.Ok(raw.trimEnd('/'))
        }

        /** `localhost` or a literal loopback address. Never a DNS lookup: a name could resolve elsewhere next time. */
        private fun isLoopback(host: String): Boolean {
            val bare = host.removePrefix("[").removeSuffix("]")
            if (bare.equals("localhost", ignoreCase = true)) return true
            return runCatching { InetAddress.ofLiteral(bare).isLoopbackAddress }.getOrDefault(false)
        }
    }
}

sealed interface BaseUrlCheck {
    data class Ok(val url: String) : BaseUrlCheck
    data class Refused(val reason: String) : BaseUrlCheck
}
