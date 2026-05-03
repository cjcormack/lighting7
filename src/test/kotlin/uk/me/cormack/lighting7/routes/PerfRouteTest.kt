package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.perf.CueEditHistogramSnapshot
import uk.me.cormack.lighting7.perf.MidiLatencyStage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The integration harness seeds only MOCK controllers, so no ArtNet universes appear in the response.
class PerfRouteTest : RouteIntegrationTest() {

    @Test
    fun `GET artnet-rates returns 30s window with no universes for mock-only show`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.get("/api/rest/perf/artnet-rates")

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<ArtNetRatesResponse>()
        assertEquals(30, body.windowSeconds)
        assertTrue(body.universes.isEmpty(), "no ArtNet controllers seeded in mock-only test setup")
    }

    @Test
    fun `GET cueedit-histogram returns empty snapshot before any session`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.get("/api/rest/perf/cueedit-histogram")

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<CueEditHistogramSnapshot>()
        assertFalse(body.sessionActive)
        assertEquals(0, body.live.count)
        assertNull(body.lastSessionEnded)
    }

    @Test
    fun `GET cueedit-histogram surfaces last-session snapshot after onEndEdit`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val tracker = state.cueEditLatencyTracker
        tracker.onBeginEdit()
        repeat(3) { tracker.measure { /* no-op */ } }
        tracker.onEndEdit()

        val resp = client.get("/api/rest/perf/cueedit-histogram")

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<CueEditHistogramSnapshot>()
        assertFalse(body.sessionActive)
        val last = assertNotNull(body.lastSessionEnded)
        assertEquals(3, last.count)
    }

    @Test
    fun `GET midi-latency returns zeroed histograms for fresh state`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.get("/api/rest/perf/midi-latency")

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<MidiLatencyResponse>()
        assertEquals(30, body.windowSeconds)
        assertEquals(MidiLatencyStage.entries.size, body.histograms.buckets.size)
        assertTrue(body.histograms.buckets.values.all { it.count == 0L })
        // Don't assert ports.isEmpty() — on Windows, JvmMidiAccess enumerates the OS's built-in
        // synth + sequencer, which are real ports the registry auto-opens. Just check that no
        // traffic has been recorded against any of them yet.
        assertTrue(
            body.ports.all { it.inboundCcTotal == 0L && it.outboundCcTotal == 0L },
            "fresh state should record zero CC traffic on every port",
        )
    }

    @Test
    fun `GET midi-latency surfaces recorded buckets`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        state.midiLatencyTracker.measure(MidiLatencyStage.INGRESS_CONTINUOUS) { /* no-op */ }
        state.midiLatencyTracker.record(MidiLatencyStage.EGRESS_MOTOR, 12_345)

        val resp = client.get("/api/rest/perf/midi-latency")

        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<MidiLatencyResponse>()
        assertEquals(1, body.histograms.buckets[MidiLatencyStage.INGRESS_CONTINUOUS.wireName]?.count)
        assertEquals(1, body.histograms.buckets[MidiLatencyStage.EGRESS_MOTOR.wireName]?.count)
    }

    @Test
    fun `POST midi-latency reset zeroes recorded buckets`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        state.midiLatencyTracker.record(MidiLatencyStage.INGRESS_CONTINUOUS, 1_000)
        state.midiLatencyTracker.record(MidiLatencyStage.INGRESS_CONTINUOUS, 2_000)

        val resp = client.post("/api/rest/perf/midi-latency/reset")
        assertEquals(HttpStatusCode.NoContent, resp.status)

        val after = client.get("/api/rest/perf/midi-latency").body<MidiLatencyResponse>()
        assertEquals(0, after.histograms.buckets[MidiLatencyStage.INGRESS_CONTINUOUS.wireName]?.count)
    }
}
