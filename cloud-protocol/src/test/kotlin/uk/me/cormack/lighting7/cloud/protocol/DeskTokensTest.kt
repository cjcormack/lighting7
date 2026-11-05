package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `protocol/tokens/vectors.json` was computed with Python's `cryptography` from RFC 8032's first test
 * key, outside this code, so passing it says the desk checks, and the cloud signs, what another
 * EdDSA implementation would.
 */
class DeskTokensTest {
    private val testKey = fixtureJson("signing/test-key.json").jsonObject
    private val privateKey = InstallKeys.decodePrivateKey(testKey.getValue("privateKeyPkcs8").jsonPrimitive.content)
    private val publicKey = requireNotNull(InstallKeys.decodePublicKey(testKey.getValue("publicKey").jsonPrimitive.content))

    private val vectors = fixtureJson("tokens/vectors.json").jsonObject
    private fun field(name: String) = vectors.getValue(name).jsonPrimitive.content
    private val kid = field("kid")
    private val audience = field("audience")
    private val now = Instant.parse(field("now"))
    private val keys = mapOf(kid to publicKey)

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun scopeOf(vector: JsonObject) = CloudJson.decodeFromJsonElement(TokenScope.serializer(), vector.getValue("scope"))

    @Test
    fun `the vectors' clock is the one they name`() {
        assertEquals(vectors.getValue("nowEpochS").jsonPrimitive.content.toLong(), now.epochSecond)
    }

    @Test
    fun `the test key encodes as the vectors' JWK`() {
        val jwk = CloudJson.decodeFromJsonElement(Jwk.serializer(), vectors.getValue("jwk"))
        assertEquals(jwk, Jwk.of(kid, publicKey))
        assertEquals(vectors.getValue("jwk"), CloudJson.encodeToJsonElement(Jwk.serializer(), Jwk.of(kid, publicKey)))
        assertEquals(keys, publicKeysOf(listOf(jwk)))
    }

    @Test
    fun `every valid vector signs to its token and verifies to its claims`() {
        val valid = vectors.getValue("valid").jsonArray.map { it.jsonObject }
        assertEquals(setOf("remote", "mcp", "expired-within-skew", "issued-within-skew", "ten-minute-lifetime"), valid.map { it.text("name") }.toSet())
        for (vector in valid) {
            val name = vector.text("name")
            val claims = CloudJson.decodeFromJsonElement(DeskAccessClaims.serializer(), vector.getValue("claims"))
            assertEquals(vector.getValue("claims"), CloudJson.encodeToJsonElement(DeskAccessClaims.serializer(), claims), "$name's claims re-encode")
            assertEquals(vector.text("token"), DeskTokens.sign(privateKey, vector.text("kid"), claims), "$name signs")
            assertEquals(TokenCheck.Valid(kid, claims), DeskTokens.verify(vector.text("token"), keys, audience, scopeOf(vector), now), "$name verifies")
        }
    }

    @Test
    fun `every refused vector is refused for its reason`() {
        val refused = vectors.getValue("refused").jsonArray.map { it.jsonObject }
        assertEquals(TokenRefusal.entries.map { it.code }.toSet(), refused.map { it.text("refusal") }.toSet(), "every refusal has a vector")
        for (vector in refused) {
            val check = DeskTokens.verify(vector.text("token"), keys, audience, scopeOf(vector), now)
            assertEquals(vector.text("refusal"), assertIs<TokenCheck.Refused>(check, vector.text("name")).refusal.code, vector.text("name"))
        }
    }

    @Test
    fun `a token verifies under whichever JWKS key its kid names`() {
        val next = InstallKeys.generate()
        val claims = remoteClaims()
        val token = DeskTokens.sign(next.private, "next", claims)
        assertEquals(TokenCheck.Valid("next", claims), DeskTokens.verify(token, keys + ("next" to next.public), audience, TokenScope.REMOTE, now))
        assertEquals(TokenCheck.Refused(TokenRefusal.UNKNOWN_KEY), DeskTokens.verify(token, keys, audience, TokenScope.REMOTE, now))
        assertEquals(TokenCheck.Refused(TokenRefusal.BAD_SIGNATURE), DeskTokens.verify(token, mapOf("next" to publicKey), audience, TokenScope.REMOTE, now))
    }

