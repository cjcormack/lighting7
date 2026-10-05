package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import uk.me.cormack.lighting7.fixture.trait.WithWhite

/**
 * IMG Stageline Wash-42LED (Monacor 38.7740) — 7 × 10W RGBW LED moving head wash.
 *
 * Two DMX personalities (8 / 13 channel). Only the 13-channel mode is implemented
 * for the TCH 2026 patch; the 8-channel mode remains as a `// TODO` enum entry.
 *
 * Pan/tilt range: 540° / 180°. Beam: 10°.
 */
sealed class ImgStageLineWash42LedFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<ImgStageLineWash42LedFixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        // TODO: MODE_8CH (8, "8-Channel")
        MODE_13CH(13, "13-Channel"),
    }

    /**
     * Channel 11 — colour macros. 0–7 hands control back to channels 7–10 (direct RGBW); 232–255
     * changes colour automatically. Neither carries a colour of its own.
     */
    enum class ColourMacro(
        override val level: UByte,
        override val colourPreview: String? = null,
        override val noColour: Boolean = false,
    ) : DmxFixtureColourSettingValue {
        DIRECT_RGBW(0u, noColour = true),
        // Estimate: the manual (p17) numbers the macros 1–16 and names none; a plausible RGBW spread
        // so a macro draws a colour. Checked on the rig by FU-MANUAL-S5-LIBRARY-OPTICS.
        COLOUR_1(8u, "#FF0000"),
        COLOUR_2(22u, "#00FF00"),
        COLOUR_3(35u, "#0000FF"),
        COLOUR_4(50u, "#FFFFFF"),
        COLOUR_5(64u, "#FFFF00"),
        COLOUR_6(78u, "#FF00FF"),
        COLOUR_7(92u, "#00FFFF"),
        COLOUR_8(106u, "#FF8000"),
        COLOUR_9(120u, "#FF0080"),
        COLOUR_10(134u, "#80FF00"),
        COLOUR_11(148u, "#0080FF"),
        COLOUR_12(162u, "#8000FF"),
        COLOUR_13(176u, "#FFC080"),
        COLOUR_14(190u, "#FF8080"),
        COLOUR_15(204u, "#80FF80"),
        COLOUR_16(218u, "#8080FF"),
        AUTOMATIC_COLOUR_CHANGE(232u, noColour = true),
    }

    /**
     * Channel 13 — built-in show programs.
     *
     * 0–7 hands control to the other channels; 8–127 are the eight automatic
     * movement-and-colour programs; 128–255 are the same eight programs in
     * music-controlled mode.
     */
    enum class Program(override val level: UByte) : DmxFixtureSettingValue {
        OTHER_CHANNELS(0u),
        AUTO_PROGRAM_1(8u),
        AUTO_PROGRAM_2(23u),
        AUTO_PROGRAM_3(38u),
        AUTO_PROGRAM_4(54u),
        AUTO_PROGRAM_5(68u),
        AUTO_PROGRAM_6(83u),
        AUTO_PROGRAM_7(98u),
        AUTO_PROGRAM_8(113u),
        MUSIC_PROGRAM_1(128u),
        MUSIC_PROGRAM_2(143u),
        MUSIC_PROGRAM_3(158u),
        MUSIC_PROGRAM_4(173u),
        MUSIC_PROGRAM_5(188u),
        MUSIC_PROGRAM_6(203u),
        MUSIC_PROGRAM_7(218u),
        MUSIC_PROGRAM_8(233u),
    }

    /**
     * 13-channel mode — full feature set.
     *
     * - Ch 1: Pan (coarse).
     * - Ch 2: Pan (fine).
     * - Ch 3: Tilt (coarse).
     * - Ch 4: Tilt (fine).
     * - Ch 5: Movement speed (fast → slow).
     * - Ch 6: Dimmer + strobe.
     * - Ch 7: Red.
     * - Ch 8: Green.
     * - Ch 9: Blue.
     * - Ch 10: White.
     * - Ch 11: Colour macros.
     * - Ch 12: Colour-change speed (slow → fast).
     * - Ch 13: Built-in programs.
     */
    // A fixed 10° lens (manual p17, "Abstrahlwinkel 10°").
    @FixtureType(
        "imgstageline-wash-42led-13ch", manufacturer = "IMG Stageline", model = "Wash-42LED", kind = FixtureKind.MOVING_HEAD, body = FixtureBody(BodyArchetype.MOVER, MoverHead.WASH), fieldDeg = 10.0,
        // Estimate: the manual states no movement speed. Pan and tilt are a small LED wash's typical; its colour is RGBW, so it does not travel.
        travel = Travel(panDegPerS = 180.0, tiltDegPerS = 120.0),
    )
    class Mode13Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : ImgStageLineWash42LedFixture(
        universe, firstChannel, 13, key, fixtureName, transaction,
    ), WithDimmer, WithColour, WithWhite, WithStrobe, WithPosition {
        override val mode = Mode.MODE_13CH

        private constructor(fixture: Mode13Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode13Ch =
            Mode13Ch(this, transaction)

        @FixtureProperty("Pan (coarse, 0–540°)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 540.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Tilt (coarse, 0–180°)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 180.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Pan/tilt movement speed (fast → slow)", category = PropertyCategory.SPEED)
        val movementSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        /**
         * Channel 6 (shared dimmer + strobe + open):
         * - 0–[DIM_MAX]              dimmer dark → bright (0–7 reads as dark, 8–134 ramps)
         * - [STROBE_MIN]–[STROBE_MAX] strobe slow → fast
         * - [FULL_ON]–255            max brightness (overrides dimmer)
         *
         * Modelled as two properties on the same channel: a [DmxSlider] dimmer
         * clamped to 0–[DIM_MAX], and a [BandedStrobeChannel] that writes the
         * strobe band on `strobe(intensity)` and the "max brightness" value on
         * `fullOn()`. Setting the strobe overrides the dimmer; this matches
         * how the fixture behaves at the DMX level.
         */
        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(
            transaction, universe, firstChannel + 5, max = DIM_MAX,
        )

        // The strobe's view of the shared channel: the dimmer's range is open (the dimmer alone sets
        // the level), then the manual's strobe and full-on bands. Estimate: the strobe's 1–20 Hz —
        // the manual states none. Checked on the rig by FU-MANUAL-S6-STROBE.
        @FixtureProperty(
            category = PropertyCategory.STROBE,
            strobe = [
                StrobeBand(0, 134, StrobeKind.OPEN),
                StrobeBand(135, 239, StrobeKind.STROBE, hzMin = 1.0, hzMax = 20.0),
                StrobeBand(240, 255, StrobeKind.OPEN),
            ],
        )
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 5,
            strobeMin = STROBE_MIN, strobeMax = STROBE_MAX, fullOnValue = FULL_ON,
        )

        @FixtureProperty(category = PropertyCategory.COLOUR)
        override val rgbColour = DmxColour(
            transaction, universe,
            firstChannel + 6,
            firstChannel + 7,
            firstChannel + 8,
        )

        @FixtureProperty(category = PropertyCategory.WHITE, bundleWithColour = true)
        override val white: Slider = DmxSlider(transaction, universe, firstChannel + 9)

        @FixtureProperty("Colour macro", category = PropertyCategory.COLOUR)
        val colourMacro = DmxFixtureSetting(
            transaction, universe, firstChannel + 10, ColourMacro.entries.toTypedArray(),
        )

        @FixtureProperty("Colour-change speed (slow → fast)", category = PropertyCategory.SPEED)
        val colourChangeSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 11)

        @FixtureProperty("Built-in program", category = PropertyCategory.SETTING)
        val program = DmxFixtureSetting(
            transaction, universe, firstChannel + 12, Program.entries.toTypedArray(),
        )

        companion object {
            const val DIM_MAX: UByte = 134u
            const val STROBE_MIN: UByte = 135u
            const val STROBE_MAX: UByte = 239u
            const val FULL_ON: UByte = 240u
        }
    }
}
