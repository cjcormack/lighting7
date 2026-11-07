package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fx-speed plan P3: D5's rewrite moves no output. A beat FX on *Seconds* of `s` seconds ran on the
 * wall-clock loop unscaled, its `cycle` read as `s` seconds — an instance on `WALL_CLOCK` with no
 * master and no Own tempo, which is still what the engine runs for one. Its rewrite is Own tempo 120
 * at `2s` beats. Two engines run the two side by side, pass for pass on the ticker, over a grid of
 * cycles, phase offsets, directions and runs, and every channel is the same byte at every pass.
 */
class OwnTempoEquivalenceTest {

    private val universe = Universe(0, 0)
    private val hexes = (0 until 4).map { "hex-$it" }

    private fun rig(): Pair<MockDmxController, FxEngine> {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val heads = hexes.mapIndexed { i, key -> addFixture(HexFixture(universe, key, key, 1 + i * 12)) }
            createGroup<HexFixture>("hexes") { addSpread(heads) }
        }
        val store = ProgrammerStore()
        return controller to FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
        )
    }

    private data class Case(val seconds: Double, val phaseOffset: Double, val direction: FxDirection, val runs: Int?)

    private fun wave(cycle: Double, case: Case, configure: FxInstance.() -> Unit): FxInstance {
        val effect = registry.createFxWithPalettes(PaletteRegistry { null }, "Wave", mapOf("shape" to "SINE"))
        return FxInstance(effect, SliderTarget.forGroup("hexes", "dimmer"), FxTiming(cycle = cycle), BlendMode.OVERRIDE).apply {
            timingSource = TimingSource.BEAT
            order = SpreadOrder.NORMAL
            phaseOffset = case.phaseOffset
            updateDynamics { it.copy(direction = case.direction, runs = case.runs) }
            configure()
        }
    }

    @Test
    fun `a Seconds FX and its Own tempo rewrite send the same bytes on every pass`() {
        val cases = listOf(0.4, 1.5, 3.0, 7.25).flatMap { seconds ->
            listOf(0.0, 0.3).flatMap { offset ->
                FxDirection.entries.flatMap { direction ->
                    listOf(null, 2).map { runs -> Case(seconds, offset, direction, runs) }
                }
            }
        }
        for (case in cases) {
            val (beforeOut, before) = rig()
            val (afterOut, after) = rig()
            // Before: the effect D7's Seconds spawned — the wall-clock loop, no master, cycle in seconds.
            before.addFx(wave(case.seconds, case) { timingSource = TimingSource.WALL_CLOCK })
            val (bpm, beats) = secondsAsOwnTempo(case.seconds)
            after.addFx(wave(beats, case) { applyTimingChoice(TimingSource.BEAT, bpm) })

            val passes = ((case.seconds * (case.runs ?: 2) + 1.0) * 1000 / PASS_MS).toInt()
            var moved = false
            var last: Map<Int, UByte>? = null
            for (p in 0..passes) {
                val nowMs = T0 + p * PASS_MS
                before.processWallClockTick(nowMs)
                after.processWallClockTick(nowMs)
                val bytes = beforeOut.currentValues
                assertEquals(bytes, afterOut.currentValues, "$case at pass $p")
                if (last != null && bytes != last) moved = true
                last = bytes
            }
            assertTrue(moved, "$case: the output moves, so agreeing means something")
        }
    }

    @Test
    fun `an Own tempo other than 120 runs its cycle of beats in the seconds it says`() {
        // 96 BPM: one bar is 2.5 s.
        val (_, engine) = rig()
        val own = wave(CycleLength.ONE_BAR, Case(2.5, 0.0, FxDirection.FORWARD, null)) { applyTimingChoice(TimingSource.BEAT, 96.0) }
        engine.addFx(own)
        for (p in 0..(6000 / PASS_MS)) {
            engine.processWallClockTick(T0 + p * PASS_MS)
            val elapsedMs = (p * PASS_MS).toDouble()
            val expected = (elapsedMs / 2500.0) % 1.0
            val phase = own.calculateWallClockPhase()
            val apart = kotlin.math.abs(phase - expected).let { minOf(it, 1.0 - it) }
            assertTrue(apart < 1e-9, "at pass $p: phase $phase, expected $expected")
        }
    }

    companion object {
        private const val T0 = 1_000_000L
        private const val PASS_MS = 20L

        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }
}
