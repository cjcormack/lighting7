package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition

/**
 * ETC Source 4 Revolution — automated yoke fixture with framing shutters,
 * two beam wheels, and a 14-frame gel scroller.
 *
 * The ChamSys library lists five Source 4 Revolution personalities:
 * `Base` (14ch), `Base Iris` (15ch), `15ch` (15ch), `Base Module` (23ch),
 * and `Base Frame` (31ch). Only Base Frame (the chassis WITH the four-blade
 * Framing Shutter module installed) is implemented for the TCH 2026 patch;
 * the others remain as `// TODO` enum entries per the locked decision.
 *
 * Authoritative channel map:
 * `Manuals/personalities/ETC_Source4Rev_BaseFrame.md` (transcribed from
 * MagicQ `EDIT HEAD` — the on-disk `.hed` files are obfuscated). Only ch 8
 * (Zoom), ch 13 (Gel Scroller) and ch 15 (Iris) had `VIEW RANGES` detail
 * captured; the other channels are continuous controls without documented
 * value bands and are modelled as plain sliders.
 *
 * Reset (ch 12) and Reserved (ch 14) are NOT exposed as `@FixtureProperty`.
 * Reset's value bands were not captured and the TCH 2026 plan calls for
 * reset/lamp control to be unreachable from FX targeting; Reserved has no
 * documented purpose. Both default to 0 and stay there unless a script
 * writes raw values via the controller transaction.
 */
