package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FU-FX-ELEMENT-BUNDLED-COLOUR`: a colour output's white / amber / UV half reaches an **element's**
 * bundled emitter. The four `ColourTarget` helpers were gated on `fixture is Fixture`, and a
 * `FixtureElement` is not one, so a pixel's white was computed and then dropped on every write,
 * reset, programmer compose and park check.
 *
 * Extends the route base only for the park case, which needs a database-backed [ParkManager].
 */
class FxTargetBundledColourTest : RouteIntegrationTest() {

    private val universe = Universe(0, 0)
    private val cellKey = "bar-1.pixel-2"

    /** Channels of pixel 2 on a bar patched at 1: red 9, green 10, blue 11, white 12. */
    private val whiteChannel = 12

    private class Rig(val fixtures: Fixtures, val controller: MockDmxController)

    private fun rig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar-1", "Bar 1", 1))
        }
        return Rig(fixtures, controller)
    }

    private fun amber(white: UByte) = FxOutput.Colour(ExtendedColour(Color(255, 157, 74), white, 0u, 0u))

    @Test
    fun `a colour write lands its white component on the cell's white emitter`() {
        val rig = rig()
        val tx = ControllerTransaction(rig.fixtures.controllers)
        val pixel = rig.fixtures.withTransaction(tx).untypedGroupableFixture(cellKey)

        ColourTarget(cellKey).applyValueToFixture(pixel, amber(74u), BlendMode.OVERRIDE)

        assertEquals(74u.toUByte(), (pixel as WithWhite).white.value)
        assertEquals(255u.toUByte(), tx.getValue(universe, 9), "red still lands")
    }

    @Test
    fun `a fallback reset restores the cell's white emitter`() {
        val rig = rig()
        val tx = ControllerTransaction(rig.fixtures.controllers)
        val pixel = rig.fixtures.withTransaction(tx).untypedGroupableFixture(cellKey)
        (pixel as WithWhite).white.value = 30u

        ColourTarget(cellKey).resetToFallback(pixel, amber(200u), fadeMs = 0)

        assertEquals(200u.toUByte(), pixel.white.value)
    }

    @Test
    fun `the programmer's own white slider entry on a cell beats an older colour entry`() {
        val rig = rig()
        val tx = ControllerTransaction(rig.fixtures.controllers)
        val pixel = rig.fixtures.withTransaction(tx).untypedGroupableFixture(cellKey)
        val store = ProgrammerStore()
        store.put(ProgrammerOwner.WEB, cellKey, "rgbColour", CueAssignmentResolver.PropertyValue.Colour(amber(10u).color))
        store.put(ProgrammerOwner.WEB, cellKey, "white", CueAssignmentResolver.PropertyValue.Slider(99u))

        val composed = ColourTarget(cellKey).composeProgrammerOver(pixel, store, FxOutput.Colour(ExtendedColour.BLACK))

        assertEquals(99u.toUByte(), (composed as FxOutput.Colour).color.white, "the newer slider entry wins")
        assertEquals(Color(255, 157, 74), composed.color.color)
    }

    @Test
    fun `a cell is fully parked only when its white channel is parked too`() {
        val rig = rig()
        val pixel = rig.fixtures.untypedGroupableFixture(cellKey)
        val parkManager = ParkManager(state.database, projectId)
        val target = ColourTarget(cellKey)

        runBlocking { for (channel in 9..11) parkManager.park(universe = 0, channel = channel, value = 0u) }
        assertFalse(target.isPropertyFullyParked(pixel, parkManager), "the white emitter is still live")

        runBlocking { parkManager.park(universe = 0, channel = whiteChannel, value = 0u) }
        assertTrue(target.isPropertyFullyParked(pixel, parkManager))
    }
}
