package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureColour

class DmxFixtureColour(
    override val transaction: ControllerTransaction?,
    override val universe: Universe,
    redChannelNo: Int,
    greenChannelNo: Int,
    blueChannelNo: Int,
): FixtureColour<DmxFixtureSlider>(
    DmxFixtureSlider(transaction, universe, redChannelNo),
    DmxFixtureSlider(transaction, universe, greenChannelNo),
    DmxFixtureSlider(transaction, universe, blueChannelNo),
), DmxFixtureMultiSlider
