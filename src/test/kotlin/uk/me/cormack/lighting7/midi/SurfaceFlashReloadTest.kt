package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A flash held across a reload lets go on the show that is running when it is released. */
class SurfaceFlashReloadTest : RouteIntegrationTest() {

    @Test
    fun `a flash released after a reload clears it from the new show's output`() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val release = assertNotNull(DefaultSurfaceActions(state).flashPress(Subject.Fixture("hex-1"), "dimmer", 100))
        assertEquals(255u.toUByte(), dimmerOut(), "the flash is on stage")

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }
        release()

        assertNull(state.show.programmerStore.valueFor(ProgrammerOwner.FLASH, "hex-1", "dimmer"))
        assertEquals(0u.toUByte(), dimmerOut(), "the new show's output lets it go")
    }

    // The hex's dimmer is its first channel.
    private fun dimmerOut(): UByte? = state.show.fixtures.controllerOrNull(Universe(0, 0))?.getValue(1)
}
