package uk.me.cormack.lighting7.sync.auth

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the encrypted-file fallback credential store. The OS-keychain backend
 * is platform-gated (and side-effects the user's keychain) so it's not exercised here —
 * the integration test for that lives in the manual verification list in the phase 4
 * plan, not the JUnit suite.
 */
class FileCredentialStoreTest {

    private lateinit var dir: Path
    private val installUuid = "11111111-2222-3333-4444-555555555555"
    private val machineSalt = "test-machine"

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("lighting7-cred-test-")
    }

    @After
    fun tearDown() {
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun store(filename: String = "credentials.enc"): FileCredentialStore =
        FileCredentialStore(dir.resolve(filename), installUuid, machineSalt)

    @Test
    fun `set then get round trips a token`() {
        val s = store()
        s.set("https://github.com/me/repo.git", "ghp_abc123")
        assertEquals("ghp_abc123", s.get("https://github.com/me/repo.git"))
    }

    @Test
    fun `get returns null when no entry has been written`() {
        assertNull(store().get("https://github.com/me/repo.git"))
    }

    @Test
    fun `delete removes the entry but leaves others intact`() {
        val s = store()
        s.set("https://github.com/me/a.git", "tokenA")
        s.set("https://github.com/me/b.git", "tokenB")
        s.delete("https://github.com/me/a.git")
        assertNull(s.get("https://github.com/me/a.git"))
        assertEquals("tokenB", s.get("https://github.com/me/b.git"))
    }

    @Test
    fun `deleting a missing entry is a no-op`() {
        store().delete("https://github.com/me/nope.git")
    }

    @Test
    fun `blank PAT is rejected`() {
        try {
            store().set("https://github.com/me/repo.git", "")
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `file content is non-plaintext`() {
        val path = dir.resolve("credentials.enc")
        store().set("https://github.com/me/repo.git", "ghp_secretsecret")
        val raw = Files.readString(path)
        assertFalse(raw.contains("ghp_secretsecret"), "PAT must not appear cleartext on disk")
        assertTrue(raw.contains("github"), "URL keys are stored cleartext (expected)")
    }

    @Test
    fun `decryption fails silently for a wrong key`() {
        // Same path, different installUuid → wrong key → cannot decrypt.
        store().set("https://github.com/me/repo.git", "tokenX")
        val attacker = FileCredentialStore(dir.resolve("credentials.enc"), "ZZZZZZZZ-2222-3333-4444-555555555555", machineSalt)
        assertNull(attacker.get("https://github.com/me/repo.git"))
    }

    @Test
    fun `re-encryption uses a fresh IV so duplicate writes don't produce identical ciphertext`() {
        val s = store()
        s.set("https://github.com/me/repo.git", "same-token")
        val first = Files.readString(dir.resolve("credentials.enc"))
        s.set("https://github.com/me/repo.git", "same-token")
        val second = Files.readString(dir.resolve("credentials.enc"))
        assertNotEquals(first, second, "fresh IV should produce a different ciphertext for the same plaintext")
    }

    @Test
    fun `factory in file mode never returns null and survives keychain failure`() {
        val cs = CredentialStoreFactory.create("file", dir.resolve("credentials.enc"), installUuid)
        assertNotNull(cs)
        cs.set("https://github.com/me/repo.git", "ghp_x")
        assertEquals("ghp_x", cs.get("https://github.com/me/repo.git"))
    }
}
