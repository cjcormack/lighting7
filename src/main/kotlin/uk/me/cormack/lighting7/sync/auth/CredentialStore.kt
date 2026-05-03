package uk.me.cormack.lighting7.sync.auth

/**
 * Storage for cloud-sync secrets — GitHub Personal Access Tokens (one per repo URL) and,
 * since the OAuth migration, the install-wide OAuth token blob.
 *
 * Two surfaces:
 *  * The PAT API ([get]/[set]/[delete]/[contains]) is keyed by `repoUrl` so two projects
 *    can use different credentials. PATs are still the documented Advanced fallback for
 *    headless or GitHub Enterprise installs.
 *  * The blob API ([getBlob]/[setBlob]/[deleteBlob]/[containsBlob]) holds arbitrary
 *    string payloads (e.g. a serialised OAuth identity) under caller-chosen keys. The
 *    `repoUrl`-keyed methods are thin convenience wrappers over the blob API with the
 *    `pat:` prefix, so a single backend implementation covers both.
 *
 * The interface is deliberately string-in / string-out — backends (OS keychain,
 * encrypted file, in-memory) are free to choose their own storage shape.
 *
 * All methods may throw [CredentialStoreException] on backend failure (corrupted file,
 * keychain error, etc). Callers should treat that as a hard error — don't silently fall
 * back to plaintext.
 */
interface CredentialStore {

    /** Returns the stored PAT for [repoUrl], or null if none has been set. */
    fun get(repoUrl: String): String? = getBlob(patKey(repoUrl))

    /**
     * Cheaper "is a PAT stored?" check that avoids fetching the secret material into
     * heap. Default implementation falls back to [get]; backends with a presence-only
     * primitive can override to skip the decrypt / keychain copy.
     */
    fun contains(repoUrl: String): Boolean = containsBlob(patKey(repoUrl))

    /**
     * Bulk presence check — returns the subset of [repoUrls] that have a PAT stored.
     * Default loops through [contains] one at a time; backends that can answer the
     * whole question with one I/O round-trip (e.g. [FileCredentialStore], which would
     * otherwise re-read its file once per key) should override.
     */
    fun containsAll(repoUrls: Collection<String>): Set<String> =
        repoUrls.filterTo(mutableSetOf()) { contains(it) }

    /**
     * Persist [pat] under [repoUrl]. Overwrites any existing value. Blank PATs are
     * rejected — use [delete] to clear.
     */
    fun set(repoUrl: String, pat: String) {
        require(pat.isNotBlank()) { "PAT must not be blank" }
        setBlob(patKey(repoUrl), pat)
    }

    /** Remove any PAT stored under [repoUrl]. No-op if no entry exists. */
    fun delete(repoUrl: String) {
        deleteBlob(patKey(repoUrl))
    }

    /** Return the value previously stored under [key], or null if none. */
    fun getBlob(key: String): String?

    /**
     * Presence-only check for [key]. Default falls back to [getBlob]; backends with a
     * cheaper primitive should override to avoid pulling the secret into heap.
     */
    fun containsBlob(key: String): Boolean = getBlob(key) != null

    /**
     * Persist [value] under [key]. Overwrites any existing entry. Blank values are
     * rejected — use [deleteBlob] to clear.
     */
    fun setBlob(key: String, value: String)

    /** Remove the entry stored under [key]. No-op if no entry exists. */
    fun deleteBlob(key: String)

    /** Human-readable name of the active backend, e.g. "macOS Keychain" or "encrypted file". */
    val backendName: String

    companion object {
        /**
         * Namespacing convention: PATs go under `pat:<repoUrl>` and the OAuth identity
         * lives under [OAUTH_GITHUB_DEFAULT_KEY]. Centralised so backends and tests
         * don't drift on the prefix shape.
         */
        fun patKey(repoUrl: String): String = "pat:$repoUrl"

        /** Key for the install-wide GitHub OAuth identity blob. */
        const val OAUTH_GITHUB_DEFAULT_KEY: String = "oauth:github:default"
    }
}

class CredentialStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
