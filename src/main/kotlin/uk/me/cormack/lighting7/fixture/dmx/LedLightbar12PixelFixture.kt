package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import kotlin.math.roundToInt

/**
 * Showtec LED Lightbar 12 Pixel - A 12-pixel RGBW LED bar fixture.
 *
 * This fixture supports 7 different DMX modes with varying channel counts
 * and capabilities. Each mode is represented as a distinct subclass.
 *
 * The fixture has 12 x 4W RGBW LEDs that can be controlled individually
 * in 48-channel mode, in sections (2, 4) in 10/18-channel modes, or
 * as a single unit in simpler modes.
 *
 * DMX Modes:
 * - 4CH (Program): Pixel programs, color running programs, speed, strobe
 * - 4CH (RGBW): Direct RGBW color mixing
 * - 6CH: Dimmer, strobe, RGBW
 * - 10CH: Dimmer, strobe, 2 sections of RGBW
 * - 12CH: Full features with programs and RGBW
 * - 18CH: Dimmer, strobe, 4 sections of RGBW
 * - 48CH: Full pixel control (12 pixels x RGBW)
 *
 * @see <a href="https://www.highlite.com">Showtec 42197</a>
 */
sealed class LedLightbar12PixelFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<LedLightbar12PixelFixture.Mode> {

    // ============================================
    // Channel Mode Enum
    // ============================================

    /**
     * DMX channel modes for the Showtec LED Lightbar 12 Pixel.
     * Mode is set via the fixture's menu system.
     */
    enum class Mode(
        override val channelCount: Int,
        override val modeName: String
    ) : DmxChannelMode {
        MODE_4CH_PROGRAM(4, "4-Channel (Programs)"),
        MODE_4CH_RGBW(4, "4-Channel (RGBW)"),
        MODE_6CH(6, "6-Channel (Dimmer + RGBW)"),
        MODE_10CH(10, "10-Channel (2 Sections)"),
        MODE_12CH(12, "12-Channel (Full Features)"),
        MODE_18CH(18, "18-Channel (4 Sections)"),
        MODE_48CH(48, "48-Channel (Pixel Control)")
    }

    // ============================================
    // Setting Value Enums
    // ============================================

    /**
     * Pixel programs for program modes.
     * Based on DMX chart from manual.
     */
    enum class PixelProgram(override val level: UByte) : DmxFixtureSettingValue {
        OFF(0u),
        PROGRAM_1(2u),
        PROGRAM_2(25u),
        PROGRAM_3(50u),
        PROGRAM_4(75u),
        PROGRAM_5(100u),
        PROGRAM_6(125u),
        PROGRAM_7(150u),
        PROGRAM_8(175u),
        PROGRAM_9(200u),
        PROGRAM_10(225u),
        PROGRAM_11(250u);
    }

    /**
     * Color running programs for program modes.
     * Based on DMX chart from manual.
     */
    enum class ColorRunningProgram(override val level: UByte) : DmxFixtureSettingValue {
        OFF(0u),
        PROGRAM_1(2u),
        PROGRAM_2(36u),
        PROGRAM_3(54u),
        PROGRAM_4(72u),
        PROGRAM_5(90u),
        PROGRAM_6(108u),
        PROGRAM_7(126u),
        PROGRAM_8(144u),
        PROGRAM_9(162u),
        PROGRAM_10(180u),
        PROGRAM_11(198u),
        PROGRAM_12(216u),
        PROGRAM_13(234u),
        PROGRAM_14(252u);
    }

