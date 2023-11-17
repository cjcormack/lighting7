@file:OptIn(ExperimentalUnsignedTypes::class)

package uk.me.cormack.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.Fixture
import uk.me.cormack.fixture.FixtureWithSettings

enum class StarClusterMode(override val level: UByte): DmxFixtureSettingValue {
    OFF(0u),
    AUTO_SHOW(51u),
    SOUND_ACTIVE(101u),
    DMX_MODE_1(151u),
    DMX_MODE_2(201u),
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class StarClusterFixture(
    val controller: ArtNetController,
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
