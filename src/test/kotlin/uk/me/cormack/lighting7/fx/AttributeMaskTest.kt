package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The I/P/C/B mask that scopes Record, Include and Update.
 *
 * [AttributeCategory.family] is an exhaustive `when`, so *coverage* is already a compile-time
 * guarantee; what these tests pin are the judgement calls inside it — the ones a future
 * refactor could plausibly flip without noticing.
 */
class AttributeMaskTest {

    private val universe = Universe(0, 0)

    private fun hex() = HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1)

    @Test
    fun `every category classifies`() {
        // Belt and braces against someone replacing the exhaustive `when` with an `else`.
        for (category in AttributeCategory.entries) {
            assertTrue(category.family() in AttributeFamily.entries, "$category unclassified")
        }
    }

    @Test
    fun `strobe is intensity, not beam`() {
        // It modulates level (and is HTP, like DIMMER); operators reach for it beside the
        // dimmer, not beside gobos.
        assertEquals(AttributeFamily.INTENSITY, AttributeCategory.STROBE.family())
        assertEquals(AttributeFamily.INTENSITY, AttributeCategory.DIMMER.family())
    }

    @Test
    fun `extra emitters count as colour`() {
        // Recording "the colour" without W/A/UV would record half the look on a hex.
        assertEquals(AttributeFamily.COLOUR, AttributeCategory.COLOUR.family())
        assertEquals(AttributeFamily.COLOUR, AttributeCategory.WHITE.family())
        assertEquals(AttributeFamily.COLOUR, AttributeCategory.AMBER.family())
        assertEquals(AttributeFamily.COLOUR, AttributeCategory.UV.family())
    }

    @Test
    fun `fine axes stay with their coarse axis`() {
        assertEquals(AttributeFamily.POSITION, AttributeCategory.PAN.family())
        assertEquals(AttributeFamily.POSITION, AttributeCategory.PAN_FINE.family())
        assertEquals(AttributeFamily.POSITION, AttributeCategory.TILT_FINE.family())
    }

    @Test
    fun `beam is the catch-all`() {
        assertEquals(AttributeFamily.BEAM, AttributeCategory.GOBO.family())
        assertEquals(AttributeFamily.BEAM, AttributeCategory.SETTING.family())
        assertEquals(AttributeFamily.BEAM, AttributeCategory.OTHER.family())
        assertEquals(AttributeFamily.BEAM, AttributeCategory.SPEED.family())
    }

    @Test
    fun `position resolves without an annotated attribute`() {
        // `position` is the synthetic pan/tilt pair — there is no @FixtureAttribute to read a
        // category from, so the lookup has to answer it before consulting the fixture.
        assertEquals(AttributeFamily.POSITION, familyForAttribute(hex(), "position"))
        assertEquals(AttributeFamily.POSITION, familyForAttribute(hex(), "Position"))
    }

    @Test
    fun `colour aliases all reach the annotated attribute`() {
        val fixture = hex()
        assertEquals(AttributeFamily.COLOUR, familyForAttribute(fixture, "rgbColour"))
        assertEquals(AttributeFamily.COLOUR, familyForAttribute(fixture, "colour"))
        assertEquals(AttributeFamily.COLOUR, familyForAttribute(fixture, "color"))
    }

    @Test
    fun `unknown attribute has no mask group`() {
        // Caller reports MISSING_ATTRIBUTE rather than guessing a bucket.
        assertNull(familyForAttribute(hex(), "notAnAttribute"))
    }

    @Test
    fun `parse accepts any case and rejects nonsense`() {
        assertEquals(setOf(AttributeFamily.COLOUR), parseFamilies(listOf("colour")))
        assertEquals(
            setOf(AttributeFamily.COLOUR, AttributeFamily.POSITION),
            parseFamilies(listOf("COLOUR", " position ")),
        )
        assertFailsWith<IllegalArgumentException> { parseFamilies(listOf("SPARKLE")) }
    }

    @Test
    fun `absent or exhaustive mask both mean no mask`() {
        assertNull(parseFamilies(null))
        assertNull(parseFamilies(emptyList()))
        // Ticking all four is the same as ticking none; collapsing it here keeps the "is this
        // masked?" branches in Record from needing a full-set special case.
        assertNull(parseFamilies(AttributeFamily.entries.map { it.name }))
    }

    @Test
    fun `the lenient parse drops an unknown name and reads nothing left as no mask`() {
        // The `selection.set` frame's parser: no reply channel, so an unknown name is dropped as the
        // client's `parseAttributeMask` drops one, never refused.
        assertEquals(setOf(AttributeFamily.COLOUR), parseFamiliesLenient(listOf("colour", "bogus")))
        // Every name unknown leaves nothing, and nothing is every attribute — the same round trip
        // the client makes (`parseAttributeMask([]) → serializeAttributeMask → null`). The press doors
        // never take this parser: they use the strict one above and answer 400.
        assertNull(parseFamiliesLenient(listOf("GOBO")))
        assertNull(parseFamiliesLenient(null))
        assertNull(parseFamiliesLenient(emptyList()))
        assertNull(parseFamiliesLenient(AttributeFamily.entries.map { it.name }), "all four is no mask, as the strict parse reads it")
    }

    @Test
    fun `the wire and layer spellings are the names in declaration order`() {
        val mask = setOf(AttributeFamily.COLOUR, AttributeFamily.INTENSITY)
        assertEquals(listOf("INTENSITY", "COLOUR"), mask.toMaskNames())
        assertEquals("INTENSITY,COLOUR", mask.toAttributeMask())
        assertEquals(mask, parseFamilies(mask.toAttributeMask().split(",")), "the layer spelling parses back")
    }

    @Test
    fun `maskAllows treats an unclassifiable attribute as out of mask`() {
        assertTrue(maskAllows(null, null), "no mask passes everything, even unresolvable rows")
        assertTrue(maskAllows(setOf(AttributeFamily.COLOUR), AttributeFamily.COLOUR))
        assertTrue(!maskAllows(setOf(AttributeFamily.COLOUR), AttributeFamily.BEAM))
        // The load-bearing case: UPDATE_EXISTING deletes in-mask rows, so a row it cannot
        // classify must fall outside every mask and survive.
        assertTrue(!maskAllows(setOf(AttributeFamily.COLOUR), null))
    }
}
