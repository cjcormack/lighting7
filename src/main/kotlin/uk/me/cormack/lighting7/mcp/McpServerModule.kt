package uk.me.cormack.lighting7.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthenticationException
import uk.me.cormack.lighting7.auth.AuthorizationException
import uk.me.cormack.lighting7.state.State
import java.net.URI

private val log = LoggerFactory.getLogger("McpServer")

private val mcpJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * Start the MCP listener: a separate Netty server on `mcp.host:mcp.port`, serving [mcpModule]
 * and nothing else. Called from `Application.module()` — production only; tests mount
 * [mcpModule] on a test application directly.
 */
fun startMcpServer(state: State): EmbeddedServer<*, *>? {
    val config = state.mcpConfig
    if (!config.enabled) return null
    return runCatching {
        embeddedServer(Netty, port = config.port, host = config.host) { mcpModule(state) }.start(wait = false)
    }.onSuccess {
        log.info("MCP listener on {}:{} (public URL {})", config.host, config.port, config.publicUrl)
    }.onFailure {
        // A busy port must not take the desk down with it: the MCP surface is an extra.
        log.error("MCP listener failed to start on {}:{}: {}", config.host, config.port, it.message)
    }.getOrNull()
}

/**
 * Everything the tunnel may reach, and nothing more: the MCP endpoint, OAuth discovery, client
 * registration, the sign-in page and the token endpoint. No SPA, no REST API, no WebSocket, no
 * script editor — those stay on the LAN port, which is the point of a second listener
 * (`docs/mcp-engineering.md` §"Two listeners").
 */
