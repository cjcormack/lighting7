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
        val envelope = Envelope.decode("""{"v":1,"type":"mcp.call","id":"x","body":{"tool":"describe_rig"}}""")
        val body = envelope.body as Message.Unknown
        assertEquals("mcp.call", envelope.type)
        assertEquals("""{"tool":"describe_rig"}""", body.body.toString())
        assertEquals(envelope, Envelope.decode(envelope.encode()))
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
