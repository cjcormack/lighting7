package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import uk.me.cormack.lighting7.cloud.protocol.MAX_FRAME_BYTES
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The control channel's two send queues (cloud-p1 plan D16), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelOutboxTest {

    @Test
    fun `a reply is kept while heartbeats drop behind a cloud that stopped reading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val box = ChannelOutbox(heartbeat = 100.seconds) { text ->
            gate.await()
            sent += text
        }
        val running = async { box.run() }
        box.offerHealth("health-0")
        runCurrent()
        repeat(40) { box.offerHealth("health-${it + 1}") }
        assertTrue(box.offerReliable("reply"))
        gate.complete(Unit)
        runCurrent()
        running.cancel()

        assertEquals("health-0", sent.first())
        assertEquals("reply", sent[1], "the reply goes ahead of the queued heartbeats")
        val healths = sent.drop(2)
        assertEquals(ChannelOutbox.HEALTH_CAPACITY, healths.size, "the oldest heartbeats were dropped")
        assertEquals("health-40", healths.last())
    }

    @Test
    fun `a frame just under the limit on a slow uplink is not cut off at three heartbeats`() = runTest {
        val frame = "x".repeat(MAX_FRAME_BYTES - 1)
        val written = mutableListOf<Int>()
        // 8 MiB at 160 KiB/s takes about 51 s, far past three 1 s heartbeats.
        val box = ChannelOutbox(heartbeat = 1.seconds) { text ->
            delay(text.length * 1000L / (160 * 1024))
            written += text.length
        }
        assertTrue(box.offerReliable(frame))
        val running = async { box.run() }
        testScheduler.advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(frame.length), written)
        assertFalse(running.isCompleted, "the connection was cut off")
        running.cancel()
    }

    @Test
    fun `a frame that stalls past its own time ends the connection`() = runTest {
        val box = ChannelOutbox(heartbeat = 1.seconds) { delay(Long.MAX_VALUE) }
        box.offerHealth("health")
        assertEquals(ChannelOutbox.Stop.STALLED, box.run())
        assertEquals(3.seconds, box.sendTimeout(10))
        assertEquals(64.seconds, box.sendTimeout(8 * 1024 * 1024))
    }

    @Test
    fun `a full reply queue refuses the one message and keeps the rest`() = runTest {
        val box = ChannelOutbox(heartbeat = 1.seconds, maxReliableFrames = 2, maxReliableBytes = 10) { }
        assertTrue(box.offerReliable("12345"))
        assertFalse(box.offerReliable("1234567"), "over the bytes")
        assertTrue(box.offerReliable("12345"))
        assertFalse(box.offerReliable("1"), "over the frames")
        assertFalse(ChannelOutbox(1.seconds) { }.offerReliable("x".repeat(MAX_FRAME_BYTES + 1)), "over one frame")
    }

    @Test
    fun `utf8 sizes count without encoding`() {
        for (text in listOf("", "abc", "é", "€", "🎶")) {
            assertEquals(text.toByteArray(Charsets.UTF_8).size, ChannelOutbox.utf8Size(text), text)
        }
        // A lone surrogate is sent as one '?' and counted as three: over, never under.
        assertEquals(4, ChannelOutbox.utf8Size("a\ud83c"))
    }
}