    /**
     * Color presets for 12-channel mode.
     * Based on DMX chart from manual.
     */
    enum class ColorPreset(
        override val level: UByte,
        override val colourPreview: String?
    ) : DmxFixtureColourSettingValue {
        OFF(0u, null),
        COLOR_1_RED(2u, "#FF0000"),
        COLOR_2_RED_WHITE(7u, "#FF6464"),
        COLOR_3_RED_WHITE_BRIGHT(14u, "#FFC8C8"),
        COLOR_4_RED_ORANGE(21u, "#FF3200"),
        COLOR_5_ORANGE(28u, "#FF9600"),
        COLOR_6_YELLOW(35u, "#FFFF00"),
        COLOR_7_YELLOW_WHITE(42u, "#FFFF4B"),
        COLOR_8_YELLOW_WHITE_BRIGHT(49u, "#FFFFFF"),
        COLOR_9_GREEN_WHITE(56u, "#64FF96"),
        COLOR_10_GREEN_WHITE_DIM(63u, "#32FF32"),
        COLOR_11_GREEN(70u, "#00FF00"),
        COLOR_12_GREEN_CYAN(77u, "#00FF32"),
        COLOR_13_CYAN_GREEN(84u, "#00FF96"),
        COLOR_14_CYAN(91u, "#00FFFF"),
        COLOR_15_CYAN_WHITE(98u, "#4BFFFF"),
        COLOR_16_CYAN_WHITE_DIM(105u, "#96FFFF"),
        COLOR_17_CYAN_BLUE_WHITE(112u, "#64C8FF"),
        COLOR_18_BLUE_WHITE(119u, "#6464FF"),
        COLOR_19_BLUE_WHITE_DIM(126u, "#3232FF"),
        COLOR_20_BLUE(133u, "#0000FF"),
        COLOR_21_BLUE_PURPLE(140u, "#4B00FF"),
        COLOR_22_PURPLE(147u, "#A000FF"),
        COLOR_23_MAGENTA(154u, "#FF00FF"),
        COLOR_24_MAGENTA_RED(161u, "#FF00AF"),
        COLOR_25_PINK(168u, "#FF0064"),
        COLOR_26_PINK_WHITE(175u, "#FF3264"),
        COLOR_27_SALMON(182u, "#FF0019"),
        COLOR_28_SALMON_WHITE(189u, "#FF001919"),
        COLOR_29_RED_DIM(196u, "#FF0019"),
        COLOR_30_WHITE(203u, "#FFFFFF"),
        COLOR_31_WARM_WHITE(210u, "#FFFF4B"),
        COLOR_32_COOL_WHITE(217u, "#C8C8FF"),
        COLOR_33_FULL_WHITE(224u, "#FFFFFF");
    }

    /**
     * Sound mode sensitivity settings.
     */
    enum class SoundMode(override val level: UByte) : DmxFixtureSettingValue {
        OFF(0u),
        SENSITIVITY_MIN(51u),
        SENSITIVITY_MAX(255u);
    }

    // ============================================
    // Strobe Implementation
    // ============================================

    /**
     * Strobe control for modes that support it.
     * 0-1 = off, 2-255 = strobe slow to fast (0-20Hz)
     */
    class StrobeChannel(
        transaction: ControllerTransaction?,
        universe: Universe,
        channelNo: Int
    ) : DmxSlider(transaction, universe, channelNo), Strobe {
        override fun fullOn() {
            value = 0u
        }

        override fun strobe(intensity: UByte) {
            value = if (intensity == 0u.toUByte()) {
                0u
            } else {
                ((253F / 255F * intensity.toFloat()).roundToInt() + 2).toUByte()
            }
        }
    }

    // ============================================
    // Pixel/Section Element Classes
    // ============================================

    /**
     * Base class for a single controllable section/pixel within the LED bar.
     */
    abstract inner class Pixel(
        override val elementIndex: Int,
        protected val pixelTransaction: ControllerTransaction?
    ) : FixtureElement<LedLightbar12PixelFixture> {

        override val parentFixture: LedLightbar12PixelFixture
            get() = this@LedLightbar12PixelFixture

        override val elementKey: String
            get() = "${this@LedLightbar12PixelFixture.key}.pixel-$elementIndex"

        /** Display name for this pixel/section */
        val pixelName: String = "$fixtureName Pixel ${elementIndex + 1}"

        abstract override fun withTransaction(transaction: ControllerTransaction): Pixel
    }

    /**
     * RGBW pixel for 10CH, 18CH, and 48CH modes.
     * Each pixel has 4 channels: Red, Green, Blue, White.
     */
    inner class RgbwPixel(
        elementIndex: Int,
        pixelTransaction: ControllerTransaction?,
        private val pixelFirstChannel: Int
    ) : Pixel(elementIndex, pixelTransaction), WithColour {

        @FixtureProperty("RGB colour", category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            pixelTransaction,
            universe,
            pixelFirstChannel,
            pixelFirstChannel + 1,
            pixelFirstChannel + 2
        )

        @FixtureProperty("White", category = PropertyCategory.WHITE, bundleWithColour = true)
        val white = DmxSlider(pixelTransaction, universe, pixelFirstChannel + 3)

        override fun withTransaction(transaction: ControllerTransaction): RgbwPixel =
            RgbwPixel(elementIndex, transaction, pixelFirstChannel)

        override fun toString(): String = "RgbwPixel($elementKey)"
    }

