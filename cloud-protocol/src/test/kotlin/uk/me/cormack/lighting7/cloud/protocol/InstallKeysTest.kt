package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signing vectors in `protocol/signing/` were computed with Python's `cryptography` from RFC
 * 8032's first test key, outside this code, so passing them says the desk signs what another
 * Ed25519 implementation would.
 */
class InstallKeysTest {
    private val key = fixtureJson("signing/test-key.json").jsonObject
    private fun keyField(name: String) = key.getValue(name).jsonPrimitive.content
    private val privateKey = InstallKeys.decodePrivateKey(keyField("privateKeyPkcs8"))
    private val publicKey = requireNotNull(InstallKeys.decodePublicKey(keyField("publicKey")))

    @Test
    fun `the test key is RFC 8032's and the JDK signs its vector`() {
        val rfc = key.getValue("rfc8032").jsonObject
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(rfc.getValue("messageHex").jsonPrimitive.content.hexToByteArray())
        assertEquals(rfc.getValue("signatureHex").jsonPrimitive.content, signer.sign().toHexString())
    }

    @Test
    fun `keys encode as the fixture writes them`() {
        assertEquals(keyField("publicKey"), InstallKeys.encodePublicKey(publicKey))
        assertEquals(keyField("privateKeyPkcs8"), InstallKeys.encodePrivateKey(privateKey))
        assertEquals(keyField("fingerprint"), InstallKeys.fingerprint(publicKey))
    }

    @Test
    fun `every vector's canonical bytes and signature match`() {
        val vectors = fixtureJson("signing/vectors.json").jsonObject.getValue("vectors").jsonArray
        assertEquals(setOf("auth", "adoptionPoll", "unadopt", "repoApiList", "repoApiCreate"), vectors.map { it.jsonObject.getValue("payload").jsonPrimitive.content }.toSet())
        for (entry in vectors) {
            val vector = entry.jsonObject
            val fields = vector.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val payload: SignedPayload = when (val name = vector.getValue("payload").jsonPrimitive.content) {
                "auth" -> AuthPayload(fields.getValue("installId"), fields.getValue("nonce"), fields.getValue("timestamp"))
                "adoptionPoll" -> AdoptionPollPayload(fields.getValue("adoptionId"), fields.getValue("timestamp"))
                "unadopt" -> UnadoptPayload(fields.getValue("installId"), fields.getValue("timestamp"))
                "repoApiList", "repoApiCreate" -> RepoApiPayload.of(
                    fields.getValue("installId"),
                    fields.getValue("method"),
                    fields.getValue("path"),
                    fields.getValue("timestamp"),
                    vector.getValue("body").jsonPrimitive.content.toByteArray(),
                ).also { assertEquals(fields.getValue("bodySha256"), it.bodySha256, "$name's body digest") }
                else -> error("unknown vector $name")
            }
            assertEquals(vector.getValue("domain").jsonPrimitive.content, payload.domain)
            assertEquals(vector.getValue("canonicalBytesHex").jsonPrimitive.content, payload.canonicalBytes().toHexString(), "$payload's bytes")
            val signature = vector.getValue("signature").jsonPrimitive.content
            assertEquals(signature, InstallKeys.sign(privateKey, payload), "$payload's signature")
            assertTrue(InstallKeys.verify(publicKey, payload, signature))
        }
    }

    @Test
    fun `a signature over one payload never verifies as another`() {
        val poll = AdoptionPollPayload("same-id", "2026-11-02T22:18:03.000Z")
        val unadopt = UnadoptPayload("same-id", "2026-11-02T22:18:03.000Z")
        assertEquals(poll.fields, unadopt.fields)
        assertFalse(InstallKeys.verify(publicKey, unadopt, InstallKeys.sign(privateKey, poll)))
        // A boundary moved between fields gives other bytes, so the length prefixes do their job.
        assertNotEquals(
            AuthPayload("ab", "c", "d").canonicalBytes().toHexString(),
            AuthPayload("a", "bc", "d").canonicalBytes().toHexString(),
        )
    }

    @Test
    fun `a repo API signature covers its method, path and body`() {
        val list = RepoApiPayload.of("inst_01JBX3Q5Z8", "GET", "/v1/repos", "2026-11-02T22:18:03.000Z", ByteArray(0))
        val signature = InstallKeys.sign(privateKey, list)
        assertTrue(InstallKeys.verify(publicKey, list, signature))
        assertFalse(InstallKeys.verify(publicKey, list.copy(method = "POST"), signature))
        assertFalse(InstallKeys.verify(publicKey, list.copy(path = "/v1/repos/repo_1"), signature))
        val body = RepoApiPayload.of("inst_01JBX3Q5Z8", "GET", "/v1/repos", "2026-11-02T22:18:03.000Z", "{}".toByteArray())
        assertFalse(InstallKeys.verify(publicKey, body, signature))
        assertFailsWith<IllegalArgumentException> { RepoApiPayload.of("inst_01JBX3Q5Z8", "GET", "/v1/repos?x=1", "2026-11-02T22:18:03.000Z", ByteArray(0)) }
    }

    @Test
    fun `a field's length is its UTF-8 byte count`() {
        val bytes = UnadoptPayload("é", "t").canonicalBytes()
        val domain = UnadoptPayload.DOMAIN.toByteArray()
        assertEquals("00000002c3a9", bytes.copyOfRange(4 + domain.size, 4 + domain.size + 6).toHexString())
    }

    @Test
    fun `verify is false for a tampered payload, another key or a malformed signature`() {
        val payload = UnadoptPayload("inst_01JBX3Q5Z8", "2026-11-02T22:18:03.000Z")
        val signature = InstallKeys.sign(privateKey, payload)
        assertFalse(InstallKeys.verify(publicKey, payload.copy(timestamp = "2026-11-02T22:18:04.000Z"), signature))
        assertFalse(InstallKeys.verify(InstallKeys.generate().public, payload, signature))
        assertFalse(InstallKeys.verify(publicKey, payload, "not base64!"))
        assertFalse(InstallKeys.verify(publicKey, payload, signature.dropLast(4)))
        assertFalse(InstallKeys.verify(publicKey, payload, "$signature=="))
        assertFalse(InstallKeys.verify(publicKey, payload, signature.withStrayLowBit()))
    }

    /** The same bytes spelled another way: the last character's unused low bit set. */
    private fun String.withStrayLowBit(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return dropLast(1) + alphabet[alphabet.indexOf(last()) xor 1]
    }

    @Test
    fun `a generated key round-trips through its encodings and signs`() {
        val pair = InstallKeys.generate()
        val public = requireNotNull(InstallKeys.decodePublicKey(InstallKeys.encodePublicKey(pair.public)))
        val private = InstallKeys.decodePrivateKey(InstallKeys.encodePrivateKey(pair.private))
        val payload = AuthPayload("i", "n", "t")
        assertTrue(InstallKeys.verify(public, payload, InstallKeys.sign(private, payload)))
        assertEquals(43, InstallKeys.encodePublicKey(pair.public).length)
    }

    @Test
    fun `a public key that is not 32 base64url bytes does not decode`() {
        assertNull(InstallKeys.decodePublicKey("nope!"))
        assertNull(InstallKeys.decodePublicKey(keyField("publicKey").dropLast(2)))
        assertNull(InstallKeys.decodePublicKey(""))
        assertNull(InstallKeys.decodePublicKey(keyField("publicKey") + "="))
        assertNull(InstallKeys.decodePublicKey(keyField("publicKey").withStrayLowBit()))
    }
}
