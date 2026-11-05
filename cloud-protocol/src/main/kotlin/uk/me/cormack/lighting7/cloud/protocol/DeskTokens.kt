package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Duration
import java.time.Instant

/*
 * Desk access tokens: how the cloud tells a desk who is calling (docs/cloud-protocol.md §"Desk access
 * tokens"). A compact JWS (RFC 7515), EdDSA over Ed25519 (RFC 8037), signed by the cloud with a key
 * its `jwks` message names and checked by the desk with [DeskTokens.verify]. The JDK does both, so
 * neither side needs a JWT library, and both use this code, so the cloud signs exactly what the desk
 * checks.
 */

/** Which door a token opens. A token for one is refused at the other. */
@Serializable
enum class TokenScope {
    /** A remote session's requests, through the sidecar. Names a [DeskAccessClaims.sid]. */
    @SerialName("remote")
    REMOTE,

    /** An MCP call relayed over the control channel. Names a [DeskAccessClaims.gid] and [DeskAccessClaims.azp]. */
    @SerialName("mcp")
    MCP,
}

/** A token's claims. Times are seconds since the epoch, as JWT's NumericDate is. */
@Serializable
data class DeskAccessClaims(
    /** The cloud's origin. */
    val iss: String,
    /** The install id the token is for. */
    val aud: String,
    /** The cloud account id. */
    val sub: String,
    /** The cloud org id. */
    val org: String,
    val scope: TokenScope,
    /** The remote session, for [TokenScope.REMOTE]. */
    val sid: String? = null,
    /** The MCP grant, for [TokenScope.MCP]. */
    val gid: String? = null,
    /** The MCP grant's client id, for [TokenScope.MCP]. */
    val azp: String? = null,
    val iat: Long,
    val exp: Long,
    val jti: String,
) {
    /** Why these claims cannot be a token's whatever the time, or null. */
    fun shapeProblem(): String? = when {
        iat !in 0..LATEST_TIME || exp !in 0..LATEST_TIME -> "iat and exp are times from 1970 to 9999"
        exp <= iat -> "exp is not after iat"
        scope == TokenScope.REMOTE && (sid == null || gid != null || azp != null) -> "a remote token names a sid and no grant"
        scope == TokenScope.MCP && (gid == null || azp == null || sid != null) -> "an MCP token names a grant and its client, and no sid"
        else -> null
    }
}

/** 9999-12-31T23:59:59Z, the latest time a token names, so no arithmetic on one can overflow. */
private const val LATEST_TIME = 253_402_300_799L

/** A JWS header as a desk token writes it. */
@Serializable
internal data class JwsHeader(val alg: String, val kid: String, val typ: String)

/**
 * An Ed25519 public key as a JWK (RFC 8037 §2): `x` is its 32 raw bytes, base64url without padding.
 * [kid] and [x] are optional on the wire so that a JWKS holding a key of another type, which names
 * neither, still decodes, and [publicKeysOf] leaves that key out.
 */
@Serializable
data class Jwk(
    val kty: String = KTY,
    val crv: String = CRV,
    val kid: String? = null,
    val x: String? = null,
) {
    /** The key, or null when this is not an Ed25519 JWK whose `x` is a canonical 32-byte key. */
    fun publicKey(): PublicKey? = if (kty == KTY && crv == CRV && x != null) InstallKeys.decodePublicKey(x) else null

    companion object {
        const val KTY: String = "OKP"
        const val CRV: String = "Ed25519"

        fun of(kid: String, key: PublicKey): Jwk = Jwk(kid = kid, x = InstallKeys.encodePublicKey(key))
    }
}

/**
 * The keys [keys] names, by `kid`, leaving out any that names no `kid` or is not a usable Ed25519 key.
 * A `kid` named twice keeps its first usable key.
 */
fun publicKeysOf(keys: List<Jwk>): Map<String, PublicKey> = buildMap {
    for (jwk in keys) {
        val kid = jwk.kid ?: continue
        val key = jwk.publicKey() ?: continue
        putIfAbsent(kid, key)
    }
}

/** Why a desk refused a token. [code] is the [ErrorCode] a refusal answers with. */
enum class TokenRefusal(val code: String) {
    /**
     * Not three canonical base64url parts, a header or claims that do not decode, an `alg` other than
     * EdDSA, a `typ` other than JWT, any `crit` header, or claims of the wrong shape for their scope.
     */
    MALFORMED(ErrorCode.TOKEN_MALFORMED),

    /** The header's `kid` is not in the JWKS the desk holds. */
    UNKNOWN_KEY(ErrorCode.TOKEN_UNKNOWN_KEY),

    /** The signature does not verify. */
    BAD_SIGNATURE(ErrorCode.TOKEN_BAD_SIGNATURE),

    /** `aud` is not this install. */
    WRONG_AUDIENCE(ErrorCode.TOKEN_WRONG_AUDIENCE),

    /** `scope` is not the door's. */
    WRONG_SCOPE(ErrorCode.TOKEN_WRONG_SCOPE),

    /** `exp` is more than [DeskTokens.MAX_LIFETIME] after `iat`. */
    TOO_LONG_LIVED(ErrorCode.TOKEN_TOO_LONG_LIVED),

