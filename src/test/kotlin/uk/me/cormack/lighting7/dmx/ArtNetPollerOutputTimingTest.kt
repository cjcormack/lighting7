package uk.me.cormack.lighting7.dmx

import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.testsupport.FadeTiming.assertLike
import uk.me.cormack.lighting7.testsupport.FadeTiming.fadeThrough
import uk.me.cormack.lighting7.testsupport.FakeArtNetNode
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertTrue

/**
 * The poller reads the socket the show sends through (cloud-p3 plan D4) and never slows a frame: a
 * fade through the shared socket is the same with the poller polling a node as often as its loop
 * turns, every poll answered, as with no poller at all. Wall clock, through
 * [uk.me.cormack.lighting7.testsupport.FadeTiming].
 */
class ArtNetPollerOutputTimingTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun `a fade through the shared socket is unchanged with the poller reading it`() {
        ArtNetSocket(0).use { socket ->
            val baseline = fadeThrough(socket)
            FakeArtNetNode().use { node ->
                // Every reading of the clock is a poll interval later, so each turn of the loop polls.
                val clock = AtomicLong()
                ArtNetPoller(
                    socket = socket,
                    addresses = { listOf("127.0.0.1") },
                    destinationPort = node.port,
                    nowMs = { clock.addAndGet(ArtNetPoller.POLL_INTERVAL_MS) },
                    receiveTimeoutMs = 20,
                ).use { poller ->
                    poller.start()
                    val run = fadeThrough(socket)
                    assertTrue(node.polls.get() > 50, "the poller polled throughout: ${node.polls.get()}")
                    assertLike(baseline, run, "with the poller reading the socket")
                }
            }
        }
    }
}
