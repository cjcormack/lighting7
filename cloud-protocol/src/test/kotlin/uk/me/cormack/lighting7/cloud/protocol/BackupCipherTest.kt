package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The vectors in `protocol/backups/cipher.json` were computed with Python's `cryptography` (AESGCM),
 * outside this code, so passing them says the desk seals what another AES-GCM implementation would.
 */
class BackupCipherTest {
    private val fixture = fixtureJson("backups/cipher.json").jsonObject
    private fun field(name: String) = fixture.getValue(name).jsonPrimitive.content
    private val key = field("keyHex").hexToByteArray()
    private val keyId = field("keyId")
    private val backupId = field("backupId")
    private val nonce = field("baseNonceHex").hexToByteArray()

    private fun pattern(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun seal(plaintext: ByteArray, writes: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCipher.encryptingStream(out, key, keyId, backupId, nonce).use { stream ->
            val step = (plaintext.size + writes - 1) / writes.coerceAtLeast(1)
            var at = 0
            while (at < plaintext.size) {
                val n = minOf(step, plaintext.size - at)
                stream.write(plaintext, at, n)
                at += n
            }
        }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, expected: String = backupId, withKey: ByteArray = key): ByteArray =
        BackupCipher.decryptingStream(ByteArrayInputStream(file), expected) { id ->
            assertEquals(keyId, id)
            withKey
        }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    @Test
    fun `every vector seals to its file and opens back`() {
        val vectors = fixture.getValue("vectors").jsonArray.map { it.jsonObject }
        assertEquals(setOf("empty", "short", "exactChunk", "threeChunks"), vectors.map { it.getValue("name").jsonPrimitive.content }.toSet())
        for (vector in vectors) {
            val name = vector.getValue("name").jsonPrimitive.content
            val size = vector.getValue("plaintextBytes").jsonPrimitive.int
            val plaintext = vector["plaintextHex"]?.jsonPrimitive?.content?.hexToByteArray() ?: pattern(size)
            assertEquals(size, plaintext.size)
            for (writes in listOf(1, 3, 7)) {
                val file = seal(plaintext, writes)
                assertEquals(vector.getValue("fileBytes").jsonPrimitive.int, file.size, "$name's size")
                assertEquals(vector.getValue("fileSha256").jsonPrimitive.content, sha256(file), "$name's file, in $writes writes")
                vector["fileHex"]?.let { assertEquals(it.jsonPrimitive.content, file.toHexString(), "$name's bytes") }
                assertContentEquals(plaintext, open(file), "$name opens")
            }
        }
    }

    @Test
    fun `the header is as written and reads back`() {
        val file = seal(ByteArray(0))
        assertEquals(field("headerHex"), file.copyOf(field("headerHex").length / 2).toHexString())
        val header = BackupCipher.readHeader(ByteArrayInputStream(file))
        assertEquals(keyId, header.keyId)
        assertEquals(backupId, header.backupId)
        assertContentEquals(nonce, header.baseNonce)
    }

    private val threeChunks by lazy { seal(pattern(2 * BackupCipher.CHUNK_BYTES + 7)) }
    private val headerSize by lazy { field("headerHex").length / 2 }
    private val sealedChunk = BackupCipher.CHUNK_BYTES + BackupCipher.TAG_BYTES

    private fun chunk(file: ByteArray, i: Int): ByteArray {
        val from = headerSize + i * sealedChunk
        return file.copyOfRange(from, minOf(from + sealedChunk, file.size))
    }

    private fun assertRefused(file: ByteArray, expected: String = backupId, withKey: ByteArray = key) {
        assertFailsWith<BackupCipherException> { open(file, expected, withKey) }
    }

    @Test
    fun `a reordered chunk fails`() {
        val header = threeChunks.copyOf(headerSize)
        assertRefused(header + chunk(threeChunks, 1) + chunk(threeChunks, 0) + chunk(threeChunks, 2))
    }

    @Test
    fun `a dropped chunk fails`() {
        val header = threeChunks.copyOf(headerSize)
        assertRefused(header + chunk(threeChunks, 0) + chunk(threeChunks, 2))
    }

    @Test
    fun `a file cut at a chunk boundary or inside one fails`() {
        assertRefused(threeChunks.copyOf(headerSize + 2 * sealedChunk))
        assertRefused(threeChunks.copyOf(headerSize + sealedChunk))
        assertRefused(threeChunks.copyOf(threeChunks.size - 1))
        assertRefused(threeChunks.copyOf(headerSize + 5))
        assertRefused(threeChunks.copyOf(headerSize))
        assertRefused(threeChunks.copyOf(10))
    }

    @Test
    fun `a byte altered anywhere fails`() {
        for (at in listOf(headerSize - 1, headerSize, headerSize + sealedChunk + 100, threeChunks.size - 1)) {
            val altered = threeChunks.copyOf()
            altered[at] = (altered[at].toInt() xor 1).toByte()
            assertRefused(altered)
        }
    }

    @Test
    fun `bytes appended after the last chunk fail`() {
        assertRefused(seal("short".toByteArray()) + byteArrayOf(0))
    }

    @Test
    fun `a wrong key fails`() {
        assertRefused(threeChunks, withKey = ByteArray(32) { 7 })
    }

    @Test
    fun `a header naming another backup fails before any key is asked for`() {
        assertFailsWith<BackupCipherException> {
            BackupCipher.decryptingStream(ByteArrayInputStream(threeChunks), "01JC9T4M2Z8Q6V0B3N5K7R1W4Y") { error("no key is asked for") }
        }
    }

    @Test
    fun `another backup's header on these chunks fails`() {
        val other = ByteArrayOutputStream()
        BackupCipher.encryptingStream(other, key, keyId, "01JC9T4M2Z8Q6V0B3N5K7R1W4Y", nonce).close()
        val otherHeader = other.toByteArray().copyOf(headerSize)
        assertRefused(otherHeader + threeChunks.copyOfRange(headerSize, threeChunks.size), expected = "01JC9T4M2Z8Q6V0B3N5K7R1W4Y")
    }

    @Test
    fun `a file that is not a backup, or of a newer format, is refused`() {
        assertFailsWith<BackupCipherException> { BackupCipher.readHeader(ByteArrayInputStream("PK\u0003\u0004 not a backup".toByteArray())) }
        val newer = threeChunks.copyOf()
        newer[4] = 2
        assertFailsWith<BackupCipherException> { BackupCipher.readHeader(ByteArrayInputStream(newer)) }
    }

    @Test
    fun `a fresh stream picks its own nonce`() {
        fun header(): ByteArray {
            val out = ByteArrayOutputStream()
            BackupCipher.encryptingStream(out, key, keyId, backupId).close()
            return BackupCipher.readHeader(ByteArrayInputStream(out.toByteArray())).baseNonce
        }
        assertFailsWith<AssertionError> { assertContentEquals(header(), header()) }
    }

    @Test
    fun `a key of the wrong length is refused as the file not opening`() {
        val e = assertFailsWith<BackupCipherException> { open(threeChunks, withKey = ByteArray(16)) }
        assertTrue("16 bytes" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a file ending inside its header says so`() {
        for (cut in listOf(4, 5, headerSize - 1)) {
            val e = assertFailsWith<BackupCipherException> { BackupCipher.readHeader(ByteArrayInputStream(threeChunks.copyOf(cut))) }
            assertTrue("ends inside its header" in e.message.orEmpty(), "cut at $cut: ${e.message}")
        }
    }

    @Test
    fun `headers compare by their fields, and one with a malformed nonce does not throw`() {
        val a = BackupHeader(1, keyId, nonce.copyOf(), backupId)
        assertEquals(a, BackupHeader(1, keyId, nonce.copyOf(), backupId))
        assertEquals(a.hashCode(), BackupHeader(1, keyId, nonce.copyOf(), backupId).hashCode())
        val short = BackupHeader(1, keyId, ByteArray(3), backupId)
        assertFalse(a == short)
        short.hashCode()
    }
}
