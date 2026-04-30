package uk.me.cormack.lighting7.sync.auth.oauth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Returns a usable GitHub access token, refreshing it if it's at or near expiry.
 *
 * Single-flight: a [Mutex] guards the read-refresh-write sequence so two concurrent git
 * operations don't both POST to the refresh endpoint and burn the (single-use) refresh
 * token. The [RemoteSyncEngine][uk.me.cormack.lighting7.sync.RemoteSyncEngine] already
 * serialises sync per project, but a project list refresh and a sync can overlap, hence
 * the lock.
 *
 * The provider is the single throat through which all callers (JGit, REST API helpers,
 * route handlers) reach the access token. Centralising refresh here keeps the OAuth
 * lifecycle out of every call site.
 */
class OAuthTokenProvider(
    private val tokenStore: OAuthTokenStore,
    private val client: OAuthRefreshClient,
    private val onRefreshed: (suspend (StoredOAuthIdentity) -> Unit)? = null,
    /**
     * Refresh proactively when the access token has less than this many millis left.
     * 60s is enough headroom for a slow git push without forcing a refresh on every
     * call. Configurable so tests can pin it.
     */
    private val refreshThresholdMs: Long = 60_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val refreshMutex = Mutex()

    /**
     * Returns a non-expired access token. Throws if no identity is stored or the
     * refresh token has itself expired.
     */
    suspend fun accessToken(): String {
        val current = tokenStore.load()
            ?: throw OAuthReauthRequiredException("No GitHub OAuth identity stored — connect via the sync configuration UI.")
        if (!shouldRefresh(current)) return current.accessToken

        return refreshMutex.withLock {
            // Re-read inside the lock — another caller may have refreshed while we waited.
            val refreshed = tokenStore.load()
                ?: throw OAuthReauthRequiredException("OAuth identity vanished during refresh.")
            if (!shouldRefresh(refreshed)) return@withLock refreshed.accessToken

            val refreshToken = refreshed.refreshToken
                ?: throw OAuthReauthRequiredException(
                    "Access token expired and no refresh token is stored — user must re-connect.",
                )

            // Refresh-token-itself-expired check: GitHub will reject the refresh below
            // anyway, but failing fast saves a network round-trip and gives a cleaner
            // error code at the route layer.
            val refreshExp = refreshed.refreshExpiresAtMs
            if (refreshExp != null && refreshExp <= nowMs()) {
                throw OAuthReauthRequiredException(
                    "Refresh token expired at ${java.time.Instant.ofEpochMilli(refreshExp)} — user must re-connect.",
                )
            }

            logger.info(
                "Refreshing GitHub OAuth token for {} (expires in {} ms)",
                refreshed.githubLogin,
                refreshed.accessExpiresAtMs?.let { it - nowMs() } ?: -1,
            )

            val response = client.refresh(refreshToken)
            // Persist BEFORE returning so a process crash between refresh and use doesn't
            // lose the new pair. If the write fails we propagate — the caller treats it
            // as fatal rather than proceeding with an unsaved refresh.
            val updated = refreshed.applyRefresh(response, nowMs())
            tokenStore.save(updated)
            onRefreshed?.invoke(updated)
            updated.accessToken
        }
    }

    private fun shouldRefresh(identity: StoredOAuthIdentity): Boolean {
        val exp = identity.accessExpiresAtMs ?: return false // No expiry → never refresh.
        return exp - nowMs() <= refreshThresholdMs
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OAuthTokenProvider::class.java)
    }
}

/**
 * Build a refreshed identity from the previous one + the new [TokenResponse]. The login
 * / user ID are carried over (a refresh never changes who the user is).
 */
internal fun StoredOAuthIdentity.applyRefresh(
    response: TokenResponse,
    nowMs: Long,
): StoredOAuthIdentity = copy(
    accessToken = response.accessToken,
    refreshToken = response.refreshToken ?: refreshToken,
    accessExpiresAtMs = response.expiresIn?.let { nowMs + it * 1_000 },
    refreshExpiresAtMs = response.refreshTokenExpiresIn?.let { nowMs + it * 1_000 },
    tokenType = response.tokenType,
    scopes = response.scope?.split(",", " ", ";")?.filter { it.isNotBlank() } ?: scopes,
)

/**
 * Build a fresh identity from a TokenResponse + identifying user info. Used after the
 * initial code-exchange / device-flow completion.
 */
fun newStoredIdentity(
    response: TokenResponse,
    user: GithubUser,
    nowMs: Long = System.currentTimeMillis(),
): StoredOAuthIdentity = StoredOAuthIdentity(
    accessToken = response.accessToken,
    refreshToken = response.refreshToken,
    accessExpiresAtMs = response.expiresIn?.let { nowMs + it * 1_000 },
    refreshExpiresAtMs = response.refreshTokenExpiresIn?.let { nowMs + it * 1_000 },
    githubLogin = user.login,
    githubUserId = user.id,
    tokenType = response.tokenType,
    scopes = response.scope?.split(",", " ", ";")?.filter { it.isNotBlank() } ?: emptyList(),
    connectedAtMs = nowMs,
)
