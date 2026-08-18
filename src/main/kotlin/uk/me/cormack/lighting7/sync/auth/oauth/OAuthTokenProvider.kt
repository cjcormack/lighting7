package uk.me.cormack.lighting7.sync.auth.oauth

import kotlinx.coroutines.CancellationException
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
    /**
     * Called after any change this provider makes to the stored identity — a successful
     * refresh *and* a "GitHub rejected us, re-auth required" marking. Production wiring
     * mirrors the non-secret fields into `oauth_identities` and broadcasts the change, so
     * an open UI learns about a dead authorisation without waiting for a manual sync.
     */
    private val onIdentityUpdated: (suspend (StoredOAuthIdentity) -> Unit)? = null,
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
     *
     * Where there is an identity to write to, an [OAuthReauthRequiredException] raised from
     * here also *records* the fact on it (see [failReauth]) — that is what lets the UI say
     * "reconnect required" instead of "connected". Transient failures (network, 5xx, rate
     * limit) propagate without marking anything, so they keep retrying.
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
                ?: failReauth(refreshed, "Access token expired and no refresh token is stored — user must re-connect.")

            // Refresh-token-itself-expired check: GitHub will reject the refresh below
            // anyway, but failing fast saves a network round-trip and gives a cleaner
            // error code at the route layer.
            val refreshExp = refreshed.refreshExpiresAtMs
            if (refreshExp != null && refreshExp <= nowMs()) {
                failReauth(
                    refreshed,
                    "Refresh token expired at ${java.time.Instant.ofEpochMilli(refreshExp)} — user must re-connect.",
                )
            }

            logRefreshAttempt(refreshed)

            val response = try {
                client.refresh(refreshToken)
            } catch (e: OAuthReauthRequiredException) {
                // GitHub said no in a way only the user can fix (bad_refresh_token /
                // invalid_grant). Record it so this doesn't have to be rediscovered on
                // every subsequent call. Other exception types are transient — let them
                // through unmarked.
                failReauth(refreshed, e.message ?: "GitHub rejected the refresh token — user must re-connect.")
            }
            // Persist BEFORE returning so a process crash between refresh and use doesn't
            // lose the new pair. If the write fails we propagate — the caller treats it
            // as fatal rather than proceeding with an unsaved refresh.
            val updated = refreshed.applyRefresh(response, nowMs())
            tokenStore.save(updated)
            onIdentityUpdated?.invoke(updated)
            updated.accessToken
        }
    }

    private fun shouldRefresh(identity: StoredOAuthIdentity): Boolean {
        val exp = identity.accessExpiresAtMs ?: return false // No expiry → never refresh.
        return exp - nowMs() <= refreshThresholdMs
    }

    /**
     * A token that lapsed while nobody was looking is not the same event as a routine
     * proactive refresh, so it doesn't get the same log level — and neither reads well as
     * raw millis (an identity dead for a month logged `-2180852647`).
     */
    private fun logRefreshAttempt(identity: StoredOAuthIdentity) {
        val exp = identity.accessExpiresAtMs
        when {
            exp == null -> logger.info(
                "Refreshing GitHub OAuth token for {} (no recorded access-token expiry)",
                identity.githubLogin,
            )
            exp <= nowMs() -> logger.warn(
                "Refreshing GitHub OAuth token for {} — access token expired at {}",
                identity.githubLogin,
                java.time.Instant.ofEpochMilli(exp),
            )
            else -> logger.info(
                "Refreshing GitHub OAuth token for {} (expires at {}, in {}s)",
                identity.githubLogin,
                java.time.Instant.ofEpochMilli(exp),
                (exp - nowMs()) / 1_000,
            )
        }
    }

    /**
     * Record that [rejected] can only be fixed by re-connecting, then throw. Returns
     * [Nothing] so call sites can use it in an elvis or a statement position.
     *
     * **Re-reads the store and marks that copy, not [rejected].** `refresh()` is a network
     * round-trip, and `persistIdentity` in the OAuth routes writes the blob directly —
     * outside [refreshMutex], which only serialises this class. So the user can complete a
     * re-connect while we are waiting on GitHub's verdict about the *old* token. Writing our
     * captured snapshot back would then overwrite that fresh identity with the dead one and
     * broadcast `reauthRequired`, silently undoing a re-connect seconds after it succeeded —
     * in exactly the situation this whole mechanism exists to handle.
     *
     * A changed refresh token therefore means "someone re-connected under us": leave it
     * alone, because GitHub's verdict was about a token that is no longer stored. There is
     * no CAS on the credential store, so a sliver of a window survives between the read and
     * the write; it is microseconds rather than a network round-trip, and no stale token
     * material is ever written back.
     *
     * The store write and the mirror callback are best-effort: the exception the caller needs
     * to see must not be replaced by a credential-store failure. Marking an already-marked
     * identity is a no-op so a backed-off retry doesn't keep rewriting the blob or
     * re-broadcasting, and `reauthRequiredAtMs` keeps meaning *first* seen.
     */
    private suspend fun failReauth(rejected: StoredOAuthIdentity, message: String): Nothing {
        try {
            val current = tokenStore.load()
            val supersededByReconnect = current != null && current.refreshToken != rejected.refreshToken
            if (current != null && !supersededByReconnect && current.reauthRequiredAtMs == null) {
                val marked = current.copy(reauthRequiredAtMs = nowMs(), reauthReason = message)
                tokenStore.save(marked)
                onIdentityUpdated?.invoke(marked)
            } else if (supersededByReconnect) {
                logger.info("Discarding stale OAuth rejection — the identity was re-connected while we asked GitHub.")
            }
        } catch (e: CancellationException) {
            // `onIdentityUpdated` is a suspending hook, so a `runCatching` here would quietly
            // convert a cancellation into the domain exception below and leave the coroutine
            // running past its own cancellation.
            throw e
        } catch (t: Throwable) {
            logger.warn("Failed to record OAuth re-auth requirement: {}", t.toString())
        }
        throw OAuthReauthRequiredException(message)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OAuthTokenProvider::class.java)
    }
}

/**
 * Build a refreshed identity from the previous one + the new [TokenResponse]. The login
 * / user ID are carried over (a refresh never changes who the user is).
 *
 * The re-auth marking is cleared explicitly: this is a `copy`, so a previously-flagged
 * identity would otherwise stay flagged after the very refresh that proved it healthy.
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
    reauthRequiredAtMs = null,
    reauthReason = null,
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
