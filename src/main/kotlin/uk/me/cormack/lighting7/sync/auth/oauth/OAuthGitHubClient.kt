package uk.me.cormack.lighting7.sync.auth.oauth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.URLEncoder
import java.util.Base64

/**
 * Narrow interface the [OAuthTokenProvider] depends on. Allows tests to substitute a
 * fake refresher without spinning up a real HTTP client.
 */
interface OAuthRefreshClient {
    suspend fun refresh(refreshToken: String): TokenResponse
}

/**
 * Thin HTTP client for the GitHub OAuth (web + device flow) and GitHub REST surfaces we
 * actually use during cloud-sync auth setup.
 *
 * Lifecycle: one instance per [State][uk.me.cormack.lighting7.state.State]. Closed on
 * shutdown so the underlying CIO engine releases its threads.
 *
 * No retries / rate-limit handling: the OAuth endpoints are user-driven and infrequent;
 * callers surface failures up the route layer rather than burying them in client logic.
 *
 * Maps GitHub error responses to typed [OAuthException] subclasses so `routes/oauth.kt`
 * can convert them into stable HTTP status codes for the UI.
 */
class OAuthGitHubClient(
    val clientId: String,
    private val clientSecret: String,
) : Closeable, OAuthRefreshClient {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(OAUTH_JSON)
        }
        // GitHub returns its own JSON error shape on 4xx; we throw rather than letting
        // ContentNegotiation auto-decode into an unrelated class.
        expectSuccess = false
    }

    suspend fun exchangeCode(code: String, redirectUri: String): TokenResponse {
        val response = client.post("$OAUTH_BASE/login/oauth/access_token") {
            accept(ContentType.Application.Json)
            setBody(FormDataContent(parametersOf(
                "client_id" to listOf(clientId),
                "client_secret" to listOf(clientSecret),
                "code" to listOf(code),
                "redirect_uri" to listOf(redirectUri),
            )))
        }
        return parseTokenResponse(response.bodyAsText(), response.status)
    }

    /**
     * GitHub issues a new pair on each refresh; the old refresh token is invalidated
     * immediately, so a partial write must be treated as fatal upstream.
     */
    override suspend fun refresh(refreshToken: String): TokenResponse {
        val response = client.post("$OAUTH_BASE/login/oauth/access_token") {
            accept(ContentType.Application.Json)
            setBody(FormDataContent(parametersOf(
                "client_id" to listOf(clientId),
                "client_secret" to listOf(clientSecret),
                "grant_type" to listOf("refresh_token"),
                "refresh_token" to listOf(refreshToken),
            )))
        }
        return parseTokenResponse(response.bodyAsText(), response.status)
    }

    suspend fun startDeviceFlow(): DeviceCodeResponse {
        val response = client.post("$OAUTH_BASE/login/device/code") {
            accept(ContentType.Application.Json)
            setBody(FormDataContent(parametersOf("client_id" to listOf(clientId))))
        }
        if (!response.status.isSuccess()) {
            throw OAuthException("Device-flow start failed: HTTP ${response.status.value} — ${response.bodyAsText().trim()}")
        }
        return OAUTH_JSON.decodeFromString(DeviceCodeResponse.serializer(), response.bodyAsText())
    }

    /**
     * Device-flow polling always returns 200 from GitHub; the body distinguishes
     * pending / done / expired. `slow_down` is a poll-rate hint, not a failure.
     */
    suspend fun pollDeviceFlow(deviceCode: String): DevicePollResult {
        val response = client.post("$OAUTH_BASE/login/oauth/access_token") {
            accept(ContentType.Application.Json)
            setBody(FormDataContent(parametersOf(
                "client_id" to listOf(clientId),
                "device_code" to listOf(deviceCode),
                "grant_type" to listOf("urn:ietf:params:oauth:grant-type:device_code"),
            )))
        }
        val text = response.bodyAsText()
        return when (val parsed = parseTokenOrError(text)) {
            is TokenOrError.Token -> DevicePollResult.Done(parsed.token)
            is TokenOrError.Error -> when (parsed.error) {
                "authorization_pending" -> DevicePollResult.Pending
                "slow_down" -> DevicePollResult.SlowDown
                "expired_token" -> DevicePollResult.Expired
                "access_denied" -> DevicePollResult.Denied
                else -> throw OAuthException("Device-flow poll failed: ${parsed.error} — ${parsed.description ?: ""}")
            }
        }
    }

    suspend fun getAuthenticatedUser(accessToken: String): GithubUser {
        val response = client.get("$API_BASE/user") {
            githubApiHeaders(accessToken)
        }
        if (!response.status.isSuccess()) {
            throw classifyError(response.status, response.bodyAsText(), "GET /user")
        }
        return response.body()
    }

    /**
     * List repositories accessible to the user-to-server [accessToken] **through the
     * GitHub App's installation(s)**. Honours the App's per-repo permission grant —
     * "Selected repositories" mode returns only those repos; "All repositories" returns
     * everything the user can see.
     *
     * Installations are fetched in parallel; per-installation pagination still serialises
     * because we need each page's count to decide whether to ask for the next.
     */
    suspend fun listInstallationRepositories(
        accessToken: String,
        page: Int = 1,
        perPage: Int = 30,
    ): List<GithubRepository> = coroutineScope {
        val installations = listInstallations(accessToken).installations
        installations
            .map { installation ->
                async { fetchAllInstallationRepos(accessToken, installation.id, page, perPage) }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun fetchAllInstallationRepos(
        accessToken: String,
        installationId: Long,
        startPage: Int,
        perPage: Int,
    ): List<GithubRepository> {
        val collected = mutableListOf<GithubRepository>()
        var currentPage = startPage
        while (true) {
            val response = client.get("$API_BASE/user/installations/$installationId/repositories") {
                githubApiHeaders(accessToken)
                parameter("page", currentPage)
                parameter("per_page", perPage)
            }
            if (!response.status.isSuccess()) {
                throw classifyError(response.status, response.bodyAsText(), "GET /user/installations/{id}/repositories")
            }
            val body = response.body<InstallationRepositoriesResponse>()
            collected.addAll(body.repositories)
            if (body.repositories.size < perPage) break
            currentPage += 1
        }
        return collected
    }

    private suspend fun listInstallations(accessToken: String): InstallationsResponse {
        val response = client.get("$API_BASE/user/installations") {
            githubApiHeaders(accessToken)
        }
        if (!response.status.isSuccess()) {
            throw classifyError(response.status, response.bodyAsText(), "GET /user/installations")
        }
        return response.body()
    }

    /**
     * Requires `Administration: write` on the GitHub App. Newly created repos may not be
     * automatically attached to the App's installation when it's set to "Selected
     * repositories" — the route layer surfaces a deep-link in that case.
     */
    suspend fun createUserRepo(
        accessToken: String,
        name: String,
        private: Boolean,
        description: String?,
    ): GithubRepository {
        val response = client.post("$API_BASE/user/repos") {
            githubApiHeaders(accessToken)
            setBody(CreateRepoRequest(name = name, private = private, description = description))
        }
        if (!response.status.isSuccess()) {
            throw classifyError(response.status, response.bodyAsText(), "POST /user/repos")
        }
        return response.body()
    }

    /**
     * Fetch a single small text file from a repo via the GitHub Contents API, or `null`
     * when the file doesn't exist on [ref] (HTTP 404). Lets callers peek at metadata files
     * (e.g. `project.json`) without cloning. Only suitable for small files — the Contents
     * API inlines content as base64 up to ~1 MB, which the files we probe never approach.
     */
    suspend fun fetchRepoTextFile(
        accessToken: String,
        owner: String,
        repo: String,
        path: String,
        ref: String,
    ): String? {
        val response = client.get("$API_BASE/repos/$owner/$repo/contents/$path") {
            githubApiHeaders(accessToken)
            parameter("ref", ref)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            throw classifyError(response.status, response.bodyAsText(), "GET /repos/$owner/$repo/contents/$path")
        }
        val body = response.body<ContentsResponse>()
        val content = body.content ?: return null
        // GitHub returns base64 with embedded newlines; the MIME decoder tolerates them.
        return String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8)
    }

    /** Build the full `https://github.com/login/oauth/authorize?...` URL. */
    fun authorizeUrl(redirectUri: String, state: String): String {
        val q = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "state" to state,
        ).joinToString("&") { (k, v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        return "$OAUTH_BASE/login/oauth/authorize?$q"
    }

    override fun close() {
        client.close()
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun io.ktor.client.request.HttpRequestBuilder.githubApiHeaders(accessToken: String) {
        accept(ContentType("application", "vnd.github+json"))
        header("Authorization", "Bearer $accessToken")
        header("X-GitHub-Api-Version", API_VERSION)
        header("Content-Type", ContentType.Application.Json.toString())
    }

    private fun parseTokenResponse(body: String, status: HttpStatusCode): TokenResponse {
        return when (val parsed = parseTokenOrError(body)) {
            is TokenOrError.Token -> parsed.token
            is TokenOrError.Error -> {
                if (parsed.error == "bad_refresh_token" || parsed.error == "invalid_grant") {
                    throw OAuthReauthRequiredException(
                        "GitHub rejected the refresh token (${parsed.error}); user must re-connect.",
                    )
                }
                throw OAuthException(
                    "OAuth token endpoint returned error '${parsed.error}'" +
                        (parsed.description?.let { ": $it" } ?: " (HTTP ${status.value})"),
                )
            }
        }
    }

    /**
     * GitHub's token endpoint returns a JSON object with either token fields or
     * `{ error, error_description, error_uri }`. Two-attempt decode is simpler than a
     * polymorphic serializer and matches the device-flow contract (always 200).
     */
    private fun parseTokenOrError(body: String): TokenOrError {
        return try {
            TokenOrError.Token(OAUTH_JSON.decodeFromString(TokenResponse.serializer(), body))
        } catch (_: Exception) {
            try {
                val err = OAUTH_JSON.decodeFromString(OAuthErrorBody.serializer(), body)
                TokenOrError.Error(err.error, err.errorDescription)
            } catch (e: Exception) {
                throw OAuthException("Unparseable OAuth response: ${body.take(200)}", e)
            }
        }
    }

    private fun classifyError(status: HttpStatusCode, body: String, what: String): Exception {
        val trimmed = body.trim().take(500)
        return when (status) {
            HttpStatusCode.Unauthorized -> OAuthReauthRequiredException(
                "GitHub rejected the access token on $what (HTTP 401) — user must re-connect.",
            )
            HttpStatusCode.Forbidden -> {
                if ("rate limit" in body.lowercase()) {
                    OAuthRateLimitedException("GitHub rate limit exceeded on $what: $trimmed")
                } else {
                    OAuthException("$what failed (HTTP 403): $trimmed")
                }
            }
            else -> OAuthException("$what failed (HTTP ${status.value}): $trimmed")
        }
    }

    private sealed class TokenOrError {
        data class Token(val token: TokenResponse) : TokenOrError()
        data class Error(val error: String, val description: String?) : TokenOrError()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OAuthGitHubClient::class.java)

        const val OAUTH_BASE = "https://github.com"
        const val API_BASE = "https://api.github.com"
        const val API_VERSION = "2022-11-28"

        /**
         * Path under the configured public base URL that handles the OAuth callback.
         * Must match the Callback URL registered on the GitHub App.
         */
        const val CALLBACK_PATH = "/api/rest/oauth/github/callback"
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

// ─── DTOs ───────────────────────────────────────────────────────────────

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String = "bearer",
    val scope: String? = null,
)

@Serializable
data class OAuthErrorBody(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_uri") val errorUri: String? = null,
)

@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Long = 5,
)

sealed class DevicePollResult {
    data class Done(val token: TokenResponse) : DevicePollResult()
    object Pending : DevicePollResult()
    object SlowDown : DevicePollResult()
    object Expired : DevicePollResult()
    object Denied : DevicePollResult()
}

@Serializable
data class GithubUser(
    val id: Long,
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class GithubRepository(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val private: Boolean,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("clone_url") val cloneUrl: String,
    val description: String? = null,
    val permissions: RepoPermissions? = null,
    val owner: RepoOwner,
)

@Serializable
data class RepoOwner(
    val login: String,
    val id: Long,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class RepoPermissions(
    val admin: Boolean = false,
    val push: Boolean = false,
    val pull: Boolean = false,
    val maintain: Boolean = false,
)

@Serializable
internal data class InstallationsResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    val installations: List<Installation> = emptyList(),
)

@Serializable
internal data class Installation(
    val id: Long,
    val account: RepoOwner? = null,
    @SerialName("repository_selection") val repositorySelection: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
internal data class InstallationRepositoriesResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("repository_selection") val repositorySelection: String? = null,
    val repositories: List<GithubRepository> = emptyList(),
)

@Serializable
internal data class CreateRepoRequest(
    val name: String,
    val private: Boolean,
    val description: String? = null,
    @SerialName("auto_init") val autoInit: Boolean = false,
)

/** GitHub Contents API response for a single file — content is base64 (`encoding: "base64"`). */
@Serializable
data class ContentsResponse(
    val content: String? = null,
    val encoding: String? = null,
)

// ─── Errors ─────────────────────────────────────────────────────────────

open class OAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The user must re-run the OAuth flow — refresh token expired or revoked. */
class OAuthReauthRequiredException(message: String) : OAuthException(message)

/** GitHub responded with a documented rate limit — user should retry later. */
class OAuthRateLimitedException(message: String) : OAuthException(message)
