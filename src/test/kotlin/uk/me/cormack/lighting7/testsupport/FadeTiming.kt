package uk.me.cormack.lighting7.testsupport

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.ArtNetTransport
import uk.me.cormack.lighting7.dmx.Universe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A fade's Art-Net frames, timed, for the tests that hold the show's output to what it is with
 * nothing else running (NFR-SAFE-1, -2): `CloudOutageOutputTimingTest` and `LinkSidecarSafetyTest`.
 * Wall clock, so each test compares its runs with a baseline it takes itself, under generous margins
 * (`docs/testing-engineering.md` §"Tests that assert real elapsed time").
 */
object FadeTiming {
    const val FADE_MS = 3_000L
    private const val END_MARGIN_MS = 500.0
    private const val GAP_MARGIN_MS = 50.0
    private const val MAX_GAP_MARGIN_MS = 100.0
    private const val FRAME_MARGIN = 5

    /** One frame on the wire: when, and channel 1's value. */
    private data class Sent(val atNanos: Long, val level: Int)

    private class TimedTransport : ArtNetTransport {
        val frames = CopyOnWriteArrayList<Sent>()
        override fun start() {}
        override fun stop() {}
        override fun broadcastDmx(subnet: Int, universe: Int, dmxData: ByteArray) {
            frames += Sent(System.nanoTime(), dmxData[0].toInt() and 0xff)
        }
        override fun unicastDmx(address: String, subnet: Int, universe: Int, dmxData: ByteArray) =
            broadcastDmx(0, universe, dmxData)
    }

    /** One fade's measurements, from the write to the first frame at full. */
    data class Fade(val endMs: Double, val frames: Int, val p99GapMs: Double, val maxGapMs: Double, val monotonic: Boolean)

    /** Fade channel 1 from 0 to full over [FADE_MS], running [midway] halfway through. */
    fun fade(midway: () -> Unit = {}): Fade {
        val transport = TimedTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)
        try {
            runBlocking { withTimeout(2.seconds) { while (transport.frames.size < 4) delay(5) } }
            val start = System.nanoTime()
            controller.setValue(1, 255u, FADE_MS)
            Thread.sleep(FADE_MS / 2)
            midway()
            runBlocking {
                withTimeout(10.seconds) {
                    while (transport.frames.none { it.atNanos > start && it.level == 255 }) delay(5)
                }
            }
            val during = transport.frames.filter { it.atNanos >= start }
            val end = during.first { it.level == 255 }
            val window = during.filter { it.atNanos <= end.atNanos }
            val gaps = window.zipWithNext { a, b -> (b.atNanos - a.atNanos) / 1e6 }.sorted()
            return Fade(
                endMs = (end.atNanos - start) / 1e6,
                frames = window.size,
                p99GapMs = gaps[((gaps.size - 1) * 0.99).toInt()],
                maxGapMs = gaps.last(),
                monotonic = window.zipWithNext().all { (a, b) -> b.level >= a.level },
            )
        } finally {
            controller.close()
        }
    }

    fun assertLike(baseline: Fade, run: Fade, what: String) {
        assertTrue(run.monotonic, "$what: the fade went backwards")
        // The controller drops the frames a stall costs rather than bursting them, so a lost frame shows in the count.
        assertTrue(run.frames >= baseline.frames - FRAME_MARGIN, "$what: ${run.frames} frames, baseline ${baseline.frames}")
        assertTrue(run.maxGapMs <= baseline.maxGapMs + MAX_GAP_MARGIN_MS, "$what: longest gap ${run.maxGapMs} ms, baseline ${baseline.maxGapMs} ms")
        assertTrue(run.endMs <= baseline.endMs + END_MARGIN_MS, "$what: ended at ${run.endMs} ms, baseline ${baseline.endMs} ms")
        assertTrue(run.p99GapMs <= baseline.p99GapMs + GAP_MARGIN_MS, "$what: p99 gap ${run.p99GapMs} ms, baseline ${baseline.p99GapMs} ms")
    }
}
