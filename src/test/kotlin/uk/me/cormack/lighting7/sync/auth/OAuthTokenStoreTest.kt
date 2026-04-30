package uk.me.cormack.lighting7.sync.auth

import org.junit.Test
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenStore
import uk.me.cormack.lighting7.sync.auth.oauth.StoredOAuthIdentity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthTokenStoreTest {

    private val sample = StoredOAuthIdentity(
        accessToken = "ghu_abc",
        refreshToken = "ghr_def",
        accessExpiresAtMs = 1_700_000_000_000L,
        refreshExpiresAtMs = 1_710_000_000_000L,
        githubLogin = "octocat",
        githubUserId = 42L,
        scopes = listOf("contents:write", "metadata:read"),
        connectedAtMs = 1_690_000_000_000L,
    )

    @Test
    fun `load returns null when nothing stored`() {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        assertNull(store.load())
        assertFalse(store.isPresent())
    }

    @Test
    fun `save then load round-trips the identity`() {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(sample)
        assertTrue(store.isPresent())
        assertEquals(sample, store.load())
    }

    @Test
    fun `clear removes the entry`() {
        val store = OAuthTokenStore(InMemoryCredentialStore())
        store.save(sample)
        store.clear()
        assertFalse(store.isPresent())
        assertNull(store.load())
    }

    @Test
    fun `corrupt blob is treated as no identity rather than throwing`() {
        val cs = InMemoryCredentialStore()
        cs.setBlob(CredentialStore.OAUTH_GITHUB_DEFAULT_KEY, "{not valid json")
        val store = OAuthTokenStore(cs)
        // isPresent reflects the raw store's view; load() defends against parse failure.
        assertTrue(store.isPresent())
        assertNull(store.load())
    }

    @Test
    fun `multiple stores can coexist under different keys`() {
        val cs = InMemoryCredentialStore()
        val a = OAuthTokenStore(cs, key = "oauth:github:default")
        val b = OAuthTokenStore(cs, key = "oauth:github:work")
        a.save(sample)
        b.save(sample.copy(githubLogin = "work-bot", githubUserId = 99L))
        assertEquals("octocat", a.load()?.githubLogin)
        assertEquals("work-bot", b.load()?.githubLogin)
    }
}