fun Application.mcpModule(state: State) {
    val config = state.mcpConfig
    val auth = state.mcpAuthService
    val protocol = McpProtocol(state)
    val resourceMetadataUrl = "${config.publicUrl}/.well-known/oauth-protected-resource/mcp"

    install(ContentNegotiation) { json(mcpJson) }

    routing {
        get("/") {
            call.respondText("lighting7 MCP server. Add ${config.resourceUrl} as a connector in Claude.")
        }

        // ─── Discovery ─────────────────────────────────────────────────

        val protectedResource = ProtectedResourceMetadata(
            resource = config.resourceUrl,
            authorizationServers = listOf(config.publicUrl),
            scopesSupported = listOf(MCP_SCOPE),
            resourceName = "lighting7 desk",
        )
        get("/.well-known/oauth-protected-resource") { call.respond(protectedResource) }
        get("/.well-known/oauth-protected-resource/mcp") { call.respond(protectedResource) }

        val serverMetadata = AuthorizationServerMetadata(
            issuer = config.publicUrl,
            authorizationEndpoint = "${config.publicUrl}/oauth/authorize",
            tokenEndpoint = "${config.publicUrl}/oauth/token",
            registrationEndpoint = "${config.publicUrl}/oauth/register",
            revocationEndpoint = "${config.publicUrl}/oauth/revoke",
            scopesSupported = listOf(MCP_SCOPE),
        )
        get("/.well-known/oauth-authorization-server") { call.respond(serverMetadata) }
        get("/.well-known/oauth-authorization-server/mcp") { call.respond(serverMetadata) }

        // ─── Registration (RFC 7591) ───────────────────────────────────

        post("/oauth/register") {
            val request = runCatching { mcpJson.decodeFromString<ClientRegistrationRequest>(call.receiveText()) }
                .getOrElse {
                    call.oauthError(McpOAuthError("invalid_client_metadata", "Malformed registration request"))
                    return@post
                }
            try {
                val client = auth.registerClient(request.redirectUris, request.clientName)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(
                    HttpStatusCode.Created,
                    ClientRegistrationResponse(
                        clientId = client.clientId,
                        clientIdIssuedAt = System.currentTimeMillis() / 1000,
                        clientName = client.clientName,
                        redirectUris = client.redirectUris,
                    ),
                )
            } catch (e: McpOAuthError) {
                call.oauthError(e)
            }
        }

        // ─── Authorization ─────────────────────────────────────────────

        get("/oauth/authorize") {
            val p = call.request.queryParameters
            if (!state.authService.hasAnyUser) {
                call.respondPage(
                    HttpStatusCode.Forbidden,
                    McpSignInPage.error(
                        "This desk has no accounts yet",
                        "Create the first account on the desk itself, then add the connector again.",
                    ),
                )
                return@get
            }
            // Client and redirect URI are checked before anything is sent to the redirect URI:
            // an unknown pair gets an error page, never a redirect (RFC 6749 §4.1.2.1).
            val client = p["client_id"]?.let { auth.findClient(it) }
            val redirectUri = p["redirect_uri"] ?: client?.redirectUris?.singleOrNull()
            if (client == null || redirectUri == null || redirectUri !in client.redirectUris) {
                call.respondPage(
                    HttpStatusCode.BadRequest,
                    McpSignInPage.error(
                        "This sign-in link isn't valid",
                        "The app asking for access isn't registered with this desk, or asked to be sent " +
                            "somewhere it didn't register. Remove the connector and add it again.",
                    ),
                )
                return@get
            }
            val request = try {
                auth.beginAuthorization(
                    client = client,
                    redirectUri = redirectUri,
                    state = p["state"],
                    responseType = p["response_type"],
                    codeChallenge = p["code_challenge"],
                    codeChallengeMethod = p["code_challenge_method"],
                    resource = p["resource"],
                )
            } catch (e: McpOAuthError) {
                call.respondRedirect(errorRedirect(redirectUri, e.error, e.description, p["state"], config.publicUrl))
                return@get
            }
            call.respondPage(HttpStatusCode.OK, McpSignInPage.signIn(request.id, client.clientName, deskName(config)))
        }

        post("/oauth/authorize") {
            val form = call.receiveParameters()
            val request = form["request_id"]?.let { auth.pendingAuthorization(it) }
            if (request == null) {
                call.respondPage(
                    HttpStatusCode.BadRequest,
                    McpSignInPage.error(
                        "This sign-in has expired",
                        "Go back to Claude and connect again.",
                    ),
                )
                return@post
            }
            if (form["action"] != "allow") {
                auth.completeAuthorization(request.id)
                call.respondRedirect(
                    errorRedirect(request.redirectUri, "access_denied", "The desk user denied access", request.state, config.publicUrl),
                )
                return@post
            }
            val username = form["username"].orEmpty()
            val password = form["password"].orEmpty()
            val again = { message: String ->
                McpSignInPage.signIn(request.id, request.client.clientName, deskName(config), message, username)
            }
            if (auth.signInLockedOut(username)) {
                call.respondPage(HttpStatusCode.TooManyRequests, again("Too many failed sign-ins. Try again later."))
                return@post
            }
            val user = try {
                state.authService.verifyCredentials(username, password)
            } catch (e: AuthenticationException) {
                auth.recordSignInFailure(username)
                call.respondPage(HttpStatusCode.Unauthorized, again("Incorrect username or password."))
                return@post
            } catch (e: AuthorizationException) {
                call.respondPage(HttpStatusCode.Forbidden, again(e.message ?: "This account can't sign in."))
                return@post
            }
            auth.clearSignInFailures(username)
            val code = auth.issueCode(request, user.userId)
            log.info("MCP access granted to {} for {}", request.client.clientName, user.username)
            val target = URLBuilder(request.redirectUri).apply {
                parameters.append("code", code)
                request.state?.let { parameters.append("state", it) }
                parameters.append("iss", config.publicUrl)
            }.buildString()
            call.respondRedirect(target)
        }

        // ─── Tokens ────────────────────────────────────────────────────

        post("/oauth/token") {
            val form = call.receiveParameters()
            try {
                val tokens = when (form["grant_type"]) {
                    "authorization_code" -> auth.exchangeCode(
                        code = form["code"],
                        clientId = form["client_id"],
                        redirectUri = form["redirect_uri"],
                        codeVerifier = form["code_verifier"],
                        resource = form["resource"],
                    )
                    "refresh_token" -> auth.refresh(form["refresh_token"], form["client_id"], form["resource"])
                    else -> throw McpOAuthError("unsupported_grant_type", "grant_type must be authorization_code or refresh_token")
                }
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(tokens)
            } catch (e: McpOAuthError) {
                call.oauthError(e)
            }
        }

        post("/oauth/revoke") {
            call.receiveParameters()["token"]?.let { auth.revokeToken(it) }
            call.respond(HttpStatusCode.OK)
        }

        // ─── MCP (streamable HTTP, JSON responses) ─────────────────────

        post("/mcp") {
            if (!originAllowed(call.request.header(HttpHeaders.Origin), config)) {
                call.respond(HttpStatusCode.Forbidden, McpProtocol.error(JsonNull, McpProtocol.INVALID_REQUEST, "Origin not allowed"))
                return@post
            }
            val bearer = call.request.header(HttpHeaders.Authorization)
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(7)?.trim()
            val user = bearer?.let { auth.resolveAccessToken(it) }
            if (user == null) {
                val error = if (bearer == null) "" else ", error=\"invalid_token\""
                call.response.header(
                    HttpHeaders.WWWAuthenticate,
                    "Bearer resource_metadata=\"$resourceMetadataUrl\"$error",
                )
                call.respond(HttpStatusCode.Unauthorized, McpProtocol.error(JsonNull, McpProtocol.INVALID_REQUEST, "Unauthorized"))
                return@post
            }
            val message = runCatching { mcpJson.parseToJsonElement(call.receiveText()) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, McpProtocol.error(JsonNull, McpProtocol.PARSE_ERROR, "Parse error"))
                return@post
            }
            val response = protocol.handle(message, user)
            if (response == null) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respondText(response.toString(), ContentType.Application.Json)
            }
        }

        // No server-initiated stream and no session to end: both answered as the spec allows.
        get("/mcp") {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
        delete("/mcp") {
            call.response.header(HttpHeaders.Allow, "POST")
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }
}

