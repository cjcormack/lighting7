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
import java.net.URI
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.routingNormalisedPath
import uk.me.cormack.lighting7.routes.ErrorResponse
import uk.me.cormack.lighting7.state.State

private val log = LoggerFactory.getLogger("RemoteRequests")

private val RemoteRequestKey = AttributeKey<Boolean>("lighting7.remoteRequest")

/**
 * True for every request that arrived on the public listener (`mcp.port`) — which is what the
 * sidecar forwards remote sessions into, so everything on it is treated as coming from the internet.
 * Decided **by port, never by a header**: the sidecar's requests reach the desk from 127.0.0.1, so
 * the peer address says nothing, and a forwarded header is whatever the caller chose to send.
 */
val ApplicationCall.isRemote: Boolean
    get() = attributes.getOrNull(RemoteRequestKey) == true

/** Password sign-in, refused on the public listener: a remote caller signs in through the cloud. */
const val CODE_REMOTE_PASSWORD_SIGN_IN = "REMOTE_PASSWORD_SIGN_IN"

/** A script, or an undo that would put one back, refused over remote access. */
const val CODE_REMOTE_SCRIPTS_DISABLED = "REMOTE_SCRIPTS_DISABLED"

/** Thrown by [requireScriptAccess]; `plugins/ErrorHandling.kt` answers it 403 [CODE_REMOTE_SCRIPTS_DISABLED]. */
class RemoteScriptsDisabledException :
    RuntimeException("Scripts are turned off for remote access. An admin can allow them in Install settings → Cloud → Remote access.")

/** Whether this caller may compile, run or save a script: always on the LAN, per the setting remotely. */
fun ApplicationCall.scriptsAllowed(state: State): Boolean =
    !isRemote || state.remotePolicy.allowScripts

/**
 * Refuse a script-authoring or script-running request from the public listener while Remote
 * access's "allow scripts" is off. A script runs as the desk process, so a stolen remote
 * session would otherwise be code execution on the show machine.
 */
fun ApplicationCall.requireScriptAccess(state: State) {
    if (!scriptsAllowed(state)) throw RemoteScriptsDisabledException()
}

/** Thrown by [requireEffectsAccess]; `plugins/ErrorHandling.kt` answers it 403 `REMOTE_EFFECTS_DISABLED`. */
class RemoteEffectsDisabledException :
    RuntimeException("Arming and firing are turned off for remote access. An admin can allow them in Install settings → Cloud → Remote access.")

/** Whether this caller may arm the desk, fire a one-shot trigger or reload one: always on the LAN, per the setting remotely. */
fun ApplicationCall.effectsAllowed(state: State): Boolean =
    !isRemote || state.remotePolicy.allowEffects

/**
 * Refuse an arm, a fire or a reload from the public listener while Remote access's "allow arming
 * and firing" is off (stage-view plan session 9, P2, D16) — the [requireScriptAccess] twin. A cannon
 * spends something physical in a room a remote caller cannot see, so it is off unless an admin turns
 * it on; on the desk's own listener both roles may arm and fire.
 */
fun ApplicationCall.requireEffectsAccess(state: State) {
    if (!effectsAllowed(state)) throw RemoteEffectsDisabledException()
}

/** Thrown by [requireCommandsAccess]; `plugins/ErrorHandling.kt` answers it 403 `REMOTE_COMMANDS_DISABLED`. */
class RemoteCommandsDisabledException :
    RuntimeException("Fixture commands are turned off for remote access. An admin can allow them in Install settings → Cloud → Remote access.")

/** Whether this caller may run a fixture command: always on the LAN, per the setting remotely. */
fun ApplicationCall.commandsAllowed(state: State): Boolean =
    !isRemote || state.remotePolicy.allowCommands

/**
 * Refuse a fixture command — a reset, a lamp strike, a lamp off — from the public listener while
 * Remote access's "allow fixture commands" is off (fixture optics plan session 7, D13), the
 * [requireEffectsAccess] twin. A lamp off is a dark head for eight minutes and a reset swings it through
 * its travel, in a room a remote caller cannot see; on the desk's own listener both roles may run one,
 * behind the panel's confirm. MCP's `run_fixture_command` is held to the same setting.
 */
