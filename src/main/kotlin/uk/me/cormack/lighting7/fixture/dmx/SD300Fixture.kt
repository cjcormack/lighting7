@file:OptIn(ExperimentalUnsignedTypes::class)

package uk.me.cormack.lighting7.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.lighting7.artnet.ArtNetController
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureWithSettings

enum class SD300Mode(override val level: UByte): DmxFixtureSettingValue {
    OFF(0u),
    SOUND_ACTIVE(50u),
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class SD300Fixture(
    val controller: ArtNetController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "mode" to DmxFixtureSetting(controller, firstChannel, SD300Mode.entries.toTypedArray()),
        )
    )
