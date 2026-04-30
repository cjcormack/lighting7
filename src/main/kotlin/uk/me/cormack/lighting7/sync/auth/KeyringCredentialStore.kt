package uk.me.cormack.lighting7.sync.auth

import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import org.slf4j.LoggerFactory

/**
 * [CredentialStore] backed by the OS-native keychain via the
 * [java-keyring](https://github.com/javakeyring/java-keyring) library.
 *
 * Backends:
 *  * macOS — Security framework (keychain access).
 *  * Linux — D-Bus libsecret / KWallet.
 *  * Windows — Credential Manager.
 *
 * Service name is fixed to [SERVICE_NAME] across all entries. The account is the
 * caller-supplied [repoUrl]; using the URL as the account means two projects pointing at
 * different remotes can hold different PATs without leaking either to the other.
 *
 * If the platform has no supported backend, [create] throws so the factory can fall back
 * to a file-based store.
 */
class KeyringCredentialStore private constructor(private val keyring: Keyring) : CredentialStore {

    override val backendName: String = "OS keychain (${keyring.keyringStorageType})"

    override fun get(repoUrl: String): String? = try {
        keyring.getPassword(SERVICE_NAME, repoUrl)
    } catch (e: PasswordAccessException) {
        // java-keyring throws on "not found" rather than returning null. We can't easily
        // distinguish "missing entry" from "real error" without inspecting the message;
        // treat any access exception as missing. The wrapper is logged so genuine errors
        // (corrupted keychain, permission revoked) aren't completely silent.
        logger.debug("Keyring lookup for {} returned no password: {}", repoUrl, e.message)
        null
    }

    override fun set(repoUrl: String, pat: String) {
        require(pat.isNotBlank()) { "PAT must not be blank" }
        try {
            keyring.setPassword(SERVICE_NAME, repoUrl, pat)
        } catch (e: PasswordAccessException) {
            throw CredentialStoreException("Failed to write PAT to OS keychain", e)
        }
    }

    override fun delete(repoUrl: String) {
        try {
            keyring.deletePassword(SERVICE_NAME, repoUrl)
        } catch (e: PasswordAccessException) {
            // Deleting a non-existent entry throws on most backends — swallow as a no-op.
            logger.debug("Keyring delete for {} was a no-op: {}", repoUrl, e.message)
        }
    }

    companion object {
        const val SERVICE_NAME = "lighting7"
        private val logger = LoggerFactory.getLogger(KeyringCredentialStore::class.java)

        /**
         * Try to open the platform's native keychain. Returns null if no backend is
         * available (e.g. headless Linux without libsecret) so callers can fall back.
         */
        fun create(): KeyringCredentialStore? = try {
            KeyringCredentialStore(Keyring.create())
        } catch (e: BackendNotSupportedException) {
            logger.warn("OS keychain backend unavailable: {}", e.message)
            null
        } catch (e: Throwable) {
            // Some platforms throw UnsatisfiedLinkError or NoClassDefFoundError when the
            // native libraries are missing; java-keyring doesn't always wrap them.
            logger.warn("OS keychain init failed: {}", e.message)
            null
        }
    }
}
