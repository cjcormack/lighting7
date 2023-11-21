@file:OptIn(ExperimentalUnsignedTypes::class)

package uk.me.cormack.lighting7.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.Fixture
import uk.me.cormack.fixture.FixtureWithDimmer
import uk.me.cormack.fixture.FixtureWithSettings

enum class Scantastic(override val level: UByte): DmxFixtureSettingValue {
    BLACKOUT(0u),
    SOUND_ACTIVE(128u),
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class ScantasticFixture (
    val controller: ArtNetController,
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
