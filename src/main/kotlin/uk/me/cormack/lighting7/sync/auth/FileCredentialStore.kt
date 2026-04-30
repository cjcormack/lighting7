package uk.me.cormack.lighting7.sync.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM-encrypted file fallback for [CredentialStore].
 *
 * Used when no OS keychain is available (typically headless Linux without libsecret).
 * The encryption key is derived from the install UUID combined with the machine's
 * hostname — both are non-secret on their own, but the combination is stable across
 * launches on a given machine and unique enough to defeat casual file-copy leaks.
 *
 * **This is not a real secret store.** Anyone with read access to both this file and
 * the install table can decrypt the PATs. The threat model is "stop a stolen `.config`
 * directory or backed-up disk image from yielding cleartext credentials" — not "defend
 * against an attacker with shell access to the running machine". For higher assurance,
 * point [sync.credentialStore] at "keychain".
 *
 * On-disk format: a single JSON file with `{ url -> base64(iv ‖ ciphertext) }`. Each
 * entry is encrypted with its own random 12-byte IV under the same key. Posix
 * permissions are set to 0600 on write where the filesystem supports it.
 */
class FileCredentialStore(
    private val path: Path,
    installUuid: String,
    machineSalt: String,
) : CredentialStore {

    private val key: SecretKey = deriveKey(installUuid, machineSalt)

    override val backendName: String = "encrypted file ($path)"

    override fun get(repoUrl: String): String? {
        val map = readMap()
        val cipherText = map[repoUrl] ?: return null
        return decrypt(cipherText)
    }

    override fun contains(repoUrl: String): Boolean = repoUrl in readMap()

    override fun set(repoUrl: String, pat: String) {
        require(pat.isNotBlank()) { "PAT must not be blank" }
        val map = readMap().toMutableMap()
        map[repoUrl] = encrypt(pat)
        writeMap(map)
    }

    override fun delete(repoUrl: String) {
        val map = readMap()
        if (repoUrl !in map) return
        val updated = map - repoUrl
        writeMap(updated)
    }

    // ─── Encryption helpers ────────────────────────────────────────────

    private fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    private fun decrypt(payload: String): String? {
        return try {
            val raw = Base64.getDecoder().decode(payload)
            if (raw.size < IV_LENGTH + GCM_TAG_BITS / 8) return null
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val ct = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // Don't surface the encryption error to callers — they only care that the entry
            // is unusable. Logging at warn so a corrupted file doesn't fail completely silently.
            logger.warn("Failed to decrypt credential entry: {}", e.message)
            null
        }
    }

    // ─── File I/O ──────────────────────────────────────────────────────

    private fun readMap(): Map<String, String> {
        return try {
            val text = Files.readString(path)
            if (text.isBlank()) emptyMap()
            else Json.decodeFromString(MAP_SERIALIZER, text)
        } catch (_: java.nio.file.NoSuchFileException) {
            emptyMap()
        } catch (e: Exception) {
            throw CredentialStoreException("Credential file at $path is corrupt", e)
        }
    }

    private fun writeMap(map: Map<String, String>) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.encodeToString(MAP_SERIALIZER, map))
        runCatching {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        }.onFailure {
            // POSIX permissions aren't supported on Windows / non-default FS — fine.
        }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Serializable
    private data class StoredEntry(val ciphertext: String)

    companion object {
        private val logger = LoggerFactory.getLogger(FileCredentialStore::class.java)
        private val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        // 12 bytes is the GCM-recommended IV length; 128-bit auth tag is the standard.
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        // Single-iteration SHA-256 of (install || ":" || salt) is enough for this threat
        // model — the inputs aren't user-chosen passphrases that need stretching, just
        // identifiers we want a stable 256-bit key from.
        private fun deriveKey(installUuid: String, machineSalt: String): SecretKey {
            val md = MessageDigest.getInstance("SHA-256")
            val raw = md.digest("$installUuid:$machineSalt".toByteArray(Charsets.UTF_8))
            return SecretKeySpec(raw, "AES")
        }
    }
}
