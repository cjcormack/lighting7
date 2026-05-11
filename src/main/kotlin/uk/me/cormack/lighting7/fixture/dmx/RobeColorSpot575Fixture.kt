package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe

/**
 * Robe ColorSpot 575 AT — discharge moving-head spot with CMY-free dual
 * colour wheels, two gobo wheels (static + rotating), prism, frost, iris.
 *
 * Four DMX personalities (Modes 1–4) at 27 / 19 / 29 / 21 channels per the
 * Robe DMX chart. Only Mode 2 (19-channel) is implemented for the TCH 2026
 * patch; the other modes remain as `// TODO` enum entries per the locked
 * decision.
 *
 * Authoritative channel map:
 * `Manuals/personalities/Robe_ColorSpot575AT_Mode2.md`. The MagicQ
 * `EDIT HEAD` capture there was spot-checked against the bundled DMX chart
 * (`Manuals/ColorSpot_575_AT_DMX_charts.pdf`) and they agree.
 *
 * Discharge-lamp safety: lamp on/off and the seven reset bands all live on
 * **channel 6 (Control)** — not the shutter channel as on the MAC 250.
 * Channel 6 is therefore not exposed as a `@FixtureProperty` so FX can't
 * target it; lamp/reset are explicit methods on [Mode2Ch] that write the
 * channel directly via the transaction. The strobe channel (ch 18) is
 * separately clamped to its safe band.
 */
