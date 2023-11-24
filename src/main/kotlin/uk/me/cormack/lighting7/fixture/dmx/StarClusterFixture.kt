package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureWithSettings

enum class StarClusterMode(override val level: UByte): DmxFixtureSettingValue {
    OFF(0u),
    AUTO_SHOW(51u),
    SOUND_ACTIVE(101u),
    DMX_MODE_1(151u),
    DMX_MODE_2(201u),
}

class StarClusterFixture(
    val controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "mode" to DmxFixtureSetting(controller, firstChannel, StarClusterMode.entries.toTypedArray()),
        )
    )