/** What the sign-in page calls the desk: the public host the operator connected to. */
private fun deskName(config: McpConfig): String =
    runCatching { URI(config.publicUrl).host }.getOrNull() ?: "this desk"

/**
 * The MCP spec requires validating `Origin` against DNS rebinding. Server-side clients (claude.ai's
 * connector backend, Claude Code) send none; a browser always does, and only this listener's own
 * origin, claude.ai and loopback are accepted from one.
 */
internal fun originAllowed(origin: String?, config: McpConfig): Boolean {
    if (origin == null) return true
    val o = origin.trimEnd('/')
    if (o == originOf(config.publicUrl)) return true
    if (o == "https://claude.ai" || o == "https://claude.com") return true
    val host = runCatching { URI(o).host }.getOrNull() ?: return false
    return host == "localhost" || host == "127.0.0.1" || host == "[::1]"
}

private fun originOf(url: String): String? = runCatching {
    val u = URI(url)
    if (u.port == -1) "${u.scheme}://${u.host}" else "${u.scheme}://${u.host}:${u.port}"
}.getOrNull()

private fun errorRedirect(redirectUri: String, error: String, description: String, state: String?, issuer: String): String =
    URLBuilder(redirectUri).apply {
        parameters.append("error", error)
        parameters.append("error_description", description)
        state?.let { parameters.append("state", it) }
        parameters.append("iss", issuer)
    }.buildString()

private suspend fun ApplicationCall.respondPage(status: HttpStatusCode, html: String) {
    // No framing (clickjacking a consent button), no caching, and no referrer leaking the
    // request id to anything the page might link to.
    response.header("X-Frame-Options", "DENY")
    response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Referrer-Policy", "no-referrer")
    respondText(html, ContentType.Text.Html, status)
}

private suspend fun ApplicationCall.oauthError(e: McpOAuthError) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(HttpStatusCode.BadRequest, OAuthErrorResponse(e.error, e.description))
}

@Serializable
private data class OAuthErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String,
)

@Serializable
internal data class ProtectedResourceMetadata(
    val resource: String,
    @SerialName("authorization_servers") val authorizationServers: List<String>,
    @SerialName("scopes_supported") val scopesSupported: List<String>,
    @SerialName("bearer_methods_supported") val bearerMethodsSupported: List<String> = listOf("header"),
    @SerialName("resource_name") val resourceName: String,
)

@Serializable
internal data class AuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String,
    @SerialName("revocation_endpoint") val revocationEndpoint: String,
    @SerialName("scopes_supported") val scopesSupported: List<String>,
    @SerialName("response_types_supported") val responseTypesSupported: List<String> = listOf("code"),
    @SerialName("grant_types_supported") val grantTypesSupported: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = listOf("S256"),
    @SerialName("token_endpoint_auth_methods_supported") val tokenEndpointAuthMethodsSupported: List<String> = listOf("none"),
    @SerialName("revocation_endpoint_auth_methods_supported") val revocationEndpointAuthMethodsSupported: List<String> = listOf("none"),
    @SerialName("authorization_response_iss_parameter_supported") val issParameterSupported: Boolean = true,
)

@Serializable
internal data class ClientRegistrationRequest(
    @SerialName("redirect_uris") val redirectUris: List<String> = emptyList(),
    @SerialName("client_name") val clientName: String? = null,
)

@Serializable
internal data class ClientRegistrationResponse(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_id_issued_at") val clientIdIssuedAt: Long,
    @SerialName("client_name") val clientName: String,
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
    @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
)
