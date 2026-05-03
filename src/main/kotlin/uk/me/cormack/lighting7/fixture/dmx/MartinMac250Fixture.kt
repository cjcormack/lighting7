package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe

/**
 * Martin MAC 250 — discharge moving-head spot (original variant).
 *
 * Not the Krypton, Entour, Wash, Beam, or 250+. Identifiable by a single
 * colour wheel + single gobo wheel, no CMY mixing. The MAC 250+ has a
 * byte-identical Mode 4 layout (only Mode 3 diverged in the lamp-ballast
 * refresh), so a MAC 250+ user manual is a valid human-readable reference.
 *
 * Four DMX personalities (Mode 1–4). Only Mode 4 (13-channel, extended) is
 * implemented for the TCH 2026 patch; the other modes remain as `// TODO`
 * enum entries.
 *
 * Authoritative channel map:
 * `Manuals/personalities/Martin_Mac250_Mode4.md` (transcribed from MagicQ
 * `EDIT HEAD` — the on-disk `.hed` files are obfuscated). The bundled
 * `UM_MAC250_EN_D.PDF` is the **wrong manual** (it's the Krypton variant).
 *
 * Discharge-lamp safety: lamp-on/off and reset bands live on the shutter
 * channel and must not be addressable from FX targeting. The shutter is
 * exposed as [WithStrobe] clamped to the safe band (closed/open/strobe,
 * 0–72); lamp/reset are explicit methods on [Mode4Ch].
 */
