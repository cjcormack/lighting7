package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.ArtNetTransport
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The cloud is never on the show's path (NFR-SAFE-1, cloud-p0 plan session 5): a fade's output is
 * the same with the cloud connected, stopping reading, dropping the connection or refusing every
 * reconnect as with no connector at all. Wall clock, so it compares each run with a baseline it
 * takes itself, under generous margins (`docs/testing-engineering.md` §"Tests that assert real
 * elapsed time").
 */
class CloudOutageOutputTimingTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var state: State
    private lateinit var fake: FakeCloud
    private var service: CloudService? = null

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
    private data class Fade(val endMs: Double, val frames: Int, val p99GapMs: Double, val maxGapMs: Double, val monotonic: Boolean)

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        fake = FakeCloud().apply { heartbeatS = 1 }
    }

    @After
    fun tearDown() {
        service?.let { runCatching { it.close() } }
        runCatching { fake.close() }
        runCatching { state.shutdown() }
    }

    /** Fade channel 1 from 0 to full over [FADE_MS], running [midway] halfway through. */
    private fun fade(midway: () -> Unit = {}): Fade {
        val transport = TimedTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)
        try {
            runBlocking { withTimeout(2.seconds) { while (transport.frames.size < 4) kotlinx.coroutines.delay(5) } }
            val start = System.nanoTime()
            controller.setValue(1, 255u, FADE_MS)
            Thread.sleep(FADE_MS / 2)
            midway()
            runBlocking {
                withTimeout(10.seconds) {
                    while (transport.frames.none { it.atNanos > start && it.level == 255 }) kotlinx.coroutines.delay(5)
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

    private fun await(what: String, predicate: (CloudStateDto) -> Boolean) = runBlocking {
        val s = service!!
        try {
            withTimeout(15.seconds) { s.state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state is ${s.state.value}", e)
        }
    }

    private fun assertLike(baseline: Fade, run: Fade, what: String) {
        assertTrue(run.monotonic, "$what: the fade went backwards")
        // The controller drops the frames a stall costs rather than bursting them, so a lost frame shows in the count.
        assertTrue(run.frames >= baseline.frames - FRAME_MARGIN, "$what: ${run.frames} frames, baseline ${baseline.frames}")
        assertTrue(run.maxGapMs <= baseline.maxGapMs + MAX_GAP_MARGIN_MS, "$what: longest gap ${run.maxGapMs} ms, baseline ${baseline.maxGapMs} ms")
        assertTrue(run.endMs <= baseline.endMs + END_MARGIN_MS, "$what: ended at ${run.endMs} ms, baseline ${baseline.endMs} ms")
        assertTrue(run.p99GapMs <= baseline.p99GapMs + GAP_MARGIN_MS, "$what: p99 gap ${run.p99GapMs} ms, baseline ${baseline.p99GapMs} ms")
    }

    @Test
    fun `a fade runs as it does with no connector while the cloud fails`() {
        val baseline = fade()

        val adminId = seedUser(state, "admin").userId
        val store = InMemoryCredentialStore()
        val s = CloudService(
            database = state.database,
            config = CloudConfig(fake.baseUrl, true),
            credentialStore = { store },
            apiFactory = { CloudApi(it, Duration.ofSeconds(5)) },
        ).also { service = it }
        s.pollDelay = { 20.milliseconds }
        s.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { s.startAdoption(adminId) }
        await("online") { it.connection == CloudConnectionStatus.ONLINE }

        assertLike(baseline, fade(), "connected")

        assertLike(baseline, fade { fake.stopReading = true }, "the cloud stopped reading")
        fake.releaseChannel()

        assertLike(baseline, fade { fake.dropChannel() }, "the connection dropped")
        await("online again") { it.connection == CloudConnectionStatus.ONLINE }

        val attempts = fake.channelAttempts.get()
        assertLike(baseline, fade { fake.refuseChannel = true; fake.dropChannel() }, "every reconnect refused")
        await("a refused reconnect") { it.lastError?.code == CloudErrorCodes.CONNECT_FAILED }
        assertTrue(fake.channelAttempts.get() > attempts, "the desk tried to reconnect")
    }

    companion object {
        private const val FADE_MS = 3_000L
        private const val END_MARGIN_MS = 500.0
        private const val GAP_MARGIN_MS = 50.0
        private const val MAX_GAP_MARGIN_MS = 100.0
        private const val FRAME_MARGIN = 5
    }
}
