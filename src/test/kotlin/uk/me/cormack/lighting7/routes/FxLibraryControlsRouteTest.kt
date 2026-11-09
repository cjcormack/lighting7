package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxControl
import uk.me.cormack.lighting7.fx.FxTypeInfo
import uk.me.cormack.lighting7.fx.ParameterCondition
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals

/** `GET /fx/library` serves Path's Size beside its parameters, which it leaves as they were (venue-rig-fixes plan D20). */
class FxLibraryControlsRouteTest : RouteIntegrationTest() {

    @Test
    fun `Path's parameters are unchanged and its controls hold size`() = testApplication {
        mountTestApp(state)
        val response = jsonClient().get("/api/rest/fx/library")
        assertEquals(HttpStatusCode.OK, response.status)
        val library = response.body<List<FxTypeInfo>>()
        val path = library.single { it.name == "Path" }
        assertEquals(
            listOf("shape", "panCentre", "tiltCentre", "panSize", "tiltSize", "startPan", "startTilt", "endPan", "endTilt", "curve"),
            path.parameters.map { it.name },
        )
        assertEquals(
            listOf(FxControl("size", "Size", listOf("panSize", "tiltSize"), ParameterCondition("shape", listOf("CIRCLE", "FIGURE_8", "RANDOM")))),
            path.controls,
        )
        assertEquals(emptyList(), library.single { it.name == "Wave" }.controls)
    }
}