sealed class RobeColorSpot575Fixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<RobeColorSpot575Fixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        // TODO: MODE_1 (27, "Mode 1 (27-channel)")
        MODE_2(19, "Mode 2 (19-channel)"),
        // TODO: MODE_3 (29, "Mode 3 (29-channel)")
        // TODO: MODE_4 (21, "Mode 4 (21-channel)")
    }

    /**
     * Channel 7 — Colour wheel 1.
     *
     * Continual positioning (000–127) scrolls smoothly between adjacent
     * colours; positioning (128–189) jumps directly to indexed colours.
     * The enum's `level` values are the centres of the indexed positioning
     * bands, with `colourPreview` swatches for the UI.
     *
     * `colourPreview` hex values are best-effort approximations for the UI;
     * exact gel hues depend on the physical filters fitted to the wheel.
     */
    enum class Colour1(
        override val level: UByte,
        override val colourPreview: String? = null,
    ) : DmxFixtureColourSettingValue {
        OPEN(0u, "#FFFFFF"),
        LIGHT_BLUE(133u, "#ADD8E6"),
        RED(140u, "#FF0000"),
        BLUE(146u, "#0000FF"),
        LIGHT_GREEN(153u, "#90EE90"),
        YELLOW(160u, "#FFFF00"),
        MAGENTA(166u, "#FF00FF"),
        CYAN(173u, "#00FFFF"),
        GREEN(180u, "#00FF00"),
        ORANGE(186u, "#FFA500"),
        SCROLL_CW(190u),
        SCROLL_CCW(218u),
        RANDOM(244u),
        AUTO_RANDOM(250u),
    }

    /**
     * Channel 8 — Colour wheel 2.
     *
     * Layout matches Colour wheel 1 but with deep / corrective filters
     * (deep red, deep blue, pink, cyan, magenta, yellow, 3200K CTC,
     * UV filter).
     */
    enum class Colour2(
        override val level: UByte,
        override val colourPreview: String? = null,
    ) : DmxFixtureColourSettingValue {
        OPEN(0u, "#FFFFFF"),
        DEEP_RED(133u, "#8B0000"),
        DEEP_BLUE(140u, "#00008B"),
        PINK(148u, "#FFC0CB"),
        CYAN(155u, "#00FFFF"),
        MAGENTA(163u, "#FF00FF"),
        YELLOW(170u, "#FFFF00"),
        CTC_3200K(178u, "#FFE0C0"),
        UV_FILTER(185u, "#4B0082"),
        SCROLL_CW(190u),
        SCROLL_CCW(218u),
        RANDOM(244u),
        AUTO_RANDOM(250u),
    }

    /**
     * Channel 9 — Static gobo wheel.
     *
     * 9 fixed gobos (open + 9 indexed), 9 matching shake variants, and
     * forward/reverse scroll. `level` values are taken from the indexed-
     * positioning band starts (065–109).
     */
    enum class StaticGobo(override val level: UByte) : DmxFixtureSettingValue {
        OPEN(0u),
        GOBO_1(65u),
        GOBO_2(70u),
        GOBO_3(75u),
        GOBO_4(80u),
        GOBO_5(85u),
        GOBO_6(90u),
        GOBO_7(95u),
        GOBO_8(100u),
        GOBO_9(105u),
        GOBO_1_SHAKE(110u),
        GOBO_2_SHAKE(120u),
        GOBO_3_SHAKE(130u),
        GOBO_4_SHAKE(140u),
        GOBO_5_SHAKE(150u),
        GOBO_6_SHAKE(160u),
        GOBO_7_SHAKE(170u),
        GOBO_8_SHAKE(180u),
        GOBO_9_SHAKE(190u),
        SCROLL_CW(202u),
        SCROLL_CCW(224u),
        RANDOM(244u),
        AUTO_RANDOM(250u),
    }

    /**
     * Channel 10 — Rotating gobo wheel.
     *
     * 7 indexable gobos with index mode and rotation mode; ch 11 supplies
     * the index position or rotation speed. Shake variants (60–199) come
     * in two flavours: shake-while-indexed and shake-while-rotating.
     */
    enum class RotGobo(override val level: UByte) : DmxFixtureSettingValue {
        OPEN(0u),
        INDEX_GOBO_1(4u),
        INDEX_GOBO_2(8u),
        INDEX_GOBO_3(12u),
        INDEX_GOBO_4(16u),
        INDEX_GOBO_5(20u),
        INDEX_GOBO_6(24u),
        INDEX_GOBO_7(28u),
        ROTATE_GOBO_1(32u),
        ROTATE_GOBO_2(36u),
        ROTATE_GOBO_3(40u),
        ROTATE_GOBO_4(44u),
        ROTATE_GOBO_5(48u),
        ROTATE_GOBO_6(52u),
        ROTATE_GOBO_7(56u),
        SHAKE_INDEX_GOBO_1(60u),
        SHAKE_INDEX_GOBO_2(70u),
        SHAKE_INDEX_GOBO_3(80u),
        SHAKE_INDEX_GOBO_4(90u),
        SHAKE_INDEX_GOBO_5(100u),
        SHAKE_INDEX_GOBO_6(110u),
        SHAKE_INDEX_GOBO_7(120u),
        SHAKE_ROTATE_GOBO_1(130u),
        SHAKE_ROTATE_GOBO_2(140u),
        SHAKE_ROTATE_GOBO_3(150u),
        SHAKE_ROTATE_GOBO_4(160u),
        SHAKE_ROTATE_GOBO_5(170u),
        SHAKE_ROTATE_GOBO_6(180u),
        SHAKE_ROTATE_GOBO_7(190u),
        OPEN_END(200u),
        SCROLL_CW(202u),
        SCROLL_CCW(224u),
        RANDOM(244u),
        AUTO_RANDOM(250u),
    }

    /**
     * Channel 12 — Prism / macros.
     *
     * 000–019 prism off, 020–127 3-facet rotating prism, 128–255 sixteen
     * indexed prism+gobo macros (8-DMX-step bands).
     */
    enum class Prism(override val level: UByte) : DmxFixtureSettingValue {
        OFF(0u),
        ROTATING_3_FACET(20u),
        MACRO_1(128u),
        MACRO_2(136u),
        MACRO_3(144u),
        MACRO_4(152u),
        MACRO_5(160u),
        MACRO_6(168u),
        MACRO_7(176u),
        MACRO_8(184u),
        MACRO_9(192u),
        MACRO_10(200u),
        MACRO_11(208u),
        MACRO_12(216u),
        MACRO_13(224u),
        MACRO_14(232u),
        MACRO_15(240u),
        MACRO_16(248u),
    }

    /**
     * Mode 2 (19-channel) — the patched personality.
     *
     * - Ch 1/2: Pan (16-bit hi/lo).
     * - Ch 3/4: Tilt (16-bit hi/lo).
     * - Ch 5: Pan/Tilt speed (or time, depending on fixture menu).
     * - Ch 6: Control / power / special functions (NOT exposed — see class
     *         doc; lamp/reset reachable only via [lampOn] / [lampOff] /
     *         [reset] / [resetPanTilt] / [resetColour] / [resetGobo] /
     *         [resetDimmer] / [resetFocusZoomFrost] / [resetIrisPrism]).
     * - Ch 7: Colour wheel 1.
     * - Ch 8: Colour wheel 2.
     * - Ch 9: Static gobo wheel.
     * - Ch 10: Rotating gobo wheel.
     * - Ch 11: Gobo indexing/rotation (continuous, semantics depend on ch 10).
     * - Ch 12: Prism / macros.
     * - Ch 13: Prism rotation (CW / no-rot / CCW).
     * - Ch 14: Frost (open / 0–100% / pulse / ramp bands).
     * - Ch 15: Iris (open / closed / pulse / random-pulse bands).
     * - Ch 16: Zoom (three preset positions × with/without focus correction).
     * - Ch 17: Focus.
     * - Ch 18: Shutter / strobe (clamped to safe band 0–95).
     * - Ch 19: Master dimmer (HTP).
     */
    @FixtureType(
        "robe-color-spot-575-mode-2",
        manufacturer = "Robe",
        model = "ColorSpot 575 AT",
        kind = FixtureKind.MOVING_HEAD,
    )
    class Mode2Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : RobeColorSpot575Fixture(
        universe, firstChannel, 19, key, fixtureName, transaction,
    ), WithDimmer, WithPosition, WithStrobe {
        override val mode = Mode.MODE_2

        private constructor(fixture: Mode2Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode2Ch =
            Mode2Ch(this, transaction)

        @FixtureProperty("Pan (coarse)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 540.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Tilt (coarse)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 257.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Pan/tilt speed", category = PropertyCategory.SPEED)
        val panTiltSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        // Ch 6 (Control) intentionally not exposed — see class doc.

        @FixtureProperty("Colour wheel 1", category = PropertyCategory.COLOUR)
        val colour1 = DmxFixtureSetting(
            transaction, universe, firstChannel + 6, Colour1.entries.toTypedArray(),
        )

        @FixtureProperty("Colour wheel 2", category = PropertyCategory.COLOUR)
        val colour2 = DmxFixtureSetting(
            transaction, universe, firstChannel + 7, Colour2.entries.toTypedArray(),
        )

        @FixtureProperty("Static gobo wheel", category = PropertyCategory.SETTING)
        val staticGobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 8, StaticGobo.entries.toTypedArray(),
        )

        @FixtureProperty("Rotating gobo wheel", category = PropertyCategory.SETTING)
        val rotatingGobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 9, RotGobo.entries.toTypedArray(),
        )

        @FixtureProperty(
            "Gobo indexing/rotation (semantics depend on rotating gobo wheel mode)",
            category = PropertyCategory.SETTING,
        )
        val goboRotation: Slider = DmxSlider(transaction, universe, firstChannel + 10)

        @FixtureProperty("Prism", category = PropertyCategory.SETTING)
        val prism = DmxFixtureSetting(
            transaction, universe, firstChannel + 11, Prism.entries.toTypedArray(),
        )

        @FixtureProperty("Prism rotation (CW / no-rot / CCW)", category = PropertyCategory.SETTING)
        val prismRotation: Slider = DmxSlider(transaction, universe, firstChannel + 12)

        @FixtureProperty("Frost", category = PropertyCategory.OTHER)
        val frost: Slider = DmxSlider(transaction, universe, firstChannel + 13)

        @FixtureProperty("Iris", category = PropertyCategory.OTHER)
        val iris: Slider = DmxSlider(transaction, universe, firstChannel + 14)

        @FixtureProperty("Zoom", category = PropertyCategory.OTHER)
        val zoom: Slider = DmxSlider(transaction, universe, firstChannel + 15)

        @FixtureProperty("Focus", category = PropertyCategory.OTHER)
        val focus: Slider = DmxSlider(transaction, universe, firstChannel + 16)

        /**
         * Channel 18 — shutter / strobe (clamped to the safe band 0–[STROBE_BAND_MAX]).
         *
         * Personality bands: 000–031 closed, 032–063 open, 064–095 strobe,
         * 096–127 open, 128–143 opening pulse, 144–159 closing pulse, 160–191
         * open, 192–223 random strobe, 224–255 open. The slider's `max` clamp
         * prevents [WithStrobe] writes — and raw `value` writes — from straying
         * into the pulse/random bands above 95; reach those only via raw
         * transaction writes.
         */
        @FixtureProperty(category = PropertyCategory.STROBE)
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel + 17,
            strobeMin = STROBE_BAND_MIN,
            strobeMax = STROBE_BAND_MAX,
            fullOnValue = OPEN_DEFAULT,
            max = STROBE_BAND_MAX,
        )

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 18)

        private val controlChannel = firstChannel + 5

        private val nonNullTransaction get() = checkNotNull(transaction) {
            "Attempted to use fixture outside of a transaction"
        }

        /**
         * Strike the discharge lamp. Writes [LAMP_ON_LEVEL] (130) to ch 6.
         *
         * The fixture requires the value to be held for ≥3s and the shutter
         * (ch 18) to be closed for ≥3s before it acts. Don't strike multiple
         * fixtures simultaneously on the same circuit; stagger the calls.
         * Not FX-targetable by design.
         */
        fun lampOn() {
            nonNullTransaction.setValue(universe, controlChannel, LAMP_ON_LEVEL)
        }

        /**
         * Extinguish the discharge lamp. Writes [LAMP_OFF_LEVEL] (230) to
         * ch 6. The lamp needs to cool for several minutes before it can be
         * re-struck. Not FX-targetable by design.
         */
        fun lampOff() {
            nonNullTransaction.setValue(universe, controlChannel, LAMP_OFF_LEVEL)
        }

        /**
         * Total reset (re-home all motors). Writes [TOTAL_RESET_LEVEL] (200)
         * to ch 6. Not FX-targetable by design.
         */
        fun reset() {
            nonNullTransaction.setValue(universe, controlChannel, TOTAL_RESET_LEVEL)
        }

        /** Re-home pan/tilt only. Writes 140 to ch 6. */
        fun resetPanTilt() {
            nonNullTransaction.setValue(universe, controlChannel, PAN_TILT_RESET_LEVEL)
        }

        /** Re-home both colour wheels. Writes 150 to ch 6. */
        fun resetColour() {
            nonNullTransaction.setValue(universe, controlChannel, COLOUR_RESET_LEVEL)
        }

        /** Re-home both gobo wheels. Writes 160 to ch 6. */
        fun resetGobo() {
            nonNullTransaction.setValue(universe, controlChannel, GOBO_RESET_LEVEL)
        }

        /** Reset the dimmer/strobe motor. Writes 170 to ch 6. */
        fun resetDimmer() {
            nonNullTransaction.setValue(universe, controlChannel, DIMMER_RESET_LEVEL)
        }

        /** Reset focus, zoom, frost motors. Writes 180 to ch 6. */
        fun resetFocusZoomFrost() {
            nonNullTransaction.setValue(universe, controlChannel, FOCUS_ZOOM_FROST_RESET_LEVEL)
        }

        /** Reset iris and prism motors. Writes 190 to ch 6. */
        fun resetIrisPrism() {
            nonNullTransaction.setValue(universe, controlChannel, IRIS_PRISM_RESET_LEVEL)
        }

        companion object {
            /** Default open value (mid of 032–063 Open band; matches MagicQ locate). */
            const val OPEN_DEFAULT: UByte = 35u

            /** Lower bound of the strobe band (064–095). */
            const val STROBE_BAND_MIN: UByte = 64u

            /**
             * Upper bound of the strobe band; also the slider clamp, so
             * neither [WithStrobe] writes nor raw `value` writes can
             * wander into the pulse or random-strobe bands above.
             */
            const val STROBE_BAND_MAX: UByte = 95u

            const val LAMP_ON_LEVEL: UByte = 130u
            const val PAN_TILT_RESET_LEVEL: UByte = 140u
            const val COLOUR_RESET_LEVEL: UByte = 150u
            const val GOBO_RESET_LEVEL: UByte = 160u
            const val DIMMER_RESET_LEVEL: UByte = 170u
            const val FOCUS_ZOOM_FROST_RESET_LEVEL: UByte = 180u
            const val IRIS_PRISM_RESET_LEVEL: UByte = 190u
            const val TOTAL_RESET_LEVEL: UByte = 200u
            const val LAMP_OFF_LEVEL: UByte = 230u
        }
    }
}
