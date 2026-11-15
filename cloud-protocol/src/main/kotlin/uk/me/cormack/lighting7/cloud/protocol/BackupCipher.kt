package uk.me.cormack.lighting7.cloud.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup archive's encryption (docs/cloud-protocol.md §"The backup file"): AES-256-GCM over 1 MiB
 * chunks, so an archive of any size is sealed as it is written and opened as it is read.
 *
 * The file is a header, then the chunks. The header is the magic `L7BK`, the format version (one byte),
 * the key id, a random 12-byte base nonce and the backup's id, each string a 2-byte big-endian length
 * and its UTF-8. Chunk `i` (from 0) holds [CHUNK_BYTES] of plaintext, the last one the rest (none for
 * an empty archive), sealed under the base nonce with its last 8 bytes XORed with `i`, and with the
 * AAD the header's SHA-256, `i` as 8 bytes and a byte saying whether it is the last; each is its
 * ciphertext and 16-byte tag. So a chunk cannot be moved, dropped or altered, the file cannot be cut
 * at a chunk boundary, and a header cannot be swapped onto another archive's chunks.
 */
object BackupCipher {
    const val FORMAT_VERSION: Int = 1
    const val CHUNK_BYTES: Int = 1024 * 1024
    const val KEY_BYTES: Int = 32
    const val NONCE_BYTES: Int = 12
    const val TAG_BYTES: Int = 16

    private val MAGIC = "L7BK".toByteArray(Charsets.US_ASCII)
    private const val MAX_STRING_BYTES = 256

