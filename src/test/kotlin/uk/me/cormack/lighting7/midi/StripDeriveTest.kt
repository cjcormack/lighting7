package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.CueTargetDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What each control on a strip behaves as. The router, the feedback index and the *Fader only…*
 * expansion all ask this one function, so these cases pin the answer for all three.
 */
class StripDeriveTest {

    private val group = CueTargetDto("group", "front-wash")
    private val fixture = CueTargetDto("fixture", "hex-1")

    @Test
    fun `fader and flash always drive dimmer, whatever the encoder bank`() {
        assertEquals(
            BindingTarget.GroupProperty("front-wash", "dimmer"),
            deriveStripTarget(StripRole.FADER, group, encoderBank = EncoderBankSelection("pan")),
        )
        assertEquals(
            BindingTarget.Flash(BindingTarget.GroupProperty("front-wash", "dimmer")),
            deriveStripTarget(StripRole.FLASH, group, encoderBank = EncoderBankSelection("pan")),
        )
    }

    @Test
    fun `select toggles rather than replaces`() {
        assertEquals(
            BindingTarget.SelectTarget(group, BindingTarget.SelectMode.TOGGLE),
            deriveStripTarget(StripRole.SELECT, group, encoderBank = EncoderBankSelection("dimmer")),
        )
    }

    @Test
    fun `the encoder follows the encoder bank`() {
        assertEquals(
            BindingTarget.GroupProperty("front-wash", "dimmer"),
            deriveStripTarget(StripRole.ENCODER, group, encoderBank = EncoderBankSelection("dimmer")),
        )
        assertEquals(
            BindingTarget.GroupProperty("front-wash", "colour"),
            deriveStripTarget(StripRole.ENCODER, group, encoderBank = EncoderBankSelection("colour")),
        )
    }

    @Test
    fun `a fixture target derives fixture targets`() {
        assertEquals(
            BindingTarget.FixtureProperty("hex-1", "dimmer"),
            deriveStripTarget(StripRole.FADER, fixture, encoderBank = EncoderBankSelection("tilt")),
        )
        assertEquals(
            BindingTarget.FixtureProperty("hex-1", "tilt"),
            deriveStripTarget(StripRole.ENCODER, fixture, encoderBank = EncoderBankSelection("tilt")),
        )
        assertEquals(
            BindingTarget.SelectTarget(fixture, BindingTarget.SelectMode.TOGGLE),
            deriveStripTarget(StripRole.SELECT, fixture, encoderBank = EncoderBankSelection("tilt")),
        )
    }

    @Test
    fun `a master strip has no encoder and no flash`() {
        val master = StripDescriptor(id = "strip-master", fader = "fader-9", select = "btn-33")
        assertNull(master.controlFor(StripRole.ENCODER))
        assertNull(master.controlFor(StripRole.FLASH))
        assertEquals(listOf("fader-9", "btn-33"), master.controlIds)

        val derived = deriveStripTargets(master, group, encoderBank = EncoderBankSelection("colour"))
        assertEquals(setOf("fader-9", "btn-33"), derived.keys)
        assertEquals(BindingTarget.GroupProperty("front-wash", "dimmer"), derived["fader-9"])
    }

    @Test
    fun `a full strip derives one target per control`() {
        val strip = StripDescriptor("strip-1", "fader-1", "btn-25", "enc-1", "btn-1")
        assertEquals(StripRole.FADER, strip.roleOf("fader-1"))
        assertEquals(StripRole.SELECT, strip.roleOf("btn-25"))
        assertEquals(StripRole.ENCODER, strip.roleOf("enc-1"))
        assertEquals(StripRole.FLASH, strip.roleOf("btn-1"))
        assertNull(strip.roleOf("fader-2"))

        val derived = deriveStripTargets(strip, group, encoderBank = EncoderBankSelection("colour"))
        assertEquals(
            mapOf(
                "fader-1" to BindingTarget.GroupProperty("front-wash", "dimmer"),
                "btn-25" to BindingTarget.SelectTarget(group, BindingTarget.SelectMode.TOGGLE),
                "enc-1" to BindingTarget.GroupProperty("front-wash", "colour"),
                "btn-1" to BindingTarget.Flash(BindingTarget.GroupProperty("front-wash", "dimmer")),
            ),
            derived,
        )
    }

    @Test
    fun `the encoder carries the bank's colour axis, and the fader and flash never do`() {
        val sat = EncoderBankSelection("rgbColour", ColourAxis.SATURATION)
        assertEquals(
            BindingTarget.GroupProperty("front-wash", "rgbColour", ColourAxis.SATURATION),
            deriveStripTarget(StripRole.ENCODER, group, sat),
        )
        assertEquals(
            BindingTarget.FixtureProperty("hex-1", "rgbColour", ColourAxis.SATURATION),
            deriveStripTarget(StripRole.ENCODER, fixture, sat),
        )
        assertEquals(BindingTarget.GroupProperty("front-wash", "dimmer"), deriveStripTarget(StripRole.FADER, group, sat))
        assertEquals(
            BindingTarget.Flash(BindingTarget.GroupProperty("front-wash", "dimmer")),
            deriveStripTarget(StripRole.FLASH, group, sat),
        )
        val derived = deriveStripTargets(StripDescriptor("strip-1", "fader-1", "btn-25", "enc-1", "btn-1"), group, sat)
        assertEquals(BindingTarget.GroupProperty("front-wash", "rgbColour", ColourAxis.SATURATION), derived["enc-1"])
        assertEquals(BindingTarget.GroupProperty("front-wash", "dimmer"), derived["fader-1"])
        // A bank on hue derives an encoder with no axis at all — the form every pre-axis row has.
        assertEquals(
            BindingTarget.GroupProperty("front-wash", "rgbColour"),
            deriveStripTarget(StripRole.ENCODER, group, EncoderBankSelection("rgbColour")),
        )
    }
}
