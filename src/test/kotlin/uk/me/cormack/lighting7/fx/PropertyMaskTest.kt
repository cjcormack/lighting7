package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The I/P/C/B mask that scopes Record, Include and Update.
 *
 * [PropertyCategory.maskGroup] is an exhaustive `when`, so *coverage* is already a compile-time
 * guarantee; what these tests pin are the judgement calls inside it — the ones a future
 * refactor could plausibly flip without noticing.
 */
class PropertyMaskTest {

    private val universe = Universe(0, 0)

    private fun hex() = HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1)

    @Test
    fun `every category classifies`() {
        // Belt and braces against someone replacing the exhaustive `when` with an `else`.
        for (category in PropertyCategory.entries) {
            assertTrue(category.maskGroup() in PropertyMaskGroup.entries, "$category unclassified")
        }
    }

    @Test
    fun `strobe is intensity, not beam`() {
        // It modulates level (and is HTP, like DIMMER); operators reach for it beside the
        // dimmer, not beside gobos.
        assertEquals(PropertyMaskGroup.INTENSITY, PropertyCategory.STROBE.maskGroup())
        assertEquals(PropertyMaskGroup.INTENSITY, PropertyCategory.DIMMER.maskGroup())
    }

    @Test
    fun `extra emitters count as colour`() {
        // Recording "the colour" without W/A/UV would record half the look on a hex.
        assertEquals(PropertyMaskGroup.COLOUR, PropertyCategory.COLOUR.maskGroup())
        assertEquals(PropertyMaskGroup.COLOUR, PropertyCategory.WHITE.maskGroup())
        assertEquals(PropertyMaskGroup.COLOUR, PropertyCategory.AMBER.maskGroup())
        assertEquals(PropertyMaskGroup.COLOUR, PropertyCategory.UV.maskGroup())
    }

    @Test
    fun `fine axes stay with their coarse axis`() {
        assertEquals(PropertyMaskGroup.POSITION, PropertyCategory.PAN.maskGroup())
        assertEquals(PropertyMaskGroup.POSITION, PropertyCategory.PAN_FINE.maskGroup())
        assertEquals(PropertyMaskGroup.POSITION, PropertyCategory.TILT_FINE.maskGroup())
    }

    @Test
    fun `beam is the catch-all`() {
        assertEquals(PropertyMaskGroup.BEAM, PropertyCategory.GOBO.maskGroup())
        assertEquals(PropertyMaskGroup.BEAM, PropertyCategory.SETTING.maskGroup())
        assertEquals(PropertyMaskGroup.BEAM, PropertyCategory.OTHER.maskGroup())
        assertEquals(PropertyMaskGroup.BEAM, PropertyCategory.SPEED.maskGroup())
    }

    @Test
    fun `position resolves without an annotated property`() {
        // `position` is the synthetic pan/tilt pair — there is no @FixtureProperty to read a
        // category from, so the lookup has to answer it before consulting the fixture.
        assertEquals(PropertyMaskGroup.POSITION, maskGroupForProperty(hex(), "position"))
        assertEquals(PropertyMaskGroup.POSITION, maskGroupForProperty(hex(), "Position"))
    }

    @Test
    fun `colour aliases all reach the annotated property`() {
        val fixture = hex()
        assertEquals(PropertyMaskGroup.COLOUR, maskGroupForProperty(fixture, "rgbColour"))
        assertEquals(PropertyMaskGroup.COLOUR, maskGroupForProperty(fixture, "colour"))
        assertEquals(PropertyMaskGroup.COLOUR, maskGroupForProperty(fixture, "color"))
    }

    @Test
    fun `unknown property has no mask group`() {
        // Caller reports MISSING_PROPERTY rather than guessing a bucket.
        assertNull(maskGroupForProperty(hex(), "notAProperty"))
    }

    @Test
    fun `parse accepts any case and rejects nonsense`() {
        assertEquals(setOf(PropertyMaskGroup.COLOUR), parseMaskGroups(listOf("colour")))
        assertEquals(
            setOf(PropertyMaskGroup.COLOUR, PropertyMaskGroup.POSITION),
            parseMaskGroups(listOf("COLOUR", " position ")),
        )
        assertFailsWith<IllegalArgumentException> { parseMaskGroups(listOf("SPARKLE")) }
    }

    @Test
    fun `absent or exhaustive mask both mean no mask`() {
        assertNull(parseMaskGroups(null))
        assertNull(parseMaskGroups(emptyList()))
        // Ticking all four is the same as ticking none; collapsing it here keeps the "is this
        // masked?" branches in Record from needing a full-set special case.
        assertNull(parseMaskGroups(PropertyMaskGroup.entries.map { it.name }))
    }

    @Test
    fun `maskAllows treats an unclassifiable property as out of mask`() {
        assertTrue(maskAllows(null, null), "no mask passes everything, even unresolvable rows")
        assertTrue(maskAllows(setOf(PropertyMaskGroup.COLOUR), PropertyMaskGroup.COLOUR))
        assertTrue(!maskAllows(setOf(PropertyMaskGroup.COLOUR), PropertyMaskGroup.BEAM))
        // The load-bearing case: UPDATE_EXISTING deletes in-mask rows, so a row it cannot
        // classify must fall outside every mask and survive.
        assertTrue(!maskAllows(setOf(PropertyMaskGroup.COLOUR), null))
    }
}
