package uk.me.cormack.lighting7.sync.auth

/**
 * Trivial in-memory [CredentialStore] for tests. Avoids both the OS keychain (which
 * would require platform setup and side-effect the user's keyring) and the encrypted-file
 * fallback (which writes real files we'd then have to clean up). Just a `MutableMap`.
 */
class InMemoryCredentialStore : CredentialStore {

    private val map = mutableMapOf<String, String>()
    override val backendName: String = "in-memory test store"

    override fun get(repoUrl: String): String? = map[repoUrl]
    override fun set(repoUrl: String, pat: String) {
        require(pat.isNotBlank())
        map[repoUrl] = pat
    }
    override fun delete(repoUrl: String) { map.remove(repoUrl) }
}
