package uk.me.cormack.lighting7.mcp

import io.ktor.server.config.ApplicationConfig
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString

/**
 * The `mcp { }` block of `local.conf`. Machine-local by construction — it is config, not show
 * content — and read once at startup.
 *
 * The listener is a **second server**, not a second route tree on the desk's own port: it
 * mounts the MCP endpoint, the OAuth endpoints and the sign-in page and nothing else, so a tunnel
 * pointed at it exposes a login form and an MCP endpoint rather than the desk UI, the REST API
 * and the script routes. See `docs/mcp-engineering.md` §"Two listeners".
 */
data class McpConfig(
    val enabled: Boolean,
    /** Interface the MCP listener binds. Loopback by default: a tunnel agent runs on the desk. */
    val host: String,
    val port: Int,
    /**
     * The public HTTPS base the tunnel serves this listener at, without a trailing slash —
     * `https://desk.example.ts.net`. The OAuth issuer and the protected-resource identifier are
     * built from it, so a client must reach the listener at exactly this origin. Empty falls
     * back to `http://localhost:<port>`, which is enough for Claude Code on the desk itself.
     */
    val publicUrl: String,
    /** Redirect URIs a client may register beyond the built-in allowlist. Exact strings. */
    val extraRedirectUris: List<String>,
) {
    /** The MCP endpoint's own URL — the OAuth `resource` every token is bound to. */
    val resourceUrl: String get() = "$publicUrl/mcp"

    companion object {
        const val DEFAULT_PORT = 8414

        fun from(config: ApplicationConfig): McpConfig {
            val port = config.optionalString("mcp.port")?.toIntOrNull() ?: DEFAULT_PORT
            return McpConfig(
                enabled = config.optionalBoolean("mcp.enabled", default = true),
                host = config.optionalString("mcp.host")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
                port = port,
                publicUrl = (config.optionalString("mcp.publicUrl")?.takeIf { it.isNotBlank() } ?: "http://localhost:$port")
                    .trim().trimEnd('/'),
                extraRedirectUris = config.optionalString("mcp.extraRedirectUris")
                    ?.split(',', ' ', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    .orEmpty(),
            )
        }
    }
}
