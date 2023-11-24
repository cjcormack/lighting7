package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureWithDimmer
import uk.me.cormack.lighting7.fixture.FixtureWithSettings

enum class Scantastic(override val level: UByte): DmxFixtureSettingValue {
    BLACKOUT(0u),
    SOUND_ACTIVE(128u),
}

class ScantasticFixture (
    val controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithDimmer by DmxFixtureWithDimmer(controller, firstChannel, maxDimmerLevel),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "mode" to DmxFixtureSetting(controller, firstChannel+16, Scantastic.entries.toTypedArray()),
        )
    )
