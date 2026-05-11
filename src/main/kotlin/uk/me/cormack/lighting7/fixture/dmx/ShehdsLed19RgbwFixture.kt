package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import uk.me.cormack.lighting7.fixture.trait.WithWhite

/**
 * Shehds LED Beam+Wash 19 × 15W RGBW Zoom — moving-head wash with motorised zoom.
 *
 * Two DMX personalities (16ch / 24ch). The 24-channel mode is the one patched
 * for TCH 2026; the 16-channel mode is included because both share most of
 * the channel layout.
 *
 * 24ch architecture: master pan/tilt + dimmer + strobe + zoom, plus three
 * independently addressable RGBW zones (CH9–20). The manual's 24ch chart
 * labels CH9–12 `Red1/Green1/Blue1/White1`, CH13–16 the same with `2`, CH17–20
 * with `3` — modelled here as a [MultiElementFixture] with three [Zone]
 * elements, matching the [SlenderBeamBarQuadFixture] pattern.
 *
 * 16ch mode collapses the three zones to a single global RGBW.
 */
sealed class ShehdsLed19RgbwFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<ShehdsLed19RgbwFixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        MODE_16CH(16, "16-Channel"),
        MODE_24CH(24, "24-Channel"),
    }

    /**
     * Built-in program selector — same enum names in both modes, but the band
     * labels differ slightly (16ch labels three "Effect modes"; 24ch labels
     * three "Auto modes"). The DMX values are identical, so a single enum
     * covers both.
     */
    enum class Program(override val level: UByte) : DmxFixtureSettingValue {
        PRESET_COLOUR(0u),
        PROGRAM_JUMP(128u),
        PROGRAM_GRADIENT(146u),
        PROGRAM_PULSE(160u),
        AUTO_MODE_1(173u),
        AUTO_MODE_2(201u),
        AUTO_MODE_3(231u),
    }

    /**
     * Reset channel — manual just labels it "Reset" with no value bands.
     * Modelled (like the Varytec Easymove ch 11) as a discrete two-state
     * setting so accidental FX writes can't trigger a head reset.
     */
    enum class Reset(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        RESET(255u),
    }

    /**
     * One of three RGBW zones in 24ch mode (CH9–12, CH13–16, CH17–20).
     *
     * Each zone is four channels (R, G, B, W) and exposes both
     * [WithColour] and [WithWhite] so it can be FX-targeted independently
     * via the [MultiElementFixture] elements list.
     *
     * The manual gives no information about which physical LEDs each zone
     * drives; the fixture has 19 LEDs and three zones, so a likely
     * arrangement is centre + outer-ring × 2, but that's not guaranteed.
     */
    inner class Zone(
        override val elementIndex: Int,
        zoneTransaction: ControllerTransaction?,
        private val zoneFirstChannel: Int,
    ) : FixtureElement<ShehdsLed19RgbwFixture>, WithColour, WithWhite {

        override val parentFixture: ShehdsLed19RgbwFixture
            get() = this@ShehdsLed19RgbwFixture

        override val elementKey: String
            get() = "${this@ShehdsLed19RgbwFixture.key}.zone-${elementIndex + 1}"

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            zoneTransaction, universe,
            zoneFirstChannel,
            zoneFirstChannel + 1,
            zoneFirstChannel + 2,
        )

        @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
        override val white: Slider = DmxSlider(zoneTransaction, universe, zoneFirstChannel + 3)

        override fun withTransaction(transaction: ControllerTransaction): Zone =
            Zone(elementIndex, transaction, zoneFirstChannel)

        override fun toString(): String = "Zone($elementKey)"
    }

    /**
     * 24-channel mode — pan/tilt + master dimmer/strobe/zoom + three RGBW
     * zones + programs + reset. The patched personality.
     *
     * - Ch 1: Pan (coarse, 0–540°).
     * - Ch 2: Pan (fine).
     * - Ch 3: Tilt (coarse, 0–270°).
     * - Ch 4: Tilt (fine).
     * - Ch 5: Pan/tilt speed.
     * - Ch 6: Zoom (15° narrow → 60° wide).
     * - Ch 7: Master dimmer (0–100% linear).
     * - Ch 8: Strobe.
     * - Ch 9–12: Zone 1 RGBW.
     * - Ch 13–16: Zone 2 RGBW.
     * - Ch 17–20: Zone 3 RGBW.
     * - Ch 21: Built-in program.
     * - Ch 22: Program speed.
     * - Ch 23: Control mode (slave/auto/sound) — plain slider, manual lists
     *         four broad bands but no clean named states beyond "self-propelled".
     * - Ch 24: Reset.
     */
    @FixtureType("shehds-led19-rgbw-24ch", manufacturer = "Shehds", model = "LED 19x15W RGBW Zoom", kind = FixtureKind.MOVING_HEAD)
    class Mode24Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : ShehdsLed19RgbwFixture(
        universe, firstChannel, 24, key, fixtureName, transaction,
    ), WithDimmer, WithPosition, WithStrobe,
        MultiElementFixture<ShehdsLed19RgbwFixture.Zone> {
        override val mode = Mode.MODE_24CH

        private constructor(fixture: Mode24Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode24Ch =
            Mode24Ch(this, transaction)

        @FixtureProperty("Pan (coarse, 0–540°)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 540.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Tilt (coarse, 0–270°)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 270.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Pan/tilt speed", category = PropertyCategory.SPEED)
        val panTiltSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty("Zoom (narrow → wide)", category = PropertyCategory.OTHER)
        val zoom: Slider = DmxSlider(transaction, universe, firstChannel + 5)

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 6)

        @FixtureProperty(category = PropertyCategory.STROBE)
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 7,
            strobeMin = STROBE_MIN, strobeMax = STROBE_MAX,
        )

        override val elements: List<Zone> = (0 until 3).map { idx ->
            Zone(idx, transaction, firstChannel + 8 + (idx * 4))
        }

        override val elementCount: Int = 3

        fun zone(index: Int): Zone {
            require(index in 0 until 3) { "Zone index must be 0-2, got $index" }
            return elements[index]
        }

        @FixtureProperty("Built-in program", category = PropertyCategory.SETTING)
        val program = DmxFixtureSetting(
            transaction, universe, firstChannel + 20, Program.entries.toTypedArray(),
        )

        @FixtureProperty("Program speed", category = PropertyCategory.SPEED)
        val programSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 21)

        @FixtureProperty(
            "Control mode (50–99 activates programs, 100–199 self-propelled, 200–255 voice)",
            category = PropertyCategory.SETTING,
        )
        val controlMode: Slider = DmxSlider(transaction, universe, firstChannel + 22)

        @FixtureProperty("Reset", category = PropertyCategory.SETTING)
        val reset = DmxFixtureSetting(
            transaction, universe, firstChannel + 23, Reset.entries.toTypedArray(),
        )
    }

    /**
     * 16-channel mode — pan/tilt + master dimmer/strobe + single global RGBW
     * + zoom + programs + reset.
     *
     * Note that 16ch puts zoom **after** the RGBW block (CH12), whereas 24ch
     * puts zoom **before** the dimmer (CH6). The two layouts are not just
     * truncations of each other.
     *
     * - Ch 1: Pan (coarse, 0–540°).
     * - Ch 2: Pan (fine).
     * - Ch 3: Tilt (coarse, 0–270°).
     * - Ch 4: Tilt (fine).
     * - Ch 5: Pan/tilt speed.
     * - Ch 6: Master dimmer.
     * - Ch 7: Strobe.
     * - Ch 8: Red.
     * - Ch 9: Green.
     * - Ch 10: Blue.
     * - Ch 11: White.
     * - Ch 12: Zoom.
     * - Ch 13: Built-in program.
     * - Ch 14: Program speed.
     * - Ch 15: Control mode (10–99 activates programs, 100–179 self-propelled,
     *         180–255 voice).
     * - Ch 16: Reset.
     */
    @FixtureType("shehds-led19-rgbw-16ch", manufacturer = "Shehds", model = "LED 19x15W RGBW Zoom", kind = FixtureKind.MOVING_HEAD)
    class Mode16Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : ShehdsLed19RgbwFixture(
        universe, firstChannel, 16, key, fixtureName, transaction,
    ), WithDimmer, WithColour, WithWhite, WithPosition, WithStrobe {
        override val mode = Mode.MODE_16CH

        private constructor(fixture: Mode16Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode16Ch =
            Mode16Ch(this, transaction)

        @FixtureProperty("Pan (coarse, 0–540°)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 540.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Tilt (coarse, 0–270°)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 270.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Pan/tilt speed", category = PropertyCategory.SPEED)
        val panTiltSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 5)

        @FixtureProperty(category = PropertyCategory.STROBE)
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 6,
            strobeMin = STROBE_MIN, strobeMax = STROBE_MAX,
        )

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            transaction, universe,
            firstChannel + 7,
            firstChannel + 8,
            firstChannel + 9,
        )

        @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
        override val white: Slider = DmxSlider(transaction, universe, firstChannel + 10)

        @FixtureProperty("Zoom (narrow → wide)", category = PropertyCategory.OTHER)
        val zoom: Slider = DmxSlider(transaction, universe, firstChannel + 11)

        @FixtureProperty("Built-in program", category = PropertyCategory.SETTING)
        val program = DmxFixtureSetting(
            transaction, universe, firstChannel + 12, Program.entries.toTypedArray(),
        )

        @FixtureProperty("Program speed", category = PropertyCategory.SPEED)
        val programSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 13)

        @FixtureProperty(
            "Control mode (10–99 activates programs, 100–179 self-propelled, 180–255 voice)",
            category = PropertyCategory.SETTING,
        )
        val controlMode: Slider = DmxSlider(transaction, universe, firstChannel + 14)

        @FixtureProperty("Reset", category = PropertyCategory.SETTING)
        val reset = DmxFixtureSetting(
            transaction, universe, firstChannel + 15, Reset.entries.toTypedArray(),
        )
    }

    companion object {
        /**
         * Strobe band — manual lists only "0–255 Strobe" with no value bands.
         * Modelled like the Varytec Easymove: 0 = LED constant on (no strobe),
         * 1–255 = strobe slow → fast.
         */
        const val STROBE_MIN: UByte = 1u
        const val STROBE_MAX: UByte = 255u
    }
}
