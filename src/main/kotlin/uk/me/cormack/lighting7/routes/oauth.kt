package uk.me.cormack.lighting7.routes

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoOAuthIdentities
import uk.me.cormack.lighting7.models.DaoOAuthIdentity
import uk.me.cormack.lighting7.plugins.OAuthIdentityChangedOutMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.oauth.DevicePollResult
import uk.me.cormack.lighting7.sync.auth.oauth.GithubRepository
import uk.me.cormack.lighting7.sync.auth.oauth.GithubUser
import uk.me.cormack.lighting7.sync.auth.oauth.OAUTH_JSON
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthGitHubClient
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthRateLimitedException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenProvider
import uk.me.cormack.lighting7.sync.auth.oauth.TokenResponse
import uk.me.cormack.lighting7.sync.auth.oauth.newStoredIdentity
import java.security.SecureRandom
import java.util.Base64

/**
 * GitHub OAuth routes — both the user-facing web flow (start + callback) and the
 * device-flow + identity / repo-discovery REST endpoints used by the React UI.
 *
 * Web flow uses a single short-lived cookie (`lighting7_oauth_state`) to bind the
 * `state` query param GitHub echoes back to the originating request, plus the optional
 * `projectId` / `returnTo` so the callback knows where to send the user.
 */
internal fun Route.routeApiOAuthGitHub(state: State) {

    // ─── Web flow ──────────────────────────────────────────────────────

    get<OAuthGithubStartResource> { resource ->
        val client = requireOAuthClient(state) ?: return@get

        val nonce = randomNonce()
        call.response.cookies.append(
            Cookie(
                name = STATE_COOKIE,
                value = encodeStateCookie(StateCookiePayload(nonce, resource.projectId, resource.returnTo)),
                maxAge = OAUTH_FLOW_LIFETIME_SECONDS,
                path = "/",
                httpOnly = true,
                secure = false, // local installs typically don't have HTTPS
                extensions = mapOf("SameSite" to "Lax"),
                encoding = CookieEncoding.URI_ENCODING,
            ),
        )

        val redirectUri = state.oauthPublicBaseUrl + OAuthGitHubClient.CALLBACK_PATH
        call.respondRedirect(client.authorizeUrl(redirectUri, nonce), permanent = false)
    }

    get<OAuthGithubCallbackResource> { resource ->
        val client = state.oauthGitHubClient
        if (client == null) {
            call.respondText(
                "OAuth is not configured on this lighting7 install.",
                ContentType.Text.Plain,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val cookie = call.request.cookies[STATE_COOKIE]?.let(::decodeStateCookie)
        if (cookie == null) {
            call.respondText("Missing or invalid OAuth state cookie — start the flow again.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@get
        }
        if (cookie.nonce != resource.state) {
            call.respondText("OAuth state mismatch — refusing the callback.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@get
        }

        // GitHub's hosted flow uses ?error=access_denied when the user clicks Cancel.
        resource.error?.let {
            call.respondText("GitHub authorization failed: $it", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            return@get
        }
        val code = resource.code
        if (code.isNullOrBlank()) {
            call.respondText("GitHub callback missing ?code= — refusing.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@get
        }

        try {
            val redirectUri = state.oauthPublicBaseUrl + OAuthGitHubClient.CALLBACK_PATH
            val token = withContext(Dispatchers.IO) { client.exchangeCode(code, redirectUri) }
            val user = withContext(Dispatchers.IO) { client.getAuthenticatedUser(token.accessToken) }
            persistIdentity(state, token, user)
        } catch (e: Exception) {
            logger.error("OAuth code exchange failed", e)
            call.respondText("OAuth exchange failed: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            return@get
        }

        call.response.cookies.append(Cookie(name = STATE_COOKIE, value = "", maxAge = 0, path = "/"))
        call.respondRedirect(computeReturnTo(cookie), permanent = false)
    }

    // ─── Device flow ───────────────────────────────────────────────────

    post<OAuthGithubDeviceStartResource> {
        val client = requireOAuthClient(state) ?: return@post
        try {
            val response = withContext(Dispatchers.IO) { client.startDeviceFlow() }
            call.respond(
                DeviceStartResponse(
                    deviceCode = response.deviceCode,
                    userCode = response.userCode,
                    verificationUri = response.verificationUri,
                    expiresIn = response.expiresIn,
                    interval = response.interval,
                ),
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("Device flow start failed: ${e.message}"))
        }
    }

    post<OAuthGithubDevicePollResource> {
        val client = requireOAuthClient(state) ?: return@post
        val request = call.receive<DevicePollRequest>()
        if (request.deviceCode.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("deviceCode must not be blank"))
            return@post
        }
        try {
            val result = withContext(Dispatchers.IO) { client.pollDeviceFlow(request.deviceCode) }
            when (result) {
                is DevicePollResult.Done -> {
                    val user = withContext(Dispatchers.IO) { client.getAuthenticatedUser(result.token.accessToken) }
                    persistIdentity(state, result.token, user)
                    call.respond(DevicePollResponse(DevicePollStatus.DONE, user.login))
                }
                DevicePollResult.Pending -> call.respond(DevicePollResponse(DevicePollStatus.PENDING))
                DevicePollResult.SlowDown -> call.respond(DevicePollResponse(DevicePollStatus.SLOW_DOWN))
                DevicePollResult.Expired -> call.respond(DevicePollResponse(DevicePollStatus.EXPIRED))
                DevicePollResult.Denied -> call.respond(DevicePollResponse(DevicePollStatus.DENIED))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("Device-flow poll failed: ${e.message}"))
        }
    }

    // ─── Identity ──────────────────────────────────────────────────────

    get<OAuthGithubIdentityResource> {
        val identity = transaction(state.database) {
            DaoOAuthIdentity.findGithubDefault()?.let {
                IdentityResponse(
                    connected = true,
                    login = it.githubLogin,
                    githubUserId = it.githubUserId,
                    accessExpiresAtMs = it.accessExpiresAtMs,
                    refreshExpiresAtMs = it.refreshExpiresAtMs,
                    connectedAtMs = it.connectedAtMs,
                    oauthConfigured = state.oauthGitHubClient != null,
                )
            }
        } ?: IdentityResponse(connected = false, oauthConfigured = state.oauthGitHubClient != null)
        call.respond(identity)
    }

    delete<OAuthGithubIdentityResource> {
        transaction(state.database) {
            DaoOAuthIdentity.findGithubDefault()?.delete()
        }
        withContext(Dispatchers.IO) { state.oauthTokenStore?.clear() }
        state.emitCloudSyncEvent(
            OAuthIdentityChangedOutMessage(
                provider = DaoOAuthIdentities.PROVIDER_GITHUB,
                connected = false,
            ),
        )
        call.respond(HttpStatusCode.NoContent)
    }

    // ─── Repos ─────────────────────────────────────────────────────────

    get<OAuthGithubRepositoriesResource> { resource ->
        withOAuthAccess(state) { client, accessToken ->
            try {
                val perPage = (resource.perPage ?: 30).coerceIn(1, 100)
                val page = (resource.page ?: 1).coerceAtLeast(1)
                val repos = withContext(Dispatchers.IO) {
                    client.listInstallationRepositories(accessToken, page = page, perPage = perPage)
                }
                val query = resource.query?.lowercase()?.takeIf { it.isNotBlank() }
                val filtered = repos
                    .filter { (it.permissions?.push ?: false) || (it.permissions?.admin ?: false) }
                    .filter { query == null || it.fullName.lowercase().contains(query) || it.name.lowercase().contains(query) }
                    .map(::toDto)
                call.respond(filtered)
            } catch (e: OAuthRateLimitedException) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(e.message ?: "rate limited"))
            } catch (e: OAuthException) {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("Repo list failed: ${e.message}"))
            }
        }
    }

    post<OAuthGithubRepositoriesResource> {
        // GitHub does its own validation on `name`; we only reject blank to give a
        // faster local error.
        val request = call.receive<CreateRepoBody>()
        if (request.name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("name must not be blank"))
            return@post
        }
        withOAuthAccess(state) { client, accessToken ->
            try {
                val created = withContext(Dispatchers.IO) {
                    client.createUserRepo(accessToken, name = request.name, private = request.private, description = request.description)
                }
                call.respond(toDto(created))
            } catch (e: OAuthRateLimitedException) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse(e.message ?: "rate limited"))
            } catch (e: OAuthException) {
                call.respond(HttpStatusCode.BadGateway, ErrorResponse("Create repo failed: ${e.message}"))
            }
        }
    }
}

