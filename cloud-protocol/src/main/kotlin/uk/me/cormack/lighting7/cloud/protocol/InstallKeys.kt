package uk.me.cormack.lighting7.cloud.protocol

import java.io.ByteArrayOutputStream
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.EdECKey
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The install key: an Ed25519 key pair from the JDK, made by the desk when adoption starts. Its
 * private half never leaves the desk; its public half is sent at adoption. Every request the
 * install makes is signed over a [SignedPayload], whose bytes both sides build with
 * [SignedPayload.canonicalBytes], so the desk's signing and the cloud's checking cannot drift.
 */
object InstallKeys {
    private const val ALGORITHM = "Ed25519"

    /** The DER header of an Ed25519 SubjectPublicKeyInfo (RFC 8410): what precedes the 32 key bytes. */
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private const val RAW_KEY_BYTES = 32

    fun generate(): KeyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()

    /** The public key on the wire: its 32 raw bytes (RFC 8032), base64url without padding. */
    fun encodePublicKey(key: PublicKey): String = Base64Url.encode(rawPublicKey(key))

    private fun rawPublicKey(key: PublicKey): ByteArray {
        val spki = key.encoded
        require(key.isEd25519() && spki.size == SPKI_PREFIX.size + RAW_KEY_BYTES && spki.startsWith(SPKI_PREFIX)) {
            "Not an Ed25519 public key"
        }
        return spki.copyOfRange(SPKI_PREFIX.size, spki.size)
    }

    /** The key [encoded] names, or null when it is not an unpadded base64url Ed25519 public key. */
    fun decodePublicKey(encoded: String): PublicKey? = runCatching {
        val raw = Base64Url.decode(encoded)
        require(raw.size == RAW_KEY_BYTES)
        KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(SPKI_PREFIX + raw))
    }.getOrNull()

    /** The private key as the desk stores it: PKCS#8, base64. */
    fun encodePrivateKey(key: PrivateKey): String {
        require(key.isEd25519()) { "Not an Ed25519 private key" }
        return Base64.getEncoder().encodeToString(key.encoded)
    }

    fun decodePrivateKey(encoded: String): PrivateKey =
        KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))

    /** `SHA256:` and the base64 (unpadded) SHA-256 of the raw public key: what the desk and the portal show. */
    fun fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawPublicKey(key))
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** The signature of [payload], base64url without padding. Ed25519 is deterministic, so it is the same every time. */
    fun sign(key: PrivateKey, payload: SignedPayload): String = Base64Url.encode(signBytes(key, payload.canonicalBytes()))

    /** Whether [signature] is [key]'s over [payload]. False, never an exception, for a malformed signature. */
    fun verify(key: PublicKey, payload: SignedPayload, signature: String): Boolean =
        runCatching { Base64Url.decode(signature) }.getOrNull()?.let { verifyBytes(key, payload.canonicalBytes(), it) } ?: false

    internal fun signBytes(key: PrivateKey, bytes: ByteArray): ByteArray {
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(key)
        signer.update(bytes)
        return signer.sign()
    }

    /** False, never an exception, for a key or signature the JDK refuses. */
    internal fun verifyBytes(key: PublicKey, bytes: ByteArray, signature: ByteArray): Boolean = runCatching {
        val verifier = Signature.getInstance(ALGORITHM)
        verifier.initVerify(key)
        verifier.update(bytes)
        verifier.verify(signature)
    }.getOrDefault(false)

    private fun Key.isEd25519(): Boolean = (this as? EdECKey)?.params?.name == NamedParameterSpec.ED25519.name

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/**
 * Something the install signs. Its bytes are its [domain] followed by its fields, each written as a
 * 4-byte big-endian length and its UTF-8 bytes, so no field can run into the next and a signature
 * over one kind of payload never verifies as another.
 */
sealed interface SignedPayload {
    val domain: String
    val fields: List<String>

    fun canonicalBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in listOf(domain) + fields) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            out.write(bytes.size ushr 24)
            out.write(bytes.size ushr 16)
            out.write(bytes.size ushr 8)
            out.write(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }
}

/** What [Message.Auth.signature] signs: the connection's challenge, answered by this install now. */
data class AuthPayload(val installId: String, val nonce: String, val timestamp: String) : SignedPayload {
    override val domain: String get() = DOMAIN
    override val fields: List<String> get() = listOf(installId, nonce, timestamp)

    companion object {
        const val DOMAIN: String = "lighting7-cloud/auth/v1"
    }
}

/** What an adoption poll signs ([SignatureHeaders]): this adoption, polled now. */
data class AdoptionPollPayload(val adoptionId: String, val timestamp: String) : SignedPayload {
    override val domain: String get() = DOMAIN
    override val fields: List<String> get() = listOf(adoptionId, timestamp)

    companion object {
        const val DOMAIN: String = "lighting7-cloud/adoption-poll/v1"
    }
}

/** What an unadopt from the desk signs ([SignatureHeaders]): this install, removed now. */
data class UnadoptPayload(val installId: String, val timestamp: String) : SignedPayload {
    override val domain: String get() = DOMAIN
    override val fields: List<String> get() = listOf(installId, timestamp)

    companion object {
        const val DOMAIN: String = "lighting7-cloud/unadopt/v1"
    }
}
