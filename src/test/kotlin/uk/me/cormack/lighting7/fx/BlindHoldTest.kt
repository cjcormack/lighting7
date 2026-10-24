package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Blind holds the programmer's effects (show-cue-editing plan D7, §3.6; [BlindHold]).
 *
 * Driven by synthetic passes, as `FxEnginePipelineTest` is, so what reaches the controller is a
 * function of the tick and nothing else. Each case runs a programmer-band instance on `hex-a` beside
 * a **twin** on `hex-b` — the same effect, the same timing, started in the same call, but a cue's — so
 * "paints in step with the effects it started beside" is an equality between two channels rather
 * than arithmetic about where a phase should be.
 *
 * The three origins the plan names all land in the band, which is the whole of what the rule reads:
 * a busk (`programmerOwned`) and Include's ad-hoc children at [FxEngine.PROGRAMMER_FX_PRIORITY_BASE],
 * a programmer layer's at the base plus its rank. Which route stamps which priority is pinned
 * beside the routes (`BlindHoldsProgrammerFxTest`).
 */
class BlindHoldTest {

    private val universe = Universe(0, 0)

    private class Rig(val controller: MockDmxController, val engine: FxEngine, val programmerStore: ProgrammerStore)

    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", 1))
            addFixture(HexFixture(universe, "hex-b", "Hex B", 13))
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )
        return Rig(controller, engine, programmerStore)
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    /** Paints its phase: 10 at the top of the cycle, rising 2 a percent — never 0, so a dark channel is held. */
    private class PhaseSlider : Fx {
        override val name = "Phase Slider"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput =
            FxOutput.Slider((10 + (phase * 200).toInt()).toUByte())
    }

    private fun dimmerEffect(fixtureKey: String, priority: Int, cueId: Int? = null, effect: Fx = PhaseSlider()) =
        FxInstance(
            effect = effect,
            target = SliderTarget(fixtureKey, "dimmer"),
            // Two beats a cycle, so the ticks below walk across the cycle rather than landing on 0.
            timing = FxTiming(cycle = 2.0),
            blendMode = BlendMode.OVERRIDE,
        ).also {
            it.priority = priority
            it.cueId = cueId
        }

    private fun Rig.hexA() = controller.currentValues[1]
    private fun Rig.hexB() = controller.currentValues[13]

    /** A busk / an included child, and a programmer layer at rank 3. */
    private val bandPriorities = listOf(
        "a busk or an included child" to FxEngine.PROGRAMMER_FX_PRIORITY_BASE,
        "a programmer layer's" to FxEngine.PROGRAMMER_FX_PRIORITY_BASE + 3,
    )

    @Test
    fun `a programmer-band effect paints nothing under Blind and lands in phase after it`() {
        for ((origin, priority) in bandPriorities) {
            val rig = newRig()
            // One call, so the two start in the same pass, as an operator's busk beside a cue would.
            rig.engine.addFxBatch(listOf(dimmerEffect("hex-a", priority), dimmerEffect("hex-b", 1_000_001, cueId = 42)))

            rig.engine.processBeatTick(tick(0))
            assertEquals(rig.hexB(), rig.hexA(), "$origin: in step before Blind")

            rig.engine.programmer.setBlind(true)
            for (n in 1L..20L) {
                rig.engine.processBeatTick(tick(n))
                assertEquals(0u.toUByte(), rig.hexA(), "$origin: held — the layer below (the base) shows (tick=$n)")
                assertNotEquals(0u.toUByte(), rig.hexB(), "the cue's effect keeps painting under Blind (tick=$n)")
            }

            rig.engine.programmer.setBlind(false)
            rig.engine.processBeatTick(tick(21))
            assertNotEquals(rig.hexA(), 10u.toUByte(), "$origin: not restarted from the top of its cycle")
            assertEquals(rig.hexB(), rig.hexA(), "$origin: mid-phase, in step with the effect it started beside")
        }
    }

    @Test
    fun `Blind holds the band only — a manual effect paints, and the programmer's values stop holding it back`() {
        val rig = newRig()
        rig.programmerStore.put(ProgrammerOwner.WEB, "hex-a", "dimmer", CueAssignmentResolver.AttributeValue.Slider(180u))
        rig.engine.addFx(dimmerEffect("hex-a", priority = 0))

        rig.engine.processBeatTick(tick(5))
        assertEquals(180u.toUByte(), rig.hexA(), "suppressed by the programmer's value")

        rig.engine.programmer.setBlind(true)
        rig.engine.processBeatTick(tick(6))
        assertNotEquals(180u.toUByte(), rig.hexA(), "a manual effect is not the programmer's: it paints under Blind")
        assertNotEquals(0u.toUByte(), rig.hexA())
    }

    @Test
    fun `a held wall-clock effect keeps its place in real time`() {
        // A wall-clock effect, and a beat effect on Seconds (venue-visit plan S15), which runs on the
        // wall clock too. Its clock advances on every pass, painted or held.
        val rig = newRig()
        val held = dimmerEffect("hex-a", FxEngine.PROGRAMMER_FX_PRIORITY_BASE).apply { timingSource = TimingSource.WALL_CLOCK }
        val twin = dimmerEffect("hex-b", 1_000_001, cueId = 42).apply { timingSource = TimingSource.WALL_CLOCK }
        rig.engine.addFxBatch(listOf(held, twin))

        rig.engine.processWallClockTick()
        rig.engine.programmer.setBlind(true)
        repeat(4) {
            Thread.sleep(15)
            rig.engine.processWallClockTick()
            assertEquals(0u.toUByte(), rig.hexA(), "held on the wall-clock loop")
            assertNotEquals(0u.toUByte(), rig.hexB(), "the cue's twin paints")
        }
        assertTrue(held.accumulatedScaledMs > 0.0, "the held instance's clock moved")
        assertEquals(twin.accumulatedScaledMs, held.accumulatedScaledMs, "by exactly as much as its twin's")

        rig.engine.programmer.setBlind(false)
        rig.engine.processWallClockTick()
        assertEquals(rig.hexB(), rig.hexA(), "lands in step with its twin")
    }

    @Test
    fun `a held stateful effect is still computed, so its state does not stall`() {
        val rig = newRig()
        val recorder = CountingSlider()
        rig.engine.addFx(dimmerEffect("hex-a", FxEngine.PROGRAMMER_FX_PRIORITY_BASE, effect = recorder))
        rig.engine.programmer.setBlind(true)

        for (n in 0L..4L) rig.engine.processBeatTick(tick(n))

        assertEquals(5, recorder.calls, "computed every pass while held")
        assertEquals(0u.toUByte(), rig.hexA(), "and painted on none of them")
    }

    @Test
    fun `the rule — the band while blind, and nothing else`() {
        val band = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
        assertTrue(BlindHold.holds(blind = true, priority = band))
        assertTrue(BlindHold.holds(blind = true, priority = band + FxEngine.PROGRAMMER_FX_RANK_CLAMP))
        assertEquals(false, BlindHold.holds(blind = false, priority = band))
        assertEquals(false, BlindHold.holds(blind = true, priority = 1_000_001), "a cue's")
        assertEquals(false, BlindHold.holds(blind = true, priority = 0), "a manual or script effect")
    }

    private class CountingSlider : StatefulFx {
        var calls = 0
        override val name = "Counting Slider"
        override val outputType = FxOutputType.SLIDER
        override fun calculateStateful(tick: MasterClock.ClockTick, deltaMs: Long, context: FxContext): FxOutput {
            calls++
            return FxOutput.Slider(120u)
        }
    }
}