// ─── Helpers ───────────────────────────────────────────────────────────

private val logger = LoggerFactory.getLogger("oauth.routes")

private const val STATE_COOKIE = "lighting7_oauth_state"
private const val OAUTH_FLOW_LIFETIME_SECONDS = 600 // 10 minutes

private val secureRandom = SecureRandom()

private fun randomNonce(): String {
    val bytes = ByteArray(24)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

@Serializable
private data class StateCookiePayload(
    val nonce: String,
    val projectId: String? = null,
    val returnTo: String? = null,
)

private fun encodeStateCookie(payload: StateCookiePayload): String =
    OAUTH_JSON.encodeToString(StateCookiePayload.serializer(), payload)

private fun decodeStateCookie(raw: String): StateCookiePayload? = try {
    OAUTH_JSON.decodeFromString(StateCookiePayload.serializer(), raw)
} catch (_: Exception) {
    null
}

private fun computeReturnTo(cookie: StateCookiePayload): String {
    // `startsWith("/") && !startsWith("//")` blocks protocol-relative open-redirects
    // through the user-supplied returnTo.
    cookie.returnTo?.takeIf { it.startsWith("/") && !it.startsWith("//") }?.let { return it }
    if (!cookie.projectId.isNullOrBlank()) return "/projects/${cookie.projectId}/sync"
    return "/"
}

/** Respond 503 and return null if OAuth isn't configured on this install. */
private suspend fun RoutingContext.requireOAuthClient(state: State): OAuthGitHubClient? {
    val client = state.oauthGitHubClient
    if (client == null) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("OAuth is not configured."))
        return null
    }
    return client
}

