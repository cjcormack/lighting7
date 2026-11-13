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
    fun `set then get round trips a value`() {
        val s = store()
        s.setBlob("cloud:install-key:a", "abc123")
        assertEquals("abc123", s.getBlob("cloud:install-key:a"))
    }

    @Test
    fun `get returns null when no entry has been written`() {
        assertNull(store().getBlob("cloud:install-key:a"))
    }

    @Test
    fun `delete removes the entry but leaves others intact`() {
        val s = store()
        s.setBlob("a", "tokenA")
        s.setBlob("b", "tokenB")
        s.deleteBlob("a")
        assertNull(s.getBlob("a"))
        assertEquals("tokenB", s.getBlob("b"))
    }

    @Test
    fun `deleting a missing entry is a no-op`() {
        store().deleteBlob("nope")
    }

    @Test
    fun `blank value is rejected`() {
        try {
            store().setBlob("cloud:install-key:a", "")
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `file content is non-plaintext`() {
        val path = dir.resolve("credentials.enc")
        store().setBlob("cloud:install-key:a", "secretsecret")
        val raw = Files.readString(path)
        assertFalse(raw.contains("secretsecret"), "a value must not appear cleartext on disk")
        assertTrue(raw.contains("install-key"), "keys are stored cleartext (expected)")
    }

    @Test
    fun `decryption fails silently for a wrong key`() {
        // Same path, different installUuid → wrong key → cannot decrypt.
        store().setBlob("cloud:install-key:a", "tokenX")
        val attacker = FileCredentialStore(dir.resolve("credentials.enc"), "ZZZZZZZZ-2222-3333-4444-555555555555", machineSalt)
        assertNull(attacker.getBlob("cloud:install-key:a"))
    }

    @Test
    fun `re-encryption uses a fresh IV so duplicate writes don't produce identical ciphertext`() {
        val s = store()
        s.setBlob("cloud:install-key:a", "same-token")
        val first = Files.readString(dir.resolve("credentials.enc"))
        s.setBlob("cloud:install-key:a", "same-token")
        val second = Files.readString(dir.resolve("credentials.enc"))
        assertNotEquals(first, second, "fresh IV should produce a different ciphertext for the same plaintext")
    }

    @Test
    fun `factory in file mode never returns null and survives keychain failure`() {
        val cs = CredentialStoreFactory.create("file", dir.resolve("credentials.enc"), installUuid)
        assertNotNull(cs)
        cs.setBlob("cloud:install-key:a", "x")
        assertEquals("x", cs.getBlob("cloud:install-key:a"))
    }

    @Test
    fun `blob api round trips arbitrary keys`() {
        val s = store()
        s.setBlob("some:key", "{\"accessToken\":\"abc\"}")
        assertEquals("{\"accessToken\":\"abc\"}", s.getBlob("some:key"))
        assertTrue(s.containsBlob("some:key"))
    }

    @Test
    fun `deleteBlob clears only the named key`() {
        val s = store()
        s.setBlob("a", "1")
        s.setBlob("b", "2")
        s.deleteBlob("a")
        assertNull(s.getBlob("a"))
        assertEquals("2", s.getBlob("b"))
    }

    @Test
    fun `blank blob value is rejected`() {
        try {
            store().setBlob("k", "")
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
