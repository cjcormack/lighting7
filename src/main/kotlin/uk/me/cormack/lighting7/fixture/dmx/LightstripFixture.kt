package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.*

@FixtureType("lightstrip")
class LightstripFixture (
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
): DmxFixture(controller, firstChannel, 5, key, fixtureName, position), DmxFixtureWithColour {
    @FixtureProperty
    override val rgbColour = DmxFixtureColour(
        controller,
        firstChannel,
        firstChannel + 1,
        firstChannel + 2,
    )

    @FixtureProperty
    val whiteColour = DmxFixtureSlider(controller, firstChannel + 3)
}
