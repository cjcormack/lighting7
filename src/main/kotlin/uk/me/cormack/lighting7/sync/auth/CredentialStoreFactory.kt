package uk.me.cormack.lighting7.sync.auth

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Path

/**
 * Picks a [CredentialStore] backend per the `sync.credentialStore` config knob.
 *
 *  * `"keychain"` (default) — try the OS keychain via [KeyringCredentialStore]; fall back
 *    to [FileCredentialStore] silently if no native backend is available (headless Linux,
 *    container, etc). The fall back is logged so it isn't completely invisible.
 *  * `"file"` — always use [FileCredentialStore].
 *
 * The file backend's encryption key is derived from `(installUuid, hostname)`; both are
 * stable per machine but require some external context (the install row's UUID) to
 * decrypt, so a backed-up `.config` directory alone won't yield cleartext PATs. See the
 * detailed threat-model note in [FileCredentialStore].
 */
object CredentialStoreFactory {

    private val logger = LoggerFactory.getLogger(CredentialStoreFactory::class.java)

    fun create(
        backend: String?,
        fileFallbackPath: Path,
        installUuid: String,
    ): CredentialStore {
        val choice = backend?.takeIf { it.isNotBlank() }?.lowercase() ?: "keychain"
        val machineSalt = runCatching { InetAddress.getLocalHost().hostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "lighting7-fallback-salt"

        return when (choice) {
            "keychain" -> {
                val keyring = KeyringCredentialStore.create()
                if (keyring != null) {
                    logger.info("Cloud-sync credential store: {}", keyring.backendName)
                    keyring
                } else {
                    logger.warn(
                        "Requested keychain credential store but no native backend is available; " +
                            "falling back to encrypted file at {}",
                        fileFallbackPath,
                    )
                    FileCredentialStore(fileFallbackPath, installUuid, machineSalt)
                }
            }
            "file" -> {
                logger.info("Cloud-sync credential store: encrypted file at {}", fileFallbackPath)
                FileCredentialStore(fileFallbackPath, installUuid, machineSalt)
            }
            else -> error("Unknown sync.credentialStore value: '$backend' (expected 'keychain' or 'file')")
        }
    }
}
