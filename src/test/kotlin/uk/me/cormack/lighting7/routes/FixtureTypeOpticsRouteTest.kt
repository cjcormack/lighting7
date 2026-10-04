package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The fixture optics plan's session 5 vocabulary on `GET /fixture-types`: a fixed lens's `fieldDeg`,
 * a stepped zoom's `zoomDeg`, a slider's proportional band (`activeMin`/`activeMax`) and the
 * `noColour` marker — each reaches the client the Stage view reads it from.
 */
class FixtureTypeOpticsRouteTest : RouteIntegrationTest() {

    @Test
    fun `the session's optics annotations are on the wire`() = testApplication {
        mountTestApp(state)
        val types = jsonClient().get("/api/rest/fixture-types").body<List<FixtureTypeDetails>>().associateBy { it.typeKey }

        assertEquals(10.0, types.getValue("fusion-100-spot-mkii-8ch").fieldDeg)
        assertEquals(11.0, types.getValue("scantastic-4-8ch").fieldDeg)
        val robe = types.getValue("robe-color-spot-575-mode-2")
        assertNull(robe.fieldDeg)

        val settings = robe.properties.filterIsInstance<SettingPropertyDescriptor>().associateBy { it.name }
        assertEquals(listOf(15.0, 18.0, 22.0, 15.0, 18.0, 22.0), settings.getValue("zoom").options.map { it.zoomDeg })
        assertEquals(true, settings.getValue("colour2").options.single { it.name == "SCROLL_CW" }.noColour)

        val iris = robe.properties.filterIsInstance<SliderPropertyDescriptor>().single { it.name == "iris" }
        assertEquals(1, iris.activeMin)
        assertEquals(179, iris.activeMax)
    }
}
