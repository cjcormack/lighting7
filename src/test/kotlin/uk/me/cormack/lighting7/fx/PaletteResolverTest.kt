package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.China2CellLedBlinderFixture
import uk.me.cormack.lighting7.fixture.dmx.HazerFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.FittedSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PaletteResolver] against the **real fixture classes in this rig**, not synthetic ones.
 *
 * That is the point of the tests rather than an incidental choice: a template's promise is "this
 * works on any head that can do the thing", and the only way to know whether it does is to resolve
 * it against a MAC 250's colour *wheel*, a Hex's RGBWA emitters and a Shehds' annotated pan range.
 * A hand-built fixture would let the resolver agree with a fiction.
 */
class PaletteResolverTest {

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
        PaletteIntent.Colour(hex, policy)

    // ─── Colour ─────────────────────────────────────────────────────────

    @Test
    fun `extract moves the neutral component into white and takes it out of RGB`() {
        // #FF9D4A is (255, 157, 74), so the neutral part is 74. Extract is what makes the result
        // brighter and cleaner at the same hue, which is why it is the default for a wash.
        val r = PaletteResolver.resolve(hex(), "rgbColour", colour("#FF9D4A", WhitePolicy.EXTRACT), media = null)
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
        assertEquals(PaletteResolver.Note.Exact, r.note)
    }

