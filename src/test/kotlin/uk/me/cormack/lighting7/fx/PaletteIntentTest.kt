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
class PaletteIntentTest {

    private fun roundTrip(intent: PaletteIntent) {
        assertEquals(intent, parsePaletteIntent(intent.serialize()), "round trip of ${intent.serialize()}")
    }

    @Test
    fun `every arm round-trips through its serialised form`() {
        roundTrip(PaletteIntent.Colour("#FF9D4A", WhitePolicy.EXTRACT))
        roundTrip(PaletteIntent.Colour("#FF9D4A", WhitePolicy.ADDITIVE))
        roundTrip(PaletteIntent.Colour("#FF9D4A", WhitePolicy.RGB_ONLY))
        roundTrip(PaletteIntent.Level(180))
        roundTrip(PaletteIntent.Level(0))
        roundTrip(PaletteIntent.Percent(75.0))
        roundTrip(PaletteIntent.Percent(12.5))
        roundTrip(PaletteIntent.Position(45.0, -12.5, PositionUnit.DEGREES))
        roundTrip(PaletteIntent.Position(-12.0, 30.0))
        roundTrip(PaletteIntent.Position(-128.0, 127.0))
        roundTrip(PaletteIntent.Switch(true))
        roundTrip(PaletteIntent.Switch(false))
    }

    @Test
    fun `a colour intent's serialised head is a plain hex, so every existing swatch renders it`() {
        // The reason the policy is a `;`-token rather than a different value shape: both colour
        // parsers read `parts[0]` for the hex and ignore tokens they do not know, so every cell,
        // preview and library row already draws a template's colour without being taught anything.
        val serialised = PaletteIntent.Colour("#FF9D4A", WhitePolicy.EXTRACT).serialize()
        assertTrue(serialised.startsWith("#FF9D4A;"), serialised)
        assertEquals("#FF9D4A", serialised.substringBefore(';'))
        assertEquals("#ff9d4a", parseExtendedColour(serialised).color.toHexString().lowercase())
    }

    @Test
    fun `an unknown policy token degrades to RGB only rather than throwing`() {
        val parsed = parsePaletteIntent("#FF9D4A;policy=sideways")
        assertEquals(PaletteIntent.Colour("#FF9D4A", WhitePolicy.RGB_ONLY), parsed)
    }

    @Test
    fun `a colour with no policy at all reads as RGB only`() {
        // Matching what every *other* reader of that string already does with it. The editor always
        // writes a policy, so this arm is for a row that came from somewhere else — and the safe
        // reading of "no policy stated" is the one that drives no extra emitters.
        assertEquals(
            PaletteIntent.Colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            parsePaletteIntent("#FF9D4A"),
        )
    }

    @Test
    fun `the literal parser refuses an intent rather than misreading it`() {
        // The second deliberate degradation, and the more important one: `pct:75` must never become
        // a DMX 75. `CueAssignmentResolver` answers null, and its callers skip the row with a warn.
        for (raw in listOf("pct:75", "pos:-12,30", "deg:45,12.5", "dmx:180", "on", "off")) {
            assertNull(
                CueAssignmentResolver.parseAssignmentValue(AttributeCategoryForTest.DIMMER, "dimmer", raw),
                "'$raw' must not parse as a literal",
            )
        }
    }

    @Test
    fun `garbage is null, not a guess`() {
        for (raw in listOf(
            "", "   ", "#GGGGGG", "pct:", "deg:45", "deg:a,b", "pos:12", "pos:1,2,3", "pos:,", "pos:nan,0", "#12345", "maybe",
            // A bare number is *not* a level intent, which is the whole reason `dmx:` carries a
            // prefix its payload does not need: `180` is what the literal parser reads, so the two
            // grammars would agree on some rows and silently disagree on others.
            "180", "dmx:", "dmx:abc",
        )) {
            assertNull(parsePaletteIntent(raw), "'$raw'")
        }
    }

    @Test
    fun `a position's two units parse apart, and a centre value is whole steps on the scale`() {
        // Venue-visit plan P3: `deg:` is still read as degrees; `pos:` is centre-relative coarse DMX,
        // rounded to a whole step and clamped to -128..127 on the way in, as `pct:` is to 0..100.
        assertEquals(PaletteIntent.Position(45.0, 12.5, PositionUnit.DEGREES), parsePaletteIntent("deg:45,12.5"))
        assertEquals(PaletteIntent.Position(-12.0, 30.0, PositionUnit.CENTRE), parsePaletteIntent("pos:-12,30"))
        assertEquals(PaletteIntent.Position(13.0, -128.0), parsePaletteIntent(" POS: 12.6 , -400 "))
        assertEquals("pos:127,-128", PaletteIntent.Position(300.0, -300.0).serialize())
        assertEquals("pos:3,-2", PaletteIntent.Position(2.6, -2.4).serialize())
    }