    @Test
    fun `the skew is judged at its edges`() {
        val claims = remoteClaims()
        val token = DeskTokens.sign(privateKey, kid, claims)
        fun at(epochS: Long) = DeskTokens.verify(token, keys, audience, TokenScope.REMOTE, Instant.ofEpochSecond(epochS))
        assertIs<TokenCheck.Valid>(at(claims.exp + 60))
        assertEquals(TokenCheck.Refused(TokenRefusal.EXPIRED), at(claims.exp + 61))
        assertIs<TokenCheck.Valid>(at(claims.iat - 60))
        assertEquals(TokenCheck.Refused(TokenRefusal.NOT_YET_VALID), at(claims.iat - 61))
    }

    @Test
    fun `sign refuses what verify would refuse whatever the time`() {
        assertFailsWith<IllegalArgumentException> { DeskTokens.sign(privateKey, kid, remoteClaims().copy(exp = now.epochSecond + 601)) }
        assertFailsWith<IllegalArgumentException> { DeskTokens.sign(privateKey, kid, remoteClaims().copy(sid = null)) }
        assertFailsWith<IllegalArgumentException> { DeskTokens.sign(privateKey, kid, remoteClaims().copy(gid = "gr_1")) }
        assertFailsWith<IllegalArgumentException> { DeskTokens.sign(privateKey, kid, remoteClaims().copy(scope = TokenScope.MCP)) }
        assertFailsWith<IllegalArgumentException> { DeskTokens.sign(privateKey, kid, remoteClaims().copy(exp = now.epochSecond - 60)) }
        assertFailsWith<IllegalArgumentException> { DeskTokens.sign(privateKey, kid, remoteClaims().copy(jti = "j".repeat(DeskTokens.MAX_TOKEN_CHARS))) }
    }

    @Test
    fun `times that would overflow are malformed, not accepted`() {
        val claims = remoteClaims().copy(iat = Long.MIN_VALUE, exp = Long.MAX_VALUE)
        assertTrue(claims.shapeProblem() != null)
        assertTrue(remoteClaims().copy(iat = -1).shapeProblem() != null)
        assertNull(remoteClaims().shapeProblem())
    }

    @Test
    fun `a token over the length limit is refused unread`() {
        assertEquals(TokenCheck.Refused(TokenRefusal.MALFORMED), DeskTokens.verify("a".repeat(DeskTokens.MAX_TOKEN_CHARS + 1), keys, audience, TokenScope.REMOTE, now))
    }

    @Test
    fun `a JWK that is not an Ed25519 key is left out of the key set`() {
        val x = InstallKeys.encodePublicKey(publicKey)
        val keySet = publicKeysOf(
            listOf(
                Jwk(kty = "EC", kid = "ec", x = x),
                Jwk(crv = "X25519", kid = "x25519", x = x),
                Jwk(kid = "short", x = x.dropLast(2)),
                Jwk(kid = "padded", x = "$x="),
                Jwk(kid = "good", x = "$x="),
                Jwk(x = x),
                Jwk(kid = "good", x = x),
                Jwk(kid = "good", x = InstallKeys.encodePublicKey(InstallKeys.generate().public)),
            ),
        )
        assertEquals(mapOf("good" to publicKey), keySet)
    }

    @Test
    fun `a JWKS holding keys of other types still decodes, and only its Ed25519 keys are kept`() {
        val x = InstallKeys.encodePublicKey(publicKey)
        val frame = """{"v":1,"type":"jwks","id":"c","body":{"keys":[""" +
            """{"kty":"RSA","kid":"rsa","n":"0vx7","e":"AQAB"},{"kty":"oct","k":"c2VjcmV0"},{"kty":"OKP","crv":"Ed25519","x":"$x"},""" +
            """{"kty":"OKP","crv":"Ed25519","kid":"$kid","x":"$x"}]}}"""
        val jwks = assertIs<Message.Jwks>(Envelope.decode(frame).body)
        assertEquals(keys, publicKeysOf(jwks.keys))
    }

    @Test
    fun `a JWK with only kid and x reads as Ed25519`() {
        val jwk = CloudJson.decodeFromString(Jwk.serializer(), """{"kid":"k","x":"${InstallKeys.encodePublicKey(publicKey)}"}""")
        assertEquals(publicKey, jwk.publicKey())
    }

    private fun remoteClaims() = DeskAccessClaims(
        iss = "https://cloud.example",
        aud = audience,
        sub = "acc_9Pz4Rt",
        org = "org_3Hc8Wn",
        scope = TokenScope.REMOTE,
        sid = "rs_5Tg2Lm8Qa1",
        iat = now.epochSecond - 60,
        exp = now.epochSecond + 240,
        jti = "jti_x",
    )
}
