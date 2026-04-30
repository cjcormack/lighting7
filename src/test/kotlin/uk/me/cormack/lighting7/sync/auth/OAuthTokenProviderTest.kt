package uk.me.cormack.lighting7.sync.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthRefreshClient
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenProvider
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenStore
import uk.me.cormack.lighting7.sync.auth.oauth.StoredOAuthIdentity
import uk.me.cormack.lighting7.sync.auth.oauth.TokenResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class OAuthTokenProviderTest {

    private val initialIdentity = StoredOAuthIdentity(
        accessToken = "old-access",
        refreshToken = "old-refresh",
        accessExpiresAtMs = 1_000_000L,
        refreshExpiresAtMs = 10_000_000L,
        githubLogin = "octocat",
        githubUserId = 42L,
        scopes = listOf("contents:write"),
        connectedAtMs = 0L,
    )

    @Test
    fun `returns cached token when not near expiry`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 99_999_999L))

        val client = FakeRefresher()
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = client,
            refreshThresholdMs = 60_000L,
            nowMs = { 1_000_000L },
        )

        assertEquals("old-access", provider.accessToken())
        assertEquals(0, client.hits.get())
    }

    @Test
    fun `refreshes when access token is past the threshold`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 1_000_000L))

        val client = FakeRefresher(
            response = TokenResponse(
                accessToken = "fresh",
                refreshToken = "fresh-refresh",
                expiresIn = 28_800L,
            ),
        )
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = client,
            refreshThresholdMs = 60_000L,
            nowMs = { 1_000_000L },
        )

        assertEquals("fresh", provider.accessToken())
        assertEquals(1, client.hits.get())

        val saved = store.load()!!
        assertEquals("fresh", saved.accessToken)
        assertEquals("fresh-refresh", saved.refreshToken)
        // 1_000_000 + 28_800_000 = 29_800_000
        assertEquals(29_800_000L, saved.accessExpiresAtMs)
    }

    @Test
    fun `expired refresh token throws OAuthReauthRequiredException`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(
            accessExpiresAtMs = 0L,
            refreshExpiresAtMs = 500L,
        ))

        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = FakeRefresher(),
            nowMs = { 1_000_000L },
        )
        try {
            provider.accessToken()
            fail("expected OAuthReauthRequiredException")
        } catch (_: OAuthReauthRequiredException) { /* expected */ }
    }

    @Test
    fun `no identity stored throws OAuthReauthRequiredException`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        val provider = OAuthTokenProvider(tokenStore = store, client = FakeRefresher())
        try {
            provider.accessToken()
            fail("expected OAuthReauthRequiredException")
        } catch (_: OAuthReauthRequiredException) { /* expected */ }
    }

    @Test
    fun `concurrent callers refresh exactly once`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 1_000_000L))

        val client = FakeRefresher(
            response = TokenResponse(accessToken = "shared", refreshToken = "shared-r", expiresIn = 28_800L),
            delayMs = 50L,
        )
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = client,
            refreshThresholdMs = 60_000L,
            nowMs = { 1_000_000L },
        )

        val results = (1..8).map {
            async { provider.accessToken() }
        }.awaitAll()

        assertTrue(results.all { it == "shared" }, "all callers must observe the refreshed token")
        assertEquals(1, client.hits.get(), "single-flight refresh must POST exactly once")
    }

    @Test
    fun `onRefreshed callback fires after persistence`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 1_000_000L))

        var observed: StoredOAuthIdentity? = null
        val client = FakeRefresher(response = TokenResponse(accessToken = "fresh-callback", refreshToken = "r", expiresIn = 28_800L))
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = client,
            onRefreshed = { observed = it },
            nowMs = { 1_000_000L },
        )
        provider.accessToken()
        assertEquals("fresh-callback", observed?.accessToken)
    }
}

private class FakeRefresher(
    private val response: TokenResponse = TokenResponse(
        accessToken = "new-access",
        refreshToken = "new-refresh",
        expiresIn = 28_800L,
    ),
    private val delayMs: Long = 0L,
) : OAuthRefreshClient {
    val hits = AtomicInteger(0)
    override suspend fun refresh(refreshToken: String): TokenResponse {
        hits.incrementAndGet()
        if (delayMs > 0) delay(delayMs)
        return response
    }
}
