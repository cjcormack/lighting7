package uk.me.cormack.lighting7.fx

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.Timing
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A speed change never jumps (fx-speed plan D6): an FX keeps its elapsed cycles across a change of
 * cycle, and a forever beat FX that lands on a power of two glides back onto its master's grid within
 * one cycle. The first test pins the rules the editor computes too to the shared vector,
 * `src/test/resources/fx/fxSpeed.fixture.json`, which `frontend/src/lib/fxSpeed.test.ts` reads (P2).
 */
class FxSpeedPhaseTest {

    @Serializable
    private data class Snap(val cycle: Double, val snapped: Boolean)

    @Serializable
    private data class Glide(val glideTicks: Long, val gridTicks: Long)

    @Serializable
    private data class GlideCase(val cycle: Double, val direction: String, val runs: Int? = null, val glide: Glide? = null)

    @Serializable
    private data class SpeedCase(val speed: Double, val cycle: Double, val ticks: Long, val runsAt: Double)

    @Serializable
    private data class TapCase(val times: List<Long>, val bpm: Double? = null)

    @Serializable
    private data class Vector(
        val snaps: List<Snap>,
        val glides: List<GlideCase>,
        val speeds: List<SpeedCase>,
        val taps: List<TapCase>,
    )

    private val vector: Vector = lenient.decodeFromString(
        checkNotNull(javaClass.getResource("/fx/fxSpeed.fixture.json")) { "the shared vector is missing" }.readText(),
    )

    private companion object {
        val lenient = Json { ignoreUnknownKeys = true }
    }

    // ─── The shared vector (P2) ──────────────────────────────────────────────────────────────

    @Test
    fun `the snaps, glides, whole ticks and taps match the shared vector`() {
        for (case in vector.snaps) {
            assertEquals(case.snapped, FxPhase.isSnappedCycle(case.cycle), "cycle ${case.cycle}")
        }
        for (case in vector.glides) {
            val dyn = FxDynamics(timing = Timing.CYCLE, direction = FxDirection.valueOf(case.direction), runs = case.runs)
            val grid = FxPhase.glideGridCycles(dyn.direction).takeIf { dyn.runs == null && FxPhase.isSnappedCycle(case.cycle) }
            val ticks = FxPhase.beatCycleTicks(case.cycle)
            val shift = CycleShift.across(tick = 1000, elapsed = 1000, from = null, fromTicksPerCycle = 70, toTicksPerCycle = ticks, gridCycles = grid)
            if (case.glide == null) {
                assertNull(grid, "cycle ${case.cycle} ${case.direction} does not glide")
            } else {
                val glide = assertNotNull(shift, "cycle ${case.cycle} glides")
                assertEquals(case.glide.glideTicks, glide.glideTicks, "cycle ${case.cycle}: one cycle")
                assertEquals(case.glide.gridTicks, glide.gridTicks, "cycle ${case.cycle} ${case.direction}: the grid")
            }
        }
        for (case in vector.speeds) {
            assertEquals(case.ticks, FxPhase.beatCycleTicks(case.cycle), "×${case.speed} runs whole ticks")
            val runsAt = 4.0 / (case.ticks.toDouble() / MasterClock.TICKS_PER_BEAT)
            assertTrue(abs(runsAt - case.runsAt) < 1e-9, "×${case.speed} runs at $runsAt, not ${case.runsAt}")
        }
        for (case in vector.taps) {
            val taps = TapTempo()
            var bpm: Double? = null
            for (time in case.times) bpm = taps.tap(time)
            if (case.bpm == null) assertNull(bpm, "taps ${case.times}")
            else assertTrue(abs(assertNotNull(bpm, "taps ${case.times}") - case.bpm) < 1e-9, "taps ${case.times}: $bpm")
        }
    }

