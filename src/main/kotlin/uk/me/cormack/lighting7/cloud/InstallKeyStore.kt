package uk.me.cormack.lighting7.cloud

import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import java.security.KeyPair
import java.security.PrivateKey

/**
 * The install key's private half (cloud-p0 plan D4), the [CredentialStore] blob [blobName]: PKCS#8,
 * base64. Never exported, synced or logged. A key is made when adoption starts and deleted when
 * the desk stops being adopted, so a desk never holds two.
 *
 * The name carries the install's uuid because the OS keychain is per user, not per install: a
 * second desk on the same machine (another data directory) must not find, or replace, this one's.
 */
class InstallKeyStore(private val credentialStore: () -> CredentialStore, installUuid: () -> String) {

    val blobName: String by lazy { blobNameFor(installUuid()) }

    /** A fresh key pair, stored in place of any key before it. */
    fun generate(): KeyPair {
        val pair = InstallKeys.generate()
        credentialStore().setBlob(blobName, InstallKeys.encodePrivateKey(pair.private))
        return pair
    }

    /** The stored key, or null when there is none or it cannot be read. */
    fun load(): PrivateKey? = runCatching {
        credentialStore().getBlob(blobName)?.let(InstallKeys::decodePrivateKey)
    }.getOrNull()

    fun exists(): Boolean = runCatching { credentialStore().containsBlob(blobName) }.getOrDefault(false)

    fun delete() {
        credentialStore().deleteBlob(blobName)
    }

    companion object {
        fun blobNameFor(installUuid: String): String = "cloud:install-key:$installUuid"
    }
}
