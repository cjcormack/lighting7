package uk.me.cormack.lighting7.mcp

import io.ktor.server.config.ApplicationConfig
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString

/**
 * The `mcp { }` block of `local.conf`. Machine-local by construction — it is config, not show
 * content — and read once at startup.
 *
 * The listener is the desk's **public listener**: a second server on its own port, serving the
 * whole desk (UI, REST, WebSocket) plus the MCP endpoint, the OAuth endpoints and the sign-in page.
 * Everything that arrives on it is treated as coming from the internet — remote hardening applies
 * by port, never by a forwarded header. The ngrok tunnel (Remote access) points only at it. See
 * `docs/mcp-engineering.md` §"The public listener".
 */
data class McpConfig(
    val enabled: Boolean,
    /** Interface the public listener binds. Loopback by default: the tunnel agent runs on the desk. */
    val host: String,
    val port: Int,
    /**
     * `mcp.publicUrl`: the HTTPS base of a tunnel the operator runs themselves, without a trailing
     * slash. When set it wins over the Remote access domain. Null when unset — the public URL is
     * then the ngrok domain, or [localUrl]. Read it through `RemoteAccessService.publicUrl()`.
     */
    val explicitPublicUrl: String?,
    /** Redirect URIs a client may register beyond the built-in allowlist. Exact strings. */
    val extraRedirectUris: List<String>,
    /** `mcp.tunnel.enabled`: Remote access's default until it is first saved from the desk. */
    val tunnelEnabled: Boolean = false,
    /** `mcp.tunnel.domain`: the ngrok domain's default until it is first saved from the desk. */
    val tunnelDomain: String? = null,
    /** `mcp.tunnel.ngrokPath`: run this ngrok instead of downloading the pinned one. For dev. */
    val ngrokPath: String? = null,
) {
    /** What the public URL falls back to with no tunnel: right for Claude Code on the desk itself. */
    val localUrl: String get() = "http://localhost:$port"

    companion object {
        const val DEFAULT_PORT = 8414

        fun from(config: ApplicationConfig): McpConfig {
            val port = config.optionalString("mcp.port")?.toIntOrNull() ?: DEFAULT_PORT
            return McpConfig(
                enabled = config.optionalBoolean("mcp.enabled", default = true),
                host = config.optionalString("mcp.host")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = port,
                explicitPublicUrl = config.optionalString("mcp.publicUrl")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() },
                extraRedirectUris = config.optionalString("mcp.extraRedirectUris")
                    ?.split(',', ' ', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    .orEmpty(),
                tunnelEnabled = config.optionalBoolean("mcp.tunnel.enabled", default = false),
                tunnelDomain = config.optionalString("mcp.tunnel.domain")?.takeIf { it.isNotBlank() },
                ngrokPath = config.optionalString("mcp.tunnel.ngrokPath")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
