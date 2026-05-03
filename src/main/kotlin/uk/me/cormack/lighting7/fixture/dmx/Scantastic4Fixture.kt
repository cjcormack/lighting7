package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe

/**
 * Equinox Scantastic 4 (EQLED55) - A 4-head LED scanner bar.
 *
 * Light Source: 60 x 5mm ultra-bright RGBA LEDs across 4 scanner heads.
 * Pan: 180 degrees per head, Tilt: 90 degrees per head, Beam: 11 degrees.
 *
 * Colour is NOT individually mixed via DMX — it uses colour/pattern selector
 * channels with preset macro values. There are no R/G/B/A intensity channels.
 *
 * Channel 1 in 8CH and 17CH modes is a binary shutter: 0-127 = blackout,
 * 128-255 = full on. There is no graduated dimming.
 *
 * DMX Modes:
 * - 8CH: Macro/effect mode with binary shutter, strobe, colour selectors, movement macros
 * - 12CH: Per-head control only (colour/pattern + pan + tilt per head)
 * - 17CH: Full control — 8CH macros + per-head pan/tilt + sound active
 *
 * @see <a href="https://www.prolight.co.uk">Equinox EQLED55</a>
 */
sealed class Scantastic4Fixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<Scantastic4Fixture.Mode> {

    // ============================================
    // Channel Mode Enum
    // ============================================

    /**
     * DMX channel modes for the Equinox Scantastic 4.
     * Mode is set via DIP switches on the fixture.
     */
    enum class Mode(
        override val channelCount: Int,
        override val modeName: String
    ) : DmxChannelMode {
        MODE_8CH(8, "8-Channel (Macro/Effect)"),
        MODE_12CH(12, "12-Channel (Per-Head)"),
        MODE_17CH(17, "17-Channel (Full Control)")
    }

    // ============================================
    // Setting Value Enums
    // ============================================

    /**
     * Binary shutter for channels that use 0-127 = blackout, 128-255 = full on.
     * Used in 8CH (ch1) and 17CH (ch1) modes.
     */
    enum class Shutter(override val level: UByte) : DmxFixtureSettingValue {
        BLACKOUT(0u),
        FULL_ON(128u);
    }

    /**
     * Sound active trigger for 17CH mode (ch17).
     * 0-127 = blackout, 128-255 = sound active.
     */
    enum class SoundActive(override val level: UByte) : DmxFixtureSettingValue {
        OFF(0u),
        ON(128u);
    }

    // ============================================
    // Scanner Head Element
    // ============================================

    /**
     * A single scanner head within the Scantastic 4.
     * Each head has a colour/pattern selector, pan, and tilt.
     */
    inner class ScannerHead(
        override val elementIndex: Int,
        private val headTransaction: ControllerTransaction?,
        private val headFirstChannel: Int,
        private val hasColour: Boolean,
    ) : FixtureElement<Scantastic4Fixture>, WithPosition {

        override val parentFixture: Scantastic4Fixture
            get() = this@Scantastic4Fixture

        override val elementKey: String
            get() = "${this@Scantastic4Fixture.key}.scanner-$elementIndex"

        val headName: String = "$fixtureName Scanner ${elementIndex + 1}"

        @FixtureProperty("Scanner colour/pattern selector", category = PropertyCategory.SETTING)
        val colourPattern = if (hasColour) {
            DmxSlider(headTransaction, universe, headFirstChannel)
        } else null

        @FixtureProperty("Scanner pan 0-180", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 180.0)
        override val pan = DmxSlider(headTransaction, universe, if (hasColour) headFirstChannel + 1 else headFirstChannel)

        @FixtureProperty("Scanner tilt 0-90", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 90.0)
        override val tilt = DmxSlider(headTransaction, universe, if (hasColour) headFirstChannel + 2 else headFirstChannel + 1)

        override fun withTransaction(transaction: ControllerTransaction): ScannerHead =
            ScannerHead(elementIndex, transaction, headFirstChannel, hasColour)

        override fun toString(): String = "ScannerHead($elementKey)"
    }

    // ============================================
    // Mode-Specific Subclasses
    // ============================================

    /**
     * 8-Channel Mode: Macro/effect-driven mode with no individual head control.
     * All 4 scanners are controlled together via colour/pattern selectors and movement macros.
     *
     * DMX Layout (8 channels):
     * - Ch 1: Shutter (0-127 blackout, 128-255 full on)
     * - Ch 2: Strobe (0-255)
     * - Ch 3: Colour/pattern selector 1
     * - Ch 4: Colour/pattern selector 2
     * - Ch 5: Auto scroll through patterns
     * - Ch 6: Auto scroll speed
     * - Ch 7: Movement macros
     * - Ch 8: Scanning speed
     */
    @FixtureType("scantastic-4-8ch", manufacturer = "Equinox", model = "Scantastic 4")
    class Mode8Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Scantastic4Fixture(
        universe, firstChannel, 8, key, fixtureName, transaction
    ), WithStrobe {

        override val mode = Mode.MODE_8CH

        private constructor(fixture: Mode8Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode8Ch =
            Mode8Ch(this, transaction)

        @FixtureProperty("Shutter (blackout/full on)", category = PropertyCategory.SETTING, compactDisplay = CompactDisplayRole.PRIMARY)
        val shutter = DmxFixtureSetting(transaction, universe, firstChannel, Shutter.entries.toTypedArray())

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 1,
            strobeMin = 0u, strobeMax = 255u,
        )

        @FixtureProperty("Colour/pattern selector 1", category = PropertyCategory.SETTING, compactDisplay = CompactDisplayRole.SECONDARY)
        val colourPattern1 = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Colour/pattern selector 2", category = PropertyCategory.SETTING)
        val colourPattern2 = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Auto scroll through patterns", category = PropertyCategory.SETTING)
        val autoScroll = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty("Auto scroll speed", category = PropertyCategory.SPEED)
        val autoScrollSpeed = DmxSlider(transaction, universe, firstChannel + 5)

        @FixtureProperty("Movement macros", category = PropertyCategory.SETTING)
        val movementMacros = DmxSlider(transaction, universe, firstChannel + 6)

        @FixtureProperty("Scanning speed", category = PropertyCategory.SPEED)
        val scanningSpeed = DmxSlider(transaction, universe, firstChannel + 7)
    }