    // ============================================
    // Mode-Specific Subclasses
    // ============================================

    /**
     * 4-Channel Mode (Program): Pixel programs and color running programs.
     *
     * DMX Layout (4 channels):
     * - Ch 1: Pixel Programs
     * - Ch 2: Color Running Programs
     * - Ch 3: Speed
     * - Ch 4: Linear Strobe
     */
    @FixtureType("led-lightbar-12-pixel-4ch-program", manufacturer = "Showtec", model = "LED Lightbar 12 Pixel")
    class Mode4ChProgram(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : LedLightbar12PixelFixture(
        universe, firstChannel, 4, key, fixtureName, transaction
    ), WithStrobe {

        override val mode = Mode.MODE_4CH_PROGRAM

        private constructor(fixture: Mode4ChProgram, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode4ChProgram =
            Mode4ChProgram(this, transaction)

        @FixtureProperty("Pixel program", category = PropertyCategory.SETTING)
        val pixelProgram = DmxFixtureSetting(transaction, universe, firstChannel, PixelProgram.entries.toTypedArray())

        @FixtureProperty("Color running program", category = PropertyCategory.SETTING)
        val colorRunningProgram = DmxFixtureSetting(transaction, universe, firstChannel + 1, ColorRunningProgram.entries.toTypedArray())

        @FixtureProperty("Speed", category = PropertyCategory.SPEED)
        val speed = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 3)
    }

    /**
     * 4-Channel Mode (RGBW): Direct RGBW color mixing.
     *
     * DMX Layout (4 channels):
     * - Ch 1: Red
     * - Ch 2: Green
     * - Ch 3: Blue
     * - Ch 4: White
     */
    @FixtureType("led-lightbar-12-pixel-4ch-rgbw", manufacturer = "Showtec", model = "LED Lightbar 12 Pixel")
    class Mode4ChRgbw(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : LedLightbar12PixelFixture(
        universe, firstChannel, 4, key, fixtureName, transaction
    ), WithColour {

        override val mode = Mode.MODE_4CH_RGBW

        private constructor(fixture: Mode4ChRgbw, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode4ChRgbw =
            Mode4ChRgbw(this, transaction)

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            transaction,
            universe,
            firstChannel,
            firstChannel + 1,
            firstChannel + 2
        )

        @FixtureProperty("White", category = PropertyCategory.WHITE, bundleWithColour = true)
        val white = DmxSlider(transaction, universe, firstChannel + 3)
    }

    /**
     * 6-Channel Mode: Dimmer, strobe, and RGBW.
     *
     * DMX Layout (6 channels):
     * - Ch 1: Dimmer
     * - Ch 2: Linear Strobe
     * - Ch 3: Red
     * - Ch 4: Green
     * - Ch 5: Blue
     * - Ch 6: White
     */
    @FixtureType("led-lightbar-12-pixel-6ch", manufacturer = "Showtec", model = "LED Lightbar 12 Pixel")
    class Mode6Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : LedLightbar12PixelFixture(
        universe, firstChannel, 6, key, fixtureName, transaction
    ), WithDimmer, WithColour, WithStrobe {

        override val mode = Mode.MODE_6CH

        private constructor(fixture: Mode6Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode6Ch =
            Mode6Ch(this, transaction)

        @FixtureProperty("Dimmer", category = PropertyCategory.DIMMER)
        override val dimmer = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 1)

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            transaction,
            universe,
            firstChannel + 2,
            firstChannel + 3,
            firstChannel + 4
        )

        @FixtureProperty("White", category = PropertyCategory.WHITE, bundleWithColour = true)
        val white = DmxSlider(transaction, universe, firstChannel + 5)
    }

    /**
     * 10-Channel Mode: Dimmer, strobe, and 2 sections of RGBW.
     *
     * DMX Layout (10 channels):
     * - Ch 1: Dimmer
     * - Ch 2: Linear Strobe
     * - Ch 3-6: Section 1 RGBW
     * - Ch 7-10: Section 2 RGBW
     */
    @FixtureType("led-lightbar-12-pixel-10ch", manufacturer = "Showtec", model = "LED Lightbar 12 Pixel")
    class Mode10Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : LedLightbar12PixelFixture(
        universe, firstChannel, 10, key, fixtureName, transaction
    ), WithDimmer, WithStrobe, MultiElementFixture<LedLightbar12PixelFixture.RgbwPixel> {

        override val mode = Mode.MODE_10CH

        private constructor(fixture: Mode10Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode10Ch =
            Mode10Ch(this, transaction)

        @FixtureProperty("Dimmer", category = PropertyCategory.DIMMER)
        override val dimmer = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 1)

        override val elements: List<RgbwPixel> = (0 until 2).map { idx ->
            RgbwPixel(idx, transaction, firstChannel + 2 + (idx * 4))
        }

        override val elementCount: Int = 2

        fun section(index: Int): RgbwPixel {
            require(index in 0 until 2) { "Section index must be 0-1, got $index" }
            return elements[index]
        }

        /** Set all sections to the same colour */
        fun setAllSectionsColour(red: UByte, green: UByte, blue: UByte, white: UByte) {
            elements.forEach {
                it.rgbColour.redSlider.value = red
                it.rgbColour.greenSlider.value = green
                it.rgbColour.blueSlider.value = blue
                it.white.value = white
            }
        }
    }

    /**
     * 12-Channel Mode: Full features with programs, presets, and RGBW.
     *
     * DMX Layout (12 channels):
     * - Ch 1: Dimmer
     * - Ch 2: Linear Strobe
     * - Ch 3: Random Strobe
     * - Ch 4: Color Presets
     * - Ch 5: Pixel Programs
     * - Ch 6: Color Running Programs
     * - Ch 7: Speed
     * - Ch 8: Sound Mode
     * - Ch 9: Red
     * - Ch 10: Green
     * - Ch 11: Blue
     * - Ch 12: White
     */
    @FixtureType("led-lightbar-12-pixel-12ch", manufacturer = "Showtec", model = "LED Lightbar 12 Pixel")
    class Mode12Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : LedLightbar12PixelFixture(
        universe, firstChannel, 12, key, fixtureName, transaction
    ), WithDimmer, WithColour, WithStrobe {

        override val mode = Mode.MODE_12CH

        private constructor(fixture: Mode12Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode12Ch =
            Mode12Ch(this, transaction)

        @FixtureProperty("Dimmer", category = PropertyCategory.DIMMER)
        override val dimmer = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 1)

        @FixtureProperty("Random strobe", category = PropertyCategory.STROBE)
        val randomStrobe = StrobeChannel(transaction, universe, firstChannel + 2)

        @FixtureProperty("Color preset", category = PropertyCategory.COLOUR)
        val colorPreset = DmxFixtureSetting(transaction, universe, firstChannel + 3, ColorPreset.entries.toTypedArray())

        @FixtureProperty("Pixel program", category = PropertyCategory.SETTING)
        val pixelProgram = DmxFixtureSetting(transaction, universe, firstChannel + 4, PixelProgram.entries.toTypedArray())

        @FixtureProperty("Color running program", category = PropertyCategory.SETTING)
        val colorRunningProgram = DmxFixtureSetting(transaction, universe, firstChannel + 5, ColorRunningProgram.entries.toTypedArray())

        @FixtureProperty("Speed", category = PropertyCategory.SPEED)
        val speed = DmxSlider(transaction, universe, firstChannel + 6)

        @FixtureProperty("Sound mode", category = PropertyCategory.SETTING)
        val soundMode = DmxFixtureSetting(transaction, universe, firstChannel + 7, SoundMode.entries.toTypedArray())

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            transaction,
            universe,
            firstChannel + 8,
            firstChannel + 9,
            firstChannel + 10
        )

        @FixtureProperty("White", category = PropertyCategory.WHITE, bundleWithColour = true)
        val white = DmxSlider(transaction, universe, firstChannel + 11)
    }

