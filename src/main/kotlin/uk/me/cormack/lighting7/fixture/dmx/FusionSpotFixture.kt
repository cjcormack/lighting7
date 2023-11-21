@file:OptIn(ExperimentalUnsignedTypes::class)

package uk.me.cormack.lighting7.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.Fixture
import uk.me.cormack.fixture.FixtureWithDimmer
import uk.me.cormack.fixture.FixtureWithSettings
import uk.me.cormack.fixture.FixtureWithSliders

enum class FusionSpotFunction(override val level: UByte): DmxFixtureSettingValue {
    NONE(0u),
    SOUND_ACTIVE(250u),
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class FusionSpotFixture(
    val controller: ArtNetController,
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
