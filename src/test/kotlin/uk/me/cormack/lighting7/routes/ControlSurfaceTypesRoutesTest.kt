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
import uk.me.cormack.lighting7.midi.SlotRole

/**
 * `GET /api/rest/control-surface-types` is the contract the Surfaces view draws its picture from:
 * the controls, the strips they group into, and where each one sits. Worth a route test of its
 * own because a profile change that silently drops `strips` or `layout` would leave the panel
 * rendering as a bare table with nothing failing.
 */
class ControlSurfaceTypesRoutesTest : RouteIntegrationTest() {

    @Test
    fun `the x-touch profile carries its job regions, the kinds each fits, and a complete layout`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val types = client.get("/api/rest/control-surface-types").body<List<ControlSurfaceTypeDto>>()
        val xtouch = types.single { it.typeKey == "x-touch-compact-standard" }

        assertEquals(listOf("strips", "encoders", "row-2", "row-3", "keys", "master"), xtouch.jobRegions.map { it.id })
        val strips = xtouch.jobRegions.single { it.id == "strips" }
        assertEquals("fader-1", strips.slots.first().roles[SlotRole.FADER])
        assertEquals("enc-1", strips.slots.first().roles[SlotRole.PUSH])
        assertEquals(listOf("groupStrips", "fixtureStrips", "headStrips", "stackStrips", "masterStrips"), strips.kinds)
        assertEquals(listOf("groupStrips", "fixtureStrips", "headStrips", "stackStrips", "masterStrips", "grandMaster"), xtouch.jobRegions.single { it.id == "master" }.kinds)
        assertEquals(listOf("wheels", "pagedWheels"), xtouch.jobRegions.single { it.id == "encoders" }.kinds)
        assertNull(xtouch.jobRegions.single { it.id == "master" }.slots.single().roles[SlotRole.ENCODER])

        val layout = assertNotNull(xtouch.layout)
        assertEquals(listOf("strips", "right", "master"), layout.regions.map { it.name })
        val placed = layout.regions.flatMap { it.cells }.map { it.controlId }
        assertEquals(xtouch.controls.map { it.controlId }.toSet(), placed.toSet())
        assertTrue(placed.size == placed.toSet().size, "no control is placed twice")
    }
}
