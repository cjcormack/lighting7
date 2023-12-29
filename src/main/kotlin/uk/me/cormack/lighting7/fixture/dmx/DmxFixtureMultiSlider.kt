package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.FixtureMultiSlider
import uk.me.cormack.lighting7.fixture.FixtureSlider

interface DmxFixtureMultiSlider: FixtureMultiSlider<DmxFixtureSlider> {
    val controller: DmxController
}
