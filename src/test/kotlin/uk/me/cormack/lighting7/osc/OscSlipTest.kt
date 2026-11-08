package uk.me.cormack.lighting7.osc

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** OSC 1.1's SLIP framing (RFC 1055, double-ended): escapes, frames split across reads, oversize. */
class OscSlipTest {
    private fun decode(maxFrame: Int, vararg chunks: ByteArray): Pair<List<ByteArray>, Int> {
        val frames = ArrayList<ByteArray>()
        var oversize = 0
        val decoder = OscSlip.Decoder(maxFrame)
        for (chunk in chunks) decoder.feed(chunk, chunk.size, { frames += it }) { oversize++ }
        return frames to oversize
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `an END and an ESC inside a packet are escaped, and decode back`() {
        val packet = bytes(1, 0xC0, 2, 0xDB, 3)
        val framed = OscSlip.encode(packet)
        assertContentEquals(bytes(0xC0, 1, 0xDB, 0xDC, 2, 0xDB, 0xDD, 3, 0xC0), framed)
        val (frames, oversize) = decode(64, framed)
        assertContentEquals(packet, frames.single())
        assertEquals(0, oversize)
    }

    @Test
    fun `a frame split across reads, an escape split included, is joined`() {
        val message = OscPacket.encode("/lighting7/stack/act-1/go")
        val framed = OscSlip.encode(message + bytes(0xC0))
        val (frames) = decode(1024, *framed.toList().chunked(3).map { it.toByteArray() }.toTypedArray())
        assertContentEquals(message + bytes(0xC0), frames.single())
    }

    @Test
    fun `back-to-back frames are each their own, and the empty frame between them is skipped`() {
        val a = OscSlip.encode(bytes(1, 2, 3, 4))
        val b = OscSlip.encode(bytes(5, 6, 7, 8))
        val (frames) = decode(64, a + b)
        assertEquals(listOf(listOf<Byte>(1, 2, 3, 4), listOf<Byte>(5, 6, 7, 8)), frames.map { it.toList() })
    }

    @Test
    fun `a frame longer than the limit is dropped whole, and the next one still reads`() {
        val (frames, oversize) = decode(4, OscSlip.encode(bytes(1, 2, 3, 4, 5)) + OscSlip.encode(bytes(9, 9, 9, 9)))
        assertEquals(1, oversize)
        assertEquals(listOf(listOf<Byte>(9, 9, 9, 9)), frames.map { it.toList() })
    }

    @Test
    fun `an ESC before any other byte keeps that byte`() {
        val (frames) = decode(64, bytes(0xC0, 0xDB, 7, 0xC0))
        assertContentEquals(bytes(7), frames.single())
    }
}
