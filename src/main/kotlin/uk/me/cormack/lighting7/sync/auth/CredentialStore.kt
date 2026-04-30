package uk.me.cormack.lighting7.sync.auth

/**
 * Storage for cloud-sync GitHub Personal Access Tokens.
 *
 * Tokens are keyed by [repoUrl] (one PAT per remote, so two projects can use different
 * credentials). The interface is deliberately string-in / string-out — the backend can be
 * an OS keychain, an encrypted file, or anything else without leaking JNA / crypto
 * concerns into REST handlers or the sync engine.
 *
 * All methods may throw [CredentialStoreException] on backend failure (corrupted file,
 * keychain error, etc). Callers should treat that as a hard error — don't silently fall
 * back to plaintext.
 */
interface CredentialStore {

    /** Returns the stored PAT for [repoUrl], or null if none has been set. */
    fun get(repoUrl: String): String?

    /**
     * Cheaper "is a PAT stored?" check that avoids fetching the secret material into
     * heap. Default implementation falls back to [get]; backends with a presence-only
     * primitive can override to skip the decrypt / keychain copy.
     */
    fun contains(repoUrl: String): Boolean = get(repoUrl) != null

    /**
     * Persist [pat] under [repoUrl]. Overwrites any existing value. Blank PATs are
     * rejected — use [delete] to clear.
     */
    fun set(repoUrl: String, pat: String)

    /** Remove any PAT stored under [repoUrl]. No-op if no entry exists. */
    fun delete(repoUrl: String)

    /** Human-readable name of the active backend, e.g. "macOS Keychain" or "encrypted file". */
    val backendName: String
}

class CredentialStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
