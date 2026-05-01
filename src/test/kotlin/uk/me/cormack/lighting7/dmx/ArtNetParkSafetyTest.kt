package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Safety regressions for the "park is the highest-priority output layer" invariant on
 * [ArtNetController]. Operators rely on park to lock hard-powered fixtures (pyro
 * igniters, lasers, tungsten on dimmers) at a known output, so every code path that
 * could leak a non-parked value to the wire — or report a non-parked value to a
 * caller — is a safety hazard, not a stylistic one.
 *
 * Properties asserted here:
 *
 *  1. **Bootstrap**: the very first frame after construction overlays
 *     `parkSource.universeView(...)`. No frame without the park overlay can precede it.
 *     (Boot chain: [uk.me.cormack.lighting7.show.Show.start] loads park before
 *     constructing controllers, and [ArtNetController.runTransmissionChannel] calls
 *     `sendCurrentValues()` first; that's the only call site of
 *     `transport.broadcast/unicastDmx`.)
 *
 *  2. **`getValue()` reads through park**: callers reading channel state see the
 *     parked value, not the underlying `currentValues` buffer.
 *
 *  3. **Transmit modifiers cannot override park**: a [TransmitModifier] (Blackout,
 *     Grand Master, etc.) applied at transmit time leaves parked channels at their
 *     literal parked value while still transforming non-parked channels.
 */
class ArtNetParkSafetyTest {

    private class MutableParkSource : ParkSource {
        private val parkedByUniverse = ConcurrentHashMap<Int, ConcurrentHashMap<Int, UByte>>()

        fun park(universe: Int, channel: Int, value: UByte) {
            parkedByUniverse.getOrPut(universe) { ConcurrentHashMap() }[channel] = value
        }

        override fun getParkedValue(universe: Int, channel: Int): UByte? =
            parkedByUniverse[universe]?.get(channel)

        override fun isParked(universe: Int, channel: Int): Boolean =
            parkedByUniverse[universe]?.containsKey(channel) == true

        override fun universeView(universe: Int): Map<Int, UByte>? = parkedByUniverse[universe]
    }

    private sealed class Frame {
        abstract val data: ByteArray
        abstract val threadName: String

        data class Broadcast(
            val subnet: Int,
            val universe: Int,
            override val data: ByteArray,
            override val threadName: String,
        ) : Frame()

        data class Unicast(
            val address: String,
            val subnet: Int,
            val universe: Int,
            override val data: ByteArray,
            override val threadName: String,
        ) : Frame()
    }

    /**
     * Test transport that records every frame in send order. `Channel.UNLIMITED` is FIFO
     * and never blocks senders, so the test's `frames.receive()` returns frames in the
     * exact order the controller emitted them — that's how we assert "no earlier frame
     * was recorded" without polling.
     */
    private class RecordingTransport : ArtNetTransport {
        val frames = Channel<Frame>(Channel.UNLIMITED)

        override fun start() {}
        override fun stop() {}

        override fun broadcastDmx(subnet: Int, universe: Int, dmxData: ByteArray) {
            frames.trySend(
                Frame.Broadcast(subnet, universe, dmxData.copyOf(), Thread.currentThread().name),
            )
        }

        override fun unicastDmx(address: String, subnet: Int, universe: Int, dmxData: ByteArray) {
            frames.trySend(
                Frame.Unicast(address, subnet, universe, dmxData.copyOf(), Thread.currentThread().name),
            )
        }
    }

    private fun assertOnlyTheseChannelsSet(
        data: ByteArray,
        expected: Map<Int, UByte>,
    ) {
        for (i in data.indices) {
            val channelNo = i + 1
            val expectedByte = expected[channelNo]?.toByte() ?: 0.toByte()
            assertEquals(
                expectedByte, data[i],
                "channel $channelNo (index $i) must be ${expected[channelNo] ?: 0u} on the bootstrap frame",
            )
        }
    }

    // ─── 1. Bootstrap: first frame carries park ──────────────────────────────