    @Test
    fun `additive drives the emitter alongside RGB rather than instead of part of it`() {
        val r = PaletteResolver.resolve(hex(), "rgbColour", colour("#FF9D4A", WhitePolicy.ADDITIVE), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Colour>(r.value)
        assertEquals(255, value.value.color.red, "RGB is untouched")
        assertEquals(157, value.value.color.green)
        assertEquals(74, value.value.color.blue)
        assertEquals(74u.toUByte(), value.value.white)
    }

    @Test
    fun `RGB only leaves every extra emitter at zero`() {
        val r = PaletteResolver.resolve(hex(), "rgbColour", colour("#FF9D4A", WhitePolicy.RGB_ONLY), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Colour>(r.value)
        assertEquals(255, value.value.color.red)
        assertEquals(74, value.value.color.blue)
        assertEquals(0u.toUByte(), value.value.white)
        assertEquals(0u.toUByte(), value.value.amber)
        assertEquals(PaletteResolver.Note.Exact, r.note)
    }

    @Test
    fun `a colour wheel snaps to its nearest annotated slot and reports how close it got`() {
        // The case `BeamColour.dc.html` draws as "PAR 64 — wheel only · Slot 4 — nearest · ΔE 6.2",
        // and the reason the ΔE has to come from here rather than from the editor: an operator has
        // to be able to read "this is roughly amber" before saving, and only one implementation can
        // be right about it.
        val r = PaletteResolver.resolve(mac(), "rgbColour", colour("#FFA500", WhitePolicy.EXTRACT), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Setting>(r.value)
        assertEquals(MartinMac250Fixture.Colour.ORANGE.level, value.channelValue)
        val note = assertIs<PaletteResolver.Note.Snapped>(r.note)
        assertEquals("ORANGE", note.slot)
        assertTrue(note.deltaE < 1.0, "an exact match against the slot's own preview: ${note.deltaE}")

        // And the value lands on the property that actually carries the colour on this head — the
        // wheel, not the `rgbColour` the template names and this head does not have.
        assertEquals("colourWheel", r.propertyName)
    }

    /** Two warm/cold cells under a master dimmer, and no RGB anywhere. */
    private fun blinder() = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", firstChannel = 120)

    @Test
    fun `a warm-cold cell takes a colour as its nearest mix and names the mix's temperature`() {
        val cell = blinder().cell(0)
        val r = PaletteResolver.resolve(cell, "rgbColour", colour("#FF0000", WhitePolicy.EXTRACT), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Colour>(r.value)
        // The colour the mix makes, not the red asked for: what the cell will read back as.
        assertEquals(China2CellLedBlinderFixture.WHITE_MIX.colourOf(255u, 0u), value.value.color)
        val note = assertIs<PaletteResolver.Note.Snapped>(r.note)
        assertEquals("3000 K", note.slot)
        assertTrue(note.deltaE > 10.0, "red is far from any white: ${note.deltaE}")
        assertEquals("rgbColour", r.propertyName)
    }

    @Test
    fun `a colour a warm-cold cell can make lands exactly`() {
        val made = China2CellLedBlinderFixture.WHITE_MIX.colourOf(200u, 90u)
        val hex = "#%02x%02x%02x".format(made.red, made.green, made.blue)
        val r = PaletteResolver.resolve(blinder().cell(1), "rgbColour", colour(hex, WhitePolicy.EXTRACT), media = null)
        assertEquals(PaletteResolver.Note.Exact, r.note)
        val writes = PropertyChannelWriter.resolve(blinder().cell(1), r.propertyName, assertNotNull(r.value))
        val levels = writes.map { it.channel to it.value.toInt() }
        assertEquals(126, levels[0].first)
        assertEquals(127, levels[1].first)
        assertTrue(kotlin.math.abs(levels[0].second - 200) <= 2 && kotlin.math.abs(levels[1].second - 90) <= 2, "$levels")
    }

    @Test
    fun `a blinder's colour lives on its cells, so the parent has none and its cells fan`() {
        val fixture = blinder()
        assertFalse(PaletteResolver.hasColour(fixture, media = null))
        assertTrue(PaletteResolver.hasColour(fixture.cell(0), media = null))
        assertEquals(
            listOf("blinder-1.cell-1", "blinder-1.cell-2"),
            PaletteFanOut.headsFor(fixture, "rgbColour") { null }.map { it.targetKey },
        )
        // The master dimmer is the parent's own, so a dimmer row stays there.
        assertEquals(listOf("blinder-1"), PaletteFanOut.headsFor(fixture, "dimmer") { null }.map { it.targetKey })
    }

    @Test
    fun `a warm-cold cell's colour is its two white channels, each still covered by its own slider`() {
        val fixture = blinder()
        val channels = PropertyChannelWriter.channelsFor(fixture.cell(0), "rgbColour").map { it.channel }
        assertEquals(listOf(124, 125), channels)
        val covering = PropertyChannelWriter.coveringKeysByChannel(listOf(fixture))
        assertEquals("warmWhite", covering[0 to 124]?.propertyName)
        assertEquals("coldWhite", covering[0 to 125]?.propertyName)
        val all = PropertyChannelWriter.propertyKeysByChannel(listOf(fixture))[0 to 124].orEmpty().map { it.propertyName }
        assertEquals(setOf("warmWhite", "rgbColour"), all.toSet())
    }

    @Test
    fun `a scroller snaps against the unit's fitted colours rather than the stock string`() {
        // R26 Light Red is not on ETC's stock string. Fitted where frame 9 holds L201, the snap lands
        // on frame 9 exactly; on a unit with nothing fitted it lands on whatever stock frame is nearest.
        val rev = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 100)
        val red = colour("#ee5b5e", WhitePolicy.RGB_ONLY)
        val stock = PaletteResolver.resolve(rev, "rgbColour", red, media = null)
        val stockValue = assertIs<CueAssignmentResolver.PropertyValue.Setting>(stock.value)
        assertTrue(stockValue.channelValue != Source4RevolutionFixture.GelFrame.L201_FULL_CT_BLUE.level, "stock frame 9 is blue")
        assertTrue(assertIs<PaletteResolver.Note.Snapped>(stock.note).deltaE > 0.0)

        val fitted = FittedMedia(mapOf("gelScroller" to mapOf("L201_FULL_CT_BLUE" to FittedSlot(gel = "R26"))))
        val r = PaletteResolver.resolve(rev, "rgbColour", red, media = fitted)
        assertEquals("gelScroller", r.propertyName)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Setting>(r.value)
        assertEquals(Source4RevolutionFixture.GelFrame.L201_FULL_CT_BLUE.level, value.channelValue)
        val note = assertIs<PaletteResolver.Note.Snapped>(r.note)
        assertEquals("L201_FULL_CT_BLUE (R26)", note.slot)
        assertEquals(0.0, note.deltaE, 1e-9)

        // A unit whose frame 9 is empty: L201's blue is no longer on it, so a request for L201 lands elsewhere.
        val empty = FittedMedia(mapOf("gelScroller" to mapOf("L201_FULL_CT_BLUE" to FittedSlot())))
        val blue = PaletteResolver.resolve(rev, "rgbColour", colour("#9bbede", WhitePolicy.RGB_ONLY), media = empty)
        assertTrue(assertIs<CueAssignmentResolver.PropertyValue.Setting>(blue.value).channelValue != Source4RevolutionFixture.GelFrame.L201_FULL_CT_BLUE.level)
    }

    @Test
    fun `a wheel snap names a nearby slot rather than refusing an inexact colour`() {
        // #FF9D4A (amber) has no slot on this wheel. Snapping to the nearest with a stated ΔE is the
        // honest answer; refusing would drop the head out of a template that is meant to work on
        // anything with colour.
        val r = PaletteResolver.resolve(mac(), "rgbColour", colour("#FF9D4A", WhitePolicy.EXTRACT), media = null)
        val note = assertIs<PaletteResolver.Note.Snapped>(r.note)
        assertNotNull(r.value)
        assertTrue(note.deltaE > 0.0, "an approximation should say so")
    }

    @Test
    fun `a head with no colour at all resolves to nothing`() {
        val r = PaletteResolver.resolve(hazer(), "rgbColour", colour("#FF9D4A", WhitePolicy.EXTRACT), media = null)
        assertNull(r.value)
        assertEquals(PaletteResolver.Note.Unsupported("no colour"), r.note)
    }

    // ─── Emitters set outright ──────────────────────────────────────────

    @Test
    fun `an emitter level is written straight at the head's own slider`() {
        // A byte, not a percentage — the one literal in the grammar. A Hex has all three.
        for ((property, expected) in listOf("white" to 180, "amber" to 64, "uv" to 255)) {
            val r = PaletteResolver.resolve(hex(), property, PaletteIntent.Level(expected), media = null)
            val value = assertIs<CueAssignmentResolver.PropertyValue.Slider>(r.value, property)
            assertEquals(expected.toUByte(), value.value, property)
            assertEquals(PaletteResolver.Note.Exact, r.note, property)
            // The resolved name is the row's: an emitter is not aliased the way colour is.
            assertEquals(property, r.propertyName, property)
        }
    }

    @Test
    fun `a head without that emitter says so, and that is the capability check`() {
        // Nothing else probes a head for an emitter — `unmetColourRequirement` is a fold over this,
        // and the reason string is what an apply skip carries and the resolves-to panel prints.
        val r = PaletteResolver.resolve(mover(), "amber", PaletteIntent.Level(200), media = null)
        assertNull(r.value, "the Shehds mover is RGBW — no amber")
        assertEquals(PaletteResolver.Note.Unsupported("no amber"), r.note)

        val uv = PaletteResolver.resolve(mover(), "uv", PaletteIntent.Level(200), media = null)
        assertNull(uv.value)
        assertEquals(PaletteResolver.Note.Unsupported("no uv"), uv.note)
    }

    // ─── The whole-template colour rule ─────────────────────────────────

    @Test
    fun `a head serving every colour row has no unmet requirement`() {
        val rows = listOf(
            "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            "white" to PaletteIntent.Level(180),
            "uv" to PaletteIntent.Level(255),
        )
        assertEquals(
            PaletteResolver.ColourRequirement.Met,
            PaletteResolver.unmetColourRequirement(hex(), rows, media = null),
        )
    }

    @Test
    fun `a head missing one named emitter cannot serve any of the colour rows`() {
        // The rule, and the reason for it: an explicit amber is in the template because the hex
        // alone did not get where the operator wanted, so a head without amber taking just the hex
        // would put a *different* colour on stage under this template's name.
        val rows = listOf(
            "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            "amber" to PaletteIntent.Level(200),
        )
        assertEquals(
            PaletteResolver.ColourRequirement.Unmet("no amber", "amber"),
            PaletteResolver.unmetColourRequirement(mover(), rows, media = null),
        )
    }

    @Test
    fun `a head with no colour at all is not a candidate, whatever the row order`() {
        // The distinction the editor's panel turns on, and it must not depend on which row was
        // authored first: a short-circuit over the stored order answered "no uv" for a hazer when
        // the emitter row sorted before the hex, which the panel then *listed* instead of omitting.
        val withHexFirst = listOf(
            "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
            "uv" to PaletteIntent.Level(255),
        )
        assertEquals(
            PaletteResolver.ColourRequirement.NotACandidate("no colour"),
            PaletteResolver.unmetColourRequirement(hazer(), withHexFirst, media = null),
        )
        assertEquals(
            PaletteResolver.ColourRequirement.NotACandidate("no colour"),
            PaletteResolver.unmetColourRequirement(hazer(), withHexFirst.reversed(), media = null),
        )
        // And with no colour row at all to infer it from — an emitter-only template.
        assertEquals(
            PaletteResolver.ColourRequirement.NotACandidate("no colour"),
            PaletteResolver.unmetColourRequirement(hazer(), listOf("uv" to PaletteIntent.Level(255)), media = null),
        )
    }

    @Test
    fun `the rule is colour only — other families keep their per-row skip`() {
        // A beam template naming zoom and frost on a head with one of them should still set the one.
        // Scoped deliberately: independent roles, unlike the facets of one colour.
        val rows = listOf(
            "zoom" to PaletteIntent.Percent(50.0),
            "frost" to PaletteIntent.Percent(50.0),
            "position" to PaletteIntent.Position(0.0, 0.0),
        )
        assertEquals(
            PaletteResolver.ColourRequirement.Met,
            PaletteResolver.unmetColourRequirement(hazer(), rows, media = null),
        )
    }

    @Test
    fun `a plain hex template still reaches a head with no emitters at all`() {
        // `FU-MANUAL-DESK-S3` check 1: one colour template across an RGBWA hex, a white-only head
        // and a colour wheel. Naming no emitter must keep degrading rather than refusing, or the
        // whole-template rule would have narrowed the feature it was added to.
        val rows = listOf("rgbColour" to colour("#FF9D4A", WhitePolicy.EXTRACT))
        assertEquals(
            PaletteResolver.ColourRequirement.Met,
            PaletteResolver.unmetColourRequirement(mover(), rows, media = null),
        )
        assertEquals(
            PaletteResolver.ColourRequirement.Met,
            PaletteResolver.unmetColourRequirement(mac(), rows, media = null),
        )
    }

    // ─── The fixture-free reading ───────────────────────────────────────

    @Test
    fun `a generic resolve folds the emitter rows into the one colour`() {
        // An FX colour parameter gets one `ExtendedColour` for every head it targets, so an emitter
        // row has to land here or it would simply not happen.
        val resolved = assertNotNull(
            PaletteResolver.resolveColourGeneric(
                listOf(
                    "rgbColour" to colour("#FF9D4A", WhitePolicy.RGB_ONLY),
                    "amber" to PaletteIntent.Level(200),
                    "uv" to PaletteIntent.Level(64),
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
            PaletteResolver.resolveColourGeneric(
                listOf(
                    "rgbColour" to colour("#FF9D4A", WhitePolicy.EXTRACT),
                    "white" to PaletteIntent.Level(10),
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
            PaletteResolver.resolveColourGeneric(listOf("uv" to PaletteIntent.Level(255))),
        )
        assertEquals(0, resolved.color.red)
        assertEquals(0, resolved.color.green)
        assertEquals(0, resolved.color.blue)
        assertEquals(255u.toUByte(), resolved.uv)
        // Nothing colour-ish at all is null, so a caller falls through to its literal parser.
        assertNull(PaletteResolver.resolveColourGeneric(listOf("dimmer" to PaletteIntent.Percent(50.0))))
    }

    // ─── Intensity ──────────────────────────────────────────────────────

    @Test
    fun `a level is a percentage of the head's own dimmer range`() {
        val r = PaletteResolver.resolve(hex(), "dimmer", PaletteIntent.Percent(75.0), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Slider>(r.value)
        assertEquals(191u.toUByte(), value.value, "75% of 0..255")
        assertEquals(PaletteResolver.Note.Exact, r.note)
    }

    @Test
    fun `full and zero land exactly on the ends`() {
        assertEquals(
            255u.toUByte(),
            assertIs<CueAssignmentResolver.PropertyValue.Slider>(
                PaletteResolver.resolve(hex(), "dimmer", PaletteIntent.Percent(100.0), media = null).value,
            ).value,
        )
        assertEquals(
            0u.toUByte(),
            assertIs<CueAssignmentResolver.PropertyValue.Slider>(
                PaletteResolver.resolve(hex(), "dimmer", PaletteIntent.Percent(0.0), media = null).value,
            ).value,
        )
    }

    @Test
    fun `a dimmerless head reports why rather than being given a virtual dimmer`() {
        // `BeamColour.dc.html` promises the "existing virtual-dimmer path" here. There is no such
        // path on this backend — the only virtual dimmer is a *group* gesture the client fans out —
        // so the resolver says so and the editor's panel shows it. Recorded as a follow-up rather
        // than invented inside a resolver.
        val r = PaletteResolver.resolve(hazer(), "dimmer", PaletteIntent.Percent(75.0), media = null)
        assertNull(r.value)
        assertIs<PaletteResolver.Note.Unsupported>(r.note)
    }

    // ─── Position ───────────────────────────────────────────────────────

    @Test
    fun `a centre-relative position is the same fraction of travel on every head`() {
        // D3: `pos:` is coarse DMX about the centre of travel, so a generic palette lands on the
        // same bytes on two heads whose tilt ranges differ — the opposite of the degree arm below.
        val intent = PaletteIntent.Position(-20.0, 40.0)
        val onMover = assertIs<CueAssignmentResolver.PropertyValue.Position>(
            PaletteResolver.resolve(mover(), "position", intent, media = null).value,
        )
        val onMac = assertIs<CueAssignmentResolver.PropertyValue.Position>(
            PaletteResolver.resolve(mac(), "position", intent, media = null).value,
        )
        assertEquals(108u.toUByte(), onMover.pan, "128 - 20")
        assertEquals(168u.toUByte(), onMover.tilt, "128 + 40")
        assertEquals(onMover, onMac)
    }

    @Test
    fun `the centre scale meets both ends of travel exactly`() {
        fun on(pan: Double, tilt: Double) = assertIs<CueAssignmentResolver.PropertyValue.Position>(
            PaletteResolver.resolve(mover(), "position", PaletteIntent.Position(pan, tilt), media = null).value,
        )
        assertEquals(CueAssignmentResolver.PropertyValue.Position(128u, 128u), on(0.0, 0.0), "0 is DMX 128")
        assertEquals(CueAssignmentResolver.PropertyValue.Position(0u, 255u), on(-128.0, 127.0))
        assertEquals(CueAssignmentResolver.PropertyValue.Position(1u, 129u), on(-127.0, 1.0), "one unit a step")
    }

    @Test
    fun `a centre-relative value past the scale is clamped and reported in the scale`() {
        val r = PaletteResolver.resolve(mover(), "position", PaletteIntent.Position(200.0, 0.0), media = null)
        assertEquals(255u.toUByte(), assertIs<CueAssignmentResolver.PropertyValue.Position>(r.value).pan)
        val note = assertIs<PaletteResolver.Note.Clamped>(r.note)
        assertEquals("pan +127", note.to)
    }

    @Test
    fun `centreIntentOf is the exact inverse of the centre arm`() {
        for (pan in listOf(0, 1, 64, 127, 128, 200, 255)) {
            val bytes = CueAssignmentResolver.PropertyValue.Position(pan.toUByte(), (255 - pan).toUByte())
            val intent = assertNotNull(PaletteResolver.centreIntentOf(mover(), bytes))
            assertEquals("pos:${pan - 128},${127 - pan}", intent.serialize())
            assertEquals(bytes, PaletteResolver.resolve(mover(), "position", intent, media = null).value)
        }
        assertNull(PaletteResolver.centreIntentOf(hex(), CueAssignmentResolver.PropertyValue.Position(1u, 1u)))
    }

    @Test
    fun `degrees resolve through each head's own annotated range`() {
        // Stored `deg:` rows keep resolving as angles (venue-visit plan P3). The mover's pan is
        // 0–540° and its tilt 0–270°, so the same degrees land on *different* DMX values per axis.
        val r = PaletteResolver.resolve(mover(), "position", PaletteIntent.Position(270.0, 135.0, PositionUnit.DEGREES), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Position>(r.value)
        assertEquals(128u.toUByte(), value.pan, "half of 540°")
        assertEquals(128u.toUByte(), value.tilt, "half of 270°")
        assertEquals("position", r.propertyName)
        assertEquals(PaletteResolver.Note.Exact, r.note)
    }

    @Test
    fun `the same degrees land differently on two heads with different ranges`() {
        // A MAC 250 tilts 0–257°, a Shehds 0–270°. 128° is past the middle of one and short of the
        // other, and a template that stored DMX could not express the difference at all.
        val onMover = assertIs<CueAssignmentResolver.PropertyValue.Position>(
            PaletteResolver.resolve(mover(), "position", PaletteIntent.Position(90.0, 128.0, PositionUnit.DEGREES), media = null).value,
        )
        val onMac = assertIs<CueAssignmentResolver.PropertyValue.Position>(
            PaletteResolver.resolve(mac(), "position", PaletteIntent.Position(90.0, 128.0, PositionUnit.DEGREES), media = null).value,
        )
        assertEquals(onMover.pan, onMac.pan, "both pan 0–540°, so pan agrees")
        assertTrue(onMover.tilt != onMac.tilt, "but the tilt ranges differ, so the DMX must too")
    }

    @Test
    fun `out of range degrees are clamped and the clamp is reported`() {
        // Reported, not silent: an operator pointing a template at a head that cannot reach the spot
        // needs to know it is aimed somewhere else, which is exactly what the panel shows.
        val r = PaletteResolver.resolve(mover(), "position", PaletteIntent.Position(700.0, 135.0, PositionUnit.DEGREES), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Position>(r.value)
        assertEquals(255u.toUByte(), value.pan)
        val note = assertIs<PaletteResolver.Note.Clamped>(r.note)
        assertTrue(note.to.contains("pan"), note.to)
    }

    @Test
    fun `a fixed head takes no position at all`() {
        for (unit in PositionUnit.entries) {
            val r = PaletteResolver.resolve(hex(), "position", PaletteIntent.Position(45.0, 12.0, unit), media = null)
            assertNull(r.value)
            assertIs<PaletteResolver.Note.Unsupported>(r.note)
        }
    }

    // ─── Beam ───────────────────────────────────────────────────────────

    @Test
    fun `a continuous beam role is a percentage of the head's own range`() {
        val r = PaletteResolver.resolve(mac(), "focus", PaletteIntent.Percent(70.0), media = null)
        val value = assertIs<CueAssignmentResolver.PropertyValue.Slider>(r.value)
        assertEquals(179u.toUByte(), value.value, "70% of 0..255")
    }

    @Test
    fun `prism resolves to a wheel slot on a head whose prism is a wheel`() {
        val on = PaletteResolver.resolve(mac(), "prism", PaletteIntent.Switch(true), media = null)
        assertNotNull(on.value)
        val off = PaletteResolver.resolve(mac(), "prism", PaletteIntent.Switch(false), media = null)
        assertNotNull(off.value)
        assertTrue(on.value != off.value, "on and off must not be the same slot")
    }

    @Test
    fun `a head without the beam role reports it rather than resolving to zero`() {
        val r = PaletteResolver.resolve(hex(), "zoom", PaletteIntent.Percent(14.0), media = null)
        assertNull(r.value)
        assertIs<PaletteResolver.Note.Unsupported>(r.note)
    }
}