sealed class MartinMac250Fixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<MartinMac250Fixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        // TODO: MODE_1 (9, "Mode 1 (9-channel)")
        // TODO: MODE_2 (11, "Mode 2 (11-channel)")
        // TODO: MODE_3 (13, "Mode 3 (13-channel)")
        MODE_4(13, "Mode 4 (13-channel, extended)"),
    }

    /**
     * Channel 3 — colour wheel.
     *
     * Indexed colour positions (000 open, 156–207 indexed, 208–245 scroll).
     * Split positions (001–144, smooth half-and-half between adjacent
     * colours) are not enumerated — write the raw level if needed.
     *
     * `colourPreview` hex values are best-effort approximations for the UI;
     * exact gel hues depend on the physical filters fitted to the wheel.
     */
    enum class Colour(
        override val level: UByte,
        override val colourPreview: String? = null,
    ) : DmxFixtureColourSettingValue {
        OPEN(0u, "#FFFFFF"),
        PURPLE(156u, "#800080"),
        GREEN_202(160u, "#00CC55"),
        ORANGE(164u, "#FFA500"),
        BLUE_101(168u, "#1E5BFF"),
        MAGENTA(172u, "#FF00FF"),
        RED(176u, "#FF0000"),
        BLUE_108(180u, "#3370E0"),
        GREEN(184u, "#00FF00"),
        PINK(188u, "#FFC0CB"),
        BLUE(192u, "#0000FF"),
        YELLOW(196u, "#FFFF00"),
        CTC(200u, "#FFE0C0"),
        WHITE(204u, "#FFFFFF"),
        SCROLL_CW(208u),
        SCROLL_CCW(227u),
        RANDOM_FAST(246u),
        RANDOM_MEDIUM(249u),
        RANDOM_SLOW(252u),
    }

    /**
     * Channel 4 — gobo wheel.
     *
     * Eight static gobos (000 open + 7 indexed), eight matching shake variants,
     * and forward/reverse scroll. Indices below match the personality's
     * `VIEW RANGES` capture.
     */
    enum class Gobo(override val level: UByte) : DmxFixtureSettingValue {
        OPEN(0u),
        CONE(10u),
        BAR(20u),
        FAN_HAT(30u),
        TRIPLE(40u),
        DEC_BEAM(50u),
        FIBROID(60u),
        RND_HOLES_BLUE(70u),
        PYS_CIR_MAG(80u),
        PYS_CIR_SHAKE(90u),
        RND_HOLES_SHAKE(105u),
        FIBROID_SHAKE(120u),
        DEC_BEAM_SHAKE(135u),
        TRIPLE_SHAKE(150u),
        FAN_HAT_SHAKE(165u),
        BAR_SHAKE(180u),
        CONE_SHAKE(195u),
        SCROLL_CW(210u),
        SCROLL_CCW(233u),
    }

    /**
     * Channel 7 — prism / macros.
     *
     * 000–019 prism off, 020–149 rotation bands (CCW / no rot / CW),
     * 150–215 prism off again, 216–255 eight indexed macros.
     */
    enum class Prism(override val level: UByte) : DmxFixtureSettingValue {
        PRISM_OFF(0u),
        ROT_CCW(20u),
        NO_ROT(80u),
        ROT_CW(90u),
        PRISM_OFF_2(150u),
        MACRO_1(216u),
        MACRO_2(221u),
        MACRO_3(226u),
        MACRO_4(231u),
        MACRO_5(236u),
        MACRO_6(241u),
        MACRO_7(246u),
        MACRO_8(251u),
    }

    /**
     * Mode 4 (13-channel, extended) — the patched personality.
     *
     * - Ch 1: Shutter / strobe (lamp on/off and reset live on this channel
     *         too — exposed via the explicit [lampOn] / [lampOff] / [reset]
     *         methods, NOT via the [strobe] property).
     * - Ch 2: Master dimmer (HTP).
     * - Ch 3: Colour wheel.
     * - Ch 4: Gobo wheel.
     * - Ch 5: Gobo rotation (continuous; CCW/stop/CW band layout, exact split
     *         not captured — plain slider).
     * - Ch 6: Focus.
     * - Ch 7: Prism / macros.
     * - Ch 8: Pan (16-bit hi).
     * - Ch 9: Pan (16-bit lo).
     * - Ch 10: Tilt (16-bit hi).
     * - Ch 11: Tilt (16-bit lo).
     * - Ch 12: Pan/tilt speed.
     * - Ch 13: Effect speed.
     */
    @FixtureType("martin-mac-250-mode-4", manufacturer = "Martin", model = "MAC 250")
    class Mode4Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : MartinMac250Fixture(
        universe, firstChannel, 13, key, fixtureName, transaction,
    ), WithDimmer, WithPosition, WithStrobe {
        override val mode = Mode.MODE_4

        private constructor(fixture: Mode4Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): Mode4Ch =
            Mode4Ch(this, transaction)

        /**
         * Channel 1 — shutter / strobe / lamp / reset.
         *
         * Full personality bands: 000–019 Closed, 020–049 Open, 050–072
         * Strobe F→S, 080–099 Pulse Open, 100–119 Pulse Close, 128–207
         * Random strobe variants, 208–217 Reset, 228–237 Lamp On,
         * 248–255 Lamp Off.
         *
         * Only the safe band (0–[STROBE_BAND_MAX]) is reachable through the
         * [WithStrobe] property — the underlying slider's `max` clamp keeps
         * raw `value` writes from straying into Reset/Lamp territory. Pulse
         * and random-strobe bands are reachable only via raw transaction
         * writes. Lamp on/off and reset are explicit methods on this class
         * that bypass the slider clamp.
         */
        @FixtureProperty(category = PropertyCategory.STROBE)
        override val strobe = BandedStrobeChannel(
            transaction, universe, firstChannel,
            strobeMin = STROBE_BAND_MIN,
            strobeMax = STROBE_BAND_MAX,
            fullOnValue = OPEN_DEFAULT,
            max = STROBE_BAND_MAX,
        )

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Colour wheel", category = PropertyCategory.COLOUR)
        val colour = DmxFixtureSetting(
            transaction, universe, firstChannel + 2, Colour.entries.toTypedArray(),
        )

        @FixtureProperty("Gobo wheel", category = PropertyCategory.SETTING)
        val gobo = DmxFixtureSetting(
            transaction, universe, firstChannel + 3, Gobo.entries.toTypedArray(),
        )

        @FixtureProperty(
            "Gobo rotation (CCW / stop / CW)",
            category = PropertyCategory.SETTING,
        )
        val goboRotation: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty("Focus", category = PropertyCategory.OTHER)
        val focus: Slider = DmxSlider(transaction, universe, firstChannel + 5)

        @FixtureProperty("Prism", category = PropertyCategory.SETTING)
        val prism = DmxFixtureSetting(
            transaction, universe, firstChannel + 6, Prism.entries.toTypedArray(),
        )

        @FixtureProperty("Pan (coarse)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 540.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel + 7)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 8)

        @FixtureProperty("Tilt (coarse)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 257.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 9)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 10)

        @FixtureProperty("Pan/tilt speed", category = PropertyCategory.SPEED)
        val panTiltSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 11)

        @FixtureProperty("Effect speed", category = PropertyCategory.SPEED)
        val effectSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 12)

        private val nonNullTransaction get() = checkNotNull(transaction) {
            "Attempted to use fixture outside of a transaction"
        }

        /**
         * Strike the discharge lamp. Writes 228 to the shutter channel.
         *
         * The lamp draws ~16A inrush and takes several seconds to ignite.
         * Don't strike multiple fixtures simultaneously on the same circuit;
         * stagger the calls. Not FX-targetable by design.
         */
        fun lampOn() {
            nonNullTransaction.setValue(universe, firstChannel, LAMP_ON_LEVEL)
        }

        /**
         * Extinguish the discharge lamp. Writes 248 to the shutter channel.
         * The lamp needs to cool for several minutes before it can be
         * re-struck. Not FX-targetable by design.
         */
        fun lampOff() {
            nonNullTransaction.setValue(universe, firstChannel, LAMP_OFF_LEVEL)
        }

        /**
         * Reset the fixture (re-home pan/tilt and motors). Writes 208 to the
         * shutter channel. Not FX-targetable by design.
         */
        fun reset() {
            nonNullTransaction.setValue(universe, firstChannel, RESET_LEVEL)
        }

        companion object {
            /** Default open value (mid of 020–049 Open band). */
            const val OPEN_DEFAULT: UByte = 35u

            /** Lower bound of the strobe band (050–072). */
            const val STROBE_BAND_MIN: UByte = 50u

            /**
             * Upper bound of the strobe band; also the slider clamp, so
             * neither [WithStrobe] writes nor raw `value` writes can
             * wander into Reset/Lamp bands.
             */
            const val STROBE_BAND_MAX: UByte = 72u

            const val RESET_LEVEL: UByte = 208u
            const val LAMP_ON_LEVEL: UByte = 228u
            const val LAMP_OFF_LEVEL: UByte = 248u
        }
    }
}
