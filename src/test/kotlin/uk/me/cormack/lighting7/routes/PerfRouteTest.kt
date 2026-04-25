package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
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
}
