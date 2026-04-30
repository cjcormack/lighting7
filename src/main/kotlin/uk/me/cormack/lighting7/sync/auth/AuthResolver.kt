package uk.me.cormack.lighting7.sync.auth

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.sync.GitCredentials
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenProvider
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenStore

/**
 * Picks JGit credentials for [repoUrl] from the available auth sources.
 *
 * Resolution order:
 *  1. **Install-wide GitHub OAuth identity**, if connected. Auto-refreshes the access
 *     token via [OAuthTokenProvider]. The same `x-access-token` username form GitHub
 *     uses for PATs works for user-to-server tokens too, so JGit transport is identical.
 *  2. **Per-repo PAT**, if the user has stored one via the Advanced/PAT path.
 *  3. Otherwise throws [MissingCredentialsException]. The route layer maps this to a
 *     401 with a stable code so the UI can prompt connect-or-PAT.
 *
 * The OAuth path is preferred when present even if a PAT also exists — the PAT then
 * serves only as a manual override the user can clear once OAuth is working.
 */
class AuthResolver(
    private val credentialStore: CredentialStore,
    /** May be null if `sync.oauth.github.clientId` is unset — then OAuth is not an option. */
    private val tokenStore: OAuthTokenStore?,
    private val tokenProvider: OAuthTokenProvider?,
) {

    /**
     * Resolve credentials for [repoUrl]. Suspends because the OAuth refresh path may
     * make a network call.
     *
     * If OAuth is present but the refresh token has expired
     * ([OAuthReauthRequiredException]), we fall through to the PAT path so a user with
     * a stored PAT can keep syncing while they sort out OAuth re-connection. If neither
     * works, throws [MissingCredentialsException].
     */
    suspend fun resolveFor(repoUrl: String): GitCredentials {
        when (val attempt = tryOAuth()) {
            is OAuthAttempt.Success -> return GitCredentials.forGitHubToken(attempt.accessToken)
            is OAuthAttempt.NoIdentity -> {
                val pat = credentialStore.get(repoUrl)
                    ?: throw MissingCredentialsException(
                        "No GitHub credentials configured for this repository — connect via 'Connect GitHub' " +
                            "or store a Personal Access Token under the Advanced section.",
                    )
                return GitCredentials.forGitHubToken(pat)
            }
            is OAuthAttempt.Failed -> {
                val pat = credentialStore.get(repoUrl)
                if (pat != null) {
                    logger.info(
                        "OAuth identity present but unusable ({}); falling back to PAT for {}",
                        attempt.message,
                        repoUrl,
                    )
                    return GitCredentials.forGitHubToken(pat)
                }
                // OAuth was the more specific failure (token rejected); surface it so the
                // UI's error code is more useful than a generic missing-credentials.
                throw OAuthReauthRequiredException(attempt.message ?: "OAuth refresh failed")
            }
        }
    }

    /** Cheap "do we have any credentials?" probe used by the sync config UI. */
    suspend fun hasAnyCredentialsFor(repoUrl: String): Boolean {
        if (tokenStore?.isPresent() == true) return true
        return credentialStore.contains(repoUrl)
    }

    private suspend fun tryOAuth(): OAuthAttempt {
        val store = tokenStore ?: return OAuthAttempt.NoIdentity("OAuth not configured")
        val provider = tokenProvider ?: return OAuthAttempt.NoIdentity("OAuth not configured")
        if (!store.isPresent()) return OAuthAttempt.NoIdentity("No OAuth identity stored")
        return try {
            OAuthAttempt.Success(provider.accessToken())
        } catch (e: OAuthReauthRequiredException) {
            OAuthAttempt.Failed(e.message)
        } catch (e: Exception) {
            // Don't let an unexpected OAuth failure (network, parse) prevent PAT fallback.
            OAuthAttempt.Failed("OAuth refresh failed: ${e.message}")
        }
    }

    private sealed class OAuthAttempt {
        data class Success(val accessToken: String) : OAuthAttempt()
        data class NoIdentity(val message: String) : OAuthAttempt()
        data class Failed(val message: String?) : OAuthAttempt()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AuthResolver::class.java)
    }
}

/** No usable credentials found for the requested repo. Mapped to HTTP 401 by route layer. */
class MissingCredentialsException(message: String) : RuntimeException(message)
