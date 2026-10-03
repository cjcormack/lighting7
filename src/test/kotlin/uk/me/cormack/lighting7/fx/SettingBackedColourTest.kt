package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A COLOUR property backed by a slot channel — the Source Four Revolution's gel scroller, a colour
 * wheel — stores and is written **slot levels**. The value parser used to read every COLOUR value as
 * a colour, so a level ("128") became the hex shorthand `#112288`, which the writer drops on
 * anything but an RGB property: a gel set from the programmer, recorded into a cue or held in a Look
 * row never reached the scroller.
 */
class SettingBackedColourTest {

    private val universe = Universe(0, 0)

    @Test
    fun `a slot-backed COLOUR property reads a level as a slot, and a colour as a colour`() {
        val level = assertNotNull(CueAssignmentResolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "gelScroller", "128", settingBacked = true,
        ))
        assertEquals(CueAssignmentResolver.PropertyValue.Setting(128u), level)
        // Round trip: what Record writes for a slot reads back as the same slot.
        assertEquals(level, CueAssignmentResolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "gelScroller", level.serialize(), settingBacked = true,
        ))

        assertIs<CueAssignmentResolver.PropertyValue.Colour>(CueAssignmentResolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "gelScroller", "#ff8800", settingBacked = true,
        ))
        // An RGB property is unchanged: a bare value still parses as a colour.
        assertIs<CueAssignmentResolver.PropertyValue.Colour>(CueAssignmentResolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "rgbColour", "128",
        ))
    }

    @Test
    fun `the catalogue knows which properties are slot channels`() {
        val rev = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)
        assertTrue(assertNotNull(rev.fixtureProperty("gelScroller")).settingBacked)
        assertTrue(assertNotNull(rev.fixtureProperty("fbWheelPos")).settingBacked)
        assertFalse(assertNotNull(rev.fixtureProperty("zoom")).settingBacked)
        assertTrue(isSettingBacked(rev, "gelScroller"))
        assertFalse(isSettingBacked(rev, "dimmer"))
        assertFalse(isSettingBacked(rev, "noSuchProperty"))

        val hex = HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1)
        assertFalse(isSettingBacked(hex, "rgbColour"))
    }

    @Test
    fun `a cue row holding a gel frame reaches the scroller's channel`() {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1))
        }
        val out = buildCueAssignmentsForCue(fixtures, CueApplyData(
            cueId = 7,
            cueName = "test",
            adHocEffects = emptyList(),
            propertyAssignments = listOf(
                CuePropertyAssignmentDto(
                    targetType = "fixture",
                    targetKey = "rev-1",
                    propertyName = "gelScroller",
                    value = Source4RevolutionFixture.GelFrame.R25_ORANGE_RED.level.toString(),
                ),
            ),
            cueStackId = 3,
            sortOrder = 2,
        ))
        val row = out.single()
        assertEquals(PropertyCategory.COLOUR, row.category)
        assertEquals(CueAssignmentResolver.PropertyValue.Setting(128u), row.value)

        val writes = PropertyChannelWriter.resolve(
            fixtures.untypedGroupableFixture("rev-1"), "gelScroller", row.value,
        )
        assertEquals(listOf(13 to 128), writes.map { it.channel to it.value.toInt() })
    }
}
