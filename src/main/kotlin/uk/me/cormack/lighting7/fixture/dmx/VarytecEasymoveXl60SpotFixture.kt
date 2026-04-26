package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import kotlin.math.roundToInt

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
     * The manual labels the seven discrete positions only as `Color1`..`Color7`
     * without specifying which actual colour is at each index, so no
     * [DmxFixtureColourSettingValue.colourPreview] is set. Update once the
     * physical fixture has been inspected.
     *
     * 0–17 open, 18–127 indexed colours, 128–192 forward rotation
     * (slow → fast), 193–255 reverse rotation (slow → fast).
     */
    enum class Colour(
        override val level: UByte,
        override val colourPreview: String? = null,
    ) : DmxFixtureColourSettingValue {
        OPEN(0u),
        COLOR_1(20u),
        COLOR_2(40u),
        COLOR_3(60u),
        COLOR_4(80u),
        COLOR_5(95u),
        COLOR_6(110u),
        COLOR_7(123u),
        RAINBOW_FORWARD(160u),
        RAINBOW_REVERSE(225u),
    }

    /**
     * Channel 4 — gobo wheel.
     *
     * 0–20 open, 21–127 indexed gobos, 128–192 forward wheel rotation
     * (slow → fast), 193–255 reverse wheel rotation. Gobo *spin* (rotation
     * of the selected gobo around its own axis) is on a separate channel.
     */
    enum class Gobo(override val level: UByte) : DmxFixtureSettingValue {
        OPEN(0u),
        GOBO_1(30u),
        GOBO_2(50u),
        GOBO_3(70u),
        GOBO_4(90u),
        GOBO_5(110u),
        GOBO_6(124u),
        WHEEL_FORWARD(160u),
        WHEEL_REVERSE(225u),
    }

    /**
     * Channel 11 — reset.
     *
     * The manual labels the channel only "Reset" with no value bands. To keep
     * accidental FX writes from triggering a head reset, this is modelled as
     * a setting with two discrete states rather than an open slider.
     */
    enum class Reset(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        RESET(255u),
    }

    /**
     * Channel 6 — electronic strobe.
     *
     * 0 = no strobe (LED constant on, brightness controlled by the dimmer
     * channel), 1–255 = slow → fast strobe. There is no separate "lamp on"
     * band — the dimmer is the only brightness control on this fixture.
     *
     * - [fullOn]: writes 0 (strobe disabled, dimmer in charge of brightness).
     * - [strobe]: linearly maps the input 0–255 onto the strobe band 1–255.
     */
    class StrobeChannel(
        transaction: ControllerTransaction?,
        universe: Universe,
        channelNo: Int,
    ) : DmxSlider(transaction, universe, channelNo), Strobe {
        override fun fullOn() {
            value = 0u
        }

        override fun strobe(intensity: UByte) {
            val span = (STROBE_MAX - STROBE_MIN).toFloat()
            value = ((span / 255F * intensity.toFloat()).roundToInt() + STROBE_MIN.toInt()).toUByte()
        }

        companion object {
            const val STROBE_MIN: UByte = 1u
            const val STROBE_MAX: UByte = 255u
        }
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
     * - Ch 11: Reset.
     */
    @FixtureType("varytec-easymove-xl-60-spot-11ch", manufacturer = "Varytec", model = "Easymove XL 60 Spot")
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

        @FixtureProperty("Pan (coarse, 0–630°)", category = PropertyCategory.PAN)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Tilt (coarse, 0–270°)", category = PropertyCategory.TILT)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Colour wheel", category = PropertyCategory.COLOUR)
        val colour = DmxFixtureSetting(
            transaction, universe, firstChannel + 2, Colour.entries.toTypedArray(),
        )

        @FixtureProperty("Gobo wheel", category = PropertyCategory.SETTING)
        val gobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 3, Gobo.entries.toTypedArray(),
        )

        @FixtureProperty(
            "Gobo spin (0 stop, 1–127 forward, 128–255 reverse)",
            category = PropertyCategory.SETTING,
        )
        val goboSpin: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty(category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 5)

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 6)

        @FixtureProperty("Pan/tilt speed (fast → slow)", category = PropertyCategory.SPEED)
        val panTiltSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 7)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 8)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 9)

        @FixtureProperty("Reset", category = PropertyCategory.SETTING)
        val reset = DmxFixtureSetting(
            transaction, universe, firstChannel + 10, Reset.entries.toTypedArray(),
        )
    }
}
