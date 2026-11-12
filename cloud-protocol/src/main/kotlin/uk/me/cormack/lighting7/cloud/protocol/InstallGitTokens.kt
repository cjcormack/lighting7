package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import uk.me.cormack.lighting7.cloud.protocol.CompactJws.string
import java.net.URI
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/*
 * Git tokens: how an adopted desk proves itself to the cloud's git (docs/cloud-protocol.md §"Git"). The
 * desk mints one per sync run, a compact JWS, EdDSA over its install key, typed `l7git`, and JGit sends
 * it as HTTP Basic auth with the user [InstallGitTokens.BASIC_USER]. The cloud checks it against the
 * public key of the install `iss` names, so syncing needs no round trip through the control channel.
 */

/** A git token's claims. Times are seconds since the epoch, as JWT's NumericDate is. */
@Serializable
data class InstallGitClaims(
    /** The install id. */
    val iss: String,
    /** [InstallGitTokens.audience] of the cloud's origin. */
    val aud: String,
    val iat: Long,
    val exp: Long,
    val jti: String,
) {
    /** Why these claims cannot be a token's whatever the time, or null. */
    fun shapeProblem(): String? = when {
        iss.isEmpty() -> "iss names the install"
        aud.isEmpty() -> "aud names the cloud's git"
        jti.isEmpty() -> "jti is not empty"
        iat !in 0..CompactJws.LATEST_TIME || exp !in 0..CompactJws.LATEST_TIME -> "iat and exp are times from 1970 to 9999"
        exp <= iat -> "exp is not after iat"
        else -> null
    }
}

@Serializable
private data class GitJwsHeader(val alg: String, val typ: String)

/** What [InstallGitTokens.verify] found. */
sealed interface GitTokenCheck {
    data class Valid(val claims: InstallGitClaims) : GitTokenCheck

    data class Refused(val refusal: TokenRefusal) : GitTokenCheck
}

object InstallGitTokens {
    const val ALGORITHM: String = CompactJws.ALGORITHM
    const val TYPE: String = "l7git"

    /** The HTTP Basic user a git token is sent as; the token is the password. */
    const val BASIC_USER: String = "x-install-token"

    /** The longest a token may live, `exp` less `iat`. */
    val MAX_LIFETIME: Duration = Duration.ofMinutes(10)

    /** How far the desk's clock may be from the cloud's in either direction. */
    val CLOCK_SKEW: Duration = Duration.ofSeconds(60)

    /** A token longer than this is refused unread. */
    const val MAX_TOKEN_CHARS: Int = 2048

    private val random = SecureRandom()

    /**
     * The `aud` a token for the cloud at [cloudUrl] names: the URL's origin, scheme and host lowercased
     * and a default port dropped, then `/git`. Any path, query or user is dropped.
     *
     * @throws IllegalArgumentException when [cloudUrl] is not an http or https URL with a host.
     */
    fun audience(cloudUrl: String): String {
        val uri = runCatching { URI(cloudUrl) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        require((scheme == "https" || scheme == "http") && !host.isNullOrEmpty()) { "$cloudUrl is not an http(s) URL" }
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port.takeUnless { it == -1 || it == defaultPort }?.let { ":$it" } ?: ""
        return "$scheme://$host$port/git"
    }

    /** A fresh token for [installId] at [cloudOrigin], living [MAX_LIFETIME] from [now]. */
    fun mint(key: PrivateKey, installId: String, cloudOrigin: String, now: Instant): String {
        val iat = now.epochSecond
        val jti = ByteArray(16).also { random.nextBytes(it) }
        return sign(key, InstallGitClaims(installId, audience(cloudOrigin), iat, iat + MAX_LIFETIME.seconds, Base64Url.encode(jti)))
    }

    /** [claims] as a token under [key]. Deterministic, since Ed25519 is. */
    fun sign(key: PrivateKey, claims: InstallGitClaims): String {
        claims.shapeProblem()?.let { throw IllegalArgumentException(it) }
        require(claims.exp - claims.iat <= MAX_LIFETIME.seconds) { "A git token lives at most ${MAX_LIFETIME.seconds} s" }
        val header = CloudJson.encodeToString(GitJwsHeader.serializer(), GitJwsHeader(ALGORITHM, TYPE))
        val token = CompactJws.sign(key, header, CloudJson.encodeToString(InstallGitClaims.serializer(), claims))
        require(token.length <= MAX_TOKEN_CHARS) { "A git token is at most $MAX_TOKEN_CHARS characters" }
        return token
    }

    /**
     * Whether [token] is one the install it names signed for [audience], judged at [now]. [keyOf] is the
     * public key of an install id, or null for an install the cloud does not hold (or has removed); it is
     * asked only for a non-empty `iss`. The signature is checked before any claim but `iss` is trusted;
     * each refusal names the first problem found.
     */
    fun verify(token: String, audience: String, now: Instant, keyOf: (installId: String) -> PublicKey?): GitTokenCheck {
        val parts = CompactJws.parse(token, MAX_TOKEN_CHARS, TYPE, GitJwsHeader.serializer()) ?: return refused(TokenRefusal.MALFORMED)
        val iss = CompactJws.decodeJson(parts.claims, JsonObject.serializer())?.string("iss")
        if (iss.isNullOrEmpty()) return refused(TokenRefusal.MALFORMED)
        val key = keyOf(iss) ?: return refused(TokenRefusal.UNKNOWN_KEY)
        if (!parts.signedBy(key)) return refused(TokenRefusal.BAD_SIGNATURE)
        val claims = CompactJws.decodeJson(parts.claims, InstallGitClaims.serializer()) ?: return refused(TokenRefusal.MALFORMED)
        if (claims.iss != iss || claims.shapeProblem() != null) return refused(TokenRefusal.MALFORMED)
        if (claims.aud != audience) return refused(TokenRefusal.WRONG_AUDIENCE)
        if (claims.exp - claims.iat > MAX_LIFETIME.seconds) return refused(TokenRefusal.TOO_LONG_LIVED)
        val nowS = now.epochSecond
        if (claims.iat > nowS + CLOCK_SKEW.seconds) return refused(TokenRefusal.NOT_YET_VALID)
        if (nowS > claims.exp + CLOCK_SKEW.seconds) return refused(TokenRefusal.EXPIRED)
        return GitTokenCheck.Valid(claims)
    }

    private fun refused(refusal: TokenRefusal) = GitTokenCheck.Refused(refusal)
}
