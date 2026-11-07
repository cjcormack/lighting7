package uk.me.cormack.lighting7.osc

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [OscPacket]: OSC 1.0's own examples, bundles, padding and every way a packet is refused (OSC plan D9). */
class OscPacketTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun single(bytes: ByteArray): OscMessage =
        assertIs<OscPacket.Item.Message>(OscPacket.parse(bytes).single()).message

    private fun bad(bytes: ByteArray): OscPacket.Item.Bad = assertIs<OscPacket.Item.Bad>(OscPacket.parse(bytes).single())

    /** The specification's first example: `/oscillator/4/frequency` with the float 440.0. */
    @Test
    fun `the specification's frequency example parses, and encodes back to the same bytes`() {
        val spec = "/oscillator/4/frequency".toByteArray() + bytes(0) + ",f".toByteArray() + bytes(0, 0) + bytes(0x43, 0xdc, 0x00, 0x00)
        assertEquals(OscMessage("/oscillator/4/frequency", listOf(OscArg.Float32(440f))), single(spec))
        assertContentEquals(spec, OscPacket.encode("/oscillator/4/frequency", OscArg.Float32(440f)))
    }

    /** The second: `/foo` with 1000, -1, "hello", 1.234 and 5.678. */
    @Test
    fun `the specification's mixed example parses every argument`() {
        val spec = "/foo".toByteArray() + bytes(0, 0, 0, 0) + ",iisff".toByteArray() + bytes(0, 0) +
            bytes(0, 0, 0x03, 0xe8) + bytes(0xff, 0xff, 0xff, 0xff) + "hello".toByteArray() + bytes(0, 0, 0) +
            bytes(0x3f, 0x9d, 0xf3, 0xb6) + bytes(0x40, 0xb5, 0xb2, 0x2d)
        val message = single(spec)
        assertEquals("/foo", message.address)
        assertEquals(
            listOf(OscArg.Int32(1000), OscArg.Int32(-1), OscArg.Str("hello"), OscArg.Float32(1.234f), OscArg.Float32(5.678f)),
            message.args,
        )
        assertContentEquals(spec, OscPacket.encode(message))
    }

    @Test
    fun `h, d, T, F and b are read, and T and F take no bytes`() {
        val message = OscMessage(
            "/x",
            listOf(OscArg.Int64(1L shl 40), OscArg.Float64(0.25), OscArg.Bool(true), OscArg.Bool(false), OscArg.Blob(bytes(1, 2, 3)), OscArg.Int32(7)),
        )
        assertEquals(message, single(OscPacket.encode(message)))
        assertEquals(listOf(1099511627776.0, 0.25, 1.0, 0.0, null, 7.0), message.args.map { it.number() })
    }

    @Test
    fun `a message with no type tags at all has no arguments`() {
        assertEquals(OscMessage("/lighting7/stack/act-1/go", emptyList()), single("/lighting7/stack/act-1/go".toByteArray() + bytes(0, 0, 0)))
    }

    @Test
    fun `strings are padded to four bytes, so an address of four characters takes eight`() {
        val encoded = OscPacket.encode("/abc")
        assertEquals(8 + 4, encoded.size, "`/abc` and its null, then `,` and three nulls")
        assertEquals("/abc", single(encoded).address)
    }

    @Test
    fun `a blob claiming nearly 2 GB is a bad message, never an allocation`() {
        for (size in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 2, 1_000_000)) {
            val packet = "/x".toByteArray() + bytes(0, 0) + ",b".toByteArray() + bytes(0, 0) + ByteBuffer.allocate(4).putInt(size).array()
            assertTrue("blob" in bad(packet).reason)
        }
    }

    @Test
    fun `an unknown tag drops its message with a reason`() {
        val packet = "/x".toByteArray() + bytes(0, 0) + ",m".toByteArray() + bytes(0, 0) + bytes(0, 0, 0, 0)
        val item = bad(packet)
        assertEquals("/x", item.address)
        assertTrue("'m'" in item.reason, item.reason)
    }

    @Test
    fun `truncated packets are bad messages, never exceptions`() {
        val whole = OscPacket.encode("/lighting7/stack/act-1/fx-amount", OscArg.Float32(0.5f))
        for (cut in 1 until whole.size) {
            val items = OscPacket.parse(whole.copyOf(cut))
            assertTrue(items.single() is OscPacket.Item.Bad || cut % 4 == 0, "cut at $cut: $items")
        }
        assertTrue(bad(whole.copyOf(whole.size - 1)).reason.contains("truncated"))
        assertTrue(bad("lighting7".toByteArray() + bytes(0, 0, 0)).reason.contains("/"))
        assertIs<OscPacket.Item.Bad>(OscPacket.parse(ByteArray(0)).single())
    }

    @Test
    fun `a bundle unpacks to its messages in order, nested bundles included, ignoring the timetag`() {
        val a = OscPacket.encode("/a", OscArg.Int32(1))
        val b = OscPacket.encode("/b")
        val c = OscPacket.encode("/c", OscArg.Str("x"))
        val packet = OscPacket.bundle(a, OscPacket.bundle(b, c))
        val later = packet.copyOf().also { ByteBuffer.wrap(it, 8, 8).putLong(Long.MAX_VALUE) }
        for (p in listOf(packet, later)) {
            assertEquals(listOf("/a", "/b", "/c"), OscPacket.parse(p).map { (it as OscPacket.Item.Message).message.address })
        }
    }

    @Test
    fun `a bad message in a bundle drops only that message`() {
        val broken = "/x".toByteArray() + bytes(0, 0) + ",q".toByteArray() + bytes(0, 0)
        val items = OscPacket.parse(OscPacket.bundle(OscPacket.encode("/a"), broken, OscPacket.encode("/b")))
        assertEquals(3, items.size)
        assertIs<OscPacket.Item.Message>(items[0])
        assertIs<OscPacket.Item.Bad>(items[1])
        assertIs<OscPacket.Item.Message>(items[2])
    }

    @Test
    fun `bundles nested past the depth cap drop the whole packet`() {
        fun nested(depth: Int): ByteArray = if (depth == 0) OscPacket.encode("/deep") else OscPacket.bundle(nested(depth - 1))
        assertEquals("/deep", (OscPacket.parse(nested(OscPacket.MAX_DEPTH)).single() as OscPacket.Item.Message).message.address)
        val refused = bad(nested(OscPacket.MAX_DEPTH + 1))
        assertTrue("deeper" in refused.reason, refused.reason)
    }

    @Test
    fun `more than 64 messages in a packet drops the whole packet`() {
        val message = OscPacket.encode("/m")
        assertEquals(OscPacket.MAX_MESSAGES, OscPacket.parse(OscPacket.bundle(*Array(OscPacket.MAX_MESSAGES) { message })).size)
        val refused = bad(OscPacket.bundle(*Array(OscPacket.MAX_MESSAGES + 1) { message }))
        assertTrue("64" in refused.reason, refused.reason)
    }

    @Test
    fun `a bundle element running past the packet drops the packet`() {
        val packet = OscPacket.bundle(OscPacket.encode("/a"))
        ByteBuffer.wrap(packet, 16, 4).putInt(400)
        assertIs<OscPacket.Item.Bad>(OscPacket.parse(packet).single())
    }
}
