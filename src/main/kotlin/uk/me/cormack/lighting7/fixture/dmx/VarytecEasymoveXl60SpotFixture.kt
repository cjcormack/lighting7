package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe

/**
 * Varytec Easymove XL 60 Spot — single-LED moving-head spot fixture.
 *
 * White LED engine with a 7-position colour wheel + open, 6-position rotating
 * gobo wheel + open, electronic strobe, electronic dimmer. Pan 630° / tilt 270°
 * (16-bit fine on both axes).
 *
 * One DMX personality (11 channels). The fixture is modelled as a `sealed
 * class` family for consistency with the other multi-mode classes; only
 * [Mode11Ch] exists today.
 */
sealed class VarytecEasymoveXl60SpotFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<VarytecEasymoveXl60SpotFixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        MODE_11CH(11, "11-Channel"),
    }

    /**
     * Channel 3 — colour wheel.
     *
     * The manual says only "7 colours + open, rainbow effect" and labels the
     * positions `Color1`..`Color7`, so the previews below are estimates: a
     * plausible spread for a wheel of this class, so the beam draws a colour
     * rather than black (fixture-optics plan D8). The rainbow bands spin the
     * wheel and carry no single colour.
     *
     * 0–17 open, 18–127 indexed colours, 128–192 forward rotation
     * (slow → fast), 193–255 reverse rotation (slow → fast).
     */
    enum class Colour(
        override val level: UByte,
        override val colourPreview: String? = null,
        override val noColour: Boolean = false,
    ) : DmxFixtureColourSettingValue {
        OPEN(0u, "#FFFFFF"),
        // Estimate: the manual names no colours; checked on the rig by FU-MANUAL-S5-LIBRARY-OPTICS.
        COLOR_1(20u, "#FF0000"),
        COLOR_2(40u, "#00FF00"),
        COLOR_3(60u, "#0000FF"),
        COLOR_4(80u, "#FFFF00"),
        COLOR_5(95u, "#FF00FF"),
        COLOR_6(110u, "#00FFFF"),
        COLOR_7(123u, "#FFA500"),
        RAINBOW_FORWARD(160u, noColour = true),
        RAINBOW_REVERSE(225u, noColour = true),
    }

    /**
     * Channel 4 — gobo wheel.
     *
     * 0–20 open, 21–127 indexed gobos, 128–192 forward wheel rotation
     * (slow → fast), 193–255 reverse wheel rotation. Gobo *spin* (rotation
     * of the selected gobo around its own axis) is on a separate channel.
     */
    enum class Gobo(
        override val level: UByte,
        // GOBO_1..6 are unnamed in the manual; a plausible spread, chosen
        // mostly disjoint from the Fusion's so the two rigs read differently
        // on stage. Wheel-rotation bands stay null (open).
        override val gobo: GoboPattern? = null,
    ) : DmxFixtureGoboSettingValue {
        OPEN(0u),
        GOBO_1(30u, GoboPattern.SPOKES),
        GOBO_2(50u, GoboPattern.DOTS),
        GOBO_3(70u, GoboPattern.CLOUDS),
        GOBO_4(90u, GoboPattern.BARS),
        GOBO_5(110u, GoboPattern.RINGS),
        GOBO_6(124u, GoboPattern.STARBURST),
        WHEEL_FORWARD(160u),
        WHEEL_REVERSE(225u),
    }

    /**
     * 11-channel mode — the only personality the fixture exposes.
     *
     * - Ch 1: Pan (coarse, 630°).
     * - Ch 2: Tilt (coarse, 270°).
     * - Ch 3: Colour wheel.
     * - Ch 4: Gobo wheel.
     * - Ch 5: Gobo spin (0 stop, 1–127 forward speed, 128–255 reverse speed).
     * - Ch 6: Strobe.
     * - Ch 7: Dimmer (0–100%).
     * - Ch 8: Pan/tilt speed (0 fastest → 255 slowest).
     * - Ch 9: Pan (fine).
     * - Ch 10: Tilt (fine).
     * - Ch 11: Reset — the [Mode11Ch.reset] command, not a property (fixture optics plan session 7).
     */
    @FixtureType("varytec-easymove-xl-60-spot-11ch", manufacturer = "Varytec", model = "Easymove XL 60 Spot", kind = FixtureKind.MOVING_HEAD, body = FixtureBody(BodyArchetype.MOVER, MoverHead.SPOT))
    class Mode11Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : VarytecEasymoveXl60SpotFixture(
        universe, firstChannel, 11, key, fixtureName, transaction,
    ), WithDimmer, WithPosition, WithStrobe {
        override val mode = Mode.MODE_11CH

        private constructor(fixture: Mode11Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode11Ch =
            Mode11Ch(this, transaction)

        @FixtureProperty("Pan (coarse, 0–630°)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 630.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Tilt (coarse, 0–270°)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 270.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Colour wheel", category = PropertyCategory.COLOUR)
        val colourWheel = DmxFixtureSetting(
            transaction, universe, firstChannel + 2, Colour.entries.toTypedArray(),
        )

        @FixtureProperty("Gobo wheel", category = PropertyCategory.GOBO)
        val gobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 3, Gobo.entries.toTypedArray(),
        )

        @FixtureProperty(
            "Gobo spin (0 stop, 1–127 forward, 128–255 reverse)",
            category = PropertyCategory.GOBO_ROTATION,
        )
        val goboSpin: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        /**
         * Channel 6 — electronic strobe. 0 = no strobe (LED constant on,
         * dimmer in charge of brightness), 1–255 = slow → fast.
         *
         * Estimate: the rate, 1–20 Hz — the manual says only "variable electronic strobo". Checked
         * on the rig by FU-MANUAL-S6-STROBE.
         */
        @FixtureProperty(
            category = PropertyCategory.STROBE,
            strobe = [
                StrobeBand(0, 0, StrobeKind.OPEN),
                StrobeBand(1, 255, StrobeKind.STROBE, hzMin = 1.0, hzMax = 20.0),
            ],
        )
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 5,
            strobeMin = STROBE_MIN, strobeMax = STROBE_MAX,
        )

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 6)

        @FixtureProperty("Pan/tilt speed (fast → slow)", category = PropertyCategory.SPEED)
        val panTiltSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 7)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 8)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 9)

        // Ch 11. The manual labels the channel only "Reset", with no value bands; 255 is the level the
        // desk has always sent for it (it was a two-option setting until fixture optics session 7).
        // Estimate: a 5 s hold — the manual gives none.
        // Checked on the rig by FU-MANUAL-S7-COMMANDS.
        @FixtureCommand(
            label = "Reset",
            description = "Re-homes pan, tilt and the wheels. The head swings through its travel while it runs.",
            holdMs = 5_000,
        )
        val reset = DmxCommand(universe, firstChannel + 10, 255u)

        companion object {
            const val STROBE_MIN: UByte = 1u
            const val STROBE_MAX: UByte = 255u
        }
    }
}
