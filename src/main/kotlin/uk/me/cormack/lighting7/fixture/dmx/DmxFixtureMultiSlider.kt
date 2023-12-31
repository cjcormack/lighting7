package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureMultiSlider

interface DmxFixtureMultiSlider: FixtureMultiSlider<DmxFixtureSlider> {
    val transaction: ControllerTransaction?
    val universe: Universe
}
