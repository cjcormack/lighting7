package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureWithSettings

enum class LaserscanMode(override val level: UByte): DmxFixtureSettingValue {
    OFF(0u),
    SOUND_ACTIVE(101u),
}

class LaserscanFixture(
    val controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "mode" to DmxFixtureSetting(controller, firstChannel, LaserscanMode.entries.toTypedArray()),
        )
    )
