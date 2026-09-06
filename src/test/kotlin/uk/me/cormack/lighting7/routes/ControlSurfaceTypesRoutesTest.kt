package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/rest/control-surface-types` is the contract the Surfaces view draws its picture from:
 * the controls, the strips they group into, and where each one sits. Worth a route test of its
 * own because a profile change that silently drops `strips` or `layout` would leave the panel
 * rendering as a bare table with nothing failing.
 */
class ControlSurfaceTypesRoutesTest : RouteIntegrationTest() {

    @Test
    fun `the x-touch profile carries its strips and a complete layout`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val types = client.get("/api/rest/control-surface-types").body<List<ControlSurfaceTypeDto>>()
        val xtouch = types.single { it.typeKey == "x-touch-compact-standard" }

        assertEquals(9, xtouch.strips.size)
        val strip1 = xtouch.strips.single { it.id == "strip-1" }
        assertEquals("fader-1", strip1.fader)
        assertEquals("btn-25", strip1.select)
        assertEquals("enc-1", strip1.encoder)
        assertEquals("btn-1", strip1.flash)
        assertNull(xtouch.strips.single { it.id == "strip-master" }.encoder)

        val layout = assertNotNull(xtouch.layout)
        assertEquals(listOf("strips", "right", "master"), layout.regions.map { it.name })
        val placed = layout.regions.flatMap { it.cells }.map { it.controlId }
        assertEquals(xtouch.controls.map { it.controlId }.toSet(), placed.toSet())
        assertTrue(placed.size == placed.toSet().size, "no control is placed twice")
    }
}
