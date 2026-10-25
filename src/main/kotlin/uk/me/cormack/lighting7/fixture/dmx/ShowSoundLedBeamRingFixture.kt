package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.attribute.Slider
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe

/**
 * Show-Sound LEDBEAMRING — an unbranded LED beam moving head with an LED ring around its lens.
 *
 * There is no manual: the channel layout is the ChamSys head file a Show-Sound colleague wrote by hand
 * (`SHOW-SOUND_LEDBEAMRING_14CHNEW.hed`, "LED Beam 150"), so the manufacturer is Show-Sound and every
 * range below is that file's. Pan 540° / tilt 180°, both 16-bit; a colour wheel of seven colours and
 * open, a gobo wheel of seven gobos (each also shaking) and open, one rotating prism.
 *
 * The ring is a second emitter around the lens with its own two channels, `ringFx` and `ringSpeed`.
 * It is part of the one head rather than a cell: nothing but those two channels drives it, and the
 * beam is what the unit is for.
 */
sealed class ShowSoundLedBeamRingFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<ShowSoundLedBeamRingFixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        MODE_14CH(14, "14-Channel"),
    }

    /**
     * Channel 8 — colour wheel. 0–127 seven colours and white in 16-step bands, 128–191 rotation
     * forward (fast → slow), 192–255 reverse (slow → fast).
     */
    enum class Colour(
        override val level: UByte,
        override val colourPreview: String? = null,
        override val noColour: Boolean = false,
    ) : DmxFixtureColourSettingValue {
        WHITE(0u, "#FFFFFF"),
        RED(16u, "#FF0000"),
        YELLOW(32u, "#FFFF00"),
        BLUE(48u, "#0000FF"),
        GREEN(64u, "#00FF00"),
        MAGENTA(80u, "#FF00FF"),
        AMBER(96u, "#FF8000"),
        CYAN(112u, "#00FFFF"),
        ROTATE_FORWARD(128u, noColour = true),
        ROTATE_REVERSE(192u, noColour = true),
    }

    /**
     * Channel 9 — gobo wheel. 0–63 open and seven gobos in 8-step bands, 64–119 the same seven
     * shaking, 128–191 wheel rotation forward (fast → slow), 192–255 reverse (slow → fast). The head
     * file names 120–127 nothing.
     */
    enum class Gobo(
        override val level: UByte,
        // Estimate: the head file names only Flower, Stars and Squares; the rest are a plausible
        // spread. Checked on the rig by FU-MANUAL-SHOWSOUND-BEAMRING.
        override val gobo: GoboPattern? = null,
    ) : DmxFixtureGoboSettingValue {
        OPEN(0u),
        FLOWER(8u, GoboPattern.FAN),
        GOBO_2(16u, GoboPattern.DOTS),
        GOBO_3(24u, GoboPattern.RINGS),
        GOBO_4(32u, GoboPattern.BARS),
        STARS(40u, GoboPattern.STARS),
        SQUARES(48u, GoboPattern.HOLES),
        GOBO_7(56u, GoboPattern.SPOKES),
        FLOWER_SHAKE(64u, GoboPattern.FAN),
        GOBO_2_SHAKE(72u, GoboPattern.DOTS),
        GOBO_3_SHAKE(80u, GoboPattern.RINGS),
        GOBO_4_SHAKE(88u, GoboPattern.BARS),
        STARS_SHAKE(96u, GoboPattern.STARS),
        SQUARES_SHAKE(104u, GoboPattern.HOLES),
        GOBO_7_SHAKE(112u, GoboPattern.SPOKES),
        WHEEL_FORWARD(128u),
        WHEEL_REVERSE(192u),
    }

    /** Channel 10 — prism. 0–7 out, 8–127 in, 128–255 in and rotating. */
    enum class Prism(
        override val level: UByte,
        // Estimate: the head file gives no facet count; eight is usual for a beam of this class.
        // Checked on the rig by FU-MANUAL-SHOWSOUND-BEAMRING.
        override val prismFacets: Int? = null,
    ) : DmxFixturePrismSettingValue {
        OPEN(0u),
        PRISM(8u, 8),
        PRISM_ROTATING(128u, 8),
    }

    /**
     * Channel 11 — the head file's "macro" channel: Index 0–49, CW 50–100, Stop 101–200, Sound
     * 201–255. It does not say what the program moves, so the view never reads it.
     */
    enum class Macro(override val level: UByte) : DmxFixtureSettingValue {
        INDEX(0u),
        FORWARD(50u),
        STOP(101u),
        SOUND(201u),
    }

    /**
     * Channel 13 — the LED ring around the lens: off 0–4, a colour 5–74, a chase 75–248, a second
     * chase 249–255. The head file divides the colour band no further.
     */
    enum class RingFx(
        override val level: UByte,
        override val ringColour: String? = null,
        override val ringChase: Boolean = false,
    ) : DmxFixtureRingSettingValue {
        OFF(0u),
        // Estimate: the head file names one colour band, not its colours. Checked on the rig by
        // FU-MANUAL-SHOWSOUND-BEAMRING.
        COLOUR(5u, "#FFFFFF"),
        CHASE(75u, ringChase = true),
        CHASE_2(249u, ringChase = true),
    }

    /**
     * 14-channel mode, the head file's `14CHNEW`.
     *
     * - Ch 1–4: Pan, pan fine, tilt, tilt fine.
     * - Ch 5: Pan/tilt speed.
     * - Ch 6: Dimmer.
     * - Ch 7: Strobe.
     * - Ch 8: Colour wheel.
     * - Ch 9: Gobo wheel.
     * - Ch 10: Prism.
     * - Ch 11: Macro.
     * - Ch 12: Reset — the [Mode14Ch.reset] command, not a property.
     * - Ch 13: Ring effect.
     * - Ch 14: Ring speed.
     */
    @FixtureType(
        "show-sound-ledbeamring-14ch", manufacturer = "Show-Sound", model = "LEDBEAMRING",
        kind = FixtureKind.MOVING_HEAD, body = FixtureBody(BodyArchetype.MOVER, MoverHead.SPOT),
        // Estimate: the head file's narrowest zoom, 3°, for a lens with no zoom. Checked on the rig by
        // FU-MANUAL-SHOWSOUND-BEAMRING.
        fieldDeg = 3.0,
        // Estimate: the head file states no speed. A small beam's typical pan and tilt; the gobo wheel
        // across its range in 0.5 s, the colour wheel in 0.6 s.
        travel = Travel(panDegPerS = 180.0, tiltDegPerS = 150.0, beamMs = 500, colourMs = 600),
    )
    class Mode14Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : ShowSoundLedBeamRingFixture(
        universe, firstChannel, 14, key, fixtureName, transaction,
    ), WithDimmer, WithPosition, WithStrobe {
        override val mode = Mode.MODE_14CH

        private constructor(fixture: Mode14Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode14Ch =
            Mode14Ch(this, transaction)

        // The head file locates pan at 86 of 255 (182°), not mid-travel: its front, as the Shehds'.
        // Estimate: checked on the rig by FU-MANUAL-SHOWSOUND-BEAMRING.
        @FixtureAttribute("Pan (coarse, 0–540°)", category = AttributeCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 540.0, forwardDeg = 180.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureAttribute("Pan (fine)", category = AttributeCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureAttribute("Tilt (coarse, 0–180°)", category = AttributeCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 180.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureAttribute("Tilt (fine)", category = AttributeCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureAttribute("Pan/tilt speed", category = AttributeCategory.SPEED)
        val panTiltSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureAttribute(category = AttributeCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 5)

        /**
         * Channel 7 — 0–3 open, 4–99 pulse, 100–200 strobe, 201–250 random strobe, each slow → fast,
         * and 251–255 open again (the head file types it "Strobe F>S" but draws it open).
         *
         * Estimate: the strobe and random rates are the head file's 1–13 Hz; a pulse at 0.5–2 Hz.
         * Checked on the rig by FU-MANUAL-SHOWSOUND-BEAMRING.
         */
        @FixtureAttribute(
            category = AttributeCategory.STROBE,
            strobe = [
                StrobeBand(0, 3, StrobeKind.OPEN),
                StrobeBand(4, 99, StrobeKind.PULSE, hzMin = 0.5, hzMax = 2.0),
                StrobeBand(100, 200, StrobeKind.STROBE, hzMin = 1.0, hzMax = 13.0),
                StrobeBand(201, 250, StrobeKind.RANDOM, hzMin = 1.0, hzMax = 13.0),
                StrobeBand(251, 255, StrobeKind.OPEN),
            ],
        )
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 6,
            strobeMin = STROBE_MIN, strobeMax = STROBE_MAX,
        )

        @FixtureAttribute("Colour wheel", category = AttributeCategory.COLOUR)
        val colourWheel = DmxFixtureSetting(
            transaction, universe, firstChannel + 7, Colour.entries.toTypedArray(),
        )

        @FixtureAttribute("Gobo wheel", category = AttributeCategory.GOBO)
        val gobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 8, Gobo.entries.toTypedArray(),
        )

        @FixtureAttribute("Prism", category = AttributeCategory.PRISM)
        val prism = DmxFixtureSetting(
            transaction, universe, firstChannel + 9, Prism.entries.toTypedArray(),
        )

        @FixtureAttribute("Macro", category = AttributeCategory.SETTING)
        val macro = DmxFixtureSetting(
            transaction, universe, firstChannel + 10, Macro.entries.toTypedArray(),
        )

        // Estimate: a 5 s hold — the head file gives none. Checked on the rig by FU-MANUAL-SHOWSOUND-BEAMRING.
        @FixtureCommand(
            label = "Reset",
            description = "Re-homes pan, tilt and the wheels. The head swings through its travel while it runs.",
            holdMs = 5_000,
            role = CommandRole.RESET,
        )
        val reset = DmxCommand(universe, firstChannel + 11, 255u, bandMin = 250u, bandMax = 255u)

        @FixtureAttribute("LED ring", category = AttributeCategory.RING)
        val ringFx = DmxFixtureSetting(
            transaction, universe, firstChannel + 12, RingFx.entries.toTypedArray(),
        )

        @FixtureAttribute("LED ring speed", category = AttributeCategory.RING_SPEED)
        val ringSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 13)

        companion object {
            const val STROBE_MIN: UByte = 100u
            const val STROBE_MAX: UByte = 200u
        }
    }
}
