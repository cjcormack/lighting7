package uk.me.cormack.lighting7.sync.auth.oauth

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.sync.auth.CredentialStore

/**
 * Persists the install-wide GitHub OAuth identity in [CredentialStore] under the
 * well-known key [CredentialStore.OAUTH_GITHUB_DEFAULT_KEY].
 *
 * The whole identity (access token, refresh token, expiries, GitHub user metadata) is
 * serialised to a single JSON blob — refresh always overwrites the entire entry so a
 * partial write can't leave the store with a fresh access token paired with a stale
 * refresh token.
 *
 * The non-secret subset (login, expiries) is mirrored into the `oauth_identities`
 * table by the route layer so the UI can show "Connected as @ccormack" without
 * round-tripping through the credential store on every render.
 */
class OAuthTokenStore(
    private val credentialStore: CredentialStore,
    private val key: String = CredentialStore.OAUTH_GITHUB_DEFAULT_KEY,
) {

    fun load(): StoredOAuthIdentity? {
        val raw = credentialStore.getBlob(key) ?: return null
        return try {
            OAUTH_JSON.decodeFromString(StoredOAuthIdentity.serializer(), raw)
        } catch (_: Exception) {
            // A corrupted blob is treated as "no identity" so a clean reauth path still
            // works after a bad write.
            null
        }
    }

    /** Cheaper than [load] on backends that have a presence-only primitive. */
    fun isPresent(): Boolean = credentialStore.containsBlob(key)

    fun save(identity: StoredOAuthIdentity) {
        credentialStore.setBlob(key, OAUTH_JSON.encodeToString(StoredOAuthIdentity.serializer(), identity))
    }

    /** No-op if no identity is stored. */
    fun clear() {
        credentialStore.deleteBlob(key)
    }
}

/**
 * Serialised shape of the install-wide OAuth identity. Times are epoch millis so
 * comparisons against `System.currentTimeMillis()` are direct.
 *
 * `accessExpiresAtMs == null` means "doesn't expire" (e.g. classic OAuth App tokens).
 * `refreshToken == null` means we can't refresh — the user re-runs the OAuth flow once
 * the access token is rejected.
 */
@Serializable
data class StoredOAuthIdentity(
    val accessToken: String,
    val refreshToken: String? = null,
    val accessExpiresAtMs: Long? = null,
    val refreshExpiresAtMs: Long? = null,
    val githubLogin: String,
    val githubUserId: Long,
    val tokenType: String = "bearer",
    val scopes: List<String> = emptyList(),
    /** When this identity was first stored, so the UI can show "connected since X". */
    val connectedAtMs: Long,
)
