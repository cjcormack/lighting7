package uk.me.cormack.lighting7.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val secureRandom = SecureRandom()

/**
 * Session-token primitives. The raw token lives only in the client's httpOnly cookie;
 * the server persists and caches its SHA-256 hex, so a copied SQLite file never holds
 * a usable credential (multi-user-auth plan, Decision 3).
 */
object SessionTokens {
    /** 32 random bytes as unpadded base64url — the value written into the session cookie. */
    fun newToken(): String = randomUrlToken(32)

    /**
     * 16 random bytes as unpadded base64url (~22 chars) — the value in a QR reset URL.
     * Shorter than a session token on purpose: it lives for 15 minutes, is single-use, and
     * has to survive being rendered as a QR code and occasionally typed off a screen.
     */
    fun newResetToken(): String = randomUrlToken(16)

    /**
     * 32 random bytes as unpadded base64url — the value in a device-login QR URL.
     *
     * A session token's entropy, not a reset token's, because this is exchanged **for** a
     * session: a reset token can only ever set a password and burns itself doing so, whereas
     * whoever redeems this one is signed in. Nobody types it off a screen either — it is
     * scanned — so the extra 16 bytes cost only QR density.
     */
    fun newDeviceLoginToken(): String = randomUrlToken(32)

    private fun randomUrlToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Hex-encoded SHA-256 of [token] — the stored/cached key for a session. */
    @OptIn(ExperimentalStdlibApi::class)
    fun sha256Hex(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).toHexString()

    /** 16-char random password for break-glass admin resets (alphanumeric, unambiguous). */
    fun randomPassword(length: Int = 16): String {
        // No 0/O/1/l/I — these get read off a console or a printed txt file.
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return buildString(length) {
            repeat(length) { append(alphabet[secureRandom.nextInt(alphabet.length)]) }
        }
    }
}