    /**
     * A stream that writes [backupId]'s archive, encrypted under [key] (named [keyId]), to [out]. The
     * header is written at once; [OutputStream.close] seals the last chunk and closes [out]. A chunk is
     * sealed only once the next byte arrives, so nothing but the last is marked last.
     */
    fun encryptingStream(
        out: OutputStream,
        key: ByteArray,
        keyId: String,
        backupId: String,
        baseNonce: ByteArray = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes),
    ): OutputStream {
        require(key.size == KEY_BYTES) { "A backup key is $KEY_BYTES bytes" }
        val header = BackupHeader(FORMAT_VERSION, keyId, baseNonce.copyOf(), backupId)
        val headerBytes = header.encode()
        out.write(headerBytes)
        return SealingStream(out, ChunkSealer(key, header.baseNonce, sha256(headerBytes)))
    }

    /** The header at the start of [input], which is left at the first chunk. */
    fun readHeader(input: InputStream): BackupHeader = readRawHeader(input).first

    private fun readRawHeader(input: InputStream): Pair<BackupHeader, ByteArray> {
        val raw = RecordingInput(input)
        val magic = raw.readExactly(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw BackupCipherException("Not a backup file")
        val version = raw.readExactly(1)[0].toInt() and 0xff
        if (version != FORMAT_VERSION) throw BackupCipherException("Backup file format $version is not one this version reads")
        val keyId = raw.readString()
        val nonce = raw.readExactly(NONCE_BYTES)
        val backupId = raw.readString()
        return BackupHeader(version, keyId, nonce, backupId) to raw.recorded.toByteArray()
    }

    /**
     * A stream of the archive [input] holds, read from its header on. Its header must name
     * [expectedBackupId]; [keyFor] is asked for the key its header names. Each chunk's plaintext is
     * released only once its tag verifies, and the stream fails at its end when the file was cut short:
     * the archive is good only once the stream reads to its end without throwing.
     */
    fun decryptingStream(input: InputStream, expectedBackupId: String, keyFor: (keyId: String) -> ByteArray): InputStream {
        val (header, headerBytes) = readRawHeader(input)
        if (header.backupId != expectedBackupId) {
            throw BackupCipherException("The backup file is ${header.backupId}'s, not $expectedBackupId's")
        }
        val key = keyFor(header.keyId)
        if (key.size != KEY_BYTES) throw BackupCipherException("The key for ${header.keyId} is ${key.size} bytes, not $KEY_BYTES")
        return OpeningStream(input, ChunkSealer(key, header.baseNonce, sha256(headerBytes)))
    }

    private class ChunkSealer(key: ByteArray, private val baseNonce: ByteArray, private val headerHash: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        fun seal(index: Long, last: Boolean, plaintext: ByteArray, length: Int): ByteArray {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BYTES * 8, nonce(index)))
            cipher.updateAAD(aad(index, last))
            return cipher.doFinal(plaintext, 0, length)
        }

        fun open(index: Long, last: Boolean, sealed: ByteArray, length: Int): ByteArray = try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BYTES * 8, nonce(index)))
            cipher.updateAAD(aad(index, last))
            cipher.doFinal(sealed, 0, length)
        } catch (e: GeneralSecurityException) {
            throw BackupCipherException("Chunk $index does not open: altered, moved, cut short or under another key", e)
        }

        private fun nonce(index: Long): ByteArray {
            val nonce = baseNonce.copyOf()
            for (i in 0 until 8) {
                nonce[NONCE_BYTES - 1 - i] = (nonce[NONCE_BYTES - 1 - i].toInt() xor (index ushr (8 * i)).toInt()).toByte()
            }
            return nonce
        }

        private fun aad(index: Long, last: Boolean): ByteArray = headerHash + longBytes(index) + byteArrayOf(if (last) 1 else 0)
    }

    private class SealingStream(private val out: OutputStream, private val sealer: ChunkSealer) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var filled = 0
        private var index = 0L
        private var closed = false
        private val one = ByteArray(1)

        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("The backup stream is closed")
            var at = off
            var left = len
            while (left > 0) {
                if (filled == CHUNK_BYTES) {
                    out.write(sealer.seal(index++, last = false, buffer, filled))
                    filled = 0
                }
                val n = minOf(left, CHUNK_BYTES - filled)
                System.arraycopy(b, at, buffer, filled, n)
                filled += n
                at += n
                left -= n
            }
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            out.use { it.write(sealer.seal(index, last = true, buffer, filled)) }
        }
    }

    private class OpeningStream(private val input: InputStream, private val sealer: ChunkSealer) : InputStream() {
        private val sealed = ByteArray(CHUNK_BYTES + TAG_BYTES)
        private var plain = ByteArray(0)
        private var at = 0
        private var index = 0L
        private var done = false
        private var lookahead = -1
        private val one = ByteArray(1)

        override fun read(): Int {
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at == plain.size) {
                if (done) return -1
                nextChunk()
            }
            val n = minOf(len, plain.size - at)
            System.arraycopy(plain, at, b, off, n)
            at += n
            return n
        }

        private fun nextChunk() {
            var length = 0
            if (lookahead >= 0) {
                sealed[0] = lookahead.toByte()
                length = 1
                lookahead = -1
            }
            while (length < sealed.size) {
                val n = input.read(sealed, length, sealed.size - length)
                if (n == -1) break
                length += n
            }
            if (length < TAG_BYTES) throw BackupCipherException("The backup file ends inside chunk $index")
            val last = length < sealed.size || input.read().also { lookahead = it } == -1
            plain = sealer.open(index++, last, sealed, length)
            at = 0
            done = last
        }

        override fun close() {
            input.close()
        }
    }

    private class RecordingInput(private val input: InputStream) {
        val recorded = ByteArrayOutputStream()

        fun readExactly(n: Int): ByteArray = input.readExactly(n).also(recorded::write)

        fun readString(): String {
            val length = readExactly(2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
            if (length > MAX_STRING_BYTES) throw BackupCipherException("A backup header string of $length bytes")
            return readExactly(length).toString(Charsets.UTF_8)
        }
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = read(bytes, read, n - read)
            if (got == -1) throw BackupCipherException("The backup file ends inside its header", EOFException())
            read += got
        }
        return bytes
    }

    internal fun encodeHeader(header: BackupHeader): ByteArray {
        require(header.baseNonce.size == NONCE_BYTES) { "A base nonce is $NONCE_BYTES bytes" }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(header.formatVersion)
        out.writeString(header.keyId)
        out.write(header.baseNonce)
        out.writeString(header.backupId)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "A backup header string is at most $MAX_STRING_BYTES bytes" }
        write(bytes.size ushr 8)
        write(bytes.size and 0xff)
        write(bytes)
    }

    private fun longBytes(value: Long): ByteArray = ByteArray(8) { (value ushr (8 * (7 - it))).toByte() }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** A backup file's header: which key it is under and which backup it is. */
class BackupHeader(val formatVersion: Int, val keyId: String, val baseNonce: ByteArray, val backupId: String) {
    fun encode(): ByteArray = BackupCipher.encodeHeader(this)

    override fun equals(other: Any?): Boolean =
        other is BackupHeader && formatVersion == other.formatVersion && keyId == other.keyId &&
            baseNonce.contentEquals(other.baseNonce) && backupId == other.backupId

    override fun hashCode(): Int = ((formatVersion * 31 + keyId.hashCode()) * 31 + baseNonce.contentHashCode()) * 31 + backupId.hashCode()

    override fun toString(): String = "BackupHeader(formatVersion=$formatVersion, keyId=$keyId, backupId=$backupId)"
}

/** A backup file that is not one, is cut short, or does not open under its key. */
class BackupCipherException(message: String, cause: Throwable? = null) : IOException(message, cause)
