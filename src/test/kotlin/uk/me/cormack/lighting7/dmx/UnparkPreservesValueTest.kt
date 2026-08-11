package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unpark must be a no-op on the wire.
 *
 * Park is the top output layer, so whatever sits underneath it is unrelated to what the
 * rig is emitting — usually 0. Removing the override without first pushing the parked
 * value down therefore snaps the channel from (say) 255 to 0 the instant it is unparked.
 * The Commemoration Hall rig parks channels precisely to hold hard-powered fixtures on a
 * dimmer at a safe level, so that snap is a safety failure, not cosmetics: it makes
 * park/unpark a one-way door when operators expect a toggle.
 *
 * These tests pin the property that `park → unpark → park` never moves the output, and
 * that the hand-off lands in *both* layers below park (programmer sideband and controller
 * buffer) so an effect reset-to-neutral can't quietly undo it.
 *
 * Companion coverage: [ArtNetParkSafetyTest] (park wins at transmit time) and
 * [ParkSurvivesFixtureReloadTest] (park survives a controller rebuild).
 */
class UnparkPreservesValueTest : RouteIntegrationTest() {

    private val universe = Universe(0, 0)

    private val controller get() = state.show.fixtures.controller(universe)

    @Test
    fun `unparking leaves the channel at its parked value rather than the value underneath`() = runBlocking {
        val parkManager = state.show.parkManager

        // Nothing has ever written channel 5 — the buffer under park is 0. This is the
        // hazardous shape: a fixture parked at full with nothing holding it up.
        assertEquals(0u.toUByte(), controller.getValue(5))

        parkManager.park(universe = 0, channel = 5, value = 255u)
        assertEquals(255u.toUByte(), controller.getValue(5))

        parkManager.unpark(universe = 0, channel = 5)

        assertFalse(parkManager.isParked(0, 5), "channel must actually be unparked")
        assertEquals(
            255u.toUByte(), controller.getValue(5),
            "unpark must hand the parked value down, not drop the channel to the 0 underneath",
        )
        assertEquals(
            255u.toUByte(), state.show.programmerStore.getChannel(0, 5),
            "the handed-off value must be sticky in the programmer sideband so an effect reset resumes from it",
        )
        assertEquals(
            false, state.show.programmerStore.channelEntries().single().slots.single().touched,
            "an unpark hand-down is not an operator edit — Record must not see it",
        )
    }

    @Test
    fun `park and unpark toggle repeatedly without moving the output`() = runBlocking {
        val parkManager = state.show.parkManager

        // "Park at Value" parks at a level the channel isn't currently at, so the buffer
        // underneath park (40) differs from the parked value (180) — the shape that turns a
        // toggle into a one-way door if the value isn't handed down.
        controller.setValue(7, 40u, fadeMs = 0)
        parkManager.park(universe = 0, channel = 7, value = 180u)

        repeat(3) { cycle ->
            assertEquals(
                180u.toUByte(), controller.getValue(7),
                "value drifted while parked on cycle $cycle",
            )

            parkManager.unpark(universe = 0, channel = 7)
            assertEquals(
                180u.toUByte(), controller.getValue(7),
                "value drifted across unpark on cycle $cycle",
            )

            parkManager.park(universe = 0, channel = 7, value = controller.getValue(7))
        }

        parkManager.unpark(universe = 0, channel = 7)
        assertEquals(180u.toUByte(), controller.getValue(7))
    }

    @Test
    fun `unparkAll hands off every parked value`() = runBlocking {
        val parkManager = state.show.parkManager

        parkManager.park(universe = 0, channel = 11, value = 255u)
        parkManager.park(universe = 0, channel = 12, value = 128u)
        parkManager.park(universe = 0, channel = 13, value = 1u)

        parkManager.unparkAll()

        assertTrue(parkManager.getAllParked().isEmpty(), "unparkAll must clear park state")
        assertEquals(255u.toUByte(), controller.getValue(11))
        assertEquals(128u.toUByte(), controller.getValue(12))
        assertEquals(1u.toUByte(), controller.getValue(13))
    }

    @Test
    fun `unparking a channel that was never parked writes nothing`() = runBlocking {
        val parkManager = state.show.parkManager

        parkManager.unpark(universe = 0, channel = 42)

        assertEquals(0u.toUByte(), controller.getValue(42))
        assertNull(
            state.show.programmerStore.getChannel(0, 42),
            "a no-op unpark must not plant a sticky programmer entry",
        )
    }

    @Test
    fun `the hand-off happens before the park override is dropped`() = runBlocking {
        // Ordering matters, not just the end state: the 25 ms transmit loop runs concurrently
        // with the unpark, so if the override were removed first there is a window in which a
        // frame carries the old value from underneath park. Assert from inside the sink that
        // park is still in force when the hand-off runs.
        var parkedDuringHandOff: Boolean? = null

        lateinit var parkManager: ParkManager
        parkManager = ParkManager(
            state.database,
            projectId,
            unparkValueSink = { values ->
                parkedDuringHandOff = values.all { parkManager.isParked(it.universe, it.channel) }
            },
        )

        parkManager.park(universe = 0, channel = 9, value = 200u)
        parkManager.unpark(universe = 0, channel = 9)

        assertEquals(
            true, parkedDuringHandOff,
            "the parked value must be written down while park is still overriding output",
        )
        assertFalse(parkManager.isParked(0, 9))
    }
}