fun ApplicationCall.requireCommandsAccess(state: State) {
    if (!commandsAllowed(state)) throw RemoteCommandsDisabledException()
}

private val errorJson = Json { explicitNulls = false }

/**
 * The public listener's gate, run before routing on every request: marks the call remote, then
 * refuses what must never be done from outside —
 *
 * - **Anything under `/api` on a desk with no accounts.** A zero-user desk is bootstrap-open on
 *   the LAN by design; from outside it would let anyone create the first admin.
 * - **The two QR redemption flows** (`/auth/reset/…`, `/auth/device/…`). "Same network" is their
 *   whole trust boundary, and a remote request arrives from loopback, so the LAN-peer check alone
 *   would wave it through. 404, so a probe learns nothing.
 * - **The API docs** (`/api.json`, `/openapi`): a map of the API is no use to a remote operator.
 * - **What the desk's own OAuth server and MCP endpoint answered** (`/mcp`, `/oauth/…`,
 *   `/.well-known/…`), 404 rather than the SPA's page, so a connector added before MCP moved to
 *   Lighting7 Cloud fails plainly.
 * - **Password sign-in** (`/auth/login`). A remote caller signs in through Lighting7 Cloud with a
 *   bearer token; the public listener sets and reads no session cookie (`auth/AuthGate.kt`).
 * - **A state-changing request or WebSocket upgrade from a foreign `Origin`.** On the LAN the
 *   desk's CSRF answer is SameSite=Lax plus JSON-only bodies; a browser's WebSocket carries no
 *   such guarantee, so remotely the origin must be loopback on this port, or for a request through
 *   the sidecar the cloud's origin for this install. A request with no `Origin` is not a browser
 *   and passes.
 */
internal fun Application.installRemoteHardening(state: State) {
    intercept(ApplicationCallPipeline.Setup) {
        call.attributes.put(RemoteRequestKey, true)
        val path = call.request.path()
        // Judged on the path the router will dispatch, so `//` or a percent-encoded segment cannot
        // spell its way past a refusal to the handler it names.
        val refusal = remoteRefusal(
            state,
            routingNormalisedPath(path),
            call.request.httpMethod,
            call.request.header(HttpHeaders.Origin),
            isWebSocketUpgrade(call),
            bearer = call.request.header(HttpHeaders.Authorization)?.startsWith("Bearer ", ignoreCase = true) == true,
        )
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
    bearer: Boolean = false,
): Pair<HttpStatusCode, ErrorResponse>? {
    if (path == "/api.json" || path == "/openapi" || path.startsWith("/openapi/") ||
        path == "/mcp" || path.startsWith("/oauth/") || path.startsWith("/.well-known/")
    ) {
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
    if (path == "/api/rest/auth/login") {
        return HttpStatusCode.Forbidden to ErrorResponse(
            "Sign in to this desk through Lighting7 Cloud. Password sign-in is only on the desk itself.",
            CODE_REMOTE_PASSWORD_SIGN_IN,
        )
    }
    val stateChanging = webSocket || method !in SAFE_METHODS
    if (stateChanging && !remoteOriginAllowed(origin, state, bearer)) {
        return HttpStatusCode.Forbidden to ErrorResponse(
            "This request came from a page that isn't the desk.",
            "REMOTE_ORIGIN_REFUSED",
        )
    }
    return null
}

private val SAFE_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

/**
 * Loopback on the public port, and for a [bearer] request through the sidecar the origin the cloud
 * serves this install's remote sessions from (cloud-p1 plan D4), which the sidecar forwards as the
 * request's. Missing = not a browser.
 */
internal fun remoteOriginAllowed(origin: String?, state: State, bearer: Boolean = false): Boolean {
    if (origin == null) return true
    val o = origin.trimEnd('/').lowercase()
    val port = state.mcpConfig.port
    if (bearer && state.cloud.remoteOrigin?.let { it == o } == true) return true
    return o == "http://localhost:$port" ||
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
