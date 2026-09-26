package uk.me.cormack.lighting7.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.routes.ErrorResponse
import uk.me.cormack.lighting7.state.State
import java.net.URI

private val log = LoggerFactory.getLogger("RemoteRequests")

private val RemoteRequestKey = AttributeKey<Boolean>("lighting7.remoteRequest")

/**
 * True for every request that arrived on the public listener (`mcp.port`) — which is what the
 * ngrok tunnel points at, so everything on it is treated as coming from the internet. Decided
 * **by port, never by a header**: the tunnel's requests reach the desk from 127.0.0.1, so the
 * peer address says nothing, and a forwarded header is whatever the caller chose to send.
 */
val ApplicationCall.isRemote: Boolean
    get() = attributes.getOrNull(RemoteRequestKey) == true

/** Thrown by [requireScriptAccess]; `plugins/ErrorHandling.kt` answers it 403 `REMOTE_SCRIPTS_DISABLED`. */
class RemoteScriptsDisabledException :
    RuntimeException("Scripts are turned off for remote access. An admin can allow them in Install settings → Remote access.")

/** Whether this caller may compile, run or save a script: always on the LAN, per the setting remotely. */
fun ApplicationCall.scriptsAllowed(state: State): Boolean =
    !isRemote || state.remoteAccess.allowRemoteScripts

/**
 * Refuse a script-authoring or script-running request that came through the tunnel while
 * Remote access's "allow scripts" is off. A script runs as the desk process, so a stolen remote
 * session — or a remote admin's AI chat reading content it did not write — would otherwise be
 * code execution on the show machine.
 */
fun ApplicationCall.requireScriptAccess(state: State) {
    if (!scriptsAllowed(state)) throw RemoteScriptsDisabledException()
}

private val errorJson = Json { explicitNulls = false }

/**
 * The public listener's gate, run before routing on every request: marks the call remote, then
 * refuses what must never be done from outside —
 *
 * - **Anything under `/api` on a desk with no accounts.** A zero-user desk is bootstrap-open on
 *   the LAN by design; through a tunnel it would let anyone create the first admin.
 * - **The two QR redemption flows** (`/auth/reset/…`, `/auth/device/…`). "Same network" is their
 *   whole trust boundary, and a tunnel request arrives from loopback, so the LAN-peer check alone
 *   would wave it through. 404, so a probe learns nothing.
 * - **The API docs** (`/api.json`, `/openapi`): a map of the API is no use to a remote operator.
 * - **A state-changing request or WebSocket upgrade from a foreign `Origin`.** On the LAN the
 *   desk's CSRF answer is SameSite=Lax plus JSON-only bodies; a browser's WebSocket carries no
 *   such guarantee, so remotely the origin must be the desk's own public URL (or loopback on this
 *   port). A request with no `Origin` is not a browser and passes. `/mcp` and `/oauth/…` keep
 *   their own rules.
 */
internal fun Application.installRemoteHardening(state: State) {
    intercept(ApplicationCallPipeline.Setup) {
        call.attributes.put(RemoteRequestKey, true)
        val path = call.request.path()
        val refusal = remoteRefusal(state, path, call.request.httpMethod, call.request.header(HttpHeaders.Origin), isWebSocketUpgrade(call))
        if (refusal != null) {
            val (status, body) = refusal
            if (status != HttpStatusCode.NotFound) log.warn("refusing remote {} {}: {}", call.request.httpMethod.value, path, body.error)
            call.respondText(errorJson.encodeToString(ErrorResponse.serializer(), body), ContentType.Application.Json, status)
            finish()
        }
    }
}

private fun isWebSocketUpgrade(call: ApplicationCall): Boolean =
    call.request.header(HttpHeaders.Upgrade)?.equals("websocket", ignoreCase = true) == true

internal fun remoteRefusal(
    state: State,
    path: String,
    method: HttpMethod,
    origin: String?,
    webSocket: Boolean,
): Pair<HttpStatusCode, ErrorResponse>? {
    if (path == "/api.json" || path == "/openapi" || path.startsWith("/openapi/")) {
        return HttpStatusCode.NotFound to ErrorResponse("Not found")
    }
    // Ahead of the no-accounts refusal, so a probe of a freshly exposed desk learns nothing here.
    if (path.startsWith("/api/rest/auth/reset/") || path.startsWith("/api/rest/auth/device/")) {
        return HttpStatusCode.NotFound to ErrorResponse("Not found")
    }
    val isApi = path == "/api" || path.startsWith("/api/")
    if (isApi && !state.authService.hasAnyUser) {
        return HttpStatusCode.Forbidden to ErrorResponse(
            "This desk has no accounts yet. Create the first one on the desk itself.",
            "REMOTE_NEEDS_ACCOUNT",
        )
    }
    val ownRules = path == "/mcp" || path.startsWith("/oauth/") || path.startsWith("/.well-known/")
    val stateChanging = webSocket || method !in SAFE_METHODS
    if (!ownRules && stateChanging && !remoteOriginAllowed(origin, state)) {
        return HttpStatusCode.Forbidden to ErrorResponse(
            "This request came from a page that isn't the desk.",
            "REMOTE_ORIGIN_REFUSED",
        )
    }
    return null
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/** The desk's own public origin, or loopback on the public port. Missing = not a browser. */
internal fun remoteOriginAllowed(origin: String?, state: State): Boolean {
    if (origin == null) return true
    val o = origin.trimEnd('/').lowercase()
    val port = state.mcpConfig.port
    return o == originOf(state.remoteAccess.publicUrl())?.lowercase() ||
        o == "http://localhost:$port" ||
        o == "http://127.0.0.1:$port" ||
        o == "http://[::1]:$port"
}

internal fun originOf(url: String): String? = runCatching {
    val u = URI(url)
    val scheme = u.scheme ?: return@runCatching null
    val host = u.host ?: return@runCatching null
    val defaultPort = (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)
    if (u.port == -1 || defaultPort) "$scheme://$host" else "$scheme://$host:${u.port}"
}.getOrNull()
