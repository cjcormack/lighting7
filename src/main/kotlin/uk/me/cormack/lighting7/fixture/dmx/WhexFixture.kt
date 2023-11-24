package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.*

enum class WHexProgramMode(override val level: UByte): DmxFixtureSettingValue {
    NONE(0u),
    SOUND_ACTIVE(241u),
}

class WHexFixture(
    val controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithDimmer by DmxFixtureWithDimmer(controller, firstChannel, maxDimmerLevel),
    FixtureWithColour by DmxFixtureWithColour(controller, firstChannel + 1, firstChannel + 2, firstChannel + 3, firstChannel + 4, firstChannel + 5, firstChannel + 6),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "mode" to DmxFixtureSetting(controller, firstChannel+9, WHexProgramMode.entries.toTypedArray()),
        )
    ),
    FixtureWithSliders by DmxFixtureWithSliders(controller,
        mapOf(
            "soundSensitivity" to DmxFixtureSliderSettings(firstChannel + 10, 10u)
        )
    )
