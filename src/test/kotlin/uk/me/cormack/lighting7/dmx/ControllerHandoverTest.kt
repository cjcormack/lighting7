package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.MutableParkSource
import uk.me.cormack.lighting7.testsupport.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One controller handing a universe to the next without a frame of the wrong thing in between:
 * [DmxController.freeze] pins what the outgoing one transmits while its show is torn down, and
 * [ArtNetController.awaitClosed] — which `Fixtures.register` waits on before building successors —
 * puts its last frame on the wire before the successor's first.
 */
class ControllerHandoverTest {

    private fun ByteArray.at(channel: Int): UByte = this[channel - 1].toUByte()

    @Test
    fun `a frozen controller transmits the frozen frame, park on top, whatever its buffer does`() = runBlocking {
        val parks = MutableParkSource().apply { park(0, 3, 222u) }
        val transport = RecordingTransport()
        val controller = ArtNetController(
            Universe(0, 0), initialValues = mapOf(1 to 100u), parkSource = parks, transport = transport,
        )
        try {
            controller.freeze(mapOf(1 to 100u, 2 to 50u))
            controller.setValue(1, 0u)
            controller.setValue(2, 0u)
            // Drain until the freeze is certainly in force, then every later frame must carry it.
            repeat(5) { withTimeout(2_000) { transport.frames.receive() } }
            repeat(5) {
                val frame = withTimeout(2_000) { transport.frames.receive() }.data
                assertEquals(100u.toUByte(), frame.at(1), "a teardown write never reaches the wire")
                assertEquals(50u.toUByte(), frame.at(2))
                assertEquals(222u.toUByte(), frame.at(3), "park is still overlaid")
            }
            controller.freeze(null)
            var live: ByteArray
            var n = 0
            do { live = withTimeout(2_000) { transport.frames.receive() }.data } while (live.at(1) != 0u.toUByte() && ++n < 40)
            assertEquals(0u.toUByte(), live.at(1), "unfrozen, the buffer goes out again")
        } finally {
            controller.close()
        }
    }

    @Test
    fun `awaitClosed returns once the last frame is out`() = runBlocking {
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), initialValues = mapOf(1 to 10u), transport = transport)
        controller.freeze(mapOf(1 to 77u))
        controller.close()
        assertTrue(controller.awaitClosed(), "the final frame went out in time")
        assertTrue(transport.stopped.isCompleted, "the transport was released before the wait ended")
        var last: ByteArray? = null
        while (true) last = transport.frames.tryReceive().getOrNull()?.data ?: break
        assertEquals(77u.toUByte(), checkNotNull(last).at(1), "the last frame is the frozen one")
    }

    @Test
    fun `a rebuilt universe never sees the old controller's frame after the new one's first`() = runBlocking {
        // One transport for both, so the recorded order is the wire's order.
        val transport = RecordingTransport()
        val fixtures = Fixtures()
        repeat(10) { round ->
            val outgoingLevel: UByte = 0u
            val incomingLevel: UByte = (round + 1).toUByte()
            fixtures.register {
                addController(ArtNetController(Universe(0, 0), initialValues = mapOf(1 to outgoingLevel), transport = transport))
            }
            // Let the outgoing one stream a little, so its cancel lands mid-loop.
            repeat(2) { withTimeout(2_000) { transport.frames.receive() } }
            fixtures.register {
                addController(ArtNetController(Universe(0, 0), initialValues = mapOf(1 to incomingLevel), transport = transport))
            }
            // Every frame from here: once the successor has spoken, the old level never comes back.
            var seenIncoming = false
            repeat(6) {
                val level = withTimeout(2_000) { transport.frames.receive() }.data.at(1)
                if (level == incomingLevel) seenIncoming = true
                else assertTrue(!seenIncoming, "round $round: the outgoing frame followed the successor's first")
            }
            assertTrue(seenIncoming, "round $round: the successor streams")
            fixtures.register { }
            @Suppress("ControlFlowWithEmptyBody") while (transport.frames.tryReceive().isSuccess) {}
        }
    }
}
