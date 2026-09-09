package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HazerFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TemplateResolver] against the **real fixture classes in this rig**, not synthetic ones.
 *
 * That is the point of the tests rather than an incidental choice: a template's promise is "this
 * works on any head that can do the thing", and the only way to know whether it does is to resolve
 * it against a MAC 250's colour *wheel*, a Hex's RGBWA emitters and a Shehds' annotated pan range.
 * A hand-built fixture would let the resolver agree with a fiction.
 */
class TemplateResolverTest {

    private val universe = Universe(0, 0)

    /** RGB + white + amber + UV + dimmer + strobe. */
    private fun hex() = HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1)

    /** RGB + white (no amber), a dimmer, and pan 0–540° / tilt 0–270°. */
    private fun mover() = ShehdsLed19RgbwFixture.Mode16Ch(universe, "mover-1", "Mover 1", firstChannel = 20)

    /** A colour **wheel**, a dimmer, focus, a prism wheel, pan 0–540° / tilt 0–257°. */
    private fun mac() = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", firstChannel = 60)

    /** No colour, no dimmer, no position — the "nothing to resolve against" case. */
    private fun hazer() = HazerFixture(universe, "haze-1", "Hazer", firstChannel = 90)

    private fun colour(hex: String, policy: WhitePolicy) =
        TemplateIntent.Colour(hex, policy)

    // ─── Colour ─────────────────────────────────────────────────────────

    @Test
    fun `extract moves the neutral component into white and takes it out of RGB`() {
        // #FF9D4A is (255, 157, 74), so the neutral part is 74. Extract is what makes the result
        // brighter and cleaner at the same hue, which is why it is the default for a wash.
        val r = TemplateResolver.resolve(hex(), "rgbColour", colour("#FF9D4A", WhitePolicy.EXTRACT))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Colour>(r.value)
        assertEquals(255 - 74, value.value.color.red)
        assertEquals(157 - 74, value.value.color.green)
        assertEquals(0, value.value.color.blue)
        assertEquals(74u.toUByte(), value.value.white)
        // Amber stays out of it: extract moves the *neutral* component, and amber is not neutral —
        // driving it would shift the hue warm, which is a different trick needing a colour fit.
        assertEquals(0u.toUByte(), value.value.amber)
        // UV is never part of a colour match under any policy.
        assertEquals(0u.toUByte(), value.value.uv)
        assertEquals(TemplateResolver.Note.Exact, r.note)
    }

    @Test
    fun `additive drives the emitter alongside RGB rather than instead of part of it`() {
        val r = TemplateResolver.resolve(hex(), "rgbColour", colour("#FF9D4A", WhitePolicy.ADDITIVE))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Colour>(r.value)
        assertEquals(255, value.value.color.red, "RGB is untouched")
        assertEquals(157, value.value.color.green)
        assertEquals(74, value.value.color.blue)
        assertEquals(74u.toUByte(), value.value.white)
    }

    @Test
    fun `RGB only leaves every extra emitter at zero`() {
        val r = TemplateResolver.resolve(hex(), "rgbColour", colour("#FF9D4A", WhitePolicy.RGB_ONLY))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Colour>(r.value)
        assertEquals(255, value.value.color.red)
        assertEquals(74, value.value.color.blue)
        assertEquals(0u.toUByte(), value.value.white)
        assertEquals(0u.toUByte(), value.value.amber)
        assertEquals(TemplateResolver.Note.Exact, r.note)
    }

    @Test
    fun `a colour wheel snaps to its nearest annotated slot and reports how close it got`() {
        // The case `BeamColour.dc.html` draws as "PAR 64 — wheel only · Slot 4 — nearest · ΔE 6.2",
        // and the reason the ΔE has to come from here rather than from the editor: an operator has
        // to be able to read "this is roughly amber" before saving, and only one implementation can
        // be right about it.
        val r = TemplateResolver.resolve(mac(), "rgbColour", colour("#FFA500", WhitePolicy.EXTRACT))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Setting>(r.value)
        assertEquals(MartinMac250Fixture.Colour.ORANGE.level, value.channelValue)
        val note = assertIs<TemplateResolver.Note.Snapped>(r.note)
        assertEquals("ORANGE", note.slot)
        assertTrue(note.deltaE < 1.0, "an exact match against the slot's own preview: ${note.deltaE}")

        // And the value lands on the property that actually carries the colour on this head — the
        // wheel is called `colour`, which `canonicalPropertyName` rewrites to `rgbColour` and would
        // then miss entirely.
        assertEquals("colour", r.propertyName)
    }

    @Test
    fun `a wheel snap names a nearby slot rather than refusing an inexact colour`() {
        // #FF9D4A (amber) has no slot on this wheel. Snapping to the nearest with a stated ΔE is the
        // honest answer; refusing would drop the head out of a template that is meant to work on
        // anything with colour.
        val r = TemplateResolver.resolve(mac(), "rgbColour", colour("#FF9D4A", WhitePolicy.EXTRACT))
        val note = assertIs<TemplateResolver.Note.Snapped>(r.note)
        assertNotNull(r.value)
        assertTrue(note.deltaE > 0.0, "an approximation should say so")
    }

    @Test
    fun `a head with no colour at all resolves to nothing`() {
        val r = TemplateResolver.resolve(hazer(), "rgbColour", colour("#FF9D4A", WhitePolicy.EXTRACT))
        assertNull(r.value)
        assertEquals(TemplateResolver.Note.Unsupported("no colour"), r.note)
    }

    // ─── Emitters set outright ──────────────────────────────────────────

    @Test
    fun `an emitter level is written straight at the head's own slider`() {
        // A byte, not a percentage — the one literal in the grammar. A Hex has all three.
        for ((property, expected) in listOf("white" to 180, "amber" to 64, "uv" to 255)) {
            val r = TemplateResolver.resolve(hex(), property, TemplateIntent.Level(expected))
            val value = assertIs<CueAssignmentResolver.PropertyValue.Slider>(r.value, property)
            assertEquals(expected.toUByte(), value.value, property)
            assertEquals(TemplateResolver.Note.Exact, r.note, property)
            // The resolved name is the row's: an emitter is not aliased the way colour is.
            assertEquals(property, r.propertyName, property)
        }
    }

    @Test
    fun `a head without that emitter says so, and that is the capability check`() {
        // Nothing else probes a head for an emitter — `unmetColourRequirement` is a fold over this,
        // and the reason string is what an apply skip carries and the resolves-to panel prints.
        val r = TemplateResolver.resolve(mover(), "amber", TemplateIntent.Level(200))
        assertNull(r.value, "the Shehds mover is RGBW — no amber")
        assertEquals(TemplateResolver.Note.Unsupported("no amber"), r.note)

        val uv = TemplateResolver.resolve(mover(), "uv", TemplateIntent.Level(200))
        assertNull(uv.value)
        assertEquals(TemplateResolver.Note.Unsupported("no uv"), uv.note)
    }

    // ─── The whole-template colour rule ─────────────────────────────────

    @Test
    fun `a head serving every colour row has no unmet requirement`() {
        val rows = listOf(
            "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            "white" to TemplateIntent.Level(180),
            "uv" to TemplateIntent.Level(255),
        )
        assertEquals(
            TemplateResolver.ColourRequirement.Met,
            TemplateResolver.unmetColourRequirement(hex(), rows),
        )
    }

    @Test
    fun `a head missing one named emitter cannot serve any of the colour rows`() {
        // The rule, and the reason for it: an explicit amber is in the template because the hex
        // alone did not get where the operator wanted, so a head without amber taking just the hex
        // would put a *different* colour on stage under this template's name.
        val rows = listOf(
            "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            "amber" to TemplateIntent.Level(200),
        )
        assertEquals(
            TemplateResolver.ColourRequirement.Unmet("no amber", "amber"),
            TemplateResolver.unmetColourRequirement(mover(), rows),
        )
    }

    @Test
    fun `a head with no colour at all is not a candidate, whatever the row order`() {
        // The distinction the editor's panel turns on, and it must not depend on which row was
        // authored first: a short-circuit over the stored order answered "no uv" for a hazer when
        // the emitter row sorted before the hex, which the panel then *listed* instead of omitting.
        val withHexFirst = listOf(
            "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            "uv" to TemplateIntent.Level(255),
        )
        assertEquals(
            TemplateResolver.ColourRequirement.NotACandidate("no colour"),
            TemplateResolver.unmetColourRequirement(hazer(), withHexFirst),
        )
        assertEquals(
            TemplateResolver.ColourRequirement.NotACandidate("no colour"),
            TemplateResolver.unmetColourRequirement(hazer(), withHexFirst.reversed()),
        )
        // And with no colour row at all to infer it from — an emitter-only template.
        assertEquals(
            TemplateResolver.ColourRequirement.NotACandidate("no colour"),
            TemplateResolver.unmetColourRequirement(hazer(), listOf("uv" to TemplateIntent.Level(255))),
        )
    }

    @Test
    fun `the rule is colour only — other families keep their per-row skip`() {
        // A beam template naming zoom and frost on a head with one of them should still set the one.
        // Scoped deliberately: independent roles, unlike the facets of one colour.
        val rows = listOf(
            "zoom" to TemplateIntent.Percent(50.0),
            "frost" to TemplateIntent.Percent(50.0),
            "position" to TemplateIntent.Position(0.0, 0.0),
        )
        assertEquals(
            TemplateResolver.ColourRequirement.Met,
            TemplateResolver.unmetColourRequirement(hazer(), rows),
        )
    }

    @Test
    fun `a plain hex template still reaches a head with no emitters at all`() {
        // `FU-MANUAL-DESK-S3` check 1: one colour template across an RGBWA hex, a white-only head
        // and a colour wheel. Naming no emitter must keep degrading rather than refusing, or the
        // whole-template rule would have narrowed the feature it was added to.
        val rows = listOf("rgbColour" to colour("#FF9D4A", WhitePolicy.EXTRACT))
        assertEquals(
            TemplateResolver.ColourRequirement.Met,
            TemplateResolver.unmetColourRequirement(mover(), rows),
        )
        assertEquals(
            TemplateResolver.ColourRequirement.Met,
            TemplateResolver.unmetColourRequirement(mac(), rows),
        )
    }

    // ─── The fixture-free reading ───────────────────────────────────────

    @Test
    fun `a generic resolve folds the emitter rows into the one colour`() {
        // An FX colour parameter gets one `ExtendedColour` for every head it targets, so an emitter
        // row has to land here or it would simply not happen.
        val resolved = assertNotNull(
            TemplateResolver.resolveColourGeneric(
                listOf(
                    "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
                    "amber" to TemplateIntent.Level(200),
                    "uv" to TemplateIntent.Level(64),
                ),
            ),
        )
        assertEquals(255, resolved.color.red)
        assertEquals(200u.toUByte(), resolved.amber)
        assertEquals(64u.toUByte(), resolved.uv)
    }

    @Test
    fun `an explicit emitter overwrites what the policy derived`() {
        // Cannot collide today — the write boundary refuses the pair — but the precedence is stated
        // rather than left to argument order: a row an operator typed beats one the desk inferred.
        val resolved = assertNotNull(
            TemplateResolver.resolveColourGeneric(
                listOf(
                    "rgbColour" to colour("#FF9D4A", WhitePolicy.EXTRACT),
                    "white" to TemplateIntent.Level(10),
                ),
            ),
        )
        assertEquals(10u.toUByte(), resolved.white, "not the 74 extract would have derived")
    }

    @Test
    fun `an emitter-only template resolves against black`() {
        // The honest reading of what an effect output can express: one colour per frame, and no way
        // to say "leave RGB alone". The layer path keeps that distinction; a reference cannot.
        val resolved = assertNotNull(
            TemplateResolver.resolveColourGeneric(listOf("uv" to TemplateIntent.Level(255))),
        )
        assertEquals(0, resolved.color.red)
        assertEquals(0, resolved.color.green)
        assertEquals(0, resolved.color.blue)
        assertEquals(255u.toUByte(), resolved.uv)
        // Nothing colour-ish at all is null, so a caller falls through to its literal parser.
        assertNull(TemplateResolver.resolveColourGeneric(listOf("dimmer" to TemplateIntent.Percent(50.0))))
    }

    // ─── Intensity ──────────────────────────────────────────────────────

    @Test
    fun `a level is a percentage of the head's own dimmer range`() {
        val r = TemplateResolver.resolve(hex(), "dimmer", TemplateIntent.Percent(75.0))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Slider>(r.value)
        assertEquals(191u.toUByte(), value.value, "75% of 0..255")
        assertEquals(TemplateResolver.Note.Exact, r.note)
    }

    @Test
    fun `full and zero land exactly on the ends`() {
        assertEquals(
            255u.toUByte(),
            assertIs<CueAssignmentResolver.PropertyValue.Slider>(
                TemplateResolver.resolve(hex(), "dimmer", TemplateIntent.Percent(100.0)).value,
            ).value,
        )
        assertEquals(
            0u.toUByte(),
            assertIs<CueAssignmentResolver.PropertyValue.Slider>(
                TemplateResolver.resolve(hex(), "dimmer", TemplateIntent.Percent(0.0)).value,
            ).value,
        )
    }

    @Test
    fun `a dimmerless head reports why rather than being given a virtual dimmer`() {
        // `BeamColour.dc.html` promises the "existing virtual-dimmer path" here. There is no such
        // path on this backend — the only virtual dimmer is a *group* gesture the client fans out —
        // so the resolver says so and the editor's panel shows it. Recorded as a follow-up rather
        // than invented inside a resolver.
        val r = TemplateResolver.resolve(hazer(), "dimmer", TemplateIntent.Percent(75.0))
        assertNull(r.value)
        assertIs<TemplateResolver.Note.Unsupported>(r.note)
    }

    // ─── Position ───────────────────────────────────────────────────────

    @Test
    fun `degrees resolve through each head's own annotated range`() {
        // The mover's pan is 0–540° and its tilt 0–270°, so the same degrees land on *different*
        // DMX values per axis — which is the whole reason a template stores degrees rather than DMX.
        val r = TemplateResolver.resolve(mover(), "position", TemplateIntent.Position(270.0, 135.0))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Position>(r.value)
        assertEquals(128u.toUByte(), value.pan, "half of 540°")
        assertEquals(128u.toUByte(), value.tilt, "half of 270°")
        assertEquals("position", r.propertyName)
        assertEquals(TemplateResolver.Note.Exact, r.note)
    }

    @Test
    fun `the same degrees land differently on two heads with different ranges`() {
        // A MAC 250 tilts 0–257°, a Shehds 0–270°. 128° is past the middle of one and short of the
        // other, and a template that stored DMX could not express the difference at all.
        val onMover = assertIs<CueAssignmentResolver.PropertyValue.Position>(
            TemplateResolver.resolve(mover(), "position", TemplateIntent.Position(90.0, 128.0)).value,
        )
        val onMac = assertIs<CueAssignmentResolver.PropertyValue.Position>(
            TemplateResolver.resolve(mac(), "position", TemplateIntent.Position(90.0, 128.0)).value,
        )
        assertEquals(onMover.pan, onMac.pan, "both pan 0–540°, so pan agrees")
        assertTrue(onMover.tilt != onMac.tilt, "but the tilt ranges differ, so the DMX must too")
    }

    @Test
    fun `out of range degrees are clamped and the clamp is reported`() {
        // Reported, not silent: an operator pointing a template at a head that cannot reach the spot
        // needs to know it is aimed somewhere else, which is exactly what the panel shows.
        val r = TemplateResolver.resolve(mover(), "position", TemplateIntent.Position(700.0, 135.0))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Position>(r.value)
        assertEquals(255u.toUByte(), value.pan)
        val note = assertIs<TemplateResolver.Note.Clamped>(r.note)
        assertTrue(note.to.contains("pan"), note.to)
    }

    @Test
    fun `a fixed head takes no position at all`() {
        val r = TemplateResolver.resolve(hex(), "position", TemplateIntent.Position(45.0, 12.0))
        assertNull(r.value)
        assertIs<TemplateResolver.Note.Unsupported>(r.note)
    }

    // ─── Beam ───────────────────────────────────────────────────────────

    @Test
    fun `a continuous beam role is a percentage of the head's own range`() {
        val r = TemplateResolver.resolve(mac(), "focus", TemplateIntent.Percent(70.0))
        val value = assertIs<CueAssignmentResolver.PropertyValue.Slider>(r.value)
        assertEquals(179u.toUByte(), value.value, "70% of 0..255")
    }

    @Test
    fun `prism resolves to a wheel slot on a head whose prism is a wheel`() {
        val on = TemplateResolver.resolve(mac(), "prism", TemplateIntent.Switch(true))
        assertNotNull(on.value)
        val off = TemplateResolver.resolve(mac(), "prism", TemplateIntent.Switch(false))
        assertNotNull(off.value)
        assertTrue(on.value != off.value, "on and off must not be the same slot")
    }

    @Test
    fun `a head without the beam role reports it rather than resolving to zero`() {
        val r = TemplateResolver.resolve(hex(), "zoom", TemplateIntent.Percent(14.0))
        assertNull(r.value)
        assertIs<TemplateResolver.Note.Unsupported>(r.note)
    }
}
