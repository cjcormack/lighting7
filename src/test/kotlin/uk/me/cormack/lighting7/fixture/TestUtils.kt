package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe

/**
 * Create a controller transaction for testing with the given mock controllers.
 */
fun createTestTransaction(vararg controllers: MockDmxController): ControllerTransaction {
    return ControllerTransaction(controllers.toList())
}

/**
 * Create a controller transaction for a single mock universe.
 */
fun createTestTransaction(universe: Universe = Universe(0, 0)): Pair<MockDmxController, ControllerTransaction> {
    val controller = MockDmxController(universe)
    return controller to ControllerTransaction(listOf(controller))
}
