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
 * target it; lamp and resets are fixture commands on [Mode2Ch] (fixture
 * optics plan session 7), held by the desk, which owns the channel. The
 * strobe channel (ch 18) is separately clamped to its safe band.
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
        override val noColour: Boolean = false,
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
        SCROLL_CW(190u, noColour = true),
        SCROLL_CCW(218u, noColour = true),
        RANDOM(244u, noColour = true),
        AUTO_RANDOM(250u, noColour = true),
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
        override val noColour: Boolean = false,
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
        SCROLL_CW(190u, noColour = true),
        SCROLL_CCW(218u, noColour = true),
        RANDOM(244u, noColour = true),
        AUTO_RANDOM(250u, noColour = true),
    }

    /**
     * Channel 9 — Static gobo wheel.
     *
     * 9 fixed gobos (open + 9 indexed), 9 matching shake variants, and
     * forward/reverse scroll. `level` values are taken from the indexed-
     * positioning band starts (065–109).
     */
    enum class StaticGobo(
        override val level: UByte,
        // The manual names the slots only Gobo 1..9, so these are a plausible
        // spread; shake variants share their base slot's artwork, and the
        // scroll/random bands stay null (open) because the wheel is moving.
        override val gobo: GoboPattern? = null,
    ) : DmxFixtureGoboSettingValue {
        OPEN(0u),
        GOBO_1(65u, GoboPattern.DOTS),
        GOBO_2(70u, GoboPattern.BREAKUP),
        GOBO_3(75u, GoboPattern.SPOKES),
        GOBO_4(80u, GoboPattern.BARS),
        GOBO_5(85u, GoboPattern.RINGS),
        GOBO_6(90u, GoboPattern.TRIPLE),
        GOBO_7(95u, GoboPattern.CLOUDS),
        GOBO_8(100u, GoboPattern.STARS),
        GOBO_9(105u, GoboPattern.STARBURST),
        GOBO_1_SHAKE(110u, GoboPattern.DOTS),
        GOBO_2_SHAKE(120u, GoboPattern.BREAKUP),
        GOBO_3_SHAKE(130u, GoboPattern.SPOKES),
        GOBO_4_SHAKE(140u, GoboPattern.BARS),
        GOBO_5_SHAKE(150u, GoboPattern.RINGS),
        GOBO_6_SHAKE(160u, GoboPattern.TRIPLE),
        GOBO_7_SHAKE(170u, GoboPattern.CLOUDS),
        GOBO_8_SHAKE(180u, GoboPattern.STARS),
        GOBO_9_SHAKE(190u, GoboPattern.STARBURST),
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
    enum class RotGobo(
        override val level: UByte,
        /** Physical gobo slot 1..7; 0 = none. Four mode variants (index /
         *  rotate / shake-index / shake-rotate) address the same glass, so the
         *  slot→artwork map lives once in [SLOT_PATTERNS] and all variants of
         *  a slot agree by construction. */
        private val slot: Int = 0,
    ) : DmxFixtureGoboSettingValue {
        OPEN(0u),
        INDEX_GOBO_1(4u, 1),
        INDEX_GOBO_2(8u, 2),
        INDEX_GOBO_3(12u, 3),
        INDEX_GOBO_4(16u, 4),
        INDEX_GOBO_5(20u, 5),
        INDEX_GOBO_6(24u, 6),
        INDEX_GOBO_7(28u, 7),
        ROTATE_GOBO_1(32u, 1),
        ROTATE_GOBO_2(36u, 2),
        ROTATE_GOBO_3(40u, 3),
        ROTATE_GOBO_4(44u, 4),
        ROTATE_GOBO_5(48u, 5),
        ROTATE_GOBO_6(52u, 6),
        ROTATE_GOBO_7(56u, 7),
        SHAKE_INDEX_GOBO_1(60u, 1),
        SHAKE_INDEX_GOBO_2(70u, 2),
        SHAKE_INDEX_GOBO_3(80u, 3),
        SHAKE_INDEX_GOBO_4(90u, 4),
        SHAKE_INDEX_GOBO_5(100u, 5),
        SHAKE_INDEX_GOBO_6(110u, 6),
        SHAKE_INDEX_GOBO_7(120u, 7),
        SHAKE_ROTATE_GOBO_1(130u, 1),
        SHAKE_ROTATE_GOBO_2(140u, 2),
        SHAKE_ROTATE_GOBO_3(150u, 3),
        SHAKE_ROTATE_GOBO_4(160u, 4),
        SHAKE_ROTATE_GOBO_5(170u, 5),
        SHAKE_ROTATE_GOBO_6(180u, 6),
        SHAKE_ROTATE_GOBO_7(190u, 7),
        OPEN_END(200u),
        SCROLL_CW(202u),
        SCROLL_CCW(224u),
        RANDOM(244u),
        AUTO_RANDOM(250u);

        override val gobo: GoboPattern? get() = SLOT_PATTERNS.getOrNull(slot - 1)

        companion object {
            /** Rotating-wheel artwork, slots 1..7 — a plausible spread chosen
             *  disjoint from [StaticGobo]'s so wheel switches are visible. */
            private val SLOT_PATTERNS = listOf(
                GoboPattern.SWIRL,
                GoboPattern.FIBROID,
                GoboPattern.HOLES,
                GoboPattern.CONE,
                GoboPattern.FAN,
                GoboPattern.BEAM_SPLIT,
                GoboPattern.STARS,
            )
        }
    }

    /**
     * Channel 12 — Prism / macros.
     *
     * 000–019 prism off, 020–127 3-facet rotating prism, 128–255 sixteen
     * indexed prism+gobo macros (8-DMX-step bands).
     */
    enum class Prism(
        override val level: UByte,
        // The macros are prism+gobo combination programs — the 3-facet prism
        // is in the beam for all of them.
        override val prismFacets: Int? = null,
    ) : DmxFixturePrismSettingValue {
        OFF(0u),
        ROTATING_3_FACET(20u, 3),
        MACRO_1(128u, 3),
        MACRO_2(136u, 3),
        MACRO_3(144u, 3),
        MACRO_4(152u, 3),
        MACRO_5(160u, 3),
        MACRO_6(168u, 3),
        MACRO_7(176u, 3),
        MACRO_8(184u, 3),
        MACRO_9(192u, 3),
        MACRO_10(200u, 3),
        MACRO_11(208u, 3),
        MACRO_12(216u, 3),
        MACRO_13(224u, 3),
        MACRO_14(232u, 3),
        MACRO_15(240u, 3),
        MACRO_16(248u, 3),
    }

    /**
     * Channel 16 — Zoom: three fixed angles, each with and without focus correction (DMX chart v1.0;
     * `Manuals/personalities/Robe_ColorSpot575AT_Mode2.md`). A **stepped zoom** (fixture-optics plan
     * D2): the chart's bands are positions, not a sweep, so it is a setting whose options carry their
     * angle — a slider would draw every DMX value between them as an angle the lens never takes.
     * Levels are the band starts.
     */
    enum class Zoom(
        override val level: UByte,
        override val zoomDeg: Double,
    ) : DmxFixtureZoomSettingValue {
        ZOOM_15(0u, 15.0),
        ZOOM_18(40u, 18.0),
        ZOOM_22(80u, 22.0),
        ZOOM_15_FOCUS_CORRECTED(128u, 15.0),
        ZOOM_18_FOCUS_CORRECTED(170u, 18.0),
        ZOOM_22_FOCUS_CORRECTED(220u, 22.0),
    }

    /**
     * Mode 2 (19-channel) — the patched personality.
     *
     * - Ch 1/2: Pan (16-bit hi/lo).
     * - Ch 3/4: Tilt (16-bit hi/lo).
     * - Ch 5: Pan/Tilt speed (or time, depending on fixture menu).
     * - Ch 6: Control / power / special functions (NOT a property — see class
     *         doc; lamp and resets are the [lampOn] / [lampOff] / [reset] /
     *         [resetPanTilt] / [resetColour] / [resetGobo] / [resetDimmer] /
     *         [resetFocusZoomFrost] / [resetIrisPrism] commands).
     * - Ch 7: Colour wheel 1.
     * - Ch 8: Colour wheel 2.
     * - Ch 9: Static gobo wheel.
     * - Ch 10: Rotating gobo wheel.
     * - Ch 11: Gobo indexing/rotation (continuous, semantics depend on ch 10).
     * - Ch 12: Prism / macros.
     * - Ch 13: Prism rotation (CW / no-rot / CCW).
     * - Ch 14: Frost (open / 0–100% / pulse / ramp bands).
     * - Ch 15: Iris (open / closed / pulse / random-pulse bands).
     * - Ch 16: Zoom (three preset positions × with/without focus correction — [Zoom]).
     * - Ch 17: Focus.
     * - Ch 18: Shutter / strobe (clamped to safe band 0–95).
     * - Ch 19: Master dimmer (HTP).
     */
    @FixtureType(
        "robe-color-spot-575-mode-2",
        manufacturer = "Robe",
        model = "ColorSpot 575 AT",
        kind = FixtureKind.MOVING_HEAD,
        body = FixtureBody(BodyArchetype.MOVER, MoverHead.SPOT),
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

        // Pan 530° and tilt 280° (user manual v1.4, technical specifications).
        @FixtureProperty("Pan (coarse)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 530.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Tilt (coarse)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 280.0)
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

        @FixtureProperty("Static gobo wheel", category = PropertyCategory.GOBO)
        val staticGobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 8, StaticGobo.entries.toTypedArray(),
        )

        @FixtureProperty("Rotating gobo wheel", category = PropertyCategory.GOBO)
        val rotatingGobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 9, RotGobo.entries.toTypedArray(),
        )

        @FixtureProperty(
            "Gobo indexing/rotation (semantics depend on rotating gobo wheel mode)",
            category = PropertyCategory.GOBO_ROTATION,
        )
        val goboRotation: Slider = DmxSlider(transaction, universe, firstChannel + 10)

        @FixtureProperty("Prism", category = PropertyCategory.PRISM)
        val prism = DmxFixtureSetting(
            transaction, universe, firstChannel + 11, Prism.entries.toTypedArray(),
        )

        @FixtureProperty(
            "Prism rotation (0 no rot, 1–127 CW fast→slow, 128–129 stop, 130–255 CCW slow→fast)",
            category = PropertyCategory.PRISM_ROTATION,
        )
        val prismRotation: Slider = DmxSlider(transaction, universe, firstChannel + 12)

        // DMX chart: 0 open, 1–179 frost 0→100%, then 100% frost, pulses and ramps — effects the
        // view does not draw, so it holds full frost above 179.
        @FixtureProperty("Frost", category = PropertyCategory.FROST, activeMin = 1, activeMax = 179)
        val frost: Slider = DmxSlider(transaction, universe, firstChannel + 13)

        // DMX chart: 0 open, 1–179 max→min diameter, 180–191 closed, then pulses — held at the
        // smallest diameter above 179.
        @FixtureProperty("Iris", category = PropertyCategory.IRIS, activeMin = 1, activeMax = 179)
        val iris: Slider = DmxSlider(transaction, universe, firstChannel + 14)

        @FixtureProperty("Zoom", category = PropertyCategory.ZOOM)
        val zoom = DmxFixtureSetting(
            transaction, universe, firstChannel + 15, Zoom.entries.toTypedArray(),
        )

        // Estimate: Robe publishes only "coarse focus, proportional" (ColorSpot 575 AT DMX chart
        // v1.0). Near is the manual's 2 m minimum distance to a lit surface (user manual v1.4);
        // far → near as Robe's later charts run; infinity is the Stage view's 40 m longest throw.
        @FixtureProperty(
            "Focus",
            category = PropertyCategory.FOCUS,
            focusNearM = 2.0,
            focusFarM = 40.0,
            inverted = true,
        )
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
         *
         * The bands are the DMX chart's, past the clamp too. The strobe's 1–10 Hz is the user
         * manual's ("strobe effect (1 - 10 flashes per second)", v1.4 — its spec page says "max. 15").
         * Estimate: the pulses at 0.5–2 Hz, the random strobe at the strobe's 1–10 Hz. Checked on
         * the rig by FU-MANUAL-S6-STROBE.
         */
        @FixtureProperty(
            category = PropertyCategory.STROBE,
            strobe = [
                StrobeBand(0, 31, StrobeKind.CLOSED),
                StrobeBand(32, 63, StrobeKind.OPEN),
                StrobeBand(64, 95, StrobeKind.STROBE, hzMin = 1.0, hzMax = 10.0),
                StrobeBand(96, 127, StrobeKind.OPEN),
                StrobeBand(128, 143, StrobeKind.PULSE, hzMin = 0.5, hzMax = 2.0),
                StrobeBand(144, 159, StrobeKind.PULSE, hzMin = 0.5, hzMax = 2.0, inverted = true),
                StrobeBand(160, 191, StrobeKind.OPEN),
                StrobeBand(192, 223, StrobeKind.RANDOM, hzMin = 1.0, hzMax = 10.0),
                StrobeBand(224, 255, StrobeKind.OPEN),
            ],
        )
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

        /**
         * Channel 6 — power and special functions — as commands (fixture optics plan session 7). No
         * property covers it, so the desk holds it at 0 ("Reserved", which does nothing) between them.
         *
         * The DMX chart: "To activate following functions, stop in DMX value for at least 3 s" over
         * 130–255. Its other condition — "shutter must be closed at least 3 s (Shutter, Strobe … must be
         * at range 0–31)" — heads the 50–129 switch functions, which the desk does not expose. Every
         * command closes the shutter for its hold anyway: a closed shutter costs nothing while the head
         * re-homes or the lamp strikes, and a command the fixture ignores costs a trip up the ladder.
         */
        private fun control(level: UByte, bandMin: UByte, bandMax: UByte) = DmxCommand(
            universe, controlChannel, level, bandMin, bandMax,
            alongside = listOf(DmxChannelHold(firstChannel + 17, SHUTTER_CLOSED, "Shutter closed")),
        )

        @FixtureCommand(label = "Lamp on", description = "Strikes the discharge lamp, and resets every effect but pan and tilt. A strike draws many times the running current for an instant: strike several heads one at a time.", holdMs = HOLD_MS)
        val lampOn = control(LAMP_ON_LEVEL, 130u, 139u)

        @FixtureCommand(label = "Reset pan/tilt", description = "Re-homes pan and tilt. The head swings through its travel while it runs.", holdMs = HOLD_MS)
        val resetPanTilt = control(PAN_TILT_RESET_LEVEL, 140u, 149u)

        @FixtureCommand(label = "Reset colour wheels", description = "Re-homes both colour wheels.", holdMs = HOLD_MS)
        val resetColour = control(COLOUR_RESET_LEVEL, 150u, 159u)

        @FixtureCommand(label = "Reset gobo wheels", description = "Re-homes both gobo wheels.", holdMs = HOLD_MS)
        val resetGobo = control(GOBO_RESET_LEVEL, 160u, 169u)

        @FixtureCommand(label = "Reset dimmer/strobe", description = "Re-homes the mechanical dimmer and the strobe shutter.", holdMs = HOLD_MS)
        val resetDimmer = control(DIMMER_RESET_LEVEL, 170u, 179u)

        @FixtureCommand(label = "Reset focus/zoom/frost", description = "Re-homes the focus, zoom and frost motors.", holdMs = HOLD_MS)
        val resetFocusZoomFrost = control(FOCUS_ZOOM_FROST_RESET_LEVEL, 180u, 189u)

        @FixtureCommand(label = "Reset iris/prism", description = "Re-homes the iris and the prism.", holdMs = HOLD_MS)
        val resetIrisPrism = control(IRIS_PRISM_RESET_LEVEL, 190u, 199u)

        @FixtureCommand(label = "Total reset", description = "Re-homes every motor, pan and tilt included. The head swings through its travel and the beam moves while it runs.", holdMs = HOLD_MS)
        val reset = control(TOTAL_RESET_LEVEL, 200u, 209u)

        @FixtureCommand(label = "Lamp off", description = "Douses the discharge lamp. It is a cold-restrike lamp: it must cool before it can be struck again, so this head is dark until then.", holdMs = HOLD_MS)
        val lampOff = control(LAMP_OFF_LEVEL, 230u, 239u)

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

            /**
             * How long a command holds channel 6: the chart's "at least 3 s", plus a second so a frame
             * dropped on the network cannot leave it short.
             */
            const val HOLD_MS: Long = 4_000

            /** Channel 18 inside its 0–31 "shutter closed" band. */
            const val SHUTTER_CLOSED: UByte = 0u

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