    @Test
    fun `every whole number of ticks survives the editor's division and the desk's truncation, a step's too`() {
        for (n in 1L..49_152L) {
            assertEquals(n, FxPhase.beatCycleTicks(n.toDouble() / MasterClock.TICKS_PER_BEAT), "$n ticks")
        }
        // Under One cycle for: Each head the cycle is a step's, times the steps: 32/24 × 7 is 224 ticks.
        for (n in 1L..1_200L) for (steps in 2..16) {
            val cycle = n.toDouble() / MasterClock.TICKS_PER_BEAT
            assertEquals(n * steps, FxPhase.beatCycleTicks(cycle * steps), "$n ticks × $steps steps")
        }
        // A cycle off whole ticks still truncates: a third of a beat as 0.333 is 7 ticks.
        assertEquals(7, FxPhase.beatCycleTicks(0.333))
    }

    // ─── The phase (pure) ────────────────────────────────────────────────────────────────────

    private val forever = FxDynamics(timing = Timing.CYCLE)

    private fun phase(tick: Long, cycle: Double, shift: CycleShift?, dyn: FxDynamics = forever, anchor: Long = 0) =
        FxPhase.beatPhaseAt(tick, anchor, cycle, 1, dyn, 0.0, shift = shift)

    private fun ownCycles(tick: Long, cycle: Double, shift: CycleShift?, dyn: FxDynamics = forever, anchor: Long = 0) =
        FxPhase.beatOwnCyclesAt(tick, anchor, cycle, dyn, 0.0, shift)

    private fun change(tick: Long, from: Double, to: Double, shift: CycleShift? = null, dyn: FxDynamics = forever, anchor: Long = 0): CycleShift? {
        val grid = FxPhase.glideGridCycles(dyn.direction).takeIf { dyn.runs == null && FxPhase.isSnappedCycle(to) }
        val elapsed = if (dyn.runs == null) tick else tick - anchor
        return CycleShift.across(tick, elapsed, shift, FxPhase.beatCycleTicks(from), FxPhase.beatCycleTicks(to), grid)
    }

    @Test
    fun `a change of cycle keeps the elapsed cycles, whole and fraction`() {
        val tick = 1_234L
        val before = ownCycles(tick, 4.0, null)
        val shift = change(tick, 4.0, 3.0)
        assertTrue(abs(ownCycles(tick, 3.0, shift) - before) < 1e-9, "the cycles at the change")
        assertTrue(abs(phase(tick, 3.0, shift) - phase(tick, 4.0, null)) < 1e-9, "the phase at the change")
        // Off a power of two it stays where it is: no glide, the new cycle's own rate from there.
        assertEquals(CycleShift.NO_GLIDE, assertNotNull(shift).glideFromTick)
        assertTrue(abs(ownCycles(tick + 72, 3.0, shift) - (before + 1.0)) < 1e-9, "one 3-beat cycle later")
    }

    @Test
    fun `a shift of nothing runs on the integer clock, bit for bit`() {
        for (tick in listOf(0L, 1L, 95L, 96L, 1_000_003L)) {
            assertEquals(phase(tick, 4.0, null), phase(tick, 4.0, CycleShift(0.0)), "tick $tick")
        }
    }

    @Test
    fun `a forever FX landing on a power of two glides onto the grid within one cycle, never backwards`() {
        val tick = 1_234L
        val shift = change(tick, 3.0, 2.0)
        val glide = assertNotNull(shift)
        assertEquals(tick, glide.glideFromTick)
        assertEquals(48, glide.glideTicks)
        assertTrue(abs(phase(tick, 2.0, shift) - phase(tick, 3.0, null)) < 1e-9, "no jump at the change")
        var last = ownCycles(tick, 2.0, shift)
        for (t in tick + 1..tick + 48) {
            val now = ownCycles(t, 2.0, shift)
            assertTrue(now >= last - 1e-12, "tick $t runs backwards: $last → $now")
            last = now
        }
        for (t in tick + 48..tick + 200) {
            assertTrue(abs(phase(t, 2.0, shift) - phase(t, 2.0, null)) < 1e-9, "tick $t is on the grid")
        }
    }

