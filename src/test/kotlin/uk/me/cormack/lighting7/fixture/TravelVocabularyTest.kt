package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.RobeColorSpot575Fixture
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixture optics plan's session 8, "travel time" (D14): a type's base speeds (`@FixtureType.travel`)
 * and a fixture's own timing channels (`@FixtureProperty.timing`). Both are drawn by the Stage view and
 * never output — the last test holds the desk to sending a timing channel and the move it times exactly
 * as written.
 */
class TravelVocabularyTest {

    private val universe = Universe(0, 0)

    private fun revolution() = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)

    @Test
    fun `every head whose pan and tilt the view draws in degrees declares how fast they move`() {
        var movers = 0
        for (type in FixtureTypeRegistry.allTypes) {
            val sliders = type.properties.filterIsInstance<SliderPropertyDescriptor>()
            val drawsPosition = sliders.any { it.axis == "PAN" && it.degMax != null } &&
                sliders.any { it.axis == "TILT" && it.degMax != null }
            if (!drawsPosition) continue
            movers++
            val travel = assertNotNull(type.travel, "${type.typeKey} draws pan/tilt but declares no travel")
            assertTrue((travel.panDegPerS ?: 0.0) > 0.0 && (travel.tiltDegPerS ?: 0.0) > 0.0, "${type.typeKey}: $travel")
        }
        assertTrue(movers >= 10, "found $movers movers")
    }

    @Test
    fun `the unset sentinel reflects as no travel, and a partial travel keeps only what it declares`() {
        assertNull(Travel().resolve())
        assertEquals(TravelInfo(panDegPerS = 90.0), Travel(panDegPerS = 90.0).resolve())
        val types = FixtureTypeRegistry.allTypes.associateBy { it.typeKey }
        assertNull(types.getValue("hex").travel, "a fixture with no mechanics declares none")
        val orbit = assertNotNull(types.getValue("gear4music-orbit-70-13ch").travel)
        assertNull(orbit.colourMs, "an RGBW head's colour is electronic and does not travel")
    }

    @Test
    fun `the Robe's pan and tilt are its manual's stated top speeds`() {
        val robe = assertNotNull(FixtureTypeRegistry.typeInfoForKey("robe-color-spot-575-mode-2")?.travel)
        assertEquals(157.27, robe.panDegPerS)
        assertEquals(108.95, robe.tiltDegPerS)
        // Its pan/tilt speed channel is not a timing channel: its curve depends on a menu mode.
        val speed = RobeColorSpot575Fixture.Mode2Ch(universe, "s", "S", 1).generatePropertyDescriptors()
            .filterIsInstance<SliderPropertyDescriptor>().single { it.name == "panTiltSpeed" }
        assertNull(speed.timing)
    }

    @Test
    fun `the Revolution's three timing channels stretch position, colour and beam at one second a step`() {
        val sliders = revolution().generatePropertyDescriptors().filterIsInstance<SliderPropertyDescriptor>().associateBy { it.name }
        val focus = sliders.getValue("focusTime")
        assertEquals("POSITION", focus.timing, "ETC's Focus Timing times pan and tilt (p14 [10]), not the lens")
        assertEquals(1.0, focus.timingSecondsPerStep)
        assertEquals(255, focus.timingFastFrom, "Focus Timing at 100 % is the console response option (p16 [12])")
        assertEquals("COLOUR", sliders.getValue("colTime").timing)
        assertEquals("BEAM", sliders.getValue("beamTime").timing)
        for (name in listOf("colTime", "beamTime")) {
            assertEquals(1.0, sliders.getValue(name).timingSecondsPerStep, name)
            assertNull(sliders.getValue(name).timingFastFrom, "$name: 255 is 4 min 15 s (p16 [12])")
        }
        assertNull(sliders.getValue("zoom").timing, "an ordinary slider is no timing channel")
        assertNull(sliders.getValue("zoom").timingSecondsPerStep)
    }

    @Test
    fun `every timing channel is a SPEED slider that declares its seconds per step and a fast band inside its range`() {
        var timing = 0
        for (type in FixtureTypeRegistry.allTypes) {
            for (slider in type.properties.filterIsInstance<SliderPropertyDescriptor>()) {
                if (slider.timing == null) {
                    assertNull(slider.timingSecondsPerStep, "${type.typeKey}'s ${slider.name} is no timing channel")
                    assertNull(slider.timingFastFrom, "${type.typeKey}'s ${slider.name} is no timing channel")
                    continue
                }
                timing++
                assertEquals("speed", slider.category, "${type.typeKey}'s ${slider.name}")
                val perStep = assertNotNull(slider.timingSecondsPerStep, "${type.typeKey}'s ${slider.name}")
                assertTrue(perStep > 0.0 && perStep.isFinite(), "${type.typeKey}'s ${slider.name}: $perStep")
                slider.timingFastFrom?.let { assertTrue(it in (slider.min + 1)..slider.max, "${type.typeKey}'s ${slider.name}: $it") }
            }
        }
        assertEquals(3, timing, "the Revolution's three")
    }

    @Test
    fun `drawn, never output — a timing channel and the move it times go out exactly as written`() {
        val (controller, transaction) = createTestTransaction(universe)
        val rev = revolution().withTransaction(transaction)
        rev.focusTime.value = 5u
        rev.colTime.value = 5u
        rev.pan.value = 200u
        rev.gelScroller.setting = Source4RevolutionFixture.GelFrame.L201_FULL_CT_BLUE
        transaction.apply()

        assertEquals(5u.toUByte(), controller.getValue(9))
        assertEquals(5u.toUByte(), controller.getValue(10))
        assertEquals(200u.toUByte(), controller.getValue(2))
        assertEquals(Source4RevolutionFixture.GelFrame.L201_FULL_CT_BLUE.level, controller.getValue(13))
        // One write each and no ramp: the desk never interpolates toward the timed value itself.
        assertEquals(listOf(200u.toUByte()), controller.writesTo(2))
        assertTrue(controller.changesTo(2).all { it.fadeMs == 0L }, "${controller.changesTo(2)}")
        assertEquals(listOf(Source4RevolutionFixture.GelFrame.L201_FULL_CT_BLUE.level), controller.writesTo(13))
    }
}
