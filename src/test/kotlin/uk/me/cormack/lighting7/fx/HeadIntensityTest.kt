package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.KamLiteobar252Fixture
import uk.me.cormack.lighting7.fixture.dmx.LightstripRgbFixture
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.fixture.dmx.ShowSoundLedBeamRingFixture
import uk.me.cormack.lighting7.midi.GlobalScalerState
import uk.me.cormack.lighting7.midi.GlobalScalerStateHolder
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What lights a head (move-in-dark plan D4, P3): its own dimmer and UV, its parent's master dimmer,
 * and on a dimmerless head its colour mix — read as composed, park included, the grand master and a
 * blackout not.
 */
class HeadIntensityTest {

    private val universe = Universe(0, 0)

    private fun rig(): Triple<Fixtures, MockDmxController, Map<String, HeadIntensity>> {
        val fixtures = Fixtures()
        val controller = MockDmxController(universe)
        fixtures.register(removeUnused = true) {
            addController(controller)
            // Ch 1 dimmer, 2–4 RGB, 7 UV, 8 strobe.
            addFixture(HexFixture(universe, "hex", "Hex", firstChannel = 1))
            // Ch 21–23: RGB, no dimmer.
            addFixture(LightstripRgbFixture(universe, "strip", "Strip", firstChannel = 21))
            // Ch 37 the master dimmer, ch 39–50 three RGBW zones.
            addFixture(ShehdsLed19RgbwFixture.Mode24Ch(universe, "wash", "Wash", firstChannel = 31))
        }
        return Triple(fixtures, controller, HeadIntensity.classify(fixtures))
    }

    @Test
    fun `an element under a master dimmer is dark only while the master and its own levels are`() {
        val (_, controller, heads) = rig()
        val zone = heads.values.first { it.parentKey == "wash" }
        assertTrue(37 in zone.levels, "the parent's master dimmer lights its zones")
        assertFalse(37 in zone.ownLevels, "but the grand master scales it on the parent, once")
        assertFalse(zone.colourIsIntensity, "a zone under a master dimmer is left to that dimmer")
        assertTrue(zone.isDark(controller))

        controller.setValue(37, 200u, 0)
        assertFalse(zone.isDark(controller), "the master is up")
        controller.setValue(37, 0u, 0)
        assertTrue(zone.isDark(controller))
    }

    @Test
    fun `a dimmerless head's colour is its intensity`() {
        val (_, controller, heads) = rig()
        val strip = heads.getValue("strip")
        assertEquals(listOf(21, 22, 23), strip.levels)
        assertTrue(strip.colourIsIntensity)
        assertTrue(strip.isDark(controller))
        controller.setValue(22, 10u, 0)
        assertFalse(strip.isDark(controller), "any of its mix lights it")
    }

    @Test
    fun `UV lights a head whose dimmer is out`() {
        val (_, controller, heads) = rig()
        val hex = heads.getValue("hex")
        assertEquals(setOf(1, 7), hex.levels.toSet())
        assertFalse(hex.colourIsIntensity, "a hex has a dimmer")
        controller.setValue(2, 255u, 0)
        assertTrue(hex.isDark(controller), "its colour alone does not light it")
        controller.setValue(7, 100u, 0)
        assertFalse(hex.isDark(controller))
    }

    @Test
    fun `a parked dimmer above 0 is lit`() {
        val park = object : ParkSource {
            override fun getParkedValue(universe: Int, channel: Int): UByte? = if (channel == 1) 40u else null
            override fun isParked(universe: Int, channel: Int) = channel == 1
        }
        val fixtures = Fixtures()
        val controller = MockDmxController(universe, park)
        fixtures.register(removeUnused = true) {
            addController(controller)
            addFixture(HexFixture(universe, "hex", "Hex", firstChannel = 1))
        }
        assertFalse(HeadIntensity.classify(fixtures).getValue("hex").isDark(controller), "the controller reads park first")
    }

    @Test
    fun `an LED ring's effect lights the ring, though the grand master only cuts it`() {
        val fixtures = Fixtures()
        val controller = MockDmxController(universe)
        fixtures.register(removeUnused = true) {
            addController(controller)
            // Ch 6 dimmer, 13 the ring's effect, 14 its speed.
            addFixture(ShowSoundLedBeamRingFixture.Mode14Ch(universe, "beam", "Beam", firstChannel = 1))
        }
        val beam = HeadIntensity.classify(fixtures).getValue("beam")
        assertTrue(13 in beam.levels && 13 in beam.cuts)
        assertFalse(13 in beam.ownLevels)
        assertTrue(beam.isDark(controller))
        controller.setValue(13, 30u, 0)
        assertFalse(beam.isDark(controller))
    }

    @Test
    fun `the grand master at 0 and a safe blackout do not make a head dark`() {
        val (fixtures, controller, heads) = rig()
        val holder = GlobalScalerStateHolder()
        val scaler = GlobalScalerState(fixtures, holder).also { it.attach() }
        controller.setValue(1, 200u, 0)
        scaler.setGrandMasterLevel(0.0)
        assertEquals(0u.toUByte(), controller.getEffectiveValue(1), "the wire is out")
        assertFalse(heads.getValue("hex").isDark(controller), "but what composes is lit")

        scaler.setGrandMasterLevel(100.0)
        holder.setSafeBlackout(true)
        assertFalse(heads.getValue("hex").isDark(controller))
    }

    @Test
    fun `an Intensity FX on the head or its parent lights it`() {
        val (_, _, heads) = rig()
        val hex = heads.getValue("hex")
        val zone = heads.values.first { it.parentKey == "wash" }
        assertFalse(hex.hasIntensityFx(setOf("hex" to "rgbColour")), "a colour FX does not")
        assertTrue(hex.hasIntensityFx(setOf("hex" to "dimmer")))
        assertTrue(zone.hasIntensityFx(setOf("wash" to "dimmer")), "the master's FX lights its zones")
    }

    @Test
    fun `a parent lit only through its cells is dark when they all are`() {
        val fixtures = Fixtures()
        val controller = MockDmxController(universe)
        fixtures.register(removeUnused = true) {
            addController(controller)
            // Ch 1 macro, 2 strobe, 3–11 three RGB cells — no dimmer on the bar or its cells.
            addFixture(KamLiteobar252Fixture(universe, "bar", "Bar", firstChannel = 1))
        }
        val heads = HeadIntensity.classify(fixtures)
        val bar = heads.getValue("bar")
        assertTrue(bar.ownLevels.isEmpty(), "the grand master scales the cells, not the bar")
        assertEquals(3, bar.elementKeys.size)
        assertEquals((3..11).toSet(), bar.levels.toSet())
        assertTrue(bar.isDark(controller))
        controller.setValue(9, 50u, 0)
        assertFalse(bar.isDark(controller), "one cell up lights the bar")
        assertTrue(bar.hasIntensityFx(setOf(bar.elementKeys.first() to "dimmer"), heads).not(), "a cell has no dimmer to drive")
    }

    @Test
    fun `the grand master scales exactly each head's own levels`() {
        val (_, _, heads) = rig()
        val scaled = heads.values.flatMap { it.ownLevels }.toSet()
        assertTrue(1 in scaled && 7 in scaled && 37 in scaled)
        assertTrue(21 in scaled, "a dimmerless head's mix")
        assertFalse(2 in scaled, "a dimmed head's colour")
        assertEquals(setOf(8), heads.getValue("hex").cuts.toSet(), "the strobe is cut, never scaled")
    }
}
