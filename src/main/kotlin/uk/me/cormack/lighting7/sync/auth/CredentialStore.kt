package uk.me.cormack.lighting7.sync.auth

/**
 * Storage for the desk's secrets, such as its Lighting7 Cloud install key, each a string under a
 * caller-chosen key. Backends (OS keychain, encrypted file, in-memory) choose their own storage shape.
 *
 * All methods may throw [CredentialStoreException] on backend failure (corrupted file,
 * keychain error, etc). Callers should treat that as a hard error — don't silently fall
 * back to plaintext.
 */
interface CredentialStore {

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
}

class CredentialStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
