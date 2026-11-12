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

/**
 * `protocol/tokens/git.json` was computed with Python's `cryptography` from RFC 8032's first test key,
 * outside this code, so passing it says the desk signs, and the cloud checks, what another EdDSA
 * implementation would.
 */
class InstallGitTokensTest {
    private val testKey = fixtureJson("signing/test-key.json").jsonObject
    private val privateKey = InstallKeys.decodePrivateKey(testKey.getValue("privateKeyPkcs8").jsonPrimitive.content)
    private val publicKey = requireNotNull(InstallKeys.decodePublicKey(testKey.getValue("publicKey").jsonPrimitive.content))

    private val vectors = fixtureJson("tokens/git.json").jsonObject
    private fun field(name: String) = vectors.getValue(name).jsonPrimitive.content
    private val installId = field("installId")
    private val audience = field("audience")
    private val now = Instant.parse(field("now"))
    private val keyOf = { id: String -> publicKey.takeIf { id == installId } }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun `the vectors name the clock, the audience and the Basic user`() {
        assertEquals(vectors.getValue("nowEpochS").jsonPrimitive.content.toLong(), now.epochSecond)
        assertEquals(audience, InstallGitTokens.audience("https://cloud.example"))
        assertEquals(audience, InstallGitTokens.audience("https://cloud.example/"))
        assertEquals(InstallGitTokens.BASIC_USER, field("basicUser"))
    }

    @Test
    fun `the audience is the cloud's origin, spelt one way, and its git`() {
        assertEquals(audience, InstallGitTokens.audience("HTTPS://Cloud.Example"))
        assertEquals(audience, InstallGitTokens.audience("https://cloud.example:443"))
        assertEquals(audience, InstallGitTokens.audience("https://cloud.example/portal/sync?x=1"))
        assertEquals("https://cloud.example:8443/git", InstallGitTokens.audience("https://cloud.example:8443/"))
        assertEquals("http://localhost:5173/git", InstallGitTokens.audience("http://localhost:5173"))
        assertEquals("http://localhost/git", InstallGitTokens.audience("http://localhost:80/"))
        for (notAUrl in listOf("cloud.example", "ftp://cloud.example", "https://", "https:///git", "")) {
            assertFailsWith<IllegalArgumentException>(notAUrl) { InstallGitTokens.audience(notAUrl) }
        }
    }

    @Test
    fun `every valid vector signs to its token and verifies to its claims`() {
        val valid = vectors.getValue("valid").jsonArray.map { it.jsonObject }
        assertEquals(setOf("git", "ten-minute-lifetime", "expired-within-skew", "issued-within-skew"), valid.map { it.text("name") }.toSet())
        for (vector in valid) {
            val name = vector.text("name")
            val claims = CloudJson.decodeFromJsonElement(InstallGitClaims.serializer(), vector.getValue("claims"))
            assertEquals(vector.getValue("claims"), CloudJson.encodeToJsonElement(InstallGitClaims.serializer(), claims), "$name's claims re-encode")
            assertEquals(vector.text("token"), InstallGitTokens.sign(privateKey, claims), "$name signs")
            assertEquals(GitTokenCheck.Valid(claims), InstallGitTokens.verify(vector.text("token"), audience, now, keyOf), "$name verifies")
        }
    }

    @Test
    fun `every refused vector is refused for its reason`() {
        val refused = vectors.getValue("refused").jsonArray.map { it.jsonObject }
        assertEquals(
            (TokenRefusal.entries - TokenRefusal.WRONG_SCOPE).map { it.code }.toSet(),
            refused.map { it.text("refusal") }.toSet(),
            "every refusal a git token can get has a vector",
        )
        for (vector in refused) {
            val check = InstallGitTokens.verify(vector.text("token"), audience, now, keyOf)
            assertEquals(vector.text("refusal"), assertIs<GitTokenCheck.Refused>(check, vector.text("name")).refusal.code, vector.text("name"))
        }
    }

    @Test
    fun `an iss that is no install id is refused before any key is looked up`() {
        val refused = vectors.getValue("refused").jsonArray.map { it.jsonObject }
        for (name in listOf("missing-iss", "empty-iss", "iss-not-a-string")) {
            val token = refused.single { it.text("name") == name }.text("token")
            assertEquals(GitTokenCheck.Refused(TokenRefusal.MALFORMED), InstallGitTokens.verify(token, audience, now) { error("$name looked up a key") }, name)
        }
    }

