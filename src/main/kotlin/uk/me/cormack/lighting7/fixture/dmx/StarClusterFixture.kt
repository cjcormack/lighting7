package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType

@FixtureType("starcluster")
class StarClusterFixture(
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
): DmxFixture(controller, firstChannel, 5, key, fixtureName, position) {
    enum class Mode(override val level: UByte): DmxFixtureSettingValue {
        OFF(0u),
        AUTO_SHOW(51u),
        SOUND_ACTIVE(101u),
        DMX_MODE_1(151u),
        DMX_MODE_2(201u),
    }

    @FixtureProperty
    val mode = DmxFixtureSetting(controller, firstChannel, Mode.entries.toTypedArray())
}
