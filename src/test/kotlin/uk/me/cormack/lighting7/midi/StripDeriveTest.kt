package uk.me.cormack.lighting7.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What each control on a strip behaves as. The router, the feedback index and the *Fader only…*
 * expansion all ask this one function, so these cases pin the answer for all three.
 */
class StripDeriveTest {

    private val group = Subject.Group("front-wash")
    private val fixture = Subject.Fixture("hex-1")

    @Test
    fun `fader and flash always drive dimmer, whatever the encoder bank`() {
        assertEquals(
            Binding(Does.Level("dimmer"), group),
            deriveStripBinding(StripRole.FADER, group, encoderBank = EncoderBankSelection("pan")),
        )
        assertEquals(
            Binding(Does.Flash("dimmer"), group),
            deriveStripBinding(StripRole.FLASH, group, encoderBank = EncoderBankSelection("pan")),
        )
    }

    @Test
    fun `select toggles rather than replaces, and a hold replaces`() {
        val select = deriveStripBinding(StripRole.SELECT, group, encoderBank = EncoderBankSelection("dimmer"))
        assertEquals(Binding(Does.Select, group), select)
        assertEquals(How.TOGGLE_HOLD_REPLACES, select.effectiveHow)
    }

    @Test
    fun `the encoder follows the encoder bank`() {
        assertEquals(
            Binding(Does.Level("dimmer"), group),
            deriveStripBinding(StripRole.ENCODER, group, encoderBank = EncoderBankSelection("dimmer")),
        )
        assertEquals(
            Binding(Does.Level("colour"), group),
            deriveStripBinding(StripRole.ENCODER, group, encoderBank = EncoderBankSelection("colour")),
        )
    }

    @Test
    fun `a fixture subject derives fixture bindings`() {
        assertEquals(
            Binding(Does.Level("dimmer"), fixture),
            deriveStripBinding(StripRole.FADER, fixture, encoderBank = EncoderBankSelection("tilt")),
        )
        assertEquals(
            Binding(Does.Level("tilt"), fixture),
            deriveStripBinding(StripRole.ENCODER, fixture, encoderBank = EncoderBankSelection("tilt")),
        )
        assertEquals(
            Binding(Does.Select, fixture),
            deriveStripBinding(StripRole.SELECT, fixture, encoderBank = EncoderBankSelection("tilt")),
        )
    }

    @Test
    fun `a master strip has no encoder and no flash`() {
        val master = StripDescriptor(id = "strip-master", fader = "fader-9", select = "btn-33")
        assertNull(master.controlFor(StripRole.ENCODER))
        assertNull(master.controlFor(StripRole.FLASH))
        assertEquals(listOf("fader-9", "btn-33"), master.controlIds)

        val derived = deriveStripBindings(master, group, encoderBank = EncoderBankSelection("colour"))
        assertEquals(setOf("fader-9", "btn-33"), derived.keys)
        assertEquals(Binding(Does.Level("dimmer"), group), derived["fader-9"])
    }

    @Test
    fun `a full strip derives one binding per control`() {
        val strip = StripDescriptor("strip-1", "fader-1", "btn-25", "enc-1", "btn-1")
        assertEquals(StripRole.FADER, strip.roleOf("fader-1"))
        assertEquals(StripRole.SELECT, strip.roleOf("btn-25"))
        assertEquals(StripRole.ENCODER, strip.roleOf("enc-1"))
        assertEquals(StripRole.FLASH, strip.roleOf("btn-1"))
        assertNull(strip.roleOf("fader-2"))

        val derived = deriveStripBindings(strip, group, encoderBank = EncoderBankSelection("colour"))
        assertEquals(
            mapOf(
                "fader-1" to Binding(Does.Level("dimmer"), group),
                "btn-25" to Binding(Does.Select, group),
                "enc-1" to Binding(Does.Level("colour"), group),
                "btn-1" to Binding(Does.Flash("dimmer"), group),
            ),
            derived,
        )
    }

    @Test
    fun `the encoder carries the bank's colour axis, and the fader and flash never do`() {
        val sat = EncoderBankSelection("rgbColour", ColourAxis.SATURATION)
        assertEquals(
            Binding(Does.Level("rgbColour", ColourAxis.SATURATION), group),
            deriveStripBinding(StripRole.ENCODER, group, sat),
        )
        assertEquals(
            Binding(Does.Level("rgbColour", ColourAxis.SATURATION), fixture),
            deriveStripBinding(StripRole.ENCODER, fixture, sat),
        )
        assertEquals(Binding(Does.Level("dimmer"), group), deriveStripBinding(StripRole.FADER, group, sat))
        assertEquals(Binding(Does.Flash("dimmer"), group), deriveStripBinding(StripRole.FLASH, group, sat))
        val derived = deriveStripBindings(StripDescriptor("strip-1", "fader-1", "btn-25", "enc-1", "btn-1"), group, sat)
        assertEquals(Binding(Does.Level("rgbColour", ColourAxis.SATURATION), group), derived["enc-1"])
        assertEquals(Binding(Does.Level("dimmer"), group), derived["fader-1"])
        // A bank on hue derives an encoder with no axis at all — the form every pre-axis row has.
        assertEquals(
            Binding(Does.Level("rgbColour"), group),
            deriveStripBinding(StripRole.ENCODER, group, EncoderBankSelection("rgbColour")),
        )
    }
}