    @Test
    fun `a minted token lives ten minutes and verifies`() {
        val token = InstallGitTokens.mint(privateKey, installId, "https://cloud.example", now)
        val claims = assertIs<GitTokenCheck.Valid>(InstallGitTokens.verify(token, audience, now, keyOf)).claims
        assertEquals(installId, claims.iss)
        assertEquals(now.epochSecond, claims.iat)
        assertEquals(now.epochSecond + 600, claims.exp)
        val again = InstallGitTokens.mint(privateKey, installId, "https://cloud.example", now)
        assert(token != again) { "each token has its own jti" }
    }

    @Test
    fun `a desk access token is not a git token, nor a git token a desk access token`() {
        val deskTokens = fixtureJson("tokens/vectors.json").jsonObject
        val remote = deskTokens.getValue("valid").jsonArray.map { it.jsonObject }.single { it.text("name") == "remote" }.text("token")
        assertEquals(GitTokenCheck.Refused(TokenRefusal.MALFORMED), InstallGitTokens.verify(remote, audience, now, keyOf))
        val git = vectors.getValue("valid").jsonArray.map { it.jsonObject }.single { it.text("name") == "git" }.text("token")
        val check = DeskTokens.verify(git, mapOf(deskTokens.getValue("kid").jsonPrimitive.content to publicKey), installId, TokenScope.REMOTE, now)
        assertEquals(TokenCheck.Refused(TokenRefusal.MALFORMED), check)
    }

    @Test
    fun `a removed install's key is no key`() {
        val token = InstallGitTokens.mint(privateKey, installId, "https://cloud.example", now)
        assertEquals(GitTokenCheck.Refused(TokenRefusal.UNKNOWN_KEY), InstallGitTokens.verify(token, audience, now) { null })
    }

    @Test
    fun `the skew is judged at its edges`() {
        val claims = InstallGitClaims(installId, audience, now.epochSecond, now.epochSecond + 300, "jti_e")
        val token = InstallGitTokens.sign(privateKey, claims)
        fun at(epochS: Long) = InstallGitTokens.verify(token, audience, Instant.ofEpochSecond(epochS), keyOf)
        assertIs<GitTokenCheck.Valid>(at(claims.exp + 60))
        assertEquals(GitTokenCheck.Refused(TokenRefusal.EXPIRED), at(claims.exp + 61))
        assertIs<GitTokenCheck.Valid>(at(claims.iat - 60))
        assertEquals(GitTokenCheck.Refused(TokenRefusal.NOT_YET_VALID), at(claims.iat - 61))
    }

    @Test
    fun `sign refuses what verify would refuse whatever the time`() {
        val claims = InstallGitClaims(installId, audience, now.epochSecond, now.epochSecond + 600, "jti_s")
        assertFailsWith<IllegalArgumentException> { InstallGitTokens.sign(privateKey, claims.copy(exp = claims.iat + 601)) }
        assertFailsWith<IllegalArgumentException> { InstallGitTokens.sign(privateKey, claims.copy(exp = claims.iat)) }
        assertFailsWith<IllegalArgumentException> { InstallGitTokens.sign(privateKey, claims.copy(iss = "")) }
        assertFailsWith<IllegalArgumentException> { InstallGitTokens.sign(privateKey, claims.copy(aud = "")) }
        assertFailsWith<IllegalArgumentException> { InstallGitTokens.sign(privateKey, claims.copy(jti = "")) }
        assertFailsWith<IllegalArgumentException> { InstallGitTokens.sign(privateKey, claims.copy(iat = Long.MIN_VALUE)) }
        assertFailsWith<IllegalArgumentException> { InstallGitTokens.sign(privateKey, claims.copy(jti = "j".repeat(InstallGitTokens.MAX_TOKEN_CHARS))) }
    }

    @Test
    fun `a token over the length limit is refused unread`() {
        assertEquals(
            GitTokenCheck.Refused(TokenRefusal.MALFORMED),
            InstallGitTokens.verify("a".repeat(InstallGitTokens.MAX_TOKEN_CHARS + 1), audience, now) { error("never looked up") },
        )
    }
}
