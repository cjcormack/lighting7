package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import kotlin.math.roundToInt

/**
 * Equinox Fusion 100 Spot MKII - A compact moving head spot fixture.
 *
 * Features a 80W white LED, colour wheel (7 colours + open), rotating gobo wheel
 * (5 gobos + open), 3-facet prism, motorised focus, and pan/tilt (540°/210°).
 *
 * DMX Modes:
 * - 5CH: Pan, Tilt, LED Macros, Pan/Tilt Macros, Motor Mode
 * - 8CH: Pan, Tilt, Dimmer, Colour, Gobo, Gobo Rotation, Focus, Prism
 * - 15CH: Full control with fine pan/tilt, speed, strobe, and macros
 *
 * Order codes: EQLED069 (Black), EQLED069A (White)
 */
sealed class Fusion100SpotMkIIFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<Fusion100SpotMkIIFixture.Mode> {

    // ============================================
    // Channel Mode Enum
    // ============================================

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String
    ) : DmxChannelMode {
        MODE_5CH(5, "5-Channel (Basic)"),
        MODE_8CH(8, "8-Channel (Standard)"),
        MODE_15CH(15, "15-Channel (Full Control)")
    }

    // ============================================
    // Setting Value Enums
    // ============================================

    /**
     * Colour wheel positions.
     * 8CH mode CH4 / 15CH mode CH8.
     */
    enum class Colour(
        override val level: UByte,
        override val colourPreview: String? = null
    ) : DmxFixtureColourSettingValue {
        OPEN_WHITE(0u, "#FFFFFF"),
        RED(14u, "#FF0000"),
        YELLOW(32u, "#FFFF00"),
        CYAN(50u, "#00FFFF"),
        GREEN(68u, "#00FF00"),
        ORANGE(86u, "#FF8000"),
        MAGENTA(104u, "#FF00FF"),
        BLUE(122u, "#0000FF"),
        OPEN_WHITE_2(131u, "#FFFFFF"),
        RAINBOW_EFFECT(140u),
        ROTATION_STOP(196u),
        REVERSE_RAINBOW_EFFECT(200u);
    }

    /**
     * Gobo wheel positions.
     * 8CH mode CH5 / 15CH mode CH9.
     */
    enum class Gobo(override val level: UByte) : DmxFixtureSettingValue {
        OPEN_WHITE(0u),
        GOBO_1(9u),
        GOBO_2(34u),
        GOBO_3(59u),
        GOBO_4(84u),
        GOBO_5(109u),
        RAINBOW_EFFECT(134u),
        REVERSE_RAINBOW_EFFECT(195u);
    }

    /**
     * Gobo rotation control.
     * 8CH mode CH6 / 15CH mode CH10.
     */
    enum class GoboRotation(override val level: UByte) : DmxFixtureSettingValue {
        ROTATION_STOP(0u),
        FORWARD_ROTATION_FAST(10u),
        FORWARD_ROTATION_SLOW(129u),
        ROTATION_STOP_2(130u),
        REVERSE_ROTATION_SLOW(135u),
        REVERSE_ROTATION_FAST(255u);
    }

    /**
     * Prism mode.
     * 8CH mode CH8 / 15CH mode CH12.
     */
    enum class PrismMode(override val level: UByte) : DmxFixtureSettingValue {
        OPEN(0u),
        PRISM(8u);
    }

    /**
     * LED macro programs.
     * 5CH mode CH3 / 15CH mode CH13.
     */
    enum class LedMacro(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        LED_MACRO_1(8u),
        LED_MACRO_2(48u),
        LED_MACRO_3(88u),
        LED_MACRO_4(128u),
        LED_MACRO_5(168u),
        LED_MACRO_6(208u),
        SOUND_ACTIVE(248u);
    }

    /**
     * Pan/tilt macro programs.
     * 5CH mode CH4 / 15CH mode CH14.
     */
    enum class PanTiltMacro(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        PAN_TILT_MACRO_1(8u),
        PAN_TILT_MACRO_2(59u),
        PAN_TILT_MACRO_3(111u),
        PAN_TILT_MACRO_4(163u),
        SOUND_ACTIVE(241u);
    }

    /**
     * Motor mode / reset control.
     * 5CH mode CH5 / 15CH mode CH15.
     */
    enum class MotorMode(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        HIGH_SPEED_MOTOR_MOVEMENT(51u),
        LOW_SPEED_MOTOR_MOVEMENT(151u),
        RESET(251u);
    }

    // ============================================
    // Strobe Implementation
    // ============================================

    /**
     * Strobe control for the Fusion 100 Spot MKII (15CH mode CH7).
     * 0-9 = blackout/off, 10-245 = strobe slow to fast, 246-255 = LED on.
     */
    class StrobeChannel(
        transaction: ControllerTransaction?,
        universe: Universe,
        channelNo: Int
    ) : DmxSlider(transaction, universe, channelNo), Strobe {
        override fun fullOn() {
            value = 246u
        }

        override fun strobe(intensity: UByte) {
            value = if (intensity == 0u.toUByte()) {
                246u // full on
            } else {
                ((235F / 255F * intensity.toFloat()).roundToInt() + 10).toUByte()
            }
        }
    }

    // ============================================
    // Mode-Specific Subclasses
    // ============================================

    /**
     * 5-Channel Mode: Basic pan/tilt with macros.
     *
     * DMX Layout (5 channels):
     * - Ch 1: Pan adjustment 0-540°
     * - Ch 2: Tilt adjustment 0-210°
     * - Ch 3: LED Macros / Sound active
     * - Ch 4: Pan/Tilt Macros / Sound active
     * - Ch 5: Motor mode / Reset
     */
    @FixtureType("fusion-100-spot-mkii-5ch", manufacturer = "Equinox", model = "Fusion 100 Spot MKII")
    class Mode5Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Fusion100SpotMkIIFixture(
        universe, firstChannel, 5, key, fixtureName, transaction
    ) {
        override val mode = Mode.MODE_5CH

        private constructor(fixture: Mode5Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode5Ch =
            Mode5Ch(this, transaction)

        @FixtureProperty("Pan adjustment 0-540°", category = PropertyCategory.POSITION)
        val pan = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Tilt adjustment 0-210°", category = PropertyCategory.POSITION)
        val tilt = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("LED macro", category = PropertyCategory.SETTING)
        val ledMacro = DmxFixtureSetting(transaction, universe, firstChannel + 2, LedMacro.entries.toTypedArray())

        @FixtureProperty("Pan/tilt macro", category = PropertyCategory.SETTING)
        val panTiltMacro = DmxFixtureSetting(transaction, universe, firstChannel + 3, PanTiltMacro.entries.toTypedArray())

        @FixtureProperty("Motor mode", category = PropertyCategory.SETTING)
        val motorMode = DmxFixtureSetting(transaction, universe, firstChannel + 4, MotorMode.entries.toTypedArray())
    }

    /**
     * 8-Channel Mode: Standard control with colour, gobo, focus and prism.
     *
     * DMX Layout (8 channels):
     * - Ch 1: Pan adjustment 0-540°
     * - Ch 2: Tilt adjustment 0-210°
     * - Ch 3: Master dimmer (0-100%)
     * - Ch 4: Colour wheel
     * - Ch 5: Gobo wheel
     * - Ch 6: Gobo rotation
     * - Ch 7: Focus
     * - Ch 8: Prism (open/3-facet)
     */
    @FixtureType("fusion-100-spot-mkii-8ch", manufacturer = "Equinox", model = "Fusion 100 Spot MKII")
    class Mode8Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Fusion100SpotMkIIFixture(
        universe, firstChannel, 8, key, fixtureName, transaction
    ), WithDimmer {
        override val mode = Mode.MODE_8CH

        private constructor(fixture: Mode8Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode8Ch =
            Mode8Ch(this, transaction)

        @FixtureProperty("Pan adjustment 0-540°", category = PropertyCategory.POSITION)
        val pan = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Tilt adjustment 0-210°", category = PropertyCategory.POSITION)
        val tilt = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Dimmer", category = PropertyCategory.DIMMER)
        override val dimmer = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Colour", category = PropertyCategory.COLOUR)
        val colour = DmxFixtureSetting(transaction, universe, firstChannel + 3, Colour.entries.toTypedArray())

        @FixtureProperty("Gobo", category = PropertyCategory.SETTING)
        val gobo = DmxFixtureSetting(transaction, universe, firstChannel + 4, Gobo.entries.toTypedArray())

        @FixtureProperty("Gobo rotation", category = PropertyCategory.SETTING)
        val goboRotation = DmxFixtureSetting(transaction, universe, firstChannel + 5, GoboRotation.entries.toTypedArray())

        @FixtureProperty("Focus", category = PropertyCategory.OTHER)
        val focus = DmxSlider(transaction, universe, firstChannel + 6)

        @FixtureProperty("Prism", category = PropertyCategory.SETTING)
        val prism = DmxFixtureSetting(transaction, universe, firstChannel + 7, PrismMode.entries.toTypedArray())
    }

    /**
     * 15-Channel Mode: Full control with fine pan/tilt, speed, strobe, and all features.
     *
     * DMX Layout (15 channels):
     * - Ch 1: Pan adjustment 0-540°
     * - Ch 2: Pan fine adjustment
     * - Ch 3: Tilt adjustment 0-210°
     * - Ch 4: Tilt fine adjustment
     * - Ch 5: Pan/tilt speed
     * - Ch 6: Master dimmer (0-100%)
     * - Ch 7: Strobe (blackout / slow-fast / LED on)
     * - Ch 8: Colour wheel
     * - Ch 9: Gobo wheel
     * - Ch 10: Gobo rotation
     * - Ch 11: Focus
     * - Ch 12: Prism (open/3-facet)
     * - Ch 13: LED Macros / Sound active
     * - Ch 14: Pan/Tilt Macros / Sound active
     * - Ch 15: Motor mode / Reset
     */
    @FixtureType("fusion-100-spot-mkii-15ch", manufacturer = "Equinox", model = "Fusion 100 Spot MKII")
    class Mode15Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Fusion100SpotMkIIFixture(
        universe, firstChannel, 15, key, fixtureName, transaction
    ), WithDimmer, WithStrobe {
        override val mode = Mode.MODE_15CH

        private constructor(fixture: Mode15Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode15Ch =
            Mode15Ch(this, transaction)

        @FixtureProperty("Pan adjustment 0-540°", category = PropertyCategory.POSITION)
        val pan = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan fine adjustment", category = PropertyCategory.POSITION)
        val panFine = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Tilt adjustment 0-210°", category = PropertyCategory.POSITION)
        val tilt = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Tilt fine adjustment", category = PropertyCategory.POSITION)
        val tiltFine = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Pan/tilt speed", category = PropertyCategory.SPEED)
        val panTiltSpeed = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty("Dimmer", category = PropertyCategory.DIMMER)
        override val dimmer = DmxSlider(transaction, universe, firstChannel + 5)

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 6)

        @FixtureProperty("Colour", category = PropertyCategory.COLOUR)
        val colour = DmxFixtureSetting(transaction, universe, firstChannel + 7, Colour.entries.toTypedArray())

        @FixtureProperty("Gobo", category = PropertyCategory.SETTING)
        val gobo = DmxFixtureSetting(transaction, universe, firstChannel + 8, Gobo.entries.toTypedArray())

        @FixtureProperty("Gobo rotation", category = PropertyCategory.SETTING)
        val goboRotation = DmxFixtureSetting(transaction, universe, firstChannel + 9, GoboRotation.entries.toTypedArray())

        @FixtureProperty("Focus", category = PropertyCategory.OTHER)
        val focus = DmxSlider(transaction, universe, firstChannel + 10)

        @FixtureProperty("Prism", category = PropertyCategory.SETTING)
        val prism = DmxFixtureSetting(transaction, universe, firstChannel + 11, PrismMode.entries.toTypedArray())

        @FixtureProperty("LED macro", category = PropertyCategory.SETTING)
        val ledMacro = DmxFixtureSetting(transaction, universe, firstChannel + 12, LedMacro.entries.toTypedArray())

        @FixtureProperty("Pan/tilt macro", category = PropertyCategory.SETTING)
        val panTiltMacro = DmxFixtureSetting(transaction, universe, firstChannel + 13, PanTiltMacro.entries.toTypedArray())

        @FixtureProperty("Motor mode", category = PropertyCategory.SETTING)
        val motorMode = DmxFixtureSetting(transaction, universe, firstChannel + 14, MotorMode.entries.toTypedArray())
    }
}
