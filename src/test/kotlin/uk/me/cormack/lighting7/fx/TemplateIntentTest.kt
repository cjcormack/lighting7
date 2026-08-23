package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The template intent grammar — the one thing that parses and serialises what a template row holds.
 *
 * The round-trips matter because a stored intent is read back by the cook, the apply route and the
 * editor's panel; the *degradation* cases matter more, because they are what a reader that predates
 * the grammar does with one, and both were chosen rather than tolerated.
 */
class TemplateIntentTest {

    private fun roundTrip(intent: TemplateIntent) {
        assertEquals(intent, parseTemplateIntent(intent.serialize()), "round trip of ${intent.serialize()}")
    }

    @Test
    fun `every arm round-trips through its serialised form`() {
        roundTrip(TemplateIntent.Colour("#FF9D4A", WhitePolicy.EXTRACT))
        roundTrip(TemplateIntent.Colour("#FF9D4A", WhitePolicy.ADDITIVE))
        roundTrip(TemplateIntent.Colour("#FF9D4A", WhitePolicy.RGB_ONLY))
        roundTrip(TemplateIntent.Percent(75.0))
        roundTrip(TemplateIntent.Percent(12.5))
        roundTrip(TemplateIntent.Position(45.0, -12.5))
        roundTrip(TemplateIntent.Switch(true))
        roundTrip(TemplateIntent.Switch(false))
    }

    @Test
    fun `a colour intent's serialised head is a plain hex, so every existing swatch renders it`() {
        // The reason the policy is a `;`-token rather than a different value shape: both colour
        // parsers read `parts[0]` for the hex and ignore tokens they do not know, so every cell,
        // preview and library row already draws a template's colour without being taught anything.
        val serialised = TemplateIntent.Colour("#FF9D4A", WhitePolicy.EXTRACT).serialize()
        assertTrue(serialised.startsWith("#FF9D4A;"), serialised)
        assertEquals("#FF9D4A", serialised.substringBefore(';'))
        assertEquals("#ff9d4a", parseExtendedColour(serialised).color.toHexString().lowercase())
    }

    @Test
    fun `an unknown policy token degrades to RGB only rather than throwing`() {
        val parsed = parseTemplateIntent("#FF9D4A;policy=sideways")
        assertEquals(TemplateIntent.Colour("#FF9D4A", WhitePolicy.RGB_ONLY), parsed)
    }

    @Test
    fun `a colour with no policy at all reads as RGB only`() {
        // Matching what every *other* reader of that string already does with it. The editor always
        // writes a policy, so this arm is for a row that came from somewhere else — and the safe
        // reading of "no policy stated" is the one that drives no extra emitters.
        assertEquals(
            TemplateIntent.Colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            parseTemplateIntent("#FF9D4A"),
        )
    }

    @Test
    fun `the literal parser refuses an intent rather than misreading it`() {
        // The second deliberate degradation, and the more important one: `pct:75` must never become
        // a DMX 75. `CueAssignmentResolver` answers null, and its callers skip the row with a warn.
        for (raw in listOf("pct:75", "deg:45,12.5", "on", "off")) {
            assertNull(
                CueAssignmentResolver.parseAssignmentValue(PropertyCategoryForTest.DIMMER, "dimmer", raw),
                "'$raw' must not parse as a literal",
            )
        }
    }

    @Test
    fun `garbage is null, not a guess`() {
        for (raw in listOf("", "   ", "#GGGGGG", "pct:", "deg:45", "deg:a,b", "#12345", "maybe")) {
            assertNull(parseTemplateIntent(raw), "'$raw'")
        }
    }

    @Test
    fun `a percent is clamped to nought-to-a-hundred on the way in`() {
        assertEquals(TemplateIntent.Percent(100.0), parseTemplateIntent("pct:180"))
        assertEquals(TemplateIntent.Percent(0.0), parseTemplateIntent("pct:-20"))
    }

    @Test
    fun `the property vocabulary is closed, and that is where slotted roles are refused`() {
        assertEquals(TemplateProperty.COLOUR, TemplateProperty.ofOrNull("rgbColour"))
        // `canonicalPropertyName` collapses the three spellings, so a wheel named `colour` and a
        // mixer named `rgbColour` are the same vocabulary entry.
        assertEquals(TemplateProperty.COLOUR, TemplateProperty.ofOrNull("colour"))
        assertEquals(TemplateProperty.COLOUR, TemplateProperty.ofOrNull("color"))
        assertEquals(TemplateProperty.POSITION, TemplateProperty.ofOrNull("position"))
        assertEquals(TemplateProperty.DIMMER, TemplateProperty.ofOrNull("dimmer"))

        // "Gobo 3" is a different pattern on every model, so a template cannot carry one.
        assertNull(TemplateProperty.ofOrNull("gobo"))
        assertNull(TemplateProperty.ofOrNull("goboRotation"))
        assertNull(TemplateProperty.ofOrNull("ledMacro"))
        // And a misspelling is refused too, rather than stored and silently resolving to nothing.
        assertNull(TemplateProperty.ofOrNull("dimer"))
    }

    @Test
    fun `strobe is intensity, not beam`() {
        // Mirrors `PropertyCategory.STROBE.maskGroup()`. A template holding a level and a strobe is
        // one family, which is what lets both live in the same template.
        assertEquals(PropertyMaskGroup.INTENSITY, TemplateProperty.STROBE.family)
        assertEquals(PropertyMaskGroup.INTENSITY, TemplateProperty.DIMMER.family)
        assertEquals(
            setOf(TemplateProperty.DIMMER, TemplateProperty.STROBE),
            TemplateProperty.forFamily(PropertyMaskGroup.INTENSITY).toSet(),
        )
    }

    @Test
    fun `a property only accepts the intent shape it is for`() {
        assertTrue(TemplateProperty.COLOUR.accepts(TemplateIntent.Colour("#ffffff")))
        assertTrue(!TemplateProperty.COLOUR.accepts(TemplateIntent.Percent(50.0)))
        assertTrue(TemplateProperty.POSITION.accepts(TemplateIntent.Position(0.0, 0.0)))
        assertTrue(!TemplateProperty.POSITION.accepts(TemplateIntent.Percent(50.0)))
        assertTrue(TemplateProperty.PRISM.accepts(TemplateIntent.Switch(true)))
        assertTrue(!TemplateProperty.PRISM.accepts(TemplateIntent.Percent(50.0)))
        assertTrue(TemplateProperty.ZOOM.accepts(TemplateIntent.Percent(14.0)))
        assertIs<TemplateIntent.Percent>(parseTemplateIntent("pct:14"))
    }
}

/** Local alias so the literal-parser assertion above reads without a second import line. */
private typealias PropertyCategoryForTest = uk.me.cormack.lighting7.fixture.PropertyCategory
