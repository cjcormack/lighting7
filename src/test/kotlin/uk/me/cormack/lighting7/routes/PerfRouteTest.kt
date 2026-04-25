package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.perf.CueEditHistogramSnapshot
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
}
