package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures

private val rgbHead: GroupableFixture by lazy {
    val universe = Universe(0, 0)
    val fixtures = Fixtures()
    fixtures.register {
        addController(MockDmxController(universe))
        addFixture(HexFixture(universe, "rgb", "RGB", 1))
    }
    fixtures.withTransaction(ControllerTransaction(fixtures.controllers)).untypedGroupableFixture("rgb")
}

/**
 * This output as the colour an RGB head with black underneath lands: a colour's own, or Colour
 * Cycle's steps mixed as [ColourTarget] mixes them.
 */
internal fun FxOutput.landedColour(): ExtendedColour = when (this) {
    is FxOutput.Colour -> color
    is FxOutput.Steps -> ColourTarget(FxTargetRef.fixture("rgb")).outputColour(rgbHead, this) { ExtendedColour.BLACK }
        ?: ExtendedColour.BLACK
    else -> error("$this is no colour")
}