    /** `iat` is more than [DeskTokens.CLOCK_SKEW] ahead of the desk's clock. */
    NOT_YET_VALID(ErrorCode.TOKEN_NOT_YET_VALID),

    /** `exp` is more than [DeskTokens.CLOCK_SKEW] behind the desk's clock. */
    EXPIRED(ErrorCode.TOKEN_EXPIRED),
}

/** What [DeskTokens.verify] found. */
sealed interface TokenCheck {
    data class Valid(val kid: String, val claims: DeskAccessClaims) : TokenCheck

    data class Refused(val refusal: TokenRefusal) : TokenCheck
}

object DeskTokens {
    const val ALGORITHM: String = "EdDSA"
    const val TYPE: String = "JWT"

    /** The longest a token may live, `exp` less `iat`. */
    val MAX_LIFETIME: Duration = Duration.ofMinutes(10)

    /** How far the desk's clock may be from the cloud's in either direction. */
    val CLOCK_SKEW: Duration = Duration.ofSeconds(60)

    /** A token longer than this is refused unread. */
    const val MAX_TOKEN_CHARS: Int = 4096

    /** [claims] as a token under [key], whose public half the JWKS names [kid]. Deterministic, since Ed25519 is. */
    fun sign(key: PrivateKey, kid: String, claims: DeskAccessClaims): String {
        claims.shapeProblem()?.let { throw IllegalArgumentException(it) }
        require(claims.exp - claims.iat <= MAX_LIFETIME.seconds) { "A desk token lives at most ${MAX_LIFETIME.seconds} s" }
        val header = CloudJson.encodeToString(JwsHeader.serializer(), JwsHeader(alg = ALGORITHM, kid = kid, typ = TYPE))
        val body = CloudJson.encodeToString(DeskAccessClaims.serializer(), claims)
        val signingInput = Base64Url.encode(header.toByteArray()) + "." + Base64Url.encode(body.toByteArray())
        val token = signingInput + "." + Base64Url.encode(InstallKeys.signBytes(key, signingInput.toByteArray(Charsets.US_ASCII)))
        require(token.length <= MAX_TOKEN_CHARS) { "A desk token is at most $MAX_TOKEN_CHARS characters" }
        return token
    }

    /**
     * Whether [token] is one [keys] signed for [audience] at the [scope] door, judged at [now]. The
     * signature is checked before any claim is trusted; each refusal names the first problem found.
     */
    fun verify(token: String, keys: Map<String, PublicKey>, audience: String, scope: TokenScope, now: Instant): TokenCheck {
        if (token.length > MAX_TOKEN_CHARS) return refused(TokenRefusal.MALFORMED)
        val parts = token.split('.')
        if (parts.size != 3) return refused(TokenRefusal.MALFORMED)
        val (headerPart, claimsPart, signaturePart) = parts
        val headerObject = runCatching { Base64Url.decode(headerPart) }.getOrNull()?.let { decodeJson(it, JsonObject.serializer()) }
            ?: return refused(TokenRefusal.MALFORMED)
        if ("crit" in headerObject) return refused(TokenRefusal.MALFORMED)
        val header = runCatching { CloudJson.decodeFromJsonElement(JwsHeader.serializer(), headerObject) }.getOrNull()
            ?: return refused(TokenRefusal.MALFORMED)
        if (header.alg != ALGORITHM || header.typ != TYPE) return refused(TokenRefusal.MALFORMED)
        val signature = runCatching { Base64Url.decode(signaturePart) }.getOrNull() ?: return refused(TokenRefusal.MALFORMED)
        val claimsBytes = runCatching { Base64Url.decode(claimsPart) }.getOrNull() ?: return refused(TokenRefusal.MALFORMED)
        val key = keys[header.kid] ?: return refused(TokenRefusal.UNKNOWN_KEY)
        if (!InstallKeys.verifyBytes(key, "$headerPart.$claimsPart".toByteArray(Charsets.US_ASCII), signature)) {
            return refused(TokenRefusal.BAD_SIGNATURE)
        }
        val claims = decodeJson(claimsBytes, DeskAccessClaims.serializer()) ?: return refused(TokenRefusal.MALFORMED)
        if (claims.shapeProblem() != null) return refused(TokenRefusal.MALFORMED)
        if (claims.aud != audience) return refused(TokenRefusal.WRONG_AUDIENCE)
        if (claims.scope != scope) return refused(TokenRefusal.WRONG_SCOPE)
        if (claims.exp - claims.iat > MAX_LIFETIME.seconds) return refused(TokenRefusal.TOO_LONG_LIVED)
        val nowS = now.epochSecond
        if (claims.iat > nowS + CLOCK_SKEW.seconds) return refused(TokenRefusal.NOT_YET_VALID)
        if (nowS > claims.exp + CLOCK_SKEW.seconds) return refused(TokenRefusal.EXPIRED)
        return TokenCheck.Valid(header.kid, claims)
    }

    private fun refused(refusal: TokenRefusal) = TokenCheck.Refused(refusal)

    private fun <T> decodeJson(bytes: ByteArray, serializer: KSerializer<T>): T? =
        runCatching { CloudJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)) }.getOrNull()
}
