@file:OptIn(ExperimentalUnsignedTypes::class)

package uk.me.cormack.lighting7.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.lighting7.artnet.ArtNetController
import uk.me.cormack.lighting7.fixture.*

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class LightstripFixture (
    val controller: ArtNetController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithColour by DmxFixtureWithColour(controller, firstChannel, firstChannel + 1, firstChannel + 2, firstChannel + 4)
