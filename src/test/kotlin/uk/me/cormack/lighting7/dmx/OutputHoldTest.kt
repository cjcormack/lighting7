package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.testsupport.Frame
import uk.me.cormack.lighting7.testsupport.MutableParkSource
import uk.me.cormack.lighting7.testsupport.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The show's hold on the wire (venue plan P5, session 6; `dmx/OutputHold.kt`): held, every channel goes
 * out at zero but a parked one, which goes out at its park — a hard-powered fixture on a parked dimmer
 * is not power-cycled by a show opening held — while the channel listeners, and so every view, still
 * hear what the show composed; reserved channels go out idle and the listeners hear that, since it is
 * what the wire carries.
 */
class OutputHoldTest {

    private suspend fun RecordingTransport.nextFrame(): ByteArray = withTimeout(2_000) { frames.receive() }.data

    /** Drain frames until one satisfies [ok]; the loop streams, so a change lands within a frame or two. */
    private suspend fun RecordingTransport.frameWhere(ok: (ByteArray) -> Boolean): ByteArray {
        repeat(40) { nextFrame().let { if (ok(it)) return it } }
        error("no frame matched within 40 frames")
    }

    private fun ByteArray.at(channel: Int): UByte = this[channel - 1].toUByte()

    @Test
    fun `held, every channel but a parked one goes out at zero while listeners hear the show`() = runBlocking {
        val parks = MutableParkSource().apply { park(universe = 0, channel = 5, value = 200u) }
        val hold = OutputHold().apply { setHeld(true) }
        val transport = RecordingTransport()
        val heard = Channel<Map<Int, UByte>>(Channel.UNLIMITED)
        val controller = ArtNetController(
            Universe(0, 0),
            initialValues = mapOf(1 to 77u, 9 to 255u),
            parkSource = parks,
            transport = transport,
            hold = hold,
        )
        controller.registerListener(object : ChannelChangeListener {
            override fun channelsChanged(changes: Map<Int, UByte>) {
                heard.trySend(changes)
            }
        })
        try {
            assertTrue(controller.held)
            val frame = transport.nextFrame()
            assertEquals(200u.toUByte(), frame.at(5), "a parked channel gets through the hold")
            assertTrue(frame.withIndex().all { (i, b) -> i == 4 || b == 0.toByte() }, "every other channel is zero")
            // Composition carries on underneath: the buffer, getValue (park included) and the listeners.
            controller.setValue(9, 128u)
            val held = transport.frameWhere { true }
            assertEquals(0u.toUByte(), held.at(9))
            assertEquals(200u.toUByte(), held.at(5))
            assertEquals(200u.toUByte(), controller.getValue(5), "getValue still reads through park")
            assertEquals(128u.toUByte(), controller.getValue(9))
            val change = withTimeout(2_000) {
                var seen: Map<Int, UByte>
                do { seen = heard.receive() } while (seen[9] != 128u.toUByte())
                seen
            }
            assertEquals(128u.toUByte(), change[9], "the listeners hear the composed value while held")

            // Lifted, the same buffer reaches the wire, park on top.
            hold.setHeld(false)
            assertFalse(controller.held)
            val live = transport.frameWhere { it.at(9) == 128u.toUByte() }
            assertEquals(200u.toUByte(), live.at(5))
            assertEquals(77u.toUByte(), live.at(1))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `a reserved channel goes out idle whatever the show writes, and a band only inside it`() = runBlocking {
        val hold = OutputHold()
        hold.setReserved(
            mapOf(
                0 to mapOf(
                    10 to OutputHold.Reserve.Fixed(0u),
                    12 to OutputHold.Reserve.Band(listOf(200u..255u), 5u),
                ),
            ),
        )
        val transport = RecordingTransport()
        val controller = ArtNetController(
            Universe(0, 0),
            initialValues = mapOf(10 to 255u, 11 to 255u, 12 to 230u),
            transport = transport,
            hold = hold,
        )
        try {
            val frame = transport.nextFrame()
            assertEquals(0u.toUByte(), frame.at(10), "a fixed reserve is always idle")
            assertEquals(255u.toUByte(), frame.at(11), "an unreserved channel is untouched")
            assertEquals(5u.toUByte(), frame.at(12), "a value in a band goes out at the band's idle")
            controller.setValue(12, 100u)
            val outside = transport.frameWhere { it.at(12) == 100u.toUByte() }
            assertEquals(0u.toUByte(), outside.at(10))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `the mock reports the wire and the composed value apart`() {
        val parks = MutableParkSource().apply { park(universe = 0, channel = 3, value = 180u) }
        val hold = OutputHold()
        val mock = MockDmxController(Universe(0, 0), parkSource = parks, hold = hold)
        mock.setValue(4, 90u)
        assertEquals(180u.toUByte(), mock.transmittedValue(3))
        hold.setHeld(true)
        assertTrue(mock.held)
        assertEquals(180u.toUByte(), mock.transmittedValue(3), "a park gets through the hold")
        assertEquals(0u.toUByte(), mock.transmittedValue(4))
        assertEquals(180u.toUByte(), mock.getEffectiveValue(3), "what the views draw is unchanged")
        assertEquals(90u.toUByte(), mock.getEffectiveValue(4))
        hold.setHeld(false)
        hold.setReserved(mapOf(0 to mapOf(4 to OutputHold.Reserve.Fixed(0u))))
        assertEquals(0u.toUByte(), mock.transmittedValue(4))
        assertEquals(mapOf(0 to mapOf(4 to OutputHold.Reserve.Fixed(0u))), hold.reservedChannels())
    }
}
