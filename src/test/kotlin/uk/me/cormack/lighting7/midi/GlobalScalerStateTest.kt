package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.KamLiteobar252Fixture
import uk.me.cormack.lighting7.fixture.dmx.LightstripRgbFixture
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.models.GRAND_MASTER_FULL
import uk.me.cormack.lighting7.models.ProjectScalerStateSnapshot
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

    @Test
    fun `holder seeds initial state from constructor args (restart rehydration)`() {
        val holder = GlobalScalerStateHolder(
            initial = ProjectScalerStateSnapshot(blackout = true, grandMasterLevel = 0.0),
        )
        assertTrue(holder.blackoutEnabled.value)
        assertFalse(holder.grandMasterEnabled.value)
        assertEquals(0.0, holder.grandMasterLevel.value)
        // Off at restart, so the toggle has no level of its own to go back to: full.
        holder.toggleGrandMaster()
        assertEquals(GRAND_MASTER_FULL, holder.grandMasterLevel.value)
    }

    @Test
    fun `persist callback fires on every effective state change`() {
        val calls = mutableListOf<ProjectScalerStateSnapshot>()
        val holder = GlobalScalerStateHolder(persist = { calls += it })

        // Actual change → persist called.
        holder.setBlackout(true)
        assertEquals(listOf(ProjectScalerStateSnapshot(blackout = true, grandMasterLevel = 100.0)), calls)

        // No-op write → persist not called.
        holder.setBlackout(true)
        assertEquals(1, calls.size)

        // Grand Master flip captures the current blackout alongside the new value.
        holder.setGrandMaster(false)
        assertEquals(ProjectScalerStateSnapshot(blackout = true, grandMasterLevel = 0.0), calls.last())

        // Toggles also persist the post-toggle state.
        holder.toggleBlackout()
        assertEquals(ProjectScalerStateSnapshot(blackout = false, grandMasterLevel = 0.0), calls.last())
        holder.toggleGrandMaster()
        assertEquals(ProjectScalerStateSnapshot(blackout = false, grandMasterLevel = 100.0), calls.last())

        // A level move persists; a no-op one does not.
        holder.setGrandMasterLevel(40.0)
        assertEquals(ProjectScalerStateSnapshot(blackout = false, grandMasterLevel = 40.0), calls.last())
        holder.setGrandMasterLevel(40.0)

        assertEquals(5, calls.size)
    }

    @Test
    fun `persist callback fires through the GlobalScalerState facade wrapping a persistent holder`() {
        val (fixtures, _) = registry()
        val calls = mutableListOf<ProjectScalerStateSnapshot>()
        val holder = GlobalScalerStateHolder(persist = { calls += it })
        val scaler = GlobalScalerState(fixtures, holder)
        scaler.attach()

        scaler.setBlackout(true)
        scaler.setGrandMaster(false)
        assertEquals(
            listOf(
                ProjectScalerStateSnapshot(blackout = true, grandMasterLevel = 100.0),
                ProjectScalerStateSnapshot(blackout = true, grandMasterLevel = 0.0),
            ),
            calls,
        )
    }

    @Test
    fun `the level scales dimmer and UV, leaves strobe until zero, and leaves a dimmed head's colour`() {
        val (fixtures, controller) = registry()
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()

        controller.setValue(1, 200u, 0)  // dimmer
        controller.setValue(2, 128u, 0)  // red
        controller.setValue(7, 100u, 0)  // uv
        controller.setValue(8, 50u, 0)   // strobe

        scaler.setGrandMasterLevel(50.0)
        assertTrue(scaler.grandMasterEnabled.value)
        assertEquals(100u.toUByte(), controller.getEffectiveValue(1))
        assertEquals(50u.toUByte(), controller.getEffectiveValue(7))
        // A rate, not a level: scaling it would change the strobe's speed.
        assertEquals(50u.toUByte(), controller.getEffectiveValue(8))
        // The Hex has a dimmer, so its colour is the dimmer's to scale.
        assertEquals(128u.toUByte(), controller.getEffectiveValue(2))

        scaler.setGrandMasterLevel(0.0)
        assertFalse(scaler.grandMasterEnabled.value)
        assertEquals(0u.toUByte(), controller.getEffectiveValue(1))
        assertEquals(0u.toUByte(), controller.getEffectiveValue(8))
        assertEquals(128u.toUByte(), controller.getEffectiveValue(2))
    }

    @Test
    fun `a head with no dimmer has its colour mix scaled, its strobe and macro left alone (D12)`() {
        val fixtures = Fixtures()
        val controller = MockDmxController(Universe(0, 0))
        fixtures.register(removeUnused = true) {
            addController(controller)
            // Ch 1–3: RGB, no dimmer.
            addFixture(LightstripRgbFixture(Universe(0, 0), "strip", "Strip", firstChannel = 1))
            // Ch 11 macro, 12 strobe, 13–21 three RGB cells — no dimmer on the bar or its cells.
            addFixture(KamLiteobar252Fixture(Universe(0, 0), "bar", "Bar", firstChannel = 11))
        }
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()
        for (ch in listOf(1, 2, 3, 11, 12, 13, 21)) controller.setValue(ch, 200u, 0)

        scaler.setGrandMasterLevel(25.0)
        for (ch in listOf(1, 2, 3, 13, 21)) assertEquals(50u.toUByte(), controller.getEffectiveValue(ch), "ch $ch")
        assertEquals(200u.toUByte(), controller.getEffectiveValue(11), "the macro is a setting")
        assertEquals(200u.toUByte(), controller.getEffectiveValue(12), "the strobe is a rate")

        scaler.setBlackout(true)
        for (ch in listOf(1, 3, 12, 13, 21)) assertEquals(0u.toUByte(), controller.getEffectiveValue(ch), "ch $ch")
    }

    @Test
    fun `a zone under its parent's master dimmer is left to that dimmer`() {
        val fixtures = Fixtures()
        val controller = MockDmxController(Universe(0, 0))
        fixtures.register(removeUnused = true) {
            addController(controller)
            // Ch 7 the master dimmer, ch 9–20 three RGBW zones.
            addFixture(ShehdsLed19RgbwFixture.Mode24Ch(Universe(0, 0), "wash", "Wash", firstChannel = 1))
        }
        val scaler = GlobalScalerState(fixtures)
        scaler.attach()
        for (ch in listOf(7, 9, 12, 20)) controller.setValue(ch, 200u, 0)

        scaler.setGrandMasterLevel(50.0)
        assertEquals(100u.toUByte(), controller.getEffectiveValue(7))
        for (ch in listOf(9, 12, 20)) assertEquals(200u.toUByte(), controller.getEffectiveValue(ch), "ch $ch")
    }

    @Test
    fun `the toggle takes the level to zero and back to where it was`() {
        val holder = GlobalScalerStateHolder()
        holder.setGrandMasterLevel(60.0)

        assertFalse(holder.toggleGrandMaster())
        assertEquals(0.0, holder.grandMasterLevel.value)
        assertTrue(holder.toggleGrandMaster())
        assertEquals(60.0, holder.grandMasterLevel.value)

        // Pulled to zero by the fader, then switched on: the last level above zero.
        holder.setGrandMasterLevel(30.0)
        holder.setGrandMasterLevel(0.0)
        holder.setGrandMaster(true)
        assertEquals(30.0, holder.grandMasterLevel.value)
        // Already on: nothing moves.
        assertFalse(holder.setGrandMaster(true))
    }

    @Test
    fun `the level is clamped into 0 to 100 and a non-finite one is ignored`() {
        val holder = GlobalScalerStateHolder()
        holder.setGrandMasterLevel(140.0)
        assertEquals(100.0, holder.grandMasterLevel.value)
        holder.setGrandMasterLevel(-5.0)
        assertEquals(0.0, holder.grandMasterLevel.value)
        assertFalse(holder.setGrandMasterLevel(Double.NaN))
        assertEquals(0.0, holder.grandMasterLevel.value)
    }

    @Test
    fun `a control's 0 to 127 maps onto the level and back`() {
        assertEquals(0.0, grandMasterLevelOf7Bit(0u))
        assertEquals(100.0, grandMasterLevelOf7Bit(127u))
        for (v in 0..127) assertEquals(v.toUByte(), grandMasterLevelTo7Bit(grandMasterLevelOf7Bit(v.toUByte())))
    }
}
