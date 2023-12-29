package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.*

@FixtureType("fustion_spot")
class FusionSpotFixture(
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u,
): DmxFixture(controller, firstChannel, 15, key, fixtureName, position), FixtureWithDimmer {
    // TODO Add mechanism to make this meets DmxFixtureWithColour
    enum class Color(override val level: UByte): DmxFixtureSettingValue {
        WHITE(0u),
        RED(14u),
        Yellow(32u),
        Cyan(50u),
        Green(68u),
        Orange(86u),
        Magenta(104u),
        Blue(122u),
        WHITE_2(131u),
        RAINBOW_EFFECT(140u), // TODO also support slider for speed
        ROTATION_STOP(196u),
        REVERSE_RAINBOW_EFFECT(200u), // TODO also support slider for speed (reverse)
    }

    enum class Gobo(override val level: UByte): DmxFixtureSettingValue {
        OPEN(0u),
        GOBO_1(9u),
        GOBO_2(34u),
        GOBO_3(59u),
        GOBO_4(84u),
        GOBO_5(109u),
        RAINBOW_EFFECT(134u), // TODO also support slider for speed
        REVERSE_RAINBOW_EFFECT(195u), // TODO also support slider for speed (reverse)
    }

    enum class Strobe(override val level: UByte): DmxFixtureSettingValue {
        BLACKOUT(0u),
        STROBE_1(10u),
        STROBE_20(16u),
        STROBE_40(23u),
        STROBE_60(31u),
        STROBE_80(38u),
        STROBE_100(45u),
        LED_ON(246u),
    }

    enum class PrismMode(override val level: UByte): DmxFixtureSettingValue {
        OPEN(0u),
        PRISM(8u),
    }

    enum class ColourMode(override val level: UByte): DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        LED_MACRO_1(8u),
        LED_MACRO_2(48u),
        LED_MACRO_3(88u),
        LED_MACRO_4(128u),
        LED_MACRO_5(168u),
        LED_MACRO_6(208u),
        SOUND_ACTIVE(248u),
    }

    enum class PanTiltMode(override val level: UByte): DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        PAN_TILT_MACRO_1(8u),
        PAN_TILT_MACRO_2(59u),
        PAN_TILT_MACRO_3(111u),
        PAN_TILT_MACRO_4(163u),
        SOUND_ACTIVE(241u),
    }

    enum class MotorMode(override val level: UByte): DmxFixtureSettingValue {
        NO_FUNCTION(0u),
        HIGH_SPEED_MOTOR_MOVEMENT(51u),
        LOW_SPEED_MOTOR_MOVEMENT(151u),
        RESET(251u),
    }

    @FixtureProperty("Pan adjustment 0-540°")
    val pan = DmxFixtureSlider(controller, firstChannel)
    @FixtureProperty("Pan fine adjustment")
    val panFine = DmxFixtureSlider(controller, firstChannel + 1)
    @FixtureProperty("Tilt adjustment 0-210°")
    val tilt = DmxFixtureSlider(controller, firstChannel + 2)
    @FixtureProperty("Tilt fine adjustment")
    val tiltFine = DmxFixtureSlider(controller, firstChannel + 3)
    @FixtureProperty("Pan/tilt speed")
    val panTiltSpeed = DmxFixtureSlider(controller, firstChannel + 4)

    @FixtureProperty
    override val dimmer = DmxFixtureSlider(controller, firstChannel + 5, max = maxDimmerLevel)

    @FixtureProperty
    val strobe = DmxFixtureSetting(controller, firstChannel + 6, Strobe.entries.toTypedArray())

    @FixtureProperty
    val colour = DmxFixtureSetting(controller, firstChannel + 7, Color.entries.toTypedArray())

    @FixtureProperty
    val gobo = DmxFixtureSetting(controller, firstChannel + 8, Gobo.entries.toTypedArray())

    // TODO add support for forward and reverse
    @FixtureProperty
    val rotation = DmxFixtureSlider(controller, firstChannel + 9)

    @FixtureProperty
    val focus = DmxFixtureSlider(controller, firstChannel + 10)

    @FixtureProperty
    val prism = DmxFixtureSetting(controller, firstChannel + 11, PrismMode.entries.toTypedArray())

    @FixtureProperty
    val colourMode = DmxFixtureSetting(controller, firstChannel + 12, ColourMode.entries.toTypedArray())

    @FixtureProperty
    val panTiltMode = DmxFixtureSetting(controller, firstChannel + 13, PanTiltMode.entries.toTypedArray())

    @FixtureProperty
    val motorMode = DmxFixtureSetting(controller, firstChannel + 14, MotorMode.entries.toTypedArray())
}
