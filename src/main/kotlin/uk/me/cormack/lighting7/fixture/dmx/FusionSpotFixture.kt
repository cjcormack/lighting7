package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureWithDimmer
import uk.me.cormack.lighting7.fixture.FixtureWithSettings
import uk.me.cormack.lighting7.fixture.FixtureWithSliders

enum class FusionSpotFunction(override val level: UByte): DmxFixtureSettingValue {
    NONE(0u),
    SOUND_ACTIVE(250u),
}

class FusionSpotFixture(
    val controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithSliders by DmxFixtureWithSliders(controller,
        mapOf(
            "pan" to DmxFixtureSliderSettings(firstChannel),
            "tilt" to DmxFixtureSliderSettings(firstChannel + 1),
        )
    ),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "function" to DmxFixtureSetting(controller, firstChannel, FusionSpotFunction.entries.toTypedArray()),
        )
    ),
    FixtureWithDimmer by DmxFixtureWithDimmer(controller, firstChannel+7, maxDimmerLevel)