    @Test
    fun `a Bounce glides onto a two-cycle grid, so its forward cycles start where an unmoved one's would`() {
        val dyn = FxDynamics(timing = Timing.CYCLE, direction = FxDirection.BOUNCE)
        val tick = 1_250L
        val shift = change(tick, 3.0, 4.0, dyn = dyn)
        assertEquals(192, assertNotNull(shift).gridTicks)
        val before = FxPhase.beatPhaseAt(tick, 0, 3.0, 1, dyn, 0.0)
        val after = FxPhase.beatPhaseAt(tick, 0, 4.0, 1, dyn, 0.0, shift = shift)
        assertTrue(abs(before - after) < 1e-9, "the direction and the phase are kept: $before → $after")
        for (t in tick + 96..tick + 400) {
            val unmoved = FxPhase.beatPhaseAt(t, 0, 4.0, 1, dyn, 0.0)
            assertTrue(abs(FxPhase.beatPhaseAt(t, 0, 4.0, 1, dyn, 0.0, shift = shift) - unmoved) < 1e-9, "tick $t")
        }
    }

    @Test
    fun `an FX with runs keeps its count, does not glide, and ends on it`() {
        val dyn = FxDynamics(timing = Timing.CYCLE, runs = 2)
        val anchor = 500L
        // Half way through its first 4-beat cycle, it doubles its speed.
        val tick = anchor + 48
        val shift = change(tick, 4.0, 2.0, dyn = dyn, anchor = anchor)
        assertEquals(CycleShift.NO_GLIDE, assertNotNull(shift).glideFromTick)
        assertTrue(abs(ownCycles(tick, 2.0, shift, dyn, anchor) - 0.5) < 1e-9)
        // One and a half 2-beat cycles to go: 72 ticks.
        assertTrue(!FxInstance.isHolding(phase(tick + 71, 2.0, shift, dyn, anchor)), "still running")
        assertTrue(FxInstance.isHolding(phase(tick + 72, 2.0, shift, dyn, anchor)), "spent on its count")
    }

    @Test
    fun `a change mid-glide starts from where the glide had reached`() {
        val first = change(1_000L, 3.0, 2.0)
        val tick = 1_020L
        val before = ownCycles(tick, 2.0, first)
        val second = change(tick, 2.0, 1.0, shift = first)
        assertTrue(abs(ownCycles(tick, 1.0, second) - before) < 1e-9)
    }

    // ─── Through the engine ──────────────────────────────────────────────────────────────────

    private val universe = Universe(0, 0)

    /** Paints its phase on the dimmer, 0–250. */
    private class PhaseSlider : Fx {
        override val name = "Phase Slider"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput =
            FxOutput.Slider((phase * 250).toInt().coerceIn(0, 255).toUByte())
    }

