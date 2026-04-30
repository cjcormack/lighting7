package uk.me.cormack.lighting7.sync.auth

/**
 * Trivial in-memory [CredentialStore] for tests. Avoids both the OS keychain (which
 * would require platform setup and side-effect the user's keyring) and the encrypted-file
 * fallback (which writes real files we'd then have to clean up). Just a `MutableMap`.
 */
class InMemoryCredentialStore : CredentialStore {

    private val map = mutableMapOf<String, String>()
    override val backendName: String = "in-memory test store"

    override fun getBlob(key: String): String? = map[key]

    override fun setBlob(key: String, value: String) {
        require(value.isNotBlank())
        map[key] = value
    }

    override fun deleteBlob(key: String) {
        map.remove(key)
    }
}