sealed class Source4RevolutionFixture(
    universe: Universe,
    firstChannel: Int,
    channelCount: Int,
    key: String,
    fixtureName: String,
    protected val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, channelCount, key, fixtureName),
    MultiModeFixtureFamily<Source4RevolutionFixture.Mode> {

    enum class Mode(
        override val channelCount: Int,
        override val modeName: String,
    ) : DmxChannelMode {
        // TODO: BASE (14, "Base (14-channel)")
        // TODO: BASE_IRIS (15, "Base Iris (15-channel)")
        // TODO: BASE_15CH (15, "15ch (15-channel)")
        // TODO: BASE_MODULE (23, "Base Module (23-channel)")
        BASE_FRAME(31, "Base Frame (31-channel)"),
    }

    /**
     * Channel 13 — built-in gel scroller. 14 evenly-spaced bands.
     *
     * The personality capture uses generic `Frame 0..Frame 13` labels with
     * no actual gel colours, so the enum mirrors that. Map a frame index
     * to its physical gel from the venue's gel string.
     */
    enum class GelFrame(override val level: UByte) : DmxFixtureSettingValue {
        FRAME_0(0u),
        FRAME_1(18u),
        FRAME_2(37u),
        FRAME_3(55u),
        FRAME_4(73u),
        FRAME_5(91u),
        FRAME_6(110u),
        FRAME_7(128u),
        FRAME_8(146u),
        FRAME_9(165u),
        FRAME_10(183u),
        FRAME_11(201u),
        FRAME_12(219u),
        FRAME_13(238u),
    }

    /**
     * Base Frame (31-channel) — the patched personality.
     *
     * - Ch 1: Master dimmer (HTP, mechanical douser).
     * - Ch 2/3: Pan (16-bit hi/lo).
     * - Ch 4/5: Tilt (16-bit hi/lo).
     * - Ch 6: Media frame.
     * - Ch 7: Focus.
     * - Ch 8: Zoom (wide → narrow continuous).
     * - Ch 9: Focus fade time.
     * - Ch 10: Colour fade time.
     * - Ch 11: Beam fade time.
     * - Ch 12: Reset (NOT exposed — see class doc).
     * - Ch 13: Gel scroller (14 frames).
     * - Ch 14: Reserved (NOT exposed).
     * - Ch 15: Iris (open → closed continuous).
     * - Ch 16/17: Forward beam wheel position / function.
     * - Ch 18/19: Forward beam wheel rotation (16-bit hi/lo).
     * - Ch 20/21: Rear beam wheel position / function.
     * - Ch 22/23: Rear beam wheel rotation (16-bit hi/lo).
     * - Ch 24/25: Frame 1 position / rotation.
     * - Ch 26/27: Frame 2 position / rotation.
     * - Ch 28/29: Frame 3 position / rotation.
     * - Ch 30/31: Frame 4 position / rotation.
     */
    @FixtureType(
        "etc-source4-revolution-base-frame",
        manufacturer = "ETC",
        model = "Source 4 Revolution",
    )
    class BaseFrame31Ch(
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
        transaction: ControllerTransaction? = null,
    ) : Source4RevolutionFixture(
        universe, firstChannel, 31, key, fixtureName, transaction,
    ), WithDimmer, WithPosition {
        override val mode = Mode.BASE_FRAME

        private constructor(fixture: BaseFrame31Ch, transaction: ControllerTransaction) : this(
            fixture.universe, fixture.key, fixture.fixtureName,
            fixture.firstChannel, transaction,
        )

        override fun withTransaction(transaction: ControllerTransaction): BaseFrame31Ch =
            BaseFrame31Ch(this, transaction)

        @FixtureProperty(category = PropertyCategory.DIMMER)
        override val dimmer: Slider = DmxSlider(transaction, universe, firstChannel)

        @FixtureProperty("Pan (coarse)", category = PropertyCategory.PAN,
            axis = PanTiltAxis.PAN, degMin = 0.0, degMax = 540.0)
        override val pan: Slider = DmxSlider(transaction, universe, firstChannel + 1)

        @FixtureProperty("Pan (fine)", category = PropertyCategory.PAN_FINE)
        val panFine: Slider = DmxSlider(transaction, universe, firstChannel + 2)

        @FixtureProperty("Tilt (coarse)", category = PropertyCategory.TILT,
            axis = PanTiltAxis.TILT, degMin = 0.0, degMax = 270.0)
        override val tilt: Slider = DmxSlider(transaction, universe, firstChannel + 3)

        @FixtureProperty("Tilt (fine)", category = PropertyCategory.TILT_FINE)
        val tiltFine: Slider = DmxSlider(transaction, universe, firstChannel + 4)

        @FixtureProperty("Media frame", category = PropertyCategory.OTHER)
        val mediaFrame: Slider = DmxSlider(transaction, universe, firstChannel + 5)

        @FixtureProperty("Focus", category = PropertyCategory.OTHER)
        val focus: Slider = DmxSlider(transaction, universe, firstChannel + 6)

        @FixtureProperty("Zoom (wide → narrow)", category = PropertyCategory.OTHER)
        val zoom: Slider = DmxSlider(transaction, universe, firstChannel + 7)

        @FixtureProperty("Focus fade time", category = PropertyCategory.SPEED)
        val focusTime: Slider = DmxSlider(transaction, universe, firstChannel + 8)

        @FixtureProperty("Colour fade time", category = PropertyCategory.SPEED)
        val colTime: Slider = DmxSlider(transaction, universe, firstChannel + 9)

        @FixtureProperty("Beam fade time", category = PropertyCategory.SPEED)
        val beamTime: Slider = DmxSlider(transaction, universe, firstChannel + 10)

        // Ch 12 (Reset) intentionally not exposed — see class doc.

        @FixtureProperty("Gel scroller", category = PropertyCategory.SETTING)
        val gelScroller = DmxFixtureSetting(
            transaction, universe, firstChannel + 12, GelFrame.entries.toTypedArray(),
        )

        // Ch 14 (Reserved) intentionally not exposed — see class doc.

        @FixtureProperty("Iris (open → closed)", category = PropertyCategory.OTHER)
        val iris: Slider = DmxSlider(transaction, universe, firstChannel + 14)

        @FixtureProperty("Forward beam wheel position", category = PropertyCategory.SETTING)
        val fbWheelPos: Slider = DmxSlider(transaction, universe, firstChannel + 15)

        @FixtureProperty("Forward beam wheel function", category = PropertyCategory.SETTING)
        val fbWheelFunc: Slider = DmxSlider(transaction, universe, firstChannel + 16)

        @FixtureProperty("Forward beam wheel rotation (coarse)", category = PropertyCategory.SETTING)
        val fbWheelRot: Slider = DmxSlider(transaction, universe, firstChannel + 17)

        @FixtureProperty("Forward beam wheel rotation (fine)", category = PropertyCategory.SETTING)
        val fbWheelRotFine: Slider = DmxSlider(transaction, universe, firstChannel + 18)

        @FixtureProperty("Rear beam wheel position", category = PropertyCategory.SETTING)
        val rbWheelPos: Slider = DmxSlider(transaction, universe, firstChannel + 19)

        @FixtureProperty("Rear beam wheel function", category = PropertyCategory.SETTING)
        val rbWheelFunc: Slider = DmxSlider(transaction, universe, firstChannel + 20)

        @FixtureProperty("Rear beam wheel rotation (coarse)", category = PropertyCategory.SETTING)
        val rbWheelRot: Slider = DmxSlider(transaction, universe, firstChannel + 21)

        @FixtureProperty("Rear beam wheel rotation (fine)", category = PropertyCategory.SETTING)
        val rbWheelRotFine: Slider = DmxSlider(transaction, universe, firstChannel + 22)

        @FixtureProperty("Frame 1 position", category = PropertyCategory.SETTING)
        val frame1Pos: Slider = DmxSlider(transaction, universe, firstChannel + 23)

        @FixtureProperty("Frame 1 rotation", category = PropertyCategory.SETTING)
        val frame1Rot: Slider = DmxSlider(transaction, universe, firstChannel + 24)

        @FixtureProperty("Frame 2 position", category = PropertyCategory.SETTING)
        val frame2Pos: Slider = DmxSlider(transaction, universe, firstChannel + 25)

        @FixtureProperty("Frame 2 rotation", category = PropertyCategory.SETTING)
        val frame2Rot: Slider = DmxSlider(transaction, universe, firstChannel + 26)

        @FixtureProperty("Frame 3 position", category = PropertyCategory.SETTING)
        val frame3Pos: Slider = DmxSlider(transaction, universe, firstChannel + 27)

        @FixtureProperty("Frame 3 rotation", category = PropertyCategory.SETTING)
        val frame3Rot: Slider = DmxSlider(transaction, universe, firstChannel + 28)

        @FixtureProperty("Frame 4 position", category = PropertyCategory.SETTING)
        val frame4Pos: Slider = DmxSlider(transaction, universe, firstChannel + 29)

        @FixtureProperty("Frame 4 rotation", category = PropertyCategory.SETTING)
        val frame4Rot: Slider = DmxSlider(transaction, universe, firstChannel + 30)
    }
}
