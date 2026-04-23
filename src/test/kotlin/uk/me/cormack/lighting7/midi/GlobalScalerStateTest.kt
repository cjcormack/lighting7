package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration-style tests for [GlobalScalerState]. Wires a real [Fixtures] registry with a
 * [MockDmxController] so the transmit modifier pipeline can be exercised through the public
 * [MockDmxController.getEffectiveValue] hook.
 */
class GlobalScalerStateTest {

    private fun registry(): Pair<Fixtures, MockDmxController> {
        val fixtures = Fixtures()
        val controller = MockDmxController(Universe(0, 0))
        fixtures.register(removeUnused = true) {
            addController(controller)
            addFixture(HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1))
        }
        return fixtures to controller
    }

    @Test
    fun `attach registers modifier on controller and classifies intensity channels`() {
        val (fixtures, controller) = registry()
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()

        assertEquals(1, controller.transmitModifiers.size)
        // Hex has DIMMER at ch 1, UV at ch 7, STROBE at ch 8 (firstChannel=1 + 0/6/7).
        // Initial state: not killed, so values pass through.
        controller.setValue(1, 200u, 0)
        assertEquals(200u.toUByte(), controller.getEffectiveValue(1))
    }

    @Test
    fun `blackout zeroes intensity channels but leaves colour alone`() {
        val (fixtures, controller) = registry()
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()

        controller.setValue(1, 200u, 0)  // dimmer
        controller.setValue(2, 128u, 0)  // red
        controller.setValue(7, 100u, 0)  // uv
        controller.setValue(8, 50u, 0)   // strobe
        controller.setValue(11, 90u, 0)  // programSpeed (SPEED category — not intensity)

        scaler.toggleBlackout()
        assertTrue(scaler.blackoutEnabled.value)
        assertEquals(0u.toUByte(), controller.getEffectiveValue(1))
        assertEquals(128u.toUByte(), controller.getEffectiveValue(2))
        assertEquals(0u.toUByte(), controller.getEffectiveValue(7))
        assertEquals(0u.toUByte(), controller.getEffectiveValue(8))
        assertEquals(90u.toUByte(), controller.getEffectiveValue(11))

        scaler.toggleBlackout()
        assertFalse(scaler.blackoutEnabled.value)
        assertEquals(200u.toUByte(), controller.getEffectiveValue(1))
    }

    @Test
    fun `grand master off kills intensity channels`() {
        val (fixtures, controller) = registry()
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()

        controller.setValue(1, 200u, 0)
        controller.setValue(2, 128u, 0)

        scaler.toggleGrandMaster()
        assertFalse(scaler.grandMasterEnabled.value)
        assertEquals(0u.toUByte(), controller.getEffectiveValue(1))
        assertEquals(128u.toUByte(), controller.getEffectiveValue(2))
    }

    @Test
    fun `setBlackout triggers transmit request`() {
        val (fixtures, controller) = registry()
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()

        val before = controller.transmitRequests
        scaler.setBlackout(true)
        val afterOn = controller.transmitRequests
        assertTrue(afterOn > before)

        // Setting to same value is a no-op.
        scaler.setBlackout(true)
        assertEquals(afterOn, controller.transmitRequests)
    }

    @Test
    fun `detach removes modifier from controllers`() {
        val (fixtures, controller) = registry()
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()
        assertEquals(1, controller.transmitModifiers.size)
        scaler.detach()
        assertTrue(controller.transmitModifiers.isEmpty())
    }

    @Test
    fun `seeded intensity set is used by modify`() {
        val (fixtures, _) = registry()
        val scaler = GlobalScalerState(fixtures)
        scaler.seedIntensityChannelsForTest(setOf(0 to 5))

        // Blackout on but channel 5 is the only "intensity" channel per the seed.
        scaler.setBlackout(true)
        assertEquals(0u.toUByte(), scaler.modify(Universe(0, 0), 5, 200u))
        assertEquals(200u.toUByte(), scaler.modify(Universe(0, 0), 6, 200u))
    }

    @Test
    fun `shared holder preserves state across facade re-creation`() {
        // A project's holder outlives any single `GlobalScalerState` facade. Simulate a
        // project switch A → B → A by constructing three facades against two holders and
        // confirm the A holder's state survives the B interlude.
        val (fixturesA, controllerA) = registry()
        val holderA = GlobalScalerStateHolder()
        val holderB = GlobalScalerStateHolder()

        val scalerA = GlobalScalerState(fixturesA, holderA)
        scalerA.attach()
        scalerA.setBlackout(true)
        assertTrue(holderA.blackoutEnabled.value)
        controllerA.setValue(1, 200u, 0)
        assertEquals(0u.toUByte(), controllerA.getEffectiveValue(1))

        scalerA.detach()
        val (fixturesB, _) = registry()
        val scalerB = GlobalScalerState(fixturesB, holderB)
        scalerB.attach()
        assertFalse(holderB.blackoutEnabled.value)

        scalerB.detach()
        val (fixturesA2, controllerA2) = registry()
        val scalerA2 = GlobalScalerState(fixturesA2, holderA)
        scalerA2.attach()
        assertTrue(scalerA2.blackoutEnabled.value)
        controllerA2.setValue(1, 200u, 0)
        assertEquals(0u.toUByte(), controllerA2.getEffectiveValue(1))
    }

    @Test
    fun `state exposed via facade tracks the shared holder`() {
        val (fixtures, _) = registry()
        val holder = GlobalScalerStateHolder()
        val scaler = GlobalScalerState(fixtures, holder)

        assertFalse(scaler.blackoutEnabled.value)
        assertTrue(scaler.grandMasterEnabled.value)

        holder.setBlackout(true)
        assertTrue(scaler.blackoutEnabled.value)

        scaler.toggleGrandMaster()
        assertFalse(holder.grandMasterEnabled.value)
    }
}
