package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals

/**
 * Patch-time channel-state preservation. A fixture-and-controller rebuild
 * (`DbFixtureLoader.loadFixtures`) must keep both parked and non-parked channel
 * values intact: parked via the [ParkSource] hook on the fresh controller,
 * non-parked via the snapshot/restore in `DbFixtureLoader`.
 *
 * Regression context: patching or editing a fixture via `POST /patches` /
 * `PUT /patches/{id}` was zeroing every channel on the rig. For non-parked
 * channels that's a UX bug (lights blink off mid-edit). For parked channels
 * it's a safety hazard — operators rely on park to lock hard-powered fixtures
 * (e.g. a tungsten head on a dimmer) at a known output. Quietly unparking on
 * a UI side-effect could ignite real equipment.
 */
class ParkSurvivesFixtureReloadTest : RouteIntegrationTest() {

    @Test
    fun `parked and unparked channel state both survive DbFixtureLoader rebuild`() = runBlocking {
        val parkManager = state.show.parkManager
        val universe = Universe(0, 0)

        // Park channel 5 at value 100, and write a non-parked value to channel 6.
        parkManager.park(universe = 0, channel = 5, value = 100u)
        state.show.fixtures.controller(universe).setValue(6, 200u, fadeMs = 0)

        // Sanity: both visible on the pre-rebuild controller.
        assertEquals(100u.toUByte(), state.show.fixtures.controller(universe).getValue(5))
        assertEquals(200u.toUByte(), state.show.fixtures.controller(universe).getValue(6))

        // Same rebuild path as `POST /patches`: a full controller swap.
        DbFixtureLoader.loadFixtures(
            projectId = projectId,
            fixtures = state.show.fixtures,
            database = state.database,
            parkSource = parkManager,
        )

        val freshController = state.show.fixtures.controller(universe)
        assertEquals(
            100u.toUByte(),
            freshController.getValue(5),
            "parked channel must still report its parked value on the freshly-rebuilt controller",
        )
        assertEquals(
            200u.toUByte(),
            freshController.getValue(6),
            "non-parked channel value must survive controller rebuild — phase 2 snapshot/restore",
        )
    }
}
