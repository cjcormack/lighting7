package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import uk.me.cormack.lighting7.testsupport.RecordingTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Art-Net output is a **continuous stream**: every universe transmits one frame per
 * [ArtNetController.refreshIntervalMs], whether or not any channel changed.
 *
 * It used to be change-driven — a 25 ms throttle, then a block until something wrote — so
 * an idle universe sent nothing at all. Art-Net is UDP with no retransmission, so a single
 * dropped datagram left the node holding a stale value until the next time that channel
 * happened to change. Streaming repairs a lost frame on the next tick.
 *
 * Two properties here are load-bearing beyond the feature itself:
 *
 *  - **A closed controller stops transmitting.** Controllers are rebuilt on every patch
 *    edit, and one left running keeps broadcasting its stale buffer to the universe its
 *    replacement just took over — two transmitters fighting, with the node taking whichever
 *    datagram landed last.
 *  - **The configured interval is honoured**, since it is now the actual packet rate rather
 *    than a ceiling that idle traffic rarely reached.
 */
class ArtNetStreamingTest {

    // ─── Interval bounds ─────────────────────────────────────────────────────

    @Test
    fun `default interval is 25ms`() {
        val controller = ArtNetController(Universe(0, 0), transport = RecordingTransport())
        try {
            assertEquals(ArtNetController.DEFAULT_REFRESH_INTERVAL_MS, controller.refreshIntervalMs)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `constructor clamps the interval into range`() {
        // The value arrives from a machine-local override row a user can hand-edit, so an
        // out-of-range number must not reach the loop: a `1` would pin a core at 1000
        // packets/sec on this universe.
        fun intervalFor(requested: Int): Int {
            val controller = ArtNetController(
                Universe(0, 0),
                refreshIntervalMs = requested,
                transport = RecordingTransport(),
            )
            return try {
                controller.refreshIntervalMs
            } finally {
                controller.close()
            }
        }

        assertEquals(ArtNetController.MIN_REFRESH_INTERVAL_MS, intervalFor(5))
        assertEquals(ArtNetController.MAX_REFRESH_INTERVAL_MS, intervalFor(99_999))
        assertEquals(44, intervalFor(44), "an in-range value must pass through untouched")
    }

    @Test
    fun `setter clamps the interval into range`() {
        val controller = ArtNetController(Universe(0, 0), transport = RecordingTransport())
        try {
            controller.refreshIntervalMs = 1
            assertEquals(ArtNetController.MIN_REFRESH_INTERVAL_MS, controller.refreshIntervalMs)

            controller.refreshIntervalMs = 44
            assertEquals(44, controller.refreshIntervalMs)
        } finally {
            controller.close()
        }
    }

    // ─── Streaming behaviour ─────────────────────────────────────────────────

    @Test
    fun `an idle universe keeps transmitting`() = runBlocking {
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)

        try {
            // Never write a value. Under the old change-driven loop this hung forever after
            // the single bootstrap frame — that is exactly the regression being pinned.
            withTimeout(5_000) {
                repeat(4) { transport.frames.receive() }
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `every frame carries the full current buffer, not just changes`() = runBlocking {
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)

        try {
            controller.restoreState(mapOf(10 to 77u))

            // Advance to the first frame that shows the restored value, then assert the
            // next two carry it too. A delta-only transmitter would emit it once.
            withTimeout(5_000) {
                var frame = transport.frames.receive()
                while (frame.data[9] != 77.toByte()) {
                    frame = transport.frames.receive()
                }
                repeat(2) {
                    assertEquals(
                        77.toByte(), transport.frames.receive().data[9],
                        "channel 10 must be re-sent on every frame — a dropped datagram is " +
                            "only repaired because unchanged values keep going out",
                    )
                }
            }
        } finally {
            controller.close()
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Test
    fun `close releases the transport`() = runBlocking {
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)

        withTimeout(2_000) { transport.frames.receive() }
        controller.close()

        withTimeout(2_000) { transport.stopped.await() }
    }

    @Test
    fun `close flushes a final frame carrying the last written values`() = runBlocking {
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)

        withTimeout(2_000) { transport.frames.receive() }

        // Stand in for ProjectManager.shutdownShow, which blacks out every channel and then
        // immediately tears the controllers down. Without the flush on close, the cancel
        // races the next tick and the blackout can never reach the wire.
        val blackout: Map<Int, UByte> = (1..512).associateWith { 0u.toUByte() }
        controller.restoreState(blackout + mapOf(3 to 99u.toUByte()))
        controller.close()
        withTimeout(2_000) { transport.stopped.await() }

        val frames = generateSequence { transport.frames.tryReceive().getOrNull() }.toList()
        assertTrue(
            frames.last().data[2] == 99.toByte(),
            "the last frame before teardown must carry the final written values",
        )
    }

    @Test
    fun `a closed controller stops transmitting`() = runBlocking {
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)

        withTimeout(2_000) { transport.frames.receive() }
        controller.close()
        // Awaiting the stop establishes happens-before against teardown, so anything still
        // arriving after the drain below is a live loop rather than a scheduling artefact.
        withTimeout(2_000) { transport.stopped.await() }
        while (transport.frames.tryReceive().isSuccess) { /* drain frames already in flight */ }

        assertNull(
            withTimeoutOrNull(500) { transport.frames.receive() },
            "a closed controller must not keep broadcasting — an orphan left running fights " +
                "the replacement controller for the same universe",
        )
    }

    @Test
    fun `a write to a closed controller reports not-applied instead of hanging`() = runBlocking {
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)

        withTimeout(2_000) { transport.frames.receive() }
        controller.close()

        // `close()` shuts the 512 channel-changer coroutines down by closing their channels
        // — otherwise cancelling only the transmit loop leaves them suspended forever, one
        // set per controller rebuild. The observable half of that is here: a write racing
        // the close must come back false rather than strand its caller on the ack it will
        // never receive. Reachable in normal operation, because a patch edit rebuilds
        // controllers while the FX engine is still ticking.
        withTimeout(2_000) {
            controller.setValuesSuspend(listOf(1 to ChannelChange(255u, 0L)))
        }
        Unit
    }

    // ─── Cadence ─────────────────────────────────────────────────────────────

    @Test
    fun `the configured interval governs packet cadence`() = runBlocking {
        val transport = RecordingTransport()
        // 100 ms is 4x the default, so a regression to a hardcoded 25 ms is unmissable.
        val controller = ArtNetController(
            Universe(0, 0),
            refreshIntervalMs = 100,
            transport = transport,
        )

        try {
            // Discard the bootstrap frame plus one warm-up (class loading, JIT, first send).
            withTimeout(5_000) { repeat(2) { transport.frames.receive() } }

            val startNanos = System.nanoTime()
            withTimeout(15_000) { repeat(20) { transport.frames.receive() } }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            // The lower bound is the real assertion: no scheduler delivers frames *early*,
            // so 20 frames cannot take 1600 ms if the loop ignored the configured interval
            // (at 25 ms it would finish in ~500 ms). The upper bound is deliberately loose —
            // a loaded CI box can only ever be slow.
            assertTrue(
                elapsedMs >= 1_600,
                "20 frames at 100ms took only ${elapsedMs}ms — configured interval ignored?",
            )
            assertTrue(
                elapsedMs <= 8_000,
                "20 frames at 100ms took ${elapsedMs}ms — transmission loop stalled?",
            )
        } finally {
            controller.close()
        }
    }
}
