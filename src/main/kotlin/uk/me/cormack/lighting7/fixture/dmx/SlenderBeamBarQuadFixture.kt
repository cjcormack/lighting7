package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import kotlin.math.roundToInt

/**
 * Equinox Slender Beam Bar Quad - A 4-head LED moving head bar.
 *
 * This fixture supports 5 different DMX modes with varying channel counts
 * and capabilities. Each mode is represented as a distinct subclass.
 *
 * The fixture has 4 independently controllable moving heads, each with
 * RGBW color presets (not continuous RGB mixing).
 *
 * DMX Modes:
 * - 1CH: Show presets only
 * - 6CH: Basic global control (dimmer, strobe, movement presets)
 * - 12CH: Per-head control only (pan, tilt, color per head)
 * - 14CH: Global dimmer/strobe + per-head control
 * - 27CH: Full control with fine channels
 *
 * @see <a href="https://www.prolight.co.uk">Equinox EQLED020</a>
 */
sealed class SlenderBeamBarQuadFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    position: Int,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName, position),
    MultiModeFixtureFamily<SlenderBeamBarQuadFixture.Mode> {

    // ============================================
    // Channel Mode Enum
    // ============================================

    /**
     * DMX channel modes for the Equinox Slender Beam Bar Quad.
     * Mode is set via DIP switches on the fixture.
     */
    enum class Mode(
        override val channelCount: Int,
        override val modeName: String
    ) : DmxChannelMode {
        MODE_1CH(1, "1-Channel (Show Presets)"),
        MODE_6CH(6, "6-Channel (Basic Control)"),
        MODE_12CH(12, "12-Channel (Per-Head)"),
        MODE_14CH(14, "14-Channel (Global + Per-Head)"),
        MODE_27CH(27, "27-Channel (Full Control)")
    }

    // ============================================
    // Setting Value Enums
    // ============================================

    /**
     * Color presets for the RGBW LED heads.
     * Values based on DMX chart from manual.
     */
    enum class Colour(override val level: UByte) : DmxFixtureSettingValue {
        BLACKOUT(0u),
        RED(8u),
        GREEN(25u),
        BLUE(42u),
        WHITE(58u),
        RED_GREEN(75u),
        RED_BLUE(91u),
        RED_WHITE(108u),
        GREEN_BLUE(124u),
        GREEN_WHITE(141u),
        BLUE_WHITE(157u),
        RED_GREEN_BLUE(174u),
        RED_GREEN_WHITE(190u),
        RED_BLUE_WHITE(207u),
        GREEN_BLUE_WHITE(223u),
        RED_GREEN_BLUE_WHITE(240u);
    }

    /**
     * Show/movement presets for 1CH and 6CH modes.
     */
    enum class ShowMode(override val level: UByte) : DmxFixtureSettingValue {
        OFF(0u),
        MOVEMENT_1(8u),
        MOVEMENT_2(23u),
        MOVEMENT_3(38u),
        MOVEMENT_4(53u),
        MOVEMENT_5(68u),
        MOVEMENT_6(83u),
        MOVEMENT_7(98u),
        MOVEMENT_8(113u),
        MOVEMENT_9(128u),
        MOVEMENT_10(143u),
        MOVEMENT_11(158u),
        MOVEMENT_12(173u),
        MOVEMENT_13(188u),
        MOVEMENT_14(203u),
        MOVEMENT_15(218u),
        MOVEMENT_16(233u),
        SOUND_ACTIVE(248u);
    }

    /**
     * Color chase modes for 6CH mode.
     */
    enum class ColourChase(override val level: UByte) : DmxFixtureSettingValue {
        STATIC_COLOUR(0u),
        COLOUR_CHASE(15u);
    }

    /**
     * Special function channel options for 27CH mode.
     */
    enum class SpecialFunction(override val level: UByte) : DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        RESET(200u),
        STAND_ALONE(240u);
    }

    // ============================================
    // Strobe Implementation
    // ============================================

    /**
     * Strobe control for modes that support it.
     * 0-7 = open (full on), 8-255 = strobe slow to fast
     */
    class Strobe(
        transaction: ControllerTransaction?,
        universe: Universe,
        channelNo: Int
    ) : DmxFixtureSlider(transaction, universe, channelNo), FixtureStrobe {
        override fun fullOn() {
            value = 0u
        }

        override fun strobe(intensity: UByte) {
            value = if (intensity == 0u.toUByte()) {
                0u
            } else {
                ((247F / 255F * intensity.toFloat()).roundToInt() + 8).toUByte()
            }
        }
    }

    // ============================================
    // Head Element Classes
    // ============================================

    /**
     * Base class for a single moving head within the Slender Beam Bar Quad.
     */
    abstract inner class Head(
        override val elementIndex: Int,
        protected val headTransaction: ControllerTransaction?
    ) : FixtureElement<SlenderBeamBarQuadFixture> {

        override val parentFixture: SlenderBeamBarQuadFixture
            get() = this@SlenderBeamBarQuadFixture

        override val elementKey: String
            get() = "${this@SlenderBeamBarQuadFixture.key}.head-$elementIndex"

        /** Display name for this head */
        val headName: String = "$fixtureName Head ${elementIndex + 1}"

        abstract fun withTransaction(transaction: ControllerTransaction): Head
    }

    /**
     * Basic head for 12CH and 14CH modes: pan, tilt, color preset (3 channels per head).
     */
    inner class BasicHead(
        elementIndex: Int,
        headTransaction: ControllerTransaction?,
        private val headFirstChannel: Int
    ) : Head(elementIndex, headTransaction), FixtureWithPosition {

        @FixtureProperty("Head pan 0-540")
        override val pan = DmxFixtureSlider(headTransaction, universe, headFirstChannel)

        @FixtureProperty("Head tilt 0-270")
        override val tilt = DmxFixtureSlider(headTransaction, universe, headFirstChannel + 1)

        @FixtureProperty("Head colour preset")
        val colour = DmxFixtureSetting(headTransaction, universe, headFirstChannel + 2, Colour.entries.toTypedArray())

        override fun withTransaction(transaction: ControllerTransaction): BasicHead =
            BasicHead(elementIndex, transaction, headFirstChannel)

        override fun toString(): String = "BasicHead($elementKey)"
    }

    /**
     * Full head for 27CH mode: pan, pan-fine, tilt, tilt-fine, speed, color preset (6 channels per head).
     */
    inner class FullHead(
        elementIndex: Int,
        headTransaction: ControllerTransaction?,
        private val headFirstChannel: Int
    ) : Head(elementIndex, headTransaction), FixtureWithPosition {

        @FixtureProperty("Head pan 0-540")
        override val pan = DmxFixtureSlider(headTransaction, universe, headFirstChannel)

        @FixtureProperty("Head pan fine")
        val panFine = DmxFixtureSlider(headTransaction, universe, headFirstChannel + 1)

        @FixtureProperty("Head tilt 0-270")
        override val tilt = DmxFixtureSlider(headTransaction, universe, headFirstChannel + 2)

        @FixtureProperty("Head tilt fine")
        val tiltFine = DmxFixtureSlider(headTransaction, universe, headFirstChannel + 3)

        @FixtureProperty("Head pan/tilt speed (fast to slow)")
        val speed = DmxFixtureSlider(headTransaction, universe, headFirstChannel + 4)

        @FixtureProperty("Head colour preset")
        val colour = DmxFixtureSetting(headTransaction, universe, headFirstChannel + 5, Colour.entries.toTypedArray())

        override fun withTransaction(transaction: ControllerTransaction): FullHead =
            FullHead(elementIndex, transaction, headFirstChannel)

        override fun toString(): String = "FullHead($elementKey)"
    }

    // ============================================
    // Mode-Specific Subclasses
    // ============================================

    /**
     * 1-Channel Mode: Show preset selection only.
     *
     * DMX Layout (1 channel):
     * - Ch 1: Show mode preset
     */
    @FixtureType("slender-beam-bar-quad-1ch")
    class Mode1Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        position: Int,
        transaction: ControllerTransaction? = null,
    ) : SlenderBeamBarQuadFixture(
        universe, firstChannel, 1, key, fixtureName, position, transaction
    ) {
        override val mode = Mode.MODE_1CH

        private constructor(fixture: Mode1Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, fixture.position, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode1Ch =
            Mode1Ch(this, transaction)

        @FixtureProperty("Show mode preset")
        val showMode = DmxFixtureSetting(transaction, universe, firstChannel, ShowMode.entries.toTypedArray())
    }

    /**
     * 6-Channel Mode: Basic global control without per-head addressing.
     *
     * DMX Layout (6 channels):
     * - Ch 1: Dimmer
     * - Ch 2: Strobe (0-7 open, 8-255 strobe slow-fast)
     * - Ch 3: Movement preset
     * - Ch 4: Movement speed
     * - Ch 5: Colour chase preset
     * - Ch 6: Colour chase speed
     */
    @FixtureType("slender-beam-bar-quad-6ch")
    class Mode6Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        position: Int,
        transaction: ControllerTransaction? = null,
    ) : SlenderBeamBarQuadFixture(
        universe, firstChannel, 6, key, fixtureName, position, transaction
    ), FixtureWithDimmer, FixtureWithStrobe {

        override val mode = Mode.MODE_6CH

        private constructor(fixture: Mode6Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, fixture.position, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode6Ch =
            Mode6Ch(this, transaction)

        @FixtureProperty("Dimmer")
        override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel)

        @FixtureProperty("Strobe")
        override val strobe = Strobe(transaction, universe, firstChannel + 1)

        @FixtureProperty("Movement preset")
        val movementPreset = DmxFixtureSetting(transaction, universe, firstChannel + 2, ShowMode.entries.toTypedArray())

        @FixtureProperty("Movement speed")
        val movementSpeed = DmxFixtureSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Colour chase")
        val colourChase = DmxFixtureSetting(transaction, universe, firstChannel + 4, ColourChase.entries.toTypedArray())

        @FixtureProperty("Colour chase speed")
        val colourChaseSpeed = DmxFixtureSlider(transaction, universe, firstChannel + 5)
    }

    /**
     * 12-Channel Mode: Per-head control only (no global dimmer/strobe).
     *
     * DMX Layout (12 channels):
     * - Heads 1-4 (3 channels each, starting at 1, 4, 7, 10):
     *   - +0: Pan
     *   - +1: Tilt
     *   - +2: Colour preset
     */
    @FixtureType("slender-beam-bar-quad-12ch")
    class Mode12Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        position: Int,
        transaction: ControllerTransaction? = null,
    ) : SlenderBeamBarQuadFixture(
        universe, firstChannel, 12, key, fixtureName, position, transaction
    ), MultiElementFixture<SlenderBeamBarQuadFixture.BasicHead> {

        override val mode = Mode.MODE_12CH

        private constructor(fixture: Mode12Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, fixture.position, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode12Ch =
            Mode12Ch(this, transaction)

        override val elements: List<BasicHead> = (0 until 4).map { idx ->
            BasicHead(idx, transaction, firstChannel + (idx * 3))
        }

        override val elementCount: Int = 4

        fun head(index: Int): BasicHead {
            require(index in 0 until 4) { "Head index must be 0-3, got $index" }
            return elements[index]
        }

        /** Set all heads to the same colour */
        fun setAllHeadsColour(colour: Colour) {
            elements.forEach { it.colour.setting = colour }
        }
    }

    /**
     * 14-Channel Mode: Global dimmer/strobe plus per-head control.
     *
     * DMX Layout (14 channels):
     * - Ch 1: Master dimmer
     * - Ch 2: Master strobe
     * - Heads 1-4 (3 channels each, starting at 3, 6, 9, 12):
     *   - +0: Pan
     *   - +1: Tilt
     *   - +2: Colour preset
     */
    @FixtureType("slender-beam-bar-quad-14ch")
    class Mode14Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        position: Int,
        transaction: ControllerTransaction? = null,
    ) : SlenderBeamBarQuadFixture(
        universe, firstChannel, 14, key, fixtureName, position, transaction
    ), FixtureWithDimmer, FixtureWithStrobe, MultiElementFixture<SlenderBeamBarQuadFixture.BasicHead> {

        override val mode = Mode.MODE_14CH

        private constructor(fixture: Mode14Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, fixture.position, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode14Ch =
            Mode14Ch(this, transaction)

        @FixtureProperty("Master dimmer")
        override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel)

        @FixtureProperty("Master strobe")
        override val strobe = Strobe(transaction, universe, firstChannel + 1)

        override val elements: List<BasicHead> = (0 until 4).map { idx ->
            BasicHead(idx, transaction, firstChannel + 2 + (idx * 3))
        }

        override val elementCount: Int = 4

        fun head(index: Int): BasicHead {
            require(index in 0 until 4) { "Head index must be 0-3, got $index" }
            return elements[index]
        }

        /** Set all heads to the same colour */
        fun setAllHeadsColour(colour: Colour) {
            elements.forEach { it.colour.setting = colour }
        }

        override fun blackout() {
            super.blackout()
            elements.forEach { it.colour.setting = Colour.BLACKOUT }
        }
    }

    /**
     * 27-Channel Mode: Full control with fine pan/tilt and special functions.
     *
     * DMX Layout (27 channels):
     * - Ch 1: Master dimmer
     * - Ch 2: Master strobe
     * - Heads 1-4 (6 channels each, starting at 3, 9, 15, 21):
     *   - +0: Pan
     *   - +1: Pan fine
     *   - +2: Tilt
     *   - +3: Tilt fine
     *   - +4: Pan/Tilt speed (fast to slow)
     *   - +5: Colour preset
     * - Ch 27: Special function (reset, etc.)
     */
    @FixtureType("slender-beam-bar-quad-27ch")
    class Mode27Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        position: Int,
        transaction: ControllerTransaction? = null,
    ) : SlenderBeamBarQuadFixture(
        universe, firstChannel, 27, key, fixtureName, position, transaction
    ), FixtureWithDimmer, FixtureWithStrobe, MultiElementFixture<SlenderBeamBarQuadFixture.FullHead> {

        override val mode = Mode.MODE_27CH

        private constructor(fixture: Mode27Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, fixture.position, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode27Ch =
            Mode27Ch(this, transaction)

        @FixtureProperty("Master dimmer")
        override val dimmer = DmxFixtureSlider(transaction, universe, firstChannel)

        @FixtureProperty("Master strobe")
        override val strobe = Strobe(transaction, universe, firstChannel + 1)

        override val elements: List<FullHead> = (0 until 4).map { idx ->
            FullHead(idx, transaction, firstChannel + 2 + (idx * 6))
        }

        override val elementCount: Int = 4

        fun head(index: Int): FullHead {
            require(index in 0 until 4) { "Head index must be 0-3, got $index" }
            return elements[index]
        }

        @FixtureProperty("Special function")
        val specialFunction = DmxFixtureSetting(transaction, universe, firstChannel + 26, SpecialFunction.entries.toTypedArray())

        /** Set all heads to the same colour */
        fun setAllHeadsColour(colour: Colour) {
            elements.forEach { it.colour.setting = colour }
        }

        override fun blackout() {
            super.blackout()
            elements.forEach { it.colour.setting = Colour.BLACKOUT }
        }
    }
}
