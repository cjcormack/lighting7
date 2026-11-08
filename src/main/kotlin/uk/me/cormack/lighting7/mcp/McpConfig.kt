package uk.me.cormack.lighting7.mcp

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString

/**
 * The `mcp { }` block of `local.conf`. Machine-local by construction — it is config, not show
 * content — and read once at startup.
 *
 * The listener is the desk's **public listener**: a second server on its own port, serving the
 * whole desk (UI, REST, WebSocket) to remote sessions, which `lighting7-link` forwards into it.
 * Everything that arrives on it is treated as coming from the internet — remote hardening applies
 * by port, never by a forwarded header. See `docs/mcp-engineering.md` §"The public listener".
 */
data class McpConfig(
    val enabled: Boolean,
    /** Interface the public listener binds. Loopback: the sidecar runs on the desk. */
    val host: String,
    val port: Int,
) {
    companion object {
        const val DEFAULT_PORT = 8414

        private val log = LoggerFactory.getLogger(McpConfig::class.java)

        /** Keys of the ngrok tunnel and the desk's own OAuth server, which a `local.conf` may still name. */
        internal val RETIRED_KEYS = listOf(
            "mcp.publicUrl", "mcp.extraRedirectUris",
            "mcp.tunnel.enabled", "mcp.tunnel.domain", "mcp.tunnel.ngrokPath",
        )

        fun from(config: ApplicationConfig): McpConfig {
            val retired = RETIRED_KEYS.filter { config.propertyOrNull(it) != null }
            if (retired.isNotEmpty()) {
                log.warn(
                    "local.conf names {}, which this desk no longer reads: remote access and MCP now come " +
                        "through Lighting7 Cloud (Install settings → Cloud). Remove them.",
                    retired.joinToString(", "),
                )
            }
            return McpConfig(
                enabled = config.optionalBoolean("mcp.enabled", default = true),
                host = config.optionalString("mcp.host")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = config.optionalString("mcp.port")?.toIntOrNull() ?: DEFAULT_PORT,
            )
        }
    }
}