    @Test
    fun `a percent is clamped to nought-to-a-hundred on the way in`() {
        assertEquals(PaletteIntent.Percent(100.0), parsePaletteIntent("pct:180"))
        assertEquals(PaletteIntent.Percent(0.0), parsePaletteIntent("pct:-20"))
    }

    @Test
    fun `a level is clamped to a byte on the way in`() {
        assertEquals(PaletteIntent.Level(255), parsePaletteIntent("dmx:400"))
        assertEquals(PaletteIntent.Level(0), parsePaletteIntent("dmx:-20"))
    }

    @Test
    fun `the three emitters are colour, and take a level`() {
        // Mirrors `AttributeCategory.WHITE.family()`: emitters of the same mixed colour, so a
        // template naming a hex *and* an amber is still one family and still one named thing.
        assertEquals(
            listOf(PaletteAttribute.WHITE, PaletteAttribute.AMBER, PaletteAttribute.UV),
            PaletteAttribute.EMITTERS,
        )
        for (emitter in PaletteAttribute.EMITTERS) {
            assertEquals(AttributeFamily.COLOUR, emitter.family, emitter.name)
            assertTrue(emitter.isEmitter, emitter.name)
            assertTrue(emitter.accepts(PaletteIntent.Level(1)), emitter.name)
            // Not a percentage, unlike every other continuous role — an emitter has no range of its
            // own to be a proportion of, and the operator is matching a colour by eye.
            assertTrue(!emitter.accepts(PaletteIntent.Percent(50.0)), emitter.name)
            assertEquals(emitter, PaletteAttribute.ofOrNull(emitter.attribute), emitter.name)
        }
        // The names the desk already drives them by — pinned against the rig by
        // `BundledEmitterNamesTest`, and relied on here.
        assertEquals(
            listOf("white", "amber", "uv"),
            PaletteAttribute.EMITTERS.map { it.attribute },
        )
    }

    @Test
    fun `the attribute vocabulary is closed, and that is where slotted roles are refused`() {
        assertEquals(PaletteAttribute.COLOUR, PaletteAttribute.ofOrNull("rgbColour"))
        // `canonicalAttributeName` collapses the alias's three spellings onto `rgbColour`, so all
        // three name the one vocabulary entry.
        assertEquals(PaletteAttribute.COLOUR, PaletteAttribute.ofOrNull("colour"))
        assertEquals(PaletteAttribute.COLOUR, PaletteAttribute.ofOrNull("color"))
        assertEquals(PaletteAttribute.POSITION, PaletteAttribute.ofOrNull("position"))
        assertEquals(PaletteAttribute.DIMMER, PaletteAttribute.ofOrNull("dimmer"))

        // "Gobo 3" is a different pattern on every model, so a template cannot carry one.
        assertNull(PaletteAttribute.ofOrNull("gobo"))
        assertNull(PaletteAttribute.ofOrNull("goboRotation"))
        assertNull(PaletteAttribute.ofOrNull("ledMacro"))
        // And a misspelling is refused too, rather than stored and silently resolving to nothing.
        assertNull(PaletteAttribute.ofOrNull("dimer"))
    }

    @Test
    fun `strobe is intensity, not beam`() {
        // Mirrors `AttributeCategory.STROBE.family()`. A template holding a level and a strobe is
        // one family, which is what lets both live in the same template.
        assertEquals(AttributeFamily.INTENSITY, PaletteAttribute.STROBE.family)
        assertEquals(AttributeFamily.INTENSITY, PaletteAttribute.DIMMER.family)
        assertEquals(
            setOf(PaletteAttribute.DIMMER, PaletteAttribute.STROBE),
            PaletteAttribute.forFamily(AttributeFamily.INTENSITY).toSet(),
        )
    }

    @Test
    fun `an attribute only accepts the intent shape it is for`() {
        assertTrue(PaletteAttribute.COLOUR.accepts(PaletteIntent.Colour("#ffffff")))
        assertTrue(!PaletteAttribute.COLOUR.accepts(PaletteIntent.Percent(50.0)))
        assertTrue(PaletteAttribute.POSITION.accepts(PaletteIntent.Position(0.0, 0.0)))
        assertTrue(!PaletteAttribute.POSITION.accepts(PaletteIntent.Percent(50.0)))
        assertTrue(PaletteAttribute.PRISM.accepts(PaletteIntent.Switch(true)))
        assertTrue(!PaletteAttribute.PRISM.accepts(PaletteIntent.Percent(50.0)))
        assertTrue(PaletteAttribute.ZOOM.accepts(PaletteIntent.Percent(14.0)))
        assertIs<PaletteIntent.Percent>(parsePaletteIntent("pct:14"))
    }
}

/** Local alias so the literal-parser assertion above reads without a second import line. */
private typealias AttributeCategoryForTest = uk.me.cormack.lighting7.fixture.AttributeCategory
