package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.*

@FixtureType("uv")
class UVFixture (
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 190u
): DmxFixture(controller, firstChannel, 1, key, fixtureName, position),
    FixtureWithDimmer, FixtureWithUv
{
    @FixtureProperty
    override val dimmer = DmxFixtureSlider(controller, firstChannel, max = maxDimmerLevel)

    override val uvColour: FixtureSlider
        get() = dimmer
}
