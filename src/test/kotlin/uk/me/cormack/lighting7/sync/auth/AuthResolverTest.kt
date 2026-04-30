package uk.me.cormack.lighting7.sync.auth

import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthRefreshClient
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenProvider
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenStore
import uk.me.cormack.lighting7.sync.auth.oauth.StoredOAuthIdentity
import uk.me.cormack.lighting7.sync.auth.oauth.TokenResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AuthResolverTest {

    private val repoUrl = "https://github.com/me/lighting7-show.git"

    private val livingIdentity = StoredOAuthIdentity(
        accessToken = "oauth-token",
        refreshToken = "r",
        accessExpiresAtMs = Long.MAX_VALUE,
        refreshExpiresAtMs = Long.MAX_VALUE,
        githubLogin = "octocat",
        githubUserId = 1L,
        connectedAtMs = 0L,
    )

    private fun providerFor(store: OAuthTokenStore, client: OAuthRefreshClient = NoRefresh): OAuthTokenProvider =
        OAuthTokenProvider(tokenStore = store, client = client, refreshThresholdMs = 0L)

    @Test
    fun `prefers OAuth over PAT when both are present`() = runBlocking {
        val cs = InMemoryCredentialStore()
        cs.set(repoUrl, "ghp_legacy_pat")
        val store = OAuthTokenStore(cs).also { it.save(livingIdentity) }
        val resolver = AuthResolver(cs, store, providerFor(store))

        val creds = resolver.resolveFor(repoUrl)
        assertEquals("x-access-token", creds.username)
        assertEquals("oauth-token", creds.secret)
    }

    @Test
    fun `falls back to PAT when no OAuth identity is stored`() = runBlocking {
        val cs = InMemoryCredentialStore()
        cs.set(repoUrl, "ghp_my_pat")
        val store = OAuthTokenStore(cs)
        val resolver = AuthResolver(cs, store, providerFor(store))

        val creds = resolver.resolveFor(repoUrl)
        assertEquals("ghp_my_pat", creds.secret)
    }

    @Test
    fun `falls back to PAT when OAuth refresh fails but PAT exists`() = runBlocking {
        val cs = InMemoryCredentialStore()
        cs.set(repoUrl, "ghp_backup_pat")
        val store = OAuthTokenStore(cs).also {
            it.save(livingIdentity.copy(accessExpiresAtMs = 0L, refreshExpiresAtMs = 1L))
        }
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = AlwaysFailRefresh,
            nowMs = { 100L }, // refresh expiry exceeded
        )
        val resolver = AuthResolver(cs, store, provider)

        val creds = resolver.resolveFor(repoUrl)
        assertEquals("ghp_backup_pat", creds.secret)
    }

    @Test
    fun `surfaces OAuth reauth error when no PAT fallback exists`() = runBlocking {
        val cs = InMemoryCredentialStore()
        val store = OAuthTokenStore(cs).also {
            it.save(livingIdentity.copy(accessExpiresAtMs = 0L, refreshExpiresAtMs = 1L))
        }
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = AlwaysFailRefresh,
            nowMs = { 100L },
        )
        val resolver = AuthResolver(cs, store, provider)

        try {
            resolver.resolveFor(repoUrl)
            fail("expected OAuthReauthRequiredException")
        } catch (_: OAuthReauthRequiredException) { /* expected */ }
    }

    @Test
    fun `MissingCredentialsException when neither OAuth nor PAT is present`() = runBlocking {
        val cs = InMemoryCredentialStore()
        val store = OAuthTokenStore(cs)
        val resolver = AuthResolver(cs, store, providerFor(store))
        try {
            resolver.resolveFor(repoUrl)
            fail("expected MissingCredentialsException")
        } catch (_: MissingCredentialsException) { /* expected */ }
    }

    @Test
    fun `works when OAuth is fully unconfigured (null providers)`() = runBlocking {
        val cs = InMemoryCredentialStore()
        cs.set(repoUrl, "ghp_only_path")
        val resolver = AuthResolver(cs, tokenStore = null, tokenProvider = null)
        assertEquals("ghp_only_path", resolver.resolveFor(repoUrl).secret)
    }

    @Test
    fun `hasAnyCredentialsFor returns true when OAuth is connected`() = runBlocking {
        val cs = InMemoryCredentialStore()
        val store = OAuthTokenStore(cs).also { it.save(livingIdentity) }
        val resolver = AuthResolver(cs, store, providerFor(store))
        assertTrue(resolver.hasAnyCredentialsFor(repoUrl))
    }

    @Test
    fun `hasAnyCredentialsFor returns true when only PAT is stored`() = runBlocking {
        val cs = InMemoryCredentialStore()
        cs.set(repoUrl, "ghp")
        val resolver = AuthResolver(cs, tokenStore = null, tokenProvider = null)
        assertTrue(resolver.hasAnyCredentialsFor(repoUrl))
    }

    @Test
    fun `hasAnyCredentialsFor returns false when neither is set`() = runBlocking {
        val cs = InMemoryCredentialStore()
        val store = OAuthTokenStore(cs)
        val resolver = AuthResolver(cs, store, providerFor(store))
        assertFalse(resolver.hasAnyCredentialsFor(repoUrl))
    }
}

private object NoRefresh : OAuthRefreshClient {
    override suspend fun refresh(refreshToken: String): TokenResponse =
        error("refresh should not be called")
}

private object AlwaysFailRefresh : OAuthRefreshClient {
    override suspend fun refresh(refreshToken: String): TokenResponse =
        throw OAuthReauthRequiredException("refresh rejected")
}
