package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeTest {
    @Test
    fun `a frame that is not an envelope is refused`() {
        for (frame in listOf(
            """[]""",
            """{"type":"health","id":"x","body":{"uptimeS":1,"deskVersion":"1"}}""",
            """{"v":"1","type":"health","id":"x","body":{"uptimeS":1,"deskVersion":"1"}}""",
            """{"v":1,"id":"x","body":{}}""",
            """{"v":1,"type":"health","body":{"uptimeS":1,"deskVersion":"1"}}""",
            """{"v":1,"type":"health","id":"x"}""",
            """{"v":1,"type":"health","id":"x","body":[]}""",
            """{"v":1,"type":"health","id":"x","replyTo":7,"body":{"uptimeS":1,"deskVersion":"1"}}""",
        )) {
            assertFailsWith<SerializationException>(frame) { Envelope.decode(frame) }
        }
    }

    @Test
    fun `a known type with a malformed body is refused rather than read as unknown`() {
        assertFailsWith<SerializationException> { Envelope.decode("""{"v":1,"type":"health","id":"x","body":{"uptimeS":1}}""") }
    }

    @Test
    fun `a field a later version adds is ignored`() {
        val envelope = Envelope.decode("""{"v":1,"type":"health","id":"x","body":{"uptimeS":1,"deskVersion":"1","cpu":0.4},"trace":"t"}""")
        assertEquals(Message.Health(1, "1"), envelope.body)
    }

    @Test
    fun `another version's frame decodes whole, for the reader to judge`() {
        val frame = """{"v":2,"type":"welcome","id":"x","body":{"org":{"name":"Renamed"},"heartbeatMs":25000}}"""
        val envelope = Envelope.decode(frame)
        assertEquals(2, envelope.v)
        assertEquals("welcome", envelope.type)
        assertTrue(envelope.body is Message.Unknown)
        assertEquals(Json.parseToJsonElement(frame), Json.parseToJsonElement(envelope.encode()))
    }

    @Test
    fun `an unknown type keeps its type and body`() {
        val envelope = Envelope.decode("""{"v":1,"type":"future.call","id":"x","body":{"tool":"describe_rig"}}""")
        val body = envelope.body as Message.Unknown
        assertEquals("future.call", envelope.type)
        assertEquals("""{"tool":"describe_rig"}""", body.body.toString())
        assertEquals(envelope, Envelope.decode(envelope.encode()))
    }

    @Test
    fun `a welcome from a cloud that names no remote origin still reads`() {
        val frame = """{"v":1,"type":"welcome","id":"x","body":{"orgName":"O","heartbeatS":25}}"""
        assertEquals(Message.Welcome("O", 25), Envelope.decode(frame).body)
        assertEquals(frame, Envelope("x", Message.Welcome("O", 25)).encode())
    }

    @Test
    fun `a revoke revokes every target it names`() {
        val at = "2026-11-05T12:00:00.000Z"
        assertEquals(setOf(RevokeTarget.Session("s")), Message.Revoke(at, sid = "s").targets)
        assertEquals(setOf(RevokeTarget.Account("a")), Message.Revoke(at, accountId = "a").targets)
        assertEquals(setOf(RevokeTarget.All), Message.Revoke(at, all = true).targets)
        assertEquals(emptySet(), Message.Revoke(at).targets)
        assertEquals(setOf(RevokeTarget.Session("s"), RevokeTarget.Account("a")), Message.Revoke(at, sid = "s", accountId = "a").targets)
        assertEquals("""{"v":1,"type":"revoke","id":"x","body":{"at":"$at","all":true}}""", Envelope("x", Message.Revoke(at, all = true)).encode())
        assertEquals("""{"v":1,"type":"revoke","id":"x","body":{"at":"$at","accountId":"a"}}""", Envelope("x", Message.Revoke(at, accountId = "a")).encode())
    }

    @Test
    fun `a revoke covers every token issued in or before its second`() {
        val revoke = Message.Revoke("2026-11-05T12:00:00.400Z", sid = "s")
        val second = Instant.parse("2026-11-05T12:00:00Z").epochSecond
        assertTrue(revoke.covers(second - 1))
        assertTrue(revoke.covers(second), "a token minted later in the revocation's second")
        assertFalse(revoke.covers(second + 1))
        assertTrue(Message.Revoke("yesterday", sid = "s").covers(second + 3600), "an unreadable at covers everything")
    }

    @Test
    fun `the end of a session's candidates and a notification's result carry no body field`() {
        assertEquals("""{"v":1,"type":"rtc.ice","id":"x","body":{"sid":"s"}}""", Envelope("x", Message.RtcIce("s")).encode())
        assertEquals(Message.RtcIce("s"), Envelope.decode("""{"v":1,"type":"rtc.ice","id":"x","body":{"sid":"s","candidate":null}}""").body)
        assertEquals("""{"v":1,"type":"mcp.result","id":"x","replyTo":"c","body":{}}""", Envelope("x", Message.McpResult(), replyTo = "c").encode())
    }

    @Test
    fun `a ready names an unlinked session only when it is one`() {
        assertEquals("""{"type":"ready","user":"u","role":"ADMIN","build":"b"}""", CloudJson.encodeToString(CtlMessage.serializer(), CtlMessage.Ready("u", "ADMIN", build = "b")))
        assertEquals("""{"type":"ready","unlinked":true,"build":"b"}""", CloudJson.encodeToString(CtlMessage.serializer(), CtlMessage.Ready(unlinked = true, build = "b")))
    }

    @Test
    fun `a null replyTo is omitted`() {
        assertNull(Envelope.decode("""{"v":1,"type":"unadopted","id":"x","replyTo":null,"body":{}}""").replyTo)
        assertEquals("""{"v":1,"type":"unadopted","id":"x","body":{}}""", Envelope("x", Message.Unadopted).encode())
    }

    @Test
    fun `timestamps have three fraction digits and are judged against the skew tolerance`() {
        assertEquals("2026-11-02T22:18:03.000Z", Timestamps.format(Instant.parse("2026-11-02T22:18:03Z")))
        assertEquals("2026-11-02T22:18:03.123Z", Timestamps.format(Instant.parse("2026-11-02T22:18:03.123456Z")))
        val now = Instant.parse("2026-11-02T22:18:03Z")
        assertTrue(Timestamps.withinSkew("2026-11-02T22:23:03.000Z", now))
        assertTrue(Timestamps.withinSkew("2026-11-02T22:13:03.000Z", now))
        assertFalse(Timestamps.withinSkew("2026-11-02T22:23:03.001Z", now))
        assertFalse(Timestamps.withinSkew("yesterday", now))
        for (loose in listOf("2026-11-02t22:18:03.000z", "2026-11-02T23:18:03.000+01:00", "2026-11-02T22:18:03Z", "2026-11-02T22:18:03.000000000Z")) {
            assertNull(Timestamps.parse(loose), loose)
            assertFalse(Timestamps.withinSkew(loose, now), loose)
        }
    }

    @Test
    fun `a user code is eight consonants in two fours`() {
        assertTrue(UserCode.isWellFormed("WDJB-MJHT"))
        assertFalse(UserCode.isWellFormed("WDJB-MJHA"))
        assertFalse(UserCode.isWellFormed("WDJBMJHT"))
        assertFalse(UserCode.isWellFormed("wdjb-mjht"))
    }

    @Test
    fun `an unknown close code or auth reason reads as null`() {
        assertNull(CloseCode.of(1000))
        assertEquals(AuthFailure.CLOCK_SKEW, AuthFailure.parse("CLOCK_SKEW"))
        assertNull(AuthFailure.parse("SOMETHING_NEW"))
    }
}
