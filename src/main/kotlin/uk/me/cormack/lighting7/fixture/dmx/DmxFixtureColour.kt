package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.FixtureColour

class DmxFixtureColour(
    override val controller: DmxController,
    redChannelNo: Int,
    greenChannelNo: Int,
    blueChannelNo: Int,
): FixtureColour<DmxFixtureSlider>(
    DmxFixtureSlider(controller, redChannelNo),
    DmxFixtureSlider(controller, greenChannelNo),
    DmxFixtureSlider(controller, blueChannelNo),
), DmxFixtureMultiSlider