    /**
     * 12-Channel Mode: Per-head control of colour/pattern, pan, and tilt.
     * No master dimmer, strobe, or movement macro channels.
     *
     * DMX Layout (12 channels):
     * - Scanners 1-4 (3 channels each, starting at 1, 4, 7, 10):
     *   - +0: Colour/pattern selector
     *   - +1: Pan (0-255 maps to 0-180 degrees)
     *   - +2: Tilt (0-255 maps to 0-90 degrees)
     */
    @FixtureType("scantastic-4-12ch", manufacturer = "Equinox", model = "Scantastic 4")
    class Mode12Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Scantastic4Fixture(
        universe, firstChannel, 12, key, fixtureName, transaction
    ), MultiElementFixture<Scantastic4Fixture.ScannerHead> {

        override val mode = Mode.MODE_12CH

        private constructor(fixture: Mode12Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode12Ch =
            Mode12Ch(this, transaction)

        override val elements: List<ScannerHead> = (0 until 4).map { idx ->
            ScannerHead(idx, transaction, firstChannel + (idx * 3), hasColour = true)
        }

        override val elementCount: Int = 4

        fun scanner(index: Int): ScannerHead {
            require(index in 0 until 4) { "Scanner index must be 0-3, got $index" }
            return elements[index]
        }
    }

    /**
     * 17-Channel Mode: Full control combining macro features with per-head pan/tilt.
     *
     * DMX Layout (17 channels):
     * - Ch 1: Shutter (0-127 blackout, 128-255 full on)
     * - Ch 2: Strobe (0-255)
     * - Ch 3: Colour/pattern selector 1
     * - Ch 4: Colour/pattern selector 2
     * - Ch 5: Auto scroll through patterns
     * - Ch 6: Auto scroll speed
     * - Scanners 1-4 (2 channels each, starting at 7, 9, 11, 13):
     *   - +0: Pan (0-255 maps to 0-180 degrees)
     *   - +1: Tilt (0-255 maps to 0-90 degrees)
     * - Ch 15: Movement macros
     * - Ch 16: Scanning speed
     * - Ch 17: Sound active (0-127 blackout, 128-255 sound active)
     */
    @FixtureType("scantastic-4-17ch", manufacturer = "Equinox", model = "Scantastic 4")
    class Mode17Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Scantastic4Fixture(
        universe, firstChannel, 17, key, fixtureName, transaction
    ), WithStrobe, MultiElementFixture<Scantastic4Fixture.ScannerHead> {

        override val mode = Mode.MODE_17CH

        private constructor(fixture: Mode17Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode17Ch =
            Mode17Ch(this, transaction)

        @FixtureProperty("Shutter (blackout/full on)", category = PropertyCategory.SETTING, compactDisplay = CompactDisplayRole.PRIMARY)
        val shutter = DmxFixtureSetting(transaction, universe, firstChannel, Shutter.entries.toTypedArray())

        @FixtureProperty("Strobe", category = PropertyCategory.STROBE)
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 1,
            strobeMin = 0u, strobeMax = 255u,
        )

        @FixtureProperty("Colour/pattern selector 1", category = PropertyCategory.SETTING, compactDisplay = CompactDisplayRole.SECONDARY)
        val colourPattern1 = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Colour/pattern selector 2", category = PropertyCategory.SETTING)
        val colourPattern2 = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Auto scroll through patterns", category = PropertyCategory.SETTING)
        val autoScroll = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty("Auto scroll speed", category = PropertyCategory.SPEED)
        val autoScrollSpeed = DmxSlider(transaction, universe, firstChannel + 5)

        override val elements: List<ScannerHead> = (0 until 4).map { idx ->
            ScannerHead(idx, transaction, firstChannel + 6 + (idx * 2), hasColour = false)
        }

        override val elementCount: Int = 4

        fun scanner(index: Int): ScannerHead {
            require(index in 0 until 4) { "Scanner index must be 0-3, got $index" }
            return elements[index]
        }

        @FixtureProperty("Movement macros", category = PropertyCategory.SETTING)
        val movementMacros = DmxSlider(transaction, universe, firstChannel + 14)

        @FixtureProperty("Scanning speed", category = PropertyCategory.SPEED)
        val scanningSpeed = DmxSlider(transaction, universe, firstChannel + 15)

        @FixtureProperty("Sound active", category = PropertyCategory.SETTING)
        val soundActive = DmxFixtureSetting(transaction, universe, firstChannel + 16, SoundActive.entries.toTypedArray())
    }
}