    @Test
    fun `first broadcast frame contains parked values for parked channels`() = runBlocking {
        val parkSource = MutableParkSource()
        parkSource.park(universe = 0, channel = 5, value = 200u)
        parkSource.park(universe = 0, channel = 17, value = 50u)

        val transport = RecordingTransport()
        val universe = Universe(subnet = 0, universe = 0)

        val controller = ArtNetController(
            universe = universe,
            address = null,
            parkSource = parkSource,
            transport = transport,
        )

        try {
            val first = withTimeout(2_000) { transport.frames.receive() }
            assertTrue(
                first is Frame.Broadcast,
                "broadcast path expected when address is null, got $first",
            )
            assertEquals(0, first.subnet)
            assertEquals(0, first.universe)
            assertOnlyTheseChannelsSet(first.data, mapOf(5 to 200u, 17 to 50u))
            assertTrue(
                first.threadName.startsWith("ArtNetThread-"),
                "first frame must be transmitted on the dedicated ArtNet thread, was '${first.threadName}'",
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun `first unicast frame contains parked values for parked channels`() = runBlocking {
        val parkSource = MutableParkSource()
        parkSource.park(universe = 1, channel = 1, value = 255u)
        parkSource.park(universe = 1, channel = 256, value = 128u)
        // Park entry on a different universe must NOT bleed onto this controller's frame.
        parkSource.park(universe = 0, channel = 5, value = 99u)

        val transport = RecordingTransport()
        val universe = Universe(subnet = 0, universe = 1)

        val controller = ArtNetController(
            universe = universe,
            address = "127.0.0.1",
            parkSource = parkSource,
            transport = transport,
        )

        try {
            val first = withTimeout(2_000) { transport.frames.receive() }
            assertTrue(
                first is Frame.Unicast,
                "unicast path expected when address is set, got $first",
            )
            assertEquals("127.0.0.1", first.address)
            assertEquals(0, first.subnet)
            assertEquals(1, first.universe)
            assertOnlyTheseChannelsSet(first.data, mapOf(1 to 255u, 256 to 128u))
            assertTrue(
                first.threadName.startsWith("ArtNetThread-"),
                "first frame must be transmitted on the dedicated ArtNet thread, was '${first.threadName}'",
            )
        } finally {
            controller.close()
        }
    }

    // ─── 2. getValue() reads through park ────────────────────────────────────

    @Test
    fun `getValue returns parked value over the underlying channel buffer`() {
        val parkSource = MutableParkSource()
        parkSource.park(universe = 0, channel = 5, value = 200u)

        val controller = ArtNetController(
            universe = Universe(0, 0),
            address = "127.0.0.1",
            parkSource = parkSource,
            transport = RecordingTransport(),
        )

        try {
            // Plant a different value into the underlying channel buffer. `restoreState`
            // bypasses the channel-changer machinery and writes `currentValues` directly,
            // simulating a value left over from a fixture-reload snapshot.
            controller.restoreState(mapOf(5 to 50u))

            assertEquals(
                200u.toUByte(), controller.getValue(5),
                "getValue must return the parked value, not currentValues[5] (which is 50)",
            )
            // Sanity: an unparked channel still reports its buffer value, so park is the
            // *only* override path — getValue isn't ignoring currentValues entirely.
            controller.restoreState(mapOf(7 to 99u))
            assertEquals(99u.toUByte(), controller.getValue(7))
        } finally {
            controller.close()
        }
    }

    // ─── 3. TransmitModifier cannot override park ────────────────────────────

    @Test
    fun `transmit modifier cannot override parked channel value`() = runBlocking {
        val parkSource = MutableParkSource()
        // Channel 5 is parked at 200 — a "blackout" modifier must NOT zero it.
        parkSource.park(universe = 0, channel = 5, value = 200u)

        val transport = RecordingTransport()
        val controller = ArtNetController(
            universe = Universe(0, 0),
            address = null,
            parkSource = parkSource,
            transport = transport,
        )

        try {
            // Plant a non-zero value on a non-parked channel so we can observe the
            // modifier actually firing on channels park doesn't cover.
            controller.restoreState(mapOf(6 to 100u))

            // Drain the bootstrap frame. Park is in effect from frame 1, but the modifier
            // hasn't been registered yet, so non-parked channels show their raw values.
            val bootstrap = withTimeout(2_000) { transport.frames.receive() }
            assertOnlyTheseChannelsSet(bootstrap.data, mapOf(5 to 200u, 6 to 100u))

            // Blackout-style modifier: zero everything. Park must still win on ch5.
            val blackout = TransmitModifier { _, _, _ -> 0u }
            controller.addTransmitModifier(blackout)
            controller.requestTransmit()

            val afterModifier = withTimeout(2_000) { transport.frames.receive() }
            assertEquals(
                200.toByte(), afterModifier.data[4],
                "channel 5 is parked at 200 — a transmit modifier MUST NOT zero a parked channel",
            )
            assertEquals(
                0.toByte(), afterModifier.data[5],
                "channel 6 is not parked — blackout modifier must zero it (sanity: modifier is firing)",
            )
        } finally {
            controller.close()
        }
    }
}
