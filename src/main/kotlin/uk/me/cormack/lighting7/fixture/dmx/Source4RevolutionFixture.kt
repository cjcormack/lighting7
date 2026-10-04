package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition

/**
 * ETC Source 4 Revolution — an automated yoke profile: an electronic dimmer, a 15°–35° zoom, focus,
 * a 14-frame gel scroller, an internal media frame, and two module bays — the rear holding the
 * four-blade framing shutter module.
 *
 * The authority is ETC's *Source Four Revolution User Manual*, 7160M1200 Rev E
 * (`Manuals/S4_Revolution_User_Manual_RevE.pdf`; page numbers below are the PDF's). The ChamSys
 * capture in `Manuals/personalities/ETC_Source4Rev_BaseFrame.md` gave the channel order and the
 * scroller's bands, and the manual corrects it where they differ.
 *
 * The ChamSys library lists five Revolution personalities: `Base` (14ch), `Base Iris` (15ch),
 * `15ch` (15ch), `Base Module` (23ch) and `Base Frame` (31ch). Only Base Frame — the base with the
 * framing shutter module in the rear bay — is implemented, for the TCH 2026 patch; the others remain
 * `// TODO` entries.
 *
 * The bays (p17): the shutter module fits the rear bay only, so with it fitted channels 20–23 are
 * reserved and not exposed. The front bay takes one of a blank, the iris or a static or rotating
 * three-slot wheel; which one TCH's units hold is not known, so both the iris (ch 15) and the front
 * wheel (ch 16–19) stay live (plan P3). The wheels ship empty — their slots hold the user's M-size
 * gobos or dichroics — so the front wheel's slots name no pattern.
 *
 * Two channels are not exposed as `@FixtureProperty`. **Reset (ch 12)** is a command, not a value
 * (hold a band for three seconds, then snap to 0 — p15), and a reset recorded into a Look would fire
 * on playback; it stays at 0 unless a script writes the raw channel, and becomes a fixture command
 * in the fixture optics plan's session 7. **Ch 20–23** are reserved with the shutter module fitted.
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
     * Channel 13 — the gel scroller, loaded with ETC's standard 12-colour string (p15): an open
     * leader and trailer around twelve gels. ChamSys's 14 bands match ETC's starts exactly. A
     * venue's own string is fitted media, which arrives per placement in the fixture optics plan's
     * session 3; until then this is what every unit draws.
     *
     * Previews: the desk's gel library (`frontend/src/data/gels.ts`) where it has the gel — R02,
     * L203, L201, R68 — and the plan's approximate swatches
     * (`docs/plans/fixture-optics-design/fixture-optics.html`) for the eight it lacks. The open
     * frames are `#FFFFFF`, the library's open white, which is also what Locate looks for.
     */
    enum class GelFrame(override val level: UByte, override val colourPreview: String) : DmxFixtureColourSettingValue {
        // Estimate: every preview is an approximate swatch of the gel, not a measured transmission —
        // the library's hexes for R02, L203, L201 and R68, the design record's for the other eight.
        OPEN_LEADER(0u, "#FFFFFF"),
        R02_BASTARD_AMBER(18u, "#fbcc9a"),
        R05_ROSE_TINT(37u, "#f6d3d6"),
        R09_PALE_AMBER_GOLD(55u, "#f7c587"),
        R54_SPECIAL_LAVENDER(73u, "#dac7ea"),
        R357_ROYAL_LAVENDER(91u, "#a87bc9"),
        R36_MEDIUM_PINK(110u, "#ef9fb9"),
        R25_ORANGE_RED(128u, "#e85b2b"),
        L203_QUARTER_CT_BLUE(146u, "#dbe7f2"),
        L201_FULL_CT_BLUE(165u, "#9bbede"),
        R68_SKY_BLUE(183u, "#65a8db"),
        R88_LIGHT_GREEN(201u, "#b6e09a"),
        L_HT115_PEACOCK_BLUE(219u, "#2aa5a6"),
        OPEN_TRAILER(238u, "#FFFFFF"),
    }

    /**
     * Channel 6 — the internal media frame (p31–33): two gel wings the front lens moves in and out
     * of the beam. ETC documents it as in/out only and gives no bands.
     */
    enum class MediaFrame(override val level: UByte) : DmxFixtureSettingValue {
        OUT(0u),
        // Estimate: ETC gives no bands for channel 6; half way is assumed to be the split.
        IN(128u),
    }

    /**
     * Channel 16 — the front bay wheel's position (p22, p24): open, then three slots; 51–255 is
     * reserved and holds slot 3, so slot 3's band runs to the top. The slots name no pattern: the
     * wheels ship empty, and what is loaded is fitted media (the plan's session 3).
     */
    enum class WheelPosition(override val level: UByte) : DmxFixtureSettingValue {
        OPEN(0u),
        SLOT_1(14u),
        SLOT_2(27u),
        SLOT_3(40u),
    }

    /**
     * Channel 17 — the front bay wheel's function (p24): what channels 18/19 mean. Index aligns the
     * slot at an angle; the rotate bands spin it at a speed. 40–255 is reserved.
     */
    enum class WheelFunction(override val level: UByte) : DmxFixtureSettingValue {
        INDEX(0u),
        ROTATE_FWD(14u),
        ROTATE_REV(27u),
        RESERVED(40u),
    }

    /**
     * Base Frame (31-channel) — the patched personality.
     *
     * Channel map: "Base + Framing", p14.
     *
     * - Ch 1: Intensity (HTP) — an integral PWM electronic dimmer (p4), not a douser.
     * - Ch 2/3: Pan (16-bit hi/lo).
     * - Ch 4/5: Tilt (16-bit hi/lo).
     * - Ch 6: Internal media frame, out / in.
     * - Ch 7: Focus.
     * - Ch 8: Zoom, 35° → 15°.
     * - Ch 9: Focus timing. Ch 10: Colour timing. Ch 11: Beam timing (1 s per DMX step, p16).
     * - Ch 12: Reset (NOT exposed — see the class doc).
     * - Ch 13: Gel scroller, the standard 12-colour string.
     * - Ch 14: Fan speed (0 full → 255 off; the thermal sensors override it, p16).
     * - Ch 15: Iris (open → closed).
     * - Ch 16/17: Front bay wheel position / function.
     * - Ch 18/19: Front bay wheel index / rotation (16-bit hi/lo).
     * - Ch 20–23: Reserved with the shutter module fitted (NOT exposed).
     * - Ch 24/25 … 30/31: Shutters 1–4, in / rotate (±45°, p17) — `SHUTTER` / `SHUTTER_ROTATION`
     *   sliders naming their blade: 1 top, 2 bottom, 3 left, 4 right, each reaching the centre at
     *   full and square at 128. Which side each frame cuts, the depth and the rotation's sign are
     *   estimates (the manual states only the four blades and ±45°).
     */
    @FixtureType(
        "etc-source4-revolution-base-frame",
        manufacturer = "ETC",
        model = "Source 4 Revolution",
        kind = FixtureKind.PROFILE,
        // The manual's dimensions (p10): the head is 317 mm wide and 344 mm deep, and the unit stands
        // 856 mm from its base to the top of its head, which is what the Stage view reads a mover's
        // height as.
        widthM = 0.317,
        lengthM = 0.344,
        heightM = 0.856,
        // Estimate: ETC gives no lens size. The front lens is taken as about half the 317 mm head's
        // width.
        body = FixtureBody(BodyArchetype.MOVER, MoverHead.PROFILE, lensDiameterM = 0.15),
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

        @FixtureProperty("Media frame (out / in)", category = PropertyCategory.SETTING)
        val mediaFrame = DmxFixtureSetting(
            transaction, universe, firstChannel + 5, MediaFrame.entries.toTypedArray(),
        )

        // Estimate: ETC publishes no focus range or direction ("soft to crisp focus for gobos",
        // Rev E manual 7160A1002). 2 m to infinity covers the 4.9–18.2 m throws its photometrics
        // tabulate; infinity is the Stage view's 40 m longest throw.
        @FixtureProperty("Focus", category = PropertyCategory.FOCUS, focusNearM = 2.0, focusFarM = 40.0)
        val focus: Slider = DmxSlider(transaction, universe, firstChannel + 6)

        // The manual's 15°–35° zoom (p4; field angles 15.3°–34.3°, p47).
        // Estimate: the manual does not say which end is wide; DMX 0 wide is ChamSys's range names.
        @FixtureProperty("Zoom (wide → narrow)", category = PropertyCategory.ZOOM, degMin = 35.0, degMax = 15.0)
        val zoom: Slider = DmxSlider(transaction, universe, firstChannel + 7)

        @FixtureProperty("Focus fade time", category = PropertyCategory.SPEED)
        val focusTime: Slider = DmxSlider(transaction, universe, firstChannel + 8)

        @FixtureProperty("Colour fade time", category = PropertyCategory.SPEED)
        val colTime: Slider = DmxSlider(transaction, universe, firstChannel + 9)

        @FixtureProperty("Beam fade time", category = PropertyCategory.SPEED)
        val beamTime: Slider = DmxSlider(transaction, universe, firstChannel + 10)

        // Ch 12 (Reset) intentionally not exposed — see the class doc.

        @FixtureProperty("Gel scroller", category = PropertyCategory.COLOUR)
        val gelScroller = DmxFixtureSetting(
            transaction, universe, firstChannel + 12, GelFrame.entries.toTypedArray(),
        )

        @FixtureProperty("Fan speed (full → off)", category = PropertyCategory.OTHER)
        val fanSpeed: Slider = DmxSlider(transaction, universe, firstChannel + 13)

        @FixtureProperty("Iris (open → closed)", category = PropertyCategory.IRIS)
        val iris: Slider = DmxSlider(transaction, universe, firstChannel + 14)

        @FixtureProperty("Front wheel position", category = PropertyCategory.GOBO)
        val fbWheelPos = DmxFixtureSetting(
            transaction, universe, firstChannel + 15, WheelPosition.entries.toTypedArray(),
        )

        @FixtureProperty("Front wheel function", category = PropertyCategory.GOBO_ROTATION_MODE)
        val fbWheelFunc = DmxFixtureSetting(
            transaction, universe, firstChannel + 16, WheelFunction.entries.toTypedArray(),
        )

        // 0–30 RPM in a rotate band (p24). In index the manual says only "align the image".
        // Estimate: index is taken to sweep one full turn over the 16-bit range, and speed to rise
        // linearly with DMX; which way ROTATE_FWD turns is not stated, and the Stage view takes it
        // as its positive direction.
        @FixtureProperty("Front wheel index / rotation (coarse)", category = PropertyCategory.GOBO_ROTATION,
            rpmMax = 30.0, indexDegMax = 360.0)
        val fbWheelRot: Slider = DmxSlider(transaction, universe, firstChannel + 17)

        @FixtureProperty("Front wheel index / rotation (fine)", category = PropertyCategory.GOBO_ROTATION,
            fineOf = "fbWheelRot")
        val fbWheelRotFine: Slider = DmxSlider(transaction, universe, firstChannel + 18)

        // Ch 20–23 are reserved with the shutter module fitted (p14, p17) — not exposed.

        // Ch 24–31: the shutter module's four framing shutters (p17), each an insertion and a
        // rotation. The manual says only "four blades, each rotating ±45°".
        // Estimate: which side each frame cuts (1 top, 2 bottom, 3 left, 4 right, as a hung head
        // tilted out to the stage shows them — the Stage view's mover convention), that DMX 0 is out
        // and 255 reaches the centre of the field
        // (depthMax 0.5), that depth rises linearly with DMX, and that rotation runs −45° at DMX 0 to
        // +45° at 255, square at 128 (where ChamSys locates it), positive turning the blade
        // clockwise as seen from behind the head looking along its beam.
        @FixtureProperty("Frame 1 position", category = PropertyCategory.SHUTTER,
            blade = Blade.TOP, depthMax = 0.5)
        val frame1Pos: Slider = DmxSlider(transaction, universe, firstChannel + 23)

        @FixtureProperty("Frame 1 rotation", category = PropertyCategory.SHUTTER_ROTATION,
            blade = Blade.TOP, degMin = -45.0, degMax = 45.0)
        val frame1Rot: Slider = DmxSlider(transaction, universe, firstChannel + 24)

        @FixtureProperty("Frame 2 position", category = PropertyCategory.SHUTTER,
            blade = Blade.BOTTOM, depthMax = 0.5)
        val frame2Pos: Slider = DmxSlider(transaction, universe, firstChannel + 25)

        @FixtureProperty("Frame 2 rotation", category = PropertyCategory.SHUTTER_ROTATION,
            blade = Blade.BOTTOM, degMin = -45.0, degMax = 45.0)
        val frame2Rot: Slider = DmxSlider(transaction, universe, firstChannel + 26)

        @FixtureProperty("Frame 3 position", category = PropertyCategory.SHUTTER,
            blade = Blade.LEFT, depthMax = 0.5)
        val frame3Pos: Slider = DmxSlider(transaction, universe, firstChannel + 27)

        @FixtureProperty("Frame 3 rotation", category = PropertyCategory.SHUTTER_ROTATION,
            blade = Blade.LEFT, degMin = -45.0, degMax = 45.0)
        val frame3Rot: Slider = DmxSlider(transaction, universe, firstChannel + 28)

        @FixtureProperty("Frame 4 position", category = PropertyCategory.SHUTTER,
            blade = Blade.RIGHT, depthMax = 0.5)
        val frame4Pos: Slider = DmxSlider(transaction, universe, firstChannel + 29)

        @FixtureProperty("Frame 4 rotation", category = PropertyCategory.SHUTTER_ROTATION,
            blade = Blade.RIGHT, degMin = -45.0, degMax = 45.0)
        val frame4Rot: Slider = DmxSlider(transaction, universe, firstChannel + 30)
    }
}
