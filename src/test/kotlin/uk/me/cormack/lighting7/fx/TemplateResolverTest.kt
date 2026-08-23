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
