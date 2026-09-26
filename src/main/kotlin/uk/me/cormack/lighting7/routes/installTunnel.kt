package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.mcp.tunnel.RemoteAccessException
import uk.me.cormack.lighting7.mcp.tunnel.TunnelState
import uk.me.cormack.lighting7.state.State

/**
 * Remote access (the ngrok tunnel): `GET` and `PUT /install/tunnel`, admin only. Machine-local —
 * it is about this computer's network, not the show — so it sits beside `/install` rather than
 * under a project. The authtoken is **write-only**: a `PUT` may set or clear it, and every answer
 * says only whether one is stored. Live status also streams as the admin-only `tunnel.state`
 * frame (`plugins/MachineSocket.kt`). See `docs/mcp-engineering.md` §"Remote access".
 */
internal fun Route.routeApiRestInstallTunnel(state: State) {
    get<InstallTunnelResource> {
        call.requireAdmin()
        call.respond(state.tunnelSettingsDto())
    }

    put<InstallTunnelResource> {
        call.requireAdmin()
        val request = call.receive<UpdateTunnelRequest>()
        try {
            state.remoteAccess.update(
                enabled = request.enabled,
                domain = request.domain,
                authtoken = request.authtoken,
                allowScripts = request.allowScripts,
                hasAnyUser = state.authService.hasAnyUser,
            )
        } catch (e: RemoteAccessException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Couldn't save remote access", "REMOTE_ACCESS_INVALID"))
            return@put
        }
        call.respond(state.tunnelSettingsDto())
    }
}

@Resource("/install/tunnel")
data object InstallTunnelResource

/** Every field optional: absent leaves it as it is. An empty `authtoken` or `domain` clears it. */
@Serializable
data class UpdateTunnelRequest(
    val enabled: Boolean? = null,
    val domain: String? = null,
    val authtoken: String? = null,
    val allowScripts: Boolean? = null,
)

@Serializable
data class TunnelSettingsDto(
    val enabled: Boolean,
    val domain: String? = null,
    val allowScripts: Boolean,
    /** Whether an authtoken is stored. The token itself is never sent back. */
    val hasAuthtoken: Boolean,
    /** The desk's public base URL — the OAuth issuer — as it stands now. */
    val publicUrl: String,
    /** What to paste into Claude's "Add custom connector": `<publicUrl>/mcp`. */
    val connectorUrl: String,
    /** True when `mcp.publicUrl` in local.conf names a tunnel of the operator's own, which wins. */
    val publicUrlOverridden: Boolean,
    /** The public listener's port: what the tunnel forwards to. */
    val port: Int,
    /** The ngrok version this desk downloads, or null when none is pinned for this computer. */
    val pinnedVersion: String? = null,
    val state: TunnelStateDto,
)

@Serializable
data class TunnelStateDto(
    /** `off` · `installing` · `starting` · `online` · `error` · `no-binary`. */
    val status: String,
    val url: String? = null,
    /** ngrok's own error code (`ERR_NGROK_…`) when it gave one. */
    val code: String? = null,
    val message: String? = null,
    /** An error the supervisor is retrying on its own (a dropped connection), vs one that needs the operator. */
    val retrying: Boolean = false,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
)

fun TunnelState.toDto(): TunnelStateDto = when (this) {
    TunnelState.Off -> TunnelStateDto("off")
    is TunnelState.Installing -> TunnelStateDto("installing", downloadedBytes = downloadedBytes, totalBytes = totalBytes)
    TunnelState.Starting -> TunnelStateDto("starting")
    is TunnelState.Online -> TunnelStateDto("online", url = url)
    is TunnelState.Error -> TunnelStateDto("error", code = code, message = message, retrying = retrying)
    is TunnelState.NoBinary -> TunnelStateDto("no-binary", message = message)
}

internal fun State.tunnelSettingsDto(): TunnelSettingsDto {
    val ra = remoteAccess
    val s = ra.settings
    val publicUrl = ra.publicUrl()
    return TunnelSettingsDto(
        enabled = s.enabled,
        domain = s.domain,
        allowScripts = s.allowScripts,
        hasAuthtoken = ra.hasAuthtoken,
        publicUrl = publicUrl,
        connectorUrl = "$publicUrl/mcp",
        publicUrlOverridden = mcpConfig.explicitPublicUrl != null,
        port = mcpConfig.port,
        pinnedVersion = ra.pinnedBuild?.let { uk.me.cormack.lighting7.mcp.tunnel.NgrokDistribution.VERSION },
        state = ra.state.value.toDto(),
    )
}
