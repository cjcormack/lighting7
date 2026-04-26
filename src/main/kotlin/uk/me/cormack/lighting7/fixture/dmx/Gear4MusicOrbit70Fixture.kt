package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import kotlin.math.roundToInt

/**
 * Gear4music Orbit-70 — 7 × 10W RGBW LED mini moving head.
 *
 * Two DMX personalities (9 / 13 channel). Only the 13-channel mode is
 * implemented for the TCH 2026 patch; the 9-channel mode remains as a
 * `// TODO` enum entry.
 *
 * ChamSys lists this as `Gear4Music,Orbit70WLEDHead`; the manual sells it as
 * the "Orbit-70". 4.2kg head, 7×10W RGBW LEDs.
 */
sealed class Gear4MusicOrbit70Fixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<Gear4MusicOrbit70Fixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        // TODO: MODE_9CH (9, "9-Channel")
        MODE_13CH(13, "13-Channel"),
    }

    /**
     * Channel 7 — strobe / shutter band layout from the manual:
     * - 000–007 LEDs off
     * - 008–015 LED quick start
     * - 016–131 strobe slow → fast
     * - 132–139 LED quick start
     * - 140–181 slow start, quick close (pulse)
     * - 182–189 LED quick start
     * - 190–231 quick start, slow close (pulse)
     * - 232–239 LED quick start
     * - 240–247 random flash
     * - 248–255 LED open switch (full on)
     *
     * The [Strobe] interface only exposes the linear strobe band (016–131)
     * and the LED-open band (248–255). Pulse, quick-start and random-flash
     * bands are reachable by writing the raw channel value if a script
     * needs them.
     */
    class StrobeChannel(
        transaction: ControllerTransaction?,
        universe: Universe,
        channelNo: Int,
    ) : DmxSlider(transaction, universe, channelNo), Strobe {
        override fun fullOn() {
            value = LED_OPEN
        }

        override fun strobe(intensity: UByte) {
            val span = (STROBE_MAX - STROBE_MIN).toFloat()
            value = ((span / 255F * intensity.toFloat()).roundToInt() + STROBE_MIN.toInt()).toUByte()
        }

        companion object {
            const val STROBE_MIN: UByte = 16u
            const val STROBE_MAX: UByte = 131u
            const val LED_OPEN: UByte = 248u
        }
    }

    /**
     * Channel 13 — built-in programs and reset.
     *
     * Only the bands documented in the 13CH manual table are exposed.
     * The 9CH personality additionally lists 240–255 as sound-active mode;
     * that range is not documented for 13CH and is therefore omitted.
     */
    enum class Program(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        PROGRAM_1_LEDS_OFF(70u),
        PROGRAM_1_LEDS_ON(80u),
        PROGRAM_2_LEDS_OFF(90u),
        PROGRAM_2_LEDS_ON(100u),
        RESET(200u),
    }

    /**
     * 13-channel mode — full feature set.
     *
     * - Ch 1: Pan (coarse).
     * - Ch 2: Pan (fine — the manual calls this "X axis fine adjustment").
     * - Ch 3: Tilt (coarse).
     * - Ch 4: Tilt (fine — the manual calls this "Y axis fine adjustment").
     * - Ch 5: Pan/tilt rotation speed (fast → slow).
     * - Ch 6: Master dimmer (0–100%).
     * - Ch 7: Strobe / shutter.
     * - Ch 8: Red.
     * - Ch 9: Green.
     * - Ch 10: Blue.
     * - Ch 11: White.
     * - Ch 12: Static LED colour select (raw slider — manual gives no value bands).
     * - Ch 13: Built-in programs / reset.
     */
    @FixtureType("gear4music-orbit-70-13ch", manufacturer = "Gear4music", model = "Orbit-70")
    class Mode13Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Gear4MusicOrbit70Fixture(
        universe, firstChannel, 13, key, fixtureName, transaction,
    ), WithDimmer, WithColour, WithWhite, WithStrobe, WithPosition {
        override val mode = Mode.MODE_13CH

        private constructor(fixture: Mode13Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode13Ch =
            Mode13Ch(this, transaction)

        @FixtureProperty("Pan (coarse)", category = PropertyCategory.PAN)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Tilt (coarse)", category = PropertyCategory.TILT)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Pan/tilt rotation speed (fast → slow)", category = PropertyCategory.SPEED)
        val rotationSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 5)

        @FixtureProperty(category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 6)

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            transaction, universe,
            firstChannel + 7,
            firstChannel + 8,
            firstChannel + 9,
        )

        @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
        override val white: Slider = DmxSlider(transaction, universe, firstChannel + 10)

        @FixtureProperty("Static colour select", category = PropertyCategory.COLOUR)
        val staticColour: Slider = DmxSlider(transaction, universe, firstChannel + 11)

        @FixtureProperty("Built-in program", category = PropertyCategory.SETTING)
        val program = DmxFixtureSetting(
            transaction, universe, firstChannel + 12, Program.entries.toTypedArray(),
        )
    }
}
