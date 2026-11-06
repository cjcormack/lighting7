package uk.me.cormack.lighting7.cloud

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString
import java.net.InetAddress
import java.net.URI

/**
 * The `cloud { }` block of `local.conf` (cloud-p0 plan D13). [enabled] false is the hard kill
 * switch the UI cannot override. An absent `baseUrl` is [DEFAULT_BASE_URL]; an empty one means no
 * cloud is configured, and so does one the desk refuses (D12), whose reason is [problem].
 */
data class CloudConfig(
    /** The cloud's base URL, no trailing slash, or null when none is configured or it was refused. */
    val baseUrl: String?,
    val enabled: Boolean,
    /** Why a non-empty `cloud.baseUrl` was refused. */
    val problem: String? = null,
    /** `cloud.link.binary`: a sidecar to run in place of the installed one ([uk.me.cormack.lighting7.link.LinkBinary]). */
    val linkBinary: String? = null,
    /** `cloud.remote.channelStateHz`: the most `channelState` frames a second to a socket on the public listener, 0 for no cap (P1 D14). */
    val remoteChannelStateHz: Int = DEFAULT_CHANNEL_STATE_HZ,
) {
    val configured: Boolean get() = baseUrl != null

    companion object {
        const val DEFAULT_BASE_URL = "https://lighting7.net"
        const val DEFAULT_CHANNEL_STATE_HZ = 20
        const val MAX_CHANNEL_STATE_HZ = 1000

        fun from(config: ApplicationConfig): CloudConfig {
            val enabled = config.optionalBoolean("cloud.enabled", default = true)
            val link = config.propertyOrNull("cloud.link.binary")?.getString()?.trim()?.takeIf { it.isNotEmpty() }
            val hz = channelStateHz(config.optionalString("cloud.remote.channelStateHz"))
            val raw = config.propertyOrNull("cloud.baseUrl")?.getString()?.trim() ?: DEFAULT_BASE_URL
            if (raw.isEmpty()) return CloudConfig(null, enabled, linkBinary = link, remoteChannelStateHz = hz)
            return when (val checked = checkBaseUrl(raw)) {
                is BaseUrlCheck.Ok -> CloudConfig(checked.url, enabled, linkBinary = link, remoteChannelStateHz = hz)
                is BaseUrlCheck.Refused -> CloudConfig(null, enabled, checked.reason, link, hz)
            }
        }

        /** A whole number from 0 to [MAX_CHANNEL_STATE_HZ]; anything else is the default, with a warning. */
        internal fun channelStateHz(raw: String?): Int {
            if (raw == null) return DEFAULT_CHANNEL_STATE_HZ
            val hz = raw.trim().toIntOrNull()
            if (hz != null && hz in 0..MAX_CHANNEL_STATE_HZ) return hz
            LoggerFactory.getLogger(CloudConfig::class.java)
                .warn("cloud.remote.channelStateHz must be a whole number from 0 to {}, not '{}': using {}", MAX_CHANNEL_STATE_HZ, raw, DEFAULT_CHANNEL_STATE_HZ)
            return DEFAULT_CHANNEL_STATE_HZ
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
