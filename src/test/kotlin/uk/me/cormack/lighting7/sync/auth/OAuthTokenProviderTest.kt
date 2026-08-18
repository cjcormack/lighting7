package uk.me.cormack.lighting7.sync.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthRefreshClient
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenProvider
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenStore
import uk.me.cormack.lighting7.sync.auth.oauth.StoredOAuthIdentity
import uk.me.cormack.lighting7.sync.auth.oauth.TokenResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `onIdentityUpdated callback fires after persistence`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 1_000_000L))

        var observed: StoredOAuthIdentity? = null
        val client = FakeRefresher(response = TokenResponse(accessToken = "fresh-callback", refreshToken = "r", expiresIn = 28_800L))
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = client,
            onIdentityUpdated = { observed = it },
            nowMs = { 1_000_000L },
        )
        provider.accessToken()
        assertEquals("fresh-callback", observed?.accessToken)
    }

    // ─── Re-auth marking ────────────────────────────────────────────────
    //
    // The desk has to *remember* that GitHub rejected it, or every caller rediscovers the
    // same dead token with a fresh network round-trip. These pin which failures are
    // remembered and which are not.

    @Test
    fun `a rejected refresh token is recorded on the stored identity`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 1_000_000L))

        var observed: StoredOAuthIdentity? = null
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = RejectingRefresher(),
            onIdentityUpdated = { observed = it },
            nowMs = { 1_000_000L },
        )
        try {
            provider.accessToken()
            fail("expected OAuthReauthRequiredException")
        } catch (_: OAuthReauthRequiredException) { /* expected */ }

        val saved = store.load()!!
        assertEquals(1_000_000L, saved.reauthRequiredAtMs)
        assertTrue(
            saved.reauthReason?.contains("bad_refresh_token") == true,
            "GitHub's reason must be kept for the UI, was: ${saved.reauthReason}",
        )
        // The mirror callback is how the DB row and any open UI learn about this.
        assertEquals(1_000_000L, observed?.reauthRequiredAtMs)
        // Still usable material — we didn't clear the tokens, just flagged them.
        assertEquals("old-access", saved.accessToken)
    }

    @Test
    fun `a transient refresh failure is not recorded as needing re-auth`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 1_000_000L))

        var notified = false
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = ThrowingRefresher(OAuthException("GitHub had a 503")),
            onIdentityUpdated = { notified = true },
            nowMs = { 1_000_000L },
        )
        try {
            provider.accessToken()
            fail("expected the transient failure to propagate")
        } catch (_: OAuthException) { /* expected */ }

        assertNull(store.load()!!.reauthRequiredAtMs, "a 5xx must stay retryable")
        assertFalse(notified, "nothing changed, so nothing should be broadcast")
    }

    @Test
    fun `an expired refresh token is recorded without a network call`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 0L, refreshExpiresAtMs = 500L))

        val client = FakeRefresher()
        val provider = OAuthTokenProvider(tokenStore = store, client = client, nowMs = { 1_000_000L })
        try {
            provider.accessToken()
            fail("expected OAuthReauthRequiredException")
        } catch (_: OAuthReauthRequiredException) { /* expected */ }

        assertEquals(0, client.hits.get(), "no point asking GitHub about a token we know has lapsed")
        assertEquals(1_000_000L, store.load()!!.reauthRequiredAtMs)
    }

    @Test
    fun `a successful refresh clears a previous re-auth marking`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(
            initialIdentity.copy(
                accessExpiresAtMs = 1_000_000L,
                reauthRequiredAtMs = 12L,
                reauthReason = "GitHub rejected the refresh token (bad_refresh_token)",
            ),
        )

        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = FakeRefresher(),
            nowMs = { 1_000_000L },
        )
        assertEquals("new-access", provider.accessToken())

        val saved = store.load()!!
        assertNull(saved.reauthRequiredAtMs, "the refresh that worked is proof the identity is healthy")
        assertNull(saved.reauthReason)
    }

    @Test
    fun `a rejection arriving after a reconnect does not clobber the new identity`() = runBlocking {
        // The window is real: `refresh()` is a network round-trip, and the OAuth routes'
        // persistIdentity writes the credential blob directly, outside this class's mutex. So
        // a user can finish reconnecting while GitHub is still deciding about the old token.
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(initialIdentity.copy(accessExpiresAtMs = 1_000_000L))

        var notifications = 0
        val provider = OAuthTokenProvider(
            tokenStore = store,
            // Stands in for the user completing a re-connect mid-flight.
            client = RejectingRefresher(onCall = {
                store.save(
                    StoredOAuthIdentity(
                        accessToken = "reconnected-access",
                        refreshToken = "reconnected-refresh",
                        accessExpiresAtMs = 99_999_999L,
                        githubLogin = "octocat",
                        githubUserId = 42L,
                        connectedAtMs = 999_999L,
                    ),
                )
            }),
            onIdentityUpdated = { notifications += 1 },
            nowMs = { 1_000_000L },
        )
        try {
            provider.accessToken()
            fail("expected OAuthReauthRequiredException")
        } catch (_: OAuthReauthRequiredException) { /* the caller still learns this attempt failed */ }

        val saved = store.load()!!
        assertEquals("reconnected-access", saved.accessToken, "the reconnect must survive")
        assertNull(saved.reauthRequiredAtMs, "GitHub's verdict was about a token that is no longer stored")
        assertEquals(0, notifications, "and no reauthRequired broadcast should undo it in the UI")
    }

    @Test
    fun `marking an already-marked identity does not re-broadcast`() = runBlocking {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(
            initialIdentity.copy(
                accessExpiresAtMs = 1_000_000L,
                reauthRequiredAtMs = 12L,
                reauthReason = "already known",
            ),
        )

        var notifications = 0
        val provider = OAuthTokenProvider(
            tokenStore = store,
            client = RejectingRefresher(),
            onIdentityUpdated = { notifications += 1 },
            nowMs = { 1_000_000L },
        )
        try {
            provider.accessToken()
            fail("expected OAuthReauthRequiredException")
        } catch (_: OAuthReauthRequiredException) { /* expected */ }

        assertEquals(0, notifications, "a backed-off retry must not spam the socket")
        // First-seen timestamp is preserved — it's what the UI shows as "since".
        assertEquals(12L, store.load()!!.reauthRequiredAtMs)
    }
}

/**
 * Stands in for GitHub answering `bad_refresh_token`, which only the user can fix. [onCall]
 * runs before the rejection, standing in for whatever else the desk did while the request was
 * in flight.
 */
private class RejectingRefresher(private val onCall: () -> Unit = {}) : OAuthRefreshClient {
    override suspend fun refresh(refreshToken: String): TokenResponse {
        onCall()
        throw OAuthReauthRequiredException(
            "GitHub rejected the refresh token (bad_refresh_token); user must re-connect.",
        )
    }
}

/** Stands in for a failure that says nothing about whether the identity is still good. */
private class ThrowingRefresher(private val error: Throwable) : OAuthRefreshClient {
    override suspend fun refresh(refreshToken: String): TokenResponse = throw error
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
