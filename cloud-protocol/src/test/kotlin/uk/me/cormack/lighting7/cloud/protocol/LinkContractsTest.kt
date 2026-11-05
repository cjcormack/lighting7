package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixtures under `protocol/link/` and `protocol/remote-frame/` describe contracts the desk's JVM
 * never speaks (the sidecar's framing and `ctl`, the shell's `postMessage`s), so this test keeps them
 * honest: each frame's bytes are its kind and its payload, within the limits, in an order its label
 * allows, and each message is one the contract names.
 */
class LinkContractsTest {
    private val framing = fixtureJson("link/framing.json").jsonObject

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun `the framing fixture's constants are the protocol's`() {
        assertEquals(LinkFraming.KINDS, framing.getValue("kinds").jsonObject.mapValues { it.value.jsonPrimitive.int })
        assertEquals(
            mapOf("ctl" to LinkFraming.LABEL_CTL, "http" to LinkFraming.LABEL_HTTP, "ws" to LinkFraming.LABEL_WS),
            framing.getValue("labels").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(LinkFraming.MAX_CHUNK_BYTES, framing.getValue("maxChunkBytes").jsonPrimitive.int)
        assertEquals(LinkFraming.MAX_HEAD_BYTES, framing.getValue("maxHeadBytes").jsonPrimitive.int)
        assertEquals(LinkFraming.BUFFERED_HIGH_BYTES, framing.getValue("bufferedHighBytes").jsonPrimitive.int)
        assertEquals(LinkFraming.BUFFERED_LOW_THRESHOLD_BYTES, framing.getValue("bufferedLowThresholdBytes").jsonPrimitive.int)
        assertEquals(LinkFraming.STRIPPED_HEADERS, framing.getValue("strippedHeaders").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(LinkFraming.KINDS.size, LinkFraming.KINDS.values.toSet().size, "every kind byte is distinct")
        assertEquals(64 * 1024, 1 + LinkFraming.MAX_HEAD_BYTES, "a head frame fits SCTP's default message size")
    }

    @Test
    fun `every frame's bytes are its kind and its payload, within the limits`() {
        for (exchange in framing.getValue("exchanges").jsonArray.map { it.jsonObject }) {
            val name = exchange.text("name")
            for (frame in exchange.getValue("frames").jsonArray.map { it.jsonObject }) {
                val kind = frame.text("kind")
                val kindByte = requireNotNull(LinkFraming.KINDS[kind]) { "$name: unknown kind $kind" }
                val bytes = when {
                    "fill" in frame -> {
                        val fill = frame.getValue("fill").jsonObject
                        byteArrayOf(kindByte.toByte()) + ByteArray(fill.getValue("count").jsonPrimitive.int) { fill.getValue("byte").jsonPrimitive.int.toByte() }
                    }
                    else -> frame.text("hex").hexToByteArray()
                }
                assertEquals(kindByte, bytes[0].toInt() and 0xff, "$name: $kind's first byte")
                val payload = bytes.copyOfRange(1, bytes.size)
                when (kindByte) {
                    LinkFraming.HEAD, LinkFraming.RESET, LinkFraming.CLOSE -> {
                        assertTrue(payload.size <= LinkFraming.MAX_HEAD_BYTES, "$name: $kind within the head limit")
                        assertEquals(frame.getValue("json"), Json.parseToJsonElement(payload.decodeToString()), "$name: $kind's JSON")
                    }
                    LinkFraming.END -> assertEquals(0, payload.size, "$name: END carries nothing")
                    LinkFraming.TEXT_END, LinkFraming.BINARY_END -> {
                        assertTrue(payload.size <= LinkFraming.MAX_CHUNK_BYTES, "$name: $kind within the chunk limit")
                        if ("text" in frame) assertEquals(frame.text("text"), payload.decodeToString(), "$name: $kind's text")
                    }
                    else -> {
                        assertTrue(payload.isNotEmpty() && payload.size <= LinkFraming.MAX_CHUNK_BYTES, "$name: $kind within the chunk limit")
                        if ("text" in frame) assertEquals(frame.text("text"), payload.decodeToString(), "$name: $kind's text")
                    }
                }
            }
        }
    }

    @Test
    fun `every exchange is in an order its label allows`() {
        val exchanges = framing.getValue("exchanges").jsonArray.map { it.jsonObject }
        assertEquals(
            setOf("get", "post-chunked", "binary-body", "reset", "websocket", "websocket-refused"),
            exchanges.map { it.text("name") }.toSet(),
        )
        for (exchange in exchanges) {
            val name = exchange.text("name")
            val frames = exchange.getValue("frames").jsonArray.map { it.jsonObject }
            val heads = frames.filter { it.text("kind") == "HEAD" }
            assertEquals("browser", heads.first().text("from"), "$name: the browser's head comes first")
            assertEquals(frames.first(), heads.first(), "$name: nothing precedes the browser's head")
            when (exchange.text("label")) {
                LinkFraming.LABEL_HTTP -> {
                    val request = heads.first().getValue("json").jsonObject
                    assertTrue(setOf("method", "path", "headers", "origin").all { it in request }, "$name: a request head")
                    val allowed = setOf("HEAD", "DATA", "END", "RESET")
                    assertTrue(frames.all { it.text("kind") in allowed }, "$name: HTTP frames only")
                    if (heads.size > 1) assertTrue("status" in heads[1].getValue("json").jsonObject, "$name: a response head")
                }
                LinkFraming.LABEL_WS -> {
                    val open = heads.first().getValue("json").jsonObject
                    assertTrue(setOf("path", "headers", "origin").all { it in open }, "$name: an open head")
                    val status = heads[1].getValue("json").jsonObject.getValue("status").jsonPrimitive.int
                    val after = frames.drop(frames.indexOf(heads[1]) + 1)
                    if (status != 101) assertEquals(emptyList(), after, "$name: nothing follows a refused upgrade")
                    val allowed = setOf("TEXT_PART", "TEXT_END", "BINARY_PART", "BINARY_END", "CLOSE")
                    assertTrue(after.all { it.text("kind") in allowed }, "$name: WebSocket frames only after the upgrade")
                }
                else -> error("$name: unknown label")
            }
        }
    }

    @Test
    fun `every ctl message round-trips`() {
        val ctl = fixtureJson("link/ctl.json").jsonObject
        assertEquals(LinkFraming.LABEL_CTL, ctl.text("label"))
        val messages = ctl.getValue("messages").jsonArray.map { it.jsonObject }
        val decoded = messages.map { CloudJson.decodeFromJsonElement(CtlMessage.serializer(), it.getValue("message")) }
        for ((entry, message) in messages.zip(decoded)) {
            assertEquals(entry.getValue("message"), CloudJson.encodeToJsonElement(CtlMessage.serializer(), message))
            val from = if (message is CtlMessage.Hello || message is CtlMessage.Token) "browser" else "sidecar"
            assertEquals(from, entry.text("from"), "$message's direction")
        }
        assertEquals(
            setOf(CtlMessage.Hello::class, CtlMessage.Ready::class, CtlMessage.Refused::class, CtlMessage.Token::class),
            decoded.map { it::class }.toSet(),
            "a fixture per ctl message",
        )
        assertTrue(decoded.contains(CtlMessage.Ready(unlinked = true, build = "1.4.0+3f2a9c1")), "an unlinked ready")
        for ((entry, message) in messages.zip(decoded)) {
            assertEquals(message, CtlMessage.decodeOrNull(entry.getValue("message").toString()))
        }
    }

    @Test
    fun `a ctl reader ignores a type it does not know and refuses a malformed known one`() {
        assertNull(CtlMessage.decodeOrNull("""{"type":"stats","rttMs":12}"""))
        assertFailsWith<SerializationException> { CtlMessage.decodeOrNull("""{"type":"hello"}""") }
        assertFailsWith<SerializationException> { CtlMessage.decodeOrNull("""{"token":"t"}""") }
        assertFailsWith<SerializationException> { CtlMessage.decodeOrNull("""[]""") }
    }

    @Test
    fun `every remote-frame message is one the contract names, over a channel it rides`() {
        val frame = fixtureJson("remote-frame/messages.json").jsonObject
        assertEquals(RemoteFrame.PROTOCOL, frame.getValue("protocol").jsonPrimitive.int)
        val messages = frame.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(RemoteFrame.MESSAGE_TYPES, messages.map { it.getValue("message").jsonObject.text("type") }.toSet())
        for (entry in messages) {
            val type = entry.getValue("message").jsonObject.text("type")
            val route = Triple(entry.text("from"), entry.text("to"), entry.text("over"))
            val transfers = entry["transfer"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertEquals(if (type == "port") listOf("port") else emptyList(), transfers, "$type transfers a port only as port")
            val ends = setOf(route.first, route.second)
            when (route.third) {
                "window" -> assertTrue(type in setOf("hello", "port") && ends == setOf("spa", "shell"), "$type over the window")
                "port" -> assertEquals(setOf("spa", "shell"), ends, "$type on the SPA's port")
                "worker" -> assertTrue(type in setOf("port.request", "port") && ends == setOf("spa", "worker"), "$type with the worker")
                else -> error("$type over ${route.third}")
            }
        }
        val hello = messages.first().getValue("message").jsonObject
        assertEquals("hello", hello.text("type"), "the SPA speaks first")
        assertEquals(RemoteFrame.PROTOCOL, hello.getValue("protocol").jsonPrimitive.int)
    }
}