/**
 * Fetch a fresh access token via the configured provider and run [block]. Replies 503
 * when OAuth isn't configured and 401 when the refresh token has been rejected.
 */
private suspend inline fun RoutingContext.withOAuthAccess(
    state: State,
    block: (OAuthGitHubClient, String) -> Unit,
) {
    val client = state.oauthGitHubClient
    val provider = state.oauthTokenProvider
    if (client == null || provider == null) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("OAuth is not configured."))
        return
    }
    val accessToken = try {
        withContext(Dispatchers.IO) { provider.accessToken() }
    } catch (e: OAuthReauthRequiredException) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(e.message ?: "OAuth re-auth required"))
        return
    }
    block(client, accessToken)
}

private fun persistIdentity(state: State, token: TokenResponse, user: GithubUser) {
    val identity = newStoredIdentity(token, user, System.currentTimeMillis())
    state.oauthTokenStore?.save(identity)
        ?: error("Token store should be present whenever OAuth client is configured")
    transaction(state.database) {
        val existing = DaoOAuthIdentity.findGithubDefault()
        if (existing != null) {
            existing.githubLogin = identity.githubLogin
            existing.githubUserId = identity.githubUserId
            existing.accessExpiresAtMs = identity.accessExpiresAtMs
            existing.refreshExpiresAtMs = identity.refreshExpiresAtMs
            existing.connectedAtMs = identity.connectedAtMs
        } else {
            DaoOAuthIdentity.new {
                this.provider = DaoOAuthIdentities.PROVIDER_GITHUB
                this.scope = DaoOAuthIdentities.DEFAULT_SCOPE
                this.githubLogin = identity.githubLogin
                this.githubUserId = identity.githubUserId
                this.accessExpiresAtMs = identity.accessExpiresAtMs
                this.refreshExpiresAtMs = identity.refreshExpiresAtMs
                this.connectedAtMs = identity.connectedAtMs
            }
        }
    }
    state.emitCloudSyncEvent(
        OAuthIdentityChangedOutMessage(
            provider = DaoOAuthIdentities.PROVIDER_GITHUB,
            connected = true,
            login = identity.githubLogin,
            accessExpiresAtMs = identity.accessExpiresAtMs,
            refreshExpiresAtMs = identity.refreshExpiresAtMs,
        ),
    )
}

private fun toDto(repo: GithubRepository) = RepoDto(
    fullName = repo.fullName,
    name = repo.name,
    owner = repo.owner.login,
    private = repo.private,
    defaultBranch = repo.defaultBranch,
    htmlUrl = repo.htmlUrl,
    cloneUrl = repo.cloneUrl,
    description = repo.description,
    pushPermission = repo.permissions?.push ?: false,
)

// ─── Resources & DTOs ───────────────────────────────────────────────────

@Resource("/oauth/github/start")
data class OAuthGithubStartResource(val projectId: String? = null, val returnTo: String? = null)

@Resource("/oauth/github/callback")
data class OAuthGithubCallbackResource(val code: String? = null, val state: String? = null, val error: String? = null)

@Resource("/oauth/github/device/start")
class OAuthGithubDeviceStartResource

@Resource("/oauth/github/device/poll")
class OAuthGithubDevicePollResource

@Resource("/oauth/github/identity")
class OAuthGithubIdentityResource

@Resource("/oauth/github/repositories")
data class OAuthGithubRepositoriesResource(val query: String? = null, val page: Int? = null, val perPage: Int? = null)

@Serializable
data class IdentityResponse(
    val connected: Boolean,
    val login: String? = null,
    val githubUserId: Long? = null,
    val accessExpiresAtMs: Long? = null,
    val refreshExpiresAtMs: Long? = null,
    val connectedAtMs: Long? = null,
    /** False means the UI should hide the OAuth button and offer only the PAT path. */
    val oauthConfigured: Boolean,
)

@Serializable
data class DeviceStartResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresIn: Long,
    val interval: Long,
)

@Serializable
data class DevicePollRequest(val deviceCode: String)

/** Mirrors [DevicePollResult] for wire transport; kept in lockstep with the frontend
 *  `DeviceFlowStatus` string-union in `oauthGithub.ts`. */
@Suppress("unused")
enum class DevicePollStatus { PENDING, SLOW_DOWN, DONE, EXPIRED, DENIED }

@Serializable
data class DevicePollResponse(val status: DevicePollStatus, val login: String? = null)

@Serializable
data class CreateRepoBody(val name: String, val private: Boolean = true, val description: String? = null)

@Serializable
data class RepoDto(
    val fullName: String,
    val name: String,
    val owner: String,
    val private: Boolean,
    val defaultBranch: String,
    val htmlUrl: String,
    val cloneUrl: String,
    val description: String?,
    val pushPermission: Boolean,
)
