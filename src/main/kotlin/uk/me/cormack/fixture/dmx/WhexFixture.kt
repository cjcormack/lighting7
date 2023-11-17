@file:OptIn(ExperimentalUnsignedTypes::class)

package uk.me.cormack.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.*

enum class WHexProgramMode(override val level: UByte): DmxFixtureSettingValue {
    NONE(0u),
    SOUND_ACTIVE(241u),
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class WHexFixture(
    val controller: ArtNetController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithDimmer by DmxFixtureWithDimmer(controller, firstChannel, maxDimmerLevel),
    FixtureWithColour by DmxFixtureWithColour(controller, firstChannel + 1, firstChannel + 2, firstChannel + 3, firstChannel + 4, firstChannel + 5, firstChannel + 6),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "mode" to DmxFixtureSetting(controller, firstChannel+9, WHexProgramMode.entries.toTypedArray()),
        )
    ),
    FixtureWithSliders by DmxFixtureWithSliders(controller,
        mapOf(
            "soundSensitivity" to DmxFixtureSliderSettings(firstChannel + 10, 10u)
        )
    )
