package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.*

@FixtureType("whex")
class WHexFixture(
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): DmxFixture(controller, firstChannel, 12, key, fixtureName, position),
    FixtureWithDimmer, DmxFixtureWithColour
{
    enum class Mode(override val level: UByte): DmxFixtureSettingValue {
        NONE(0u),
        SOUND_ACTIVE(241u),
    }

    @FixtureProperty
    override val dimmer = DmxFixtureSlider(controller, firstChannel, max = maxDimmerLevel)

    @FixtureProperty
    override val rgbColour = DmxFixtureColour(
        controller,
        firstChannel + 1,
        firstChannel + 2,
        firstChannel + 3,
    )

    @FixtureProperty
    val whiteColour = DmxFixtureSlider(controller, firstChannel + 4)
    @FixtureProperty
    val amberColour = DmxFixtureSlider(controller, firstChannel + 5)
    @FixtureProperty
    val uvColour = DmxFixtureSlider(controller, firstChannel + 6)

    @FixtureProperty
    val mode = DmxFixtureSetting(controller, firstChannel + 9, Mode.entries.toTypedArray())

    @FixtureProperty
    val soundSensitivity = DmxFixtureSlider(controller, firstChannel + 10, min = 11u)

}