    /**
     * 18-Channel Mode: Dimmer, strobe, and 4 sections of RGBW.
     *
     * DMX Layout (18 channels):
     * - Ch 1: Dimmer
     * - Ch 2: Linear Strobe
     * - Ch 3-6: Section 1 RGBW
     * - Ch 7-10: Section 2 RGBW
     * - Ch 11-14: Section 3 RGBW
     * - Ch 15-18: Section 4 RGBW
     */
    @FixtureType("led-lightbar-12-pixel-18ch", manufacturer = "Showtec", model = "LED Lightbar 12 Pixel")
    class Mode18Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : LedLightbar12PixelFixture(
        universe, firstChannel, 18, key, fixtureName, transaction
    ), WithDimmer, WithStrobe, MultiElementFixture<LedLightbar12PixelFixture.RgbwPixel> {

        override val mode = Mode.MODE_18CH

        private constructor(fixture: Mode18Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode18Ch =
            Mode18Ch(this, transaction)

        @FixtureProperty("Dimmer", category = PropertyCategory.DIMMER)
        override val dimmer = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = StrobeChannel(transaction, universe, firstChannel + 1)

        override val elements: List<RgbwPixel> = (0 until 4).map { idx ->
            RgbwPixel(idx, transaction, firstChannel + 2 + (idx * 4))
        }

        override val elementCount: Int = 4

        fun section(index: Int): RgbwPixel {
            require(index in 0 until 4) { "Section index must be 0-3, got $index" }
            return elements[index]
        }

        /** Set all sections to the same colour */
        fun setAllSectionsColour(red: UByte, green: UByte, blue: UByte, white: UByte) {
            elements.forEach {
                it.rgbColour.redSlider.value = red
                it.rgbColour.greenSlider.value = green
                it.rgbColour.blueSlider.value = blue
                it.white.value = white
            }
        }
    }

    /**
     * 48-Channel Mode: Full pixel control with 12 individual RGBW pixels.
     *
     * DMX Layout (48 channels):
     * - Ch 1-4: Pixel 1 RGBW
     * - Ch 5-8: Pixel 2 RGBW
     * - Ch 9-12: Pixel 3 RGBW
     * - Ch 13-16: Pixel 4 RGBW
     * - Ch 17-20: Pixel 5 RGBW
     * - Ch 21-24: Pixel 6 RGBW
     * - Ch 25-28: Pixel 7 RGBW
     * - Ch 29-32: Pixel 8 RGBW
     * - Ch 33-36: Pixel 9 RGBW
     * - Ch 37-40: Pixel 10 RGBW
     * - Ch 41-44: Pixel 11 RGBW
     * - Ch 45-48: Pixel 12 RGBW
     */
    @FixtureType("led-lightbar-12-pixel-48ch", manufacturer = "Showtec", model = "LED Lightbar 12 Pixel")
    class Mode48Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : LedLightbar12PixelFixture(
        universe, firstChannel, 48, key, fixtureName, transaction
    ), MultiElementFixture<LedLightbar12PixelFixture.RgbwPixel> {

        override val mode = Mode.MODE_48CH

        private constructor(fixture: Mode48Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode48Ch =
            Mode48Ch(this, transaction)

        override val elements: List<RgbwPixel> = (0 until 12).map { idx ->
            RgbwPixel(idx, transaction, firstChannel + (idx * 4))
        }

        override val elementCount: Int = 12

        fun pixel(index: Int): RgbwPixel {
            require(index in 0 until 12) { "Pixel index must be 0-11, got $index" }
            return elements[index]
        }

        /** Set all pixels to the same colour */
        fun setAllPixelsColour(red: UByte, green: UByte, blue: UByte, white: UByte) {
            elements.forEach {
                it.rgbColour.redSlider.value = red
                it.rgbColour.greenSlider.value = green
                it.rgbColour.blueSlider.value = blue
                it.white.value = white
            }
        }

        /** Set a range of pixels to a colour */
        fun setPixelRangeColour(startIndex: Int, endIndex: Int, red: UByte, green: UByte, blue: UByte, white: UByte) {
            require(startIndex in 0 until 12) { "Start index must be 0-11, got $startIndex" }
            require(endIndex in startIndex until 12) { "End index must be >= start and <= 11, got $endIndex" }
            (startIndex..endIndex).forEach { idx ->
                elements[idx].rgbColour.redSlider.value = red
                elements[idx].rgbColour.greenSlider.value = green
                elements[idx].rgbColour.blueSlider.value = blue
                elements[idx].white.value = white
            }
        }
    }
}