    private class Rig(val controller: MockDmxController, val engine: FxEngine, val bank: SpeedMasterBank)

    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex", "Hex", 1))
        }
        val programmer = ProgrammerStore()
        val bank = SpeedMasterBank()
        bank.master1().adoptDriven()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = bank,
            programmerStore = programmer,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmer),
        )
        return Rig(controller, engine, bank)
    }

    private fun Rig.at(n: Long) {
        bank.master1().driveTo(n, 1_000_000L + n * 20L)
        engine.processBeatTick(bank.master1().currentTick)
    }

    @Test
    fun `updateFx keeps a beat FX's phase across a speed change and settles it on the beat`() {
        val rig = newRig()
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 3.0), BlendMode.OVERRIDE)
        rig.engine.addFx(fx)
        rig.at(1_250)
        val before = rig.controller.currentValues[1]

        val updated = assertNotNull(rig.engine.updateFx(fx.id, newTiming = FxTiming(cycle = 2.0)))
        rig.at(1_250)
        assertEquals(before, rig.controller.currentValues[1], "no jump")
        assertNotNull(updated.cycleShift)

        rig.at(1_250 + 48 + 10)
        val onGrid = (MasterClock.phaseForDivision(1_250 + 48 + 10, 2.0) * 250).toInt().toUByte()
        assertEquals(onGrid, rig.controller.currentValues[1], "on the beat a cycle later")
    }

    @Test
    fun `a speed change on the wall clock rescales the accumulator, keeping the phase`() {
        val rig = newRig()
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
        fx.timingSource = TimingSource.WALL_CLOCK
        rig.engine.addFx(fx)
        fx.accumulatedScaledMs = 5_300.0
        val before = fx.calculateWallClockPhase()

        val updated = assertNotNull(rig.engine.updateFx(fx.id, newTiming = FxTiming(cycle = 1.0)))
        assertTrue(abs(updated.calculateWallClockPhase() - before) < 1e-9, "the phase is kept")
        assertTrue(abs(updated.accumulatedScaledMs - 5_300.0 / 4) < 1e-9, "the elapsed cycles are kept")
        assertNull(updated.cycleShift, "no grid to glide to")
    }

    @Test
    fun `an Own tempo FX keeps its elapsed cycles across a speed change`() {
        val rig = newRig()
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
        fx.applyTimingChoice(TimingSource.BEAT, 96.0, natural = true)
        rig.engine.addFx(fx)
        fx.accumulatedScaledMs = 7_100.0
        val before = floor(fx.accumulatedScaledMs / 4_000.0) + fx.calculateWallClockPhase()

        val updated = assertNotNull(rig.engine.updateFx(fx.id, newTiming = FxTiming(cycle = 2.0)))
        assertEquals(96.0, updated.ownBpm)
        val after = floor(updated.accumulatedScaledMs / 2_000.0) + updated.calculateWallClockPhase()
        assertTrue(abs(after - before) < 1e-9, "$before → $after")
    }

    @Test
    fun `a move between a master and Own tempo still restarts the loop, with no shift`() {
        val rig = newRig()
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
        rig.engine.addFx(fx)
        rig.at(1_000)
        val updated = assertNotNull(
            rig.engine.updateFx(fx.id, newTiming = FxTiming(cycle = 2.0), newClock = FxClockMove.OntoOwnTempo(120.0)),
        )
        assertNull(updated.cycleShift)
    }

    private fun Rig.phaseOf(fx: FxInstance) = fx.calculatePhase(bank.master1().currentTick)

    private fun Rig.spedUp(): FxInstance {
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
        fx.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
        engine.addFx(fx)
        at(1_250)
        return assertNotNull(engine.updateFx(fx.id, newTiming = FxTiming(cycle = 3.0)))
    }

    @Test
    fun `a split keeps each head's shift, so nothing jumps`() {
        val rig = newRig()
        val fx = rig.spedUp()
        val shift = assertNotNull(fx.cycleShift)
        val copies = rig.engine.headCopies(fx)
        assertEquals(1, copies.size)
        assertEquals(shift, copies[0].cycleShift)
    }

    @Test
    fun `undoing a speed change works the shift out for the restored cycle`() {
        val rig = newRig()
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
        fx.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
        rig.engine.addFx(fx)
        rig.at(1_250)
        val before = rig.engine.localFxSnapshot()
        val sped = assertNotNull(rig.engine.updateFx(fx.id, newTiming = FxTiming(cycle = 3.0)))
        rig.at(1_300)
        val running = rig.phaseOf(sped)

        rig.engine.restoreLocalFx(before, fadeMs = 0) { _, _ -> false }
        val restored = assertNotNull(rig.engine.getFx(fx.id))
        assertEquals(4.0, restored.timing.cycle)
        assertTrue(abs(rig.phaseOf(restored) - running) < 1e-9, "the undo does not jump")
        // A bar is a power of two: it glides back onto the grid a cycle later.
        rig.at(1_300 + 96)
        assertTrue(abs(rig.phaseOf(restored) - MasterClock.phaseForDivision(1_300 + 96, 4.0)) < 1e-9)
    }

    @Test
    fun `a move onto Own tempo and back lets the shift go, so it lands on the master's grid`() {
        val rig = newRig()
        val fx = rig.spedUp()
        assertNotNull(fx.cycleShift)
        val own = assertNotNull(rig.engine.updateFx(fx.id, newClock = FxClockMove.OntoOwnTempo(120.0)))
        assertNull(own.cycleShift)
        val back = assertNotNull(rig.engine.updateFx(fx.id, newClock = FxClockMove.OntoMaster))
        assertNull(back.cycleShift)
        assertTrue(abs(rig.phaseOf(back) - MasterClock.phaseForDivision(1_250, 3.0)) < 1e-9)
    }

    @Test
    fun `another master lets the shift go, which counts the old master's ticks`() {
        val rig = newRig()
        val other = java.util.UUID.randomUUID()
        rig.bank.load(
            listOf(
                SpeedMasterSnapshot(java.util.UUID.randomUUID(), 1, "M1", 120.0, SpeedMasterSource.MANUAL),
                SpeedMasterSnapshot(other, 2, "M2", 90.0, SpeedMasterSource.MANUAL),
            ),
        )
        rig.bank.master1().adoptDriven()
        val fx = rig.spedUp()
        assertNotNull(fx.cycleShift)
        val moved = assertNotNull(rig.engine.updateFx(fx.id, newSpeedMasterUuid = other))
        assertNull(moved.cycleShift)
    }

    @Test
    fun `a wall-clock FX onto Natural with a new cycle keeps its phase, on the same loop`() {
        val rig = newRig()
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 3.0), BlendMode.OVERRIDE)
        fx.timingSource = TimingSource.WALL_CLOCK
        fx.rateSpeedMasterUuid = java.util.UUID.randomUUID()
        rig.engine.addFx(fx)
        fx.accumulatedScaledMs = 4_700.0
        val before = fx.calculateWallClockPhase()
        val natural = assertNotNull(
            rig.engine.updateFx(fx.id, newTiming = FxTiming(cycle = 4.0), newClock = FxClockMove.OntoNatural),
        )
        assertTrue(natural.onNatural)
        assertTrue(abs(natural.calculateWallClockPhase() - before) < 1e-9)
    }

    @Test
    fun `a preview's copy carries the shift the stage takes, a runs change included`() {
        val rig = newRig()
        val fx = FxInstance(PhaseSlider(), SliderTarget("hex", "dimmer"), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
        fx.updateDynamics { it.copy(runs = 2) }
        rig.engine.addFx(fx)
        rig.at(1_250)
        rig.phaseOf(fx)
        val edit = FxEngine.FxEdit(newTiming = FxTiming(cycle = 2.0), clearRuns = true)
        val preview = rig.engine.editedCopy(fx, edit)
        val stage = assertNotNull(rig.engine.updateFx(fx.id, edit))
        assertEquals(stage.cycleShift, preview.cycleShift)
    }

    // ─── Runs at its own pace (D4) ───────────────────────────────────────────────────────────

    @Test
    fun `the two stateful flickers declare their own pace, and frontmatter reads it`() {
        for (path in listOf("/fx/dimmer/CandleFlicker.fx.kts", "/fx/dimmer/FluorescentFlicker.fx.kts")) {
            val (metadata, _) = FxFileLoader.parseFxFile(checkNotNull(javaClass.getResource(path)).readText())
            assertTrue(metadata.ownPace, "$path runs at its own pace")
        }
        val (wave, _) = FxFileLoader.parseFxFile(checkNotNull(javaClass.getResource("/fx/dimmer/Wave.fx.kts")).readText())
        assertTrue(!wave.ownPace, "a Wave reads its cycle")
    }
}
