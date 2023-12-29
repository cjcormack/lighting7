package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.FixtureWithDimmer

@FixtureType("scantastic")
class ScantasticFixture (
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): DmxFixture(controller, firstChannel, 17, key, fixtureName, position), FixtureWithDimmer {
    enum class Mode(override val level: UByte): DmxFixtureSettingValue {
        BLACKOUT(0u),
        SOUND_ACTIVE(128u),
    }

    @FixtureProperty
    override val dimmer = DmxFixtureSlider(controller, firstChannel, max = maxDimmerLevel)

    @FixtureProperty
    val mode = DmxFixtureSetting(controller, firstChannel + 9, Mode.entries.toTypedArray())
}
