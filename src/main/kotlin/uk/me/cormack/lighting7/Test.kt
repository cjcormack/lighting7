package uk.me.cormack.lighting7

import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture

fun main() {
    val controller = ArtNetController(Universe(0, 0))
//    val test = FusionSpotFixture(controller, "test", "Test", 1, 0)
    val test = HexFixture(controller.universe, "test", "Test", 1, 0)

    println(test.channelDescriptions())

    controller.close()
}
