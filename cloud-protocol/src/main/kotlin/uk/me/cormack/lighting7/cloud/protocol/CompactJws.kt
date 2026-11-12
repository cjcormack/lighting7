package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.PrivateKey
import java.security.PublicKey

/**
 * The compact JWS (RFC 7515) both kinds of token take, EdDSA over Ed25519 (RFC 8037): three canonical
 * base64url parts, `header.claims.signature`. Which header fields and claims a token has are each
 * token's own ([DeskTokens], [InstallGitTokens]).
 */
internal object CompactJws {
    const val ALGORITHM: String = "EdDSA"

    /** 9999-12-31T23:59:59Z, the latest time a token names, so no arithmetic on one can overflow. */
    const val LATEST_TIME: Long = 253_402_300_799L

    /** A token split into its parts, its header checked and none of it trusted yet. */
    class Parts<H>(val header: H, val claims: ByteArray, private val signature: ByteArray, private val signingInput: ByteArray) {
        fun signedBy(key: PublicKey): Boolean = InstallKeys.verifyBytes(key, signingInput, signature)
    }

    /** [header] and [claims], each already JSON, as a token under [key]. */
    fun sign(key: PrivateKey, header: String, claims: String): String {
        val signingInput = Base64Url.encode(header.toByteArray()) + "." + Base64Url.encode(claims.toByteArray())
        return signingInput + "." + Base64Url.encode(InstallKeys.signBytes(key, signingInput.toByteArray(Charsets.US_ASCII)))
    }

    /**
     * [token]'s parts, or null when it is longer than [maxChars], not three canonical base64url parts,
     * or its header is not a JSON object that decodes as [headerSerializer], names [ALGORITHM] and [typ],
     * and has no `crit`.
     */
    fun <H> parse(token: String, maxChars: Int, typ: String, headerSerializer: KSerializer<H>): Parts<H>? {
        if (token.length > maxChars) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        val (headerPart, claimsPart, signaturePart) = parts
        val json = runCatching { Base64Url.decode(headerPart) }.getOrNull()?.let { decodeJson(it, JsonObject.serializer()) } ?: return null
        if ("crit" in json || json.string("alg") != ALGORITHM || json.string("typ") != typ) return null
        val header = runCatching { CloudJson.decodeFromJsonElement(headerSerializer, json) }.getOrNull() ?: return null
        val claims = runCatching { Base64Url.decode(claimsPart) }.getOrNull() ?: return null
        val signature = runCatching { Base64Url.decode(signaturePart) }.getOrNull() ?: return null
        return Parts(header, claims, signature, "$headerPart.$claimsPart".toByteArray(Charsets.US_ASCII))
    }

    fun <T> decodeJson(bytes: ByteArray, serializer: KSerializer<T>): T? =
        runCatching { CloudJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)) }.getOrNull()

    /** The string [name] holds, or null when it is absent or not a string. */
    fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
