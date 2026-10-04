package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Blade
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Source4RevolutionFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `base frame writes dimmer, position, beam-shaping, gel, wheel and shutter channels in order`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "s4rev-1", "S4 Rev 1", 1)
            .withTransaction(transaction)

        fixture.dimmer.value = 200u
        fixture.pan.value = 100u
        fixture.panFine.value = 110u
        fixture.tilt.value = 120u
        fixture.tiltFine.value = 130u
        fixture.mediaFrame.setting = Source4RevolutionFixture.MediaFrame.IN
        fixture.focus.value = 20u
        fixture.zoom.value = 30u
        fixture.focusTime.value = 40u
        fixture.colTime.value = 50u
        fixture.beamTime.value = 60u
        // Ch 12 (Reset) intentionally not exposed.
        fixture.gelScroller.setting = Source4RevolutionFixture.GelFrame.R357_ROYAL_LAVENDER
        fixture.fanSpeed.value = 70u
        fixture.iris.value = 80u
        fixture.fbWheelPos.setting = Source4RevolutionFixture.WheelPosition.SLOT_2
        fixture.fbWheelFunc.setting = Source4RevolutionFixture.WheelFunction.ROTATE_REV
        fixture.fbWheelRot.value = 92u
        fixture.fbWheelRotFine.value = 93u
        // Ch 20–23 are reserved with the shutter module fitted, and not exposed.
        fixture.frame1Pos.value = 110u
        fixture.frame1Rot.value = 111u
        fixture.frame2Pos.value = 112u
        fixture.frame2Rot.value = 113u
        fixture.frame3Pos.value = 114u
        fixture.frame3Rot.value = 115u
        fixture.frame4Pos.value = 116u
        fixture.frame4Rot.value = 117u

        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(1))
        assertEquals(100u.toUByte(), controller.getValue(2))
        assertEquals(110u.toUByte(), controller.getValue(3))
        assertEquals(120u.toUByte(), controller.getValue(4))
        assertEquals(130u.toUByte(), controller.getValue(5))
        assertEquals(128u.toUByte(), controller.getValue(6))
        assertEquals(20u.toUByte(), controller.getValue(7))
        assertEquals(30u.toUByte(), controller.getValue(8))
        assertEquals(40u.toUByte(), controller.getValue(9))
        assertEquals(50u.toUByte(), controller.getValue(10))
        assertEquals(60u.toUByte(), controller.getValue(11))
        // Ch 12 (Reset) was never written; default is 0.
        assertEquals(0u.toUByte(), controller.getValue(12))
        assertEquals(91u.toUByte(), controller.getValue(13))
        assertEquals(70u.toUByte(), controller.getValue(14))
        assertEquals(80u.toUByte(), controller.getValue(15))
        assertEquals(27u.toUByte(), controller.getValue(16))
        assertEquals(27u.toUByte(), controller.getValue(17))
        assertEquals(92u.toUByte(), controller.getValue(18))
        assertEquals(93u.toUByte(), controller.getValue(19))
        // Ch 20–23 were never written; they stay at 0.
        for (channel in 20..23) assertEquals(0u.toUByte(), controller.getValue(channel), "ch $channel")
        assertEquals(110u.toUByte(), controller.getValue(24))
        assertEquals(111u.toUByte(), controller.getValue(25))
        assertEquals(112u.toUByte(), controller.getValue(26))
        assertEquals(113u.toUByte(), controller.getValue(27))
        assertEquals(114u.toUByte(), controller.getValue(28))
        assertEquals(115u.toUByte(), controller.getValue(29))
        assertEquals(116u.toUByte(), controller.getValue(30))
        assertEquals(117u.toUByte(), controller.getValue(31))
    }

    @Test
    fun `gel scroller frames start where the ETC standard string does, each with a preview`() {
        // ETC's "DMX Start" column for the standard 12-colour string (manual p15).
        val starts = listOf(0, 18, 37, 55, 73, 91, 110, 128, 146, 165, 183, 201, 219, 238)
        val frames = Source4RevolutionFixture.GelFrame.entries
        assertEquals(starts, frames.map { it.level.toInt() })
        assertEquals("#FFFFFF", frames.first().colourPreview, "frame 0 is the open leader")
        assertEquals("#FFFFFF", frames.last().colourPreview, "frame 13 is the open trailer")
        for (frame in frames) assertTrue(Regex("#[0-9a-fA-F]{6}").matches(frame.colourPreview), "$frame")

        val (controller, transaction) = createTestTransaction(universe)
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "s4rev-1", "S4 Rev 1", 1)
            .withTransaction(transaction)
        fixture.gelScroller.setting = Source4RevolutionFixture.GelFrame.R25_ORANGE_RED
        transaction.apply()
        assertEquals(128u.toUByte(), controller.getValue(13))
    }

    @Test
    fun `the front wheel bands are the manual ones`() {
        // Position (p22, p24): 0–13 open, 14–26 / 27–39 / 40–50 slots 1–3, 51–255 holds slot 3.
        assertEquals(
            listOf(0, 14, 27, 40),
            Source4RevolutionFixture.WheelPosition.entries.map { it.level.toInt() },
        )
        // Function (p24): 0–13 index, 14–26 rotate >>, 27–39 rotate <<, 40–255 reserved.
        assertEquals(
            listOf("INDEX" to 0, "ROTATE_FWD" to 14, "ROTATE_REV" to 27, "RESERVED" to 40),
            Source4RevolutionFixture.WheelFunction.entries.map { it.name to it.level.toInt() },
        )
    }

    @Test
    fun `the descriptor carries the optics the Stage view reads`() {
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "s4rev-1", "S4 Rev 1", 1)

        val zoom = assertNotNull(fixture.fixtureProperty("zoom"))
        assertEquals(35.0, zoom.degMin, "DMX 0 is wide")
        assertEquals(15.0, zoom.degMax)

        assertEquals(PropertyCategory.COLOUR, fixture.fixtureProperty("gelScroller")?.category)
        assertEquals(PropertyCategory.GOBO, fixture.fixtureProperty("fbWheelPos")?.category)
        assertEquals(PropertyCategory.GOBO_ROTATION_MODE, fixture.fixtureProperty("fbWheelFunc")?.category)

        val rot = assertNotNull(fixture.fixtureProperty("fbWheelRot"))
        assertEquals(PropertyCategory.GOBO_ROTATION, rot.category)
        assertEquals(30.0, rot.rpmMax)
        assertEquals(360.0, rot.indexDegMax)
        assertNull(rot.fineOf)

        val fine = assertNotNull(fixture.fixtureProperty("fbWheelRotFine"))
        assertEquals("fbWheelRot", fine.fineOf)
        assertNull(fine.rpmMax)

        assertNotNull(fixture.fixtureProperty("fanSpeed"))
        assertNotNull(fixture.fixtureProperty("mediaFrame"))
        for (removed in listOf("rbWheelPos", "rbWheelFunc", "rbWheelRot", "rbWheelRotFine")) {
            assertNull(fixture.fixtureProperty(removed), "$removed is reserved with the shutter module fitted")
        }

        // Every exposed channel is described; reset (12) and the reserved 20–23 are not.
        val described = fixture.channelDescriptions().filterValues { it.isNotEmpty() }.keys
        assertEquals((1..31).toSet() - setOf(12, 20, 21, 22, 23), described)
    }

    @Test
    fun `the four frames are framing shutters, each naming its blade and its scale`() {
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "s4rev-1", "S4 Rev 1", 1)
        val blades = listOf(Blade.TOP, Blade.BOTTOM, Blade.LEFT, Blade.RIGHT)
        for ((i, blade) in blades.withIndex()) {
            val n = i + 1
            val pos = assertNotNull(fixture.fixtureProperty("frame${n}Pos"))
            assertEquals(PropertyCategory.SHUTTER, pos.category, "frame $n position")
            assertEquals(blade, pos.blade, "frame $n cuts the $blade")
            assertEquals(0.5, pos.depthMax, "frame $n reaches the centre at full")
            assertFalse(pos.inverted)

            val rot = assertNotNull(fixture.fixtureProperty("frame${n}Rot"))
            assertEquals(PropertyCategory.SHUTTER_ROTATION, rot.category, "frame $n rotation")
            assertEquals(blade, rot.blade)
            assertEquals(-45.0, rot.degMin, "the manual's ±45°")
            assertEquals(45.0, rot.degMax)
            assertNull(rot.depthMax)
            assertFalse(rot.inverted)
        }
    }
}
