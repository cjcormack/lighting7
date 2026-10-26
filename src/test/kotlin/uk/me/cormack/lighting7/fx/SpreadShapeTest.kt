package uk.me.cormack.lighting7.fx

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.Timing
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spread's shape (effects plan session 2 — D4, §3.3 steps 2–5, P2, P6).
 *
 * The load-bearing pin is the first test: `src/test/resources/fx/spreadShape.fixture.json` is read
 * here and by the editor's `frontend/src/lib/spreadShape.test.ts`, so the dials the editor draws are the
 * offsets the desk runs. Change the rule, the vector and both pins in one commit.
 */
class SpreadShapeTest {

    @Serializable
    private data class Case(
        val name: String,
        val order: String,
        val heads: Int,
        val spread: Double = 1.0,
        val wings: Int = 1,
        val segments: Int = 1,
        val parts: Int = 0,
        val offsets: List<Double>,
        val slots: Int,
        val over: Over? = null,
    ) {
        val shape get() = SpreadShape(spread, wings, segments, parts)
    }

    /** One element mode over [fixtures] fixtures of [elementsEach] elements each. */
    @Serializable
    private data class Over(val mode: String, val fixtures: Int, val elementsEach: Int)

    @Serializable
    private data class Vector(val cases: List<Case>)

    private val vector: Vector = lenient.decodeFromString(
        checkNotNull(javaClass.getResource("/fx/spreadShape.fixture.json")) { "the shared vector is missing" }.readText(),
    )

    private companion object {
        val lenient = Json { ignoreUnknownKeys = true }
    }

    // ─── The rule (P2) ─────────────────────────────────────────────────────

    @Test
    fun `the plan matches the shared vector`() {
        assertTrue(vector.cases.size >= 20, "the vector covers the orders and each field")
        for (case in vector.cases) {
            val strategy = assertNotNull(SpreadOrder.byName(case.order), case.name)
            val plan = DistributionPlan.forElements(strategy, case.heads, case.shape)
            assertEquals(case.offsets.size, plan.offsets.size, case.name)
            for ((i, expected) in case.offsets.withIndex()) {
                assertTrue(
                    abs(plan.offsets[i] - expected) < 1e-9,
                    "${case.name}: head $i is at ${plan.offsets[i]}, not $expected",
                )
                assertEquals(case.slots, plan.contexts[i].numDistinctSlots, "${case.name}: head $i's slots")
            }
            assertEquals(case.slots, plan.slots, "${case.name}: slots")
            // The per-pass slot count the step timing and ping-pong read agrees with the plan's.
            val dyn = FxDynamics(
                timing = Timing.CYCLE, order = strategy,
                spread = case.spread, wings = case.wings, segments = case.segments, parts = case.parts,
            )
            assertEquals(case.slots, dyn.distinctSlots(case.heads), "${case.name}: FxDynamics.distinctSlots")
        }
    }

    @Test
    fun `under each element mode every element takes its unit's offset`() {
        val cases = vector.cases.filter { it.over != null }
        assertTrue(cases.map { it.over!!.mode }.toSet() == ElementMode.entries.map { it.name }.toSet(), "every mode has a case")
        for (case in cases) {
            val over = case.over!!
            val strategy = assertNotNull(SpreadOrder.byName(case.order), case.name)
            val keys = List(over.fixtures) { f -> List(over.elementsEach) { e -> "f$f.e$e" } }
            val expansion = FxTargetExpansion(
                structureVersion = 0, elementFilter = ElementFilter.ALL, kind = FxTargetExpansion.Kind.GROUP_ELEMENTS,
                coverageKeys = keys.flatten(), flat = keys.flatten(), perFixture = keys, members = emptyList(),
            )
            val plans = FxDistributionPlans.build(expansion, strategy, case.shape)
            for (f in 0 until over.fixtures) {
                for (e in 0 until over.elementsEach) {
                    val (units, offset, expected) = when (ElementMode.valueOf(over.mode)) {
                        ElementMode.WHOLE -> Triple(expansion.whole.size, plans.whole.offsets[f], case.offsets[f])
                        ElementMode.FLAT -> {
                            val i = f * over.elementsEach + e
                            Triple(expansion.flat.size, plans.flat.offsets[i], case.offsets[i])
                        }
                        ElementMode.PER_FIXTURE -> Triple(expansion.perFixture[f].size, plans.perFixture[f].offsets[e], case.offsets[e])
                    }
                    assertEquals(case.heads, units, "${case.name}: the units the spread counts")
                    assertTrue(abs(offset - expected) < 1e-9, "${case.name}: fixture $f element $e is at $offset, not $expected")
                }
            }
        }
    }

    @Test
    fun `the offset ladder's last rung is the plan's offsets before Spread`() {
        for (case in vector.cases) {
            val strategy = assertNotNull(SpreadOrder.byName(case.order), case.name)
            val ladder = DistributionPlan.elementLadder(strategy, case.heads, case.shape)
            val plan = DistributionPlan.forElements(strategy, case.heads, case.shape)
            val parts = SpreadShaping.Step.PARTS.ordinal
            assertEquals(case.slots, ladder.counts[parts], "${case.name}: the last rung's count is the slots")
            if (case.shape.isDefault) continue
            for (i in 0 until case.heads) {
                val offset = case.spread * ladder.ranks[parts][i] / ladder.counts[parts]
                assertTrue(abs(plan.offsets[i] - offset) < 1e-9, "${case.name}: head $i's ladder gives $offset, not ${plan.offsets[i]}")
            }
        }
    }

    @Test
    fun `the default shape leaves every order's offsets exactly as they were`() {
        // P6: an effect that names no shape runs bit for bit as before session 2.
        val strategies = SpreadOrder.availableOrders.map { SpreadOrder.fromName(it) }
        for (strategy in strategies) {
            for (n in 1..20) {
                val members = List(n) { i -> Member(i, if (n > 1) i.toDouble() / (n - 1) else 0.5) }
                val plan = DistributionPlan.of(strategy, members, SpreadShape.DEFAULT)
                assertContentEquals(strategy.offsets(members, n), plan.offsets, "$strategy over $n")
                assertEquals(strategy.distinctSlots(n), plan.slots, "$strategy over $n")
                assertEquals(
                    strategy.distinctSlots(n),
                    FxDynamics(timing = Timing.CYCLE, order = strategy).distinctSlots(n),
                    "$strategy over $n",
                )
            }
        }
    }

    @Test
    fun `every order's ranks give back its offsets over its slots`() {
        // Step 1: the rank the shaping starts from is the Order's own, so Spread 100 % is a no-op too.
        val strategies = SpreadOrder.availableOrders.map { SpreadOrder.fromName(it) }
        for (strategy in strategies) {
            for (n in 1..20) {
                val members = List(n) { i -> Member(i, if (n > 1) i.toDouble() / (n - 1) else 0.5) }
                val plain = DistributionPlan.of(strategy, members)
                val viaRanks = DistributionPlan.of(strategy, members, SpreadShape(parts = 64))
                for (i in 0 until n) {
                    assertTrue(abs(plain.offsets[i] - viaRanks.offsets[i]) < 1e-12, "$strategy over $n, member $i")
                }
            }
        }
    }

    @Test
    fun `blocks of 3 over 12 heads give 4 slots`() {
        val plan = DistributionPlan.forElements(SpreadOrder.NORMAL, 12, SpreadShape(segments = 3))
        assertEquals(4, plan.slots)
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75), plan.offsets.distinct())
        val dyn = FxDynamics(timing = Timing.HEAD, segments = 3)
        assertEquals(4, dyn.distinctSlots(12))
    }

    @Test
    fun `wings under a mirrored order are ignored by the plan and refused at the write boundary`() {
        for (order in listOf(SpreadOrder.CENTRE_OUT, SpreadOrder.CENTRE_IN)) {
            val winged = DistributionPlan.forElements(order, 8, SpreadShape(wings = 2))
            val plain = DistributionPlan.forElements(order, 8)
            assertContentEquals(plain.offsets, winged.offsets, "$order: a stored winged row runs as if unwinged")
            val problem = FxSpecCoercion.Strict.spreadProblem(null, 2, null, null, order)
            assertNotNull(problem, "$order refuses wings")
            assertTrue("two wings already" in problem, problem)
        }
        // The REST/MCP/Look boundary's one call names the order by its stored string.
        assertNotNull(FxSpecCoercion.Strict.problem(order = "CENTRE_OUT", wings = 2))
        assertNull(FxSpecCoercion.Strict.problem(order = "CENTRE_OUT", wings = 1, segments = 2))
        assertNull(FxSpecCoercion.Strict.problem(order = "NORMAL", wings = 2))
        // No order is NORMAL, which takes wings.
        assertNull(FxSpecCoercion.Strict.problem(wings = 2))
    }

    @Test
    fun `each field is refused outside its range`() {
        val linear = SpreadOrder.NORMAL
        assertNotNull(FxSpecCoercion.Strict.spreadProblem(-0.1, null, null, null, linear))
        assertNotNull(FxSpecCoercion.Strict.spreadProblem(2.01, null, null, null, linear))
        assertNotNull(FxSpecCoercion.Strict.spreadProblem(Double.NaN, null, null, null, linear))
        assertNull(FxSpecCoercion.Strict.spreadProblem(2.0, null, null, null, linear))
        assertNull(FxSpecCoercion.Strict.spreadProblem(0.0, null, null, null, linear))
        assertNotNull(FxSpecCoercion.Strict.spreadProblem(null, 0, null, null, linear))
        assertNotNull(FxSpecCoercion.Strict.spreadProblem(null, null, 0, null, linear))
        assertNotNull(FxSpecCoercion.Strict.spreadProblem(null, null, null, -1, linear))
        assertNotNull(FxSpecCoercion.Strict.spreadProblem(null, null, SpreadShape.MAX_COUNT + 1, null, linear))
        assertNull(FxSpecCoercion.Strict.spreadProblem(null, null, null, 0, linear), "groups 0 is off")
    }

    @Test
    fun `a stored shape out of range is clamped on spawn, not refused`() {
        val clamped = FxSpecCoercion.Lenient.spreadShape(3.0, 0, -2, -1) { "test row" }
        assertEquals(SpreadShape(spread = 2.0, wings = 1, segments = 1, parts = 0), clamped)
        val nan = FxSpecCoercion.Lenient.spreadShape(Double.NaN, 1, 1, 0) { "test row" }
        assertEquals(1.0, nan.spread)
    }

    // ─── Running it ────────────────────────────────────────────────────────

    private val universe = Universe(0, 0)

    private class Rig(val controller: MockDmxController, val engine: FxEngine, val programmer: ProgrammerStore)

    /** Four hexes (dimmers on channels 1, 13, 25 and 37) in a group `quad`, in that order. */
    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val hexes = (0 until 4).map { addFixture(HexFixture(universe, "hex-$it", "Hex $it", 1 + 12 * it)) }
            createGroup<HexFixture>("quad") { addSpread(hexes) }
        }
        val programmer = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmer,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmer),
        )
        return Rig(controller, engine, programmer)
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

    /**
     * A step chase, windowed as `fx-engineering.md` §"Step-Chase Windowing" describes: a member is lit
     * while its own phase is inside the first `1 / numDistinctSlots` of the cycle.
     */
    private class StepChase : Fx {
        override val name = "Step Chase"
        override val outputType = FxOutputType.SLIDER
        override val defaultTiming = Timing.HEAD
        override fun calculate(phase: Double, context: FxContext): FxOutput =
            FxOutput.Slider(if (phase < 1.0 / context.numDistinctSlots) 255u else 0u)
    }

    /** A constant level, whatever the phase. */
    private class Level(private val value: UByte) : Fx {
        override val name = "Level"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput = FxOutput.Slider(value)
    }

    /** 40 under every head, from the programmer — what an N-shot member hands back to. */
    private fun Rig.underneath() {
        for (i in 0 until 4) {
            programmer.put(ProgrammerOwner.WEB, "hex-$i", "dimmer", CueAssignmentResolver.AttributeValue.Slider(40u))
        }
    }

    private fun Rig.levels(n: Long): List<Int> {
        engine.processBeatTick(tick(n))
        return listOf(1, 13, 25, 37).map { controller.currentValues.getValue(it).toInt() }
    }

    @Test
    fun `a step chase with groups of 2 has no gap`() {
        val rig = newRig()
        val effect = FxInstance(StepChase(), SliderTarget.forGroup("quad", "dimmer"), FxTiming(CycleLength.ONE_BEAT))
        effect.updateDynamics { it.copy(timing = Timing.HEAD, parts = 2) }
        rig.engine.addFx(effect)

        // Two slots, so a step is one beat and the cycle two: the odd heads and the even heads take
        // turns, and at no tick is the group dark. Under the unshaped four slots the window was a
        // quarter of a four-beat cycle, leaving half of every cycle unlit.
        val ticksPerStep = MasterClock.TICKS_PER_BEAT.toLong()
        for (n in 0 until ticksPerStep * 4) {
            val lit = rig.levels(n).map { it == 255 }
            assertTrue(lit.any { it }, "tick $n: the chase has a gap — $lit")
            assertEquals(lit[0], lit[2], "tick $n: heads 0 and 2 are one group step")
            assertEquals(lit[1], lit[3], "tick $n: heads 1 and 3 are one group step")
            val evens = (n / ticksPerStep) % 2 == 0L
            assertEquals(listOf(evens, !evens, evens, !evens), lit, "tick $n")
        }
    }

    @Test
    fun `a 2-shot under Spread 50 percent ends when its last member's own clock reaches two`() {
        val rig = newRig()
        rig.underneath()
        val effect = FxInstance(Level(200u), SliderTarget.forGroup("quad", "dimmer"), FxTiming(CycleLength.ONE_BEAT))
        effect.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
        effect.updateDynamics { it.copy(runs = 2, spread = 0.5) }
        rig.engine.addFx(effect)

        // One beat (24 ticks) a cycle, started at tick 100. Spread 50 % puts the four heads at offsets
        // 0, 1/8, 2/8 and 3/8 — 0, 3, 6 and 9 ticks — each running its own two cycles from there.
        assertEquals(listOf(200, 40, 40, 40), rig.levels(100), "each later head waits for its offset")
        assertEquals(listOf(200, 200, 200, 200), rig.levels(109), "the last head's clock reaches 0")
        assertEquals(listOf(200, 200, 200, 200), rig.levels(147), "head 0's last tick")
        assertEquals(listOf(40, 200, 200, 200), rig.levels(148), "head 0 hands back after its own two")
        assertEquals(listOf(40, 40, 200, 200), rig.levels(151), "head 1 after its own")
        assertEquals(listOf(40, 40, 40, 200), rig.levels(154), "head 2 after its own")
        assertNotNull(rig.engine.getFx(effect.id), "running while the last head has runs left")
        assertEquals(listOf(40, 40, 40, 200), rig.levels(156), "the last head's last tick")
        assertEquals(listOf(40, 40, 40, 40), rig.levels(157), "the last head hands back")
        assertNull(rig.engine.getFx(effect.id), "two cycles past the largest shaped offset, the effect is gone")
    }

    @Test
    fun `a 2-shot under Groups of 2 ends two cycles past its group's largest offset`() {
        val rig = newRig()
        rig.underneath()
        val effect = FxInstance(Level(200u), SliderTarget.forGroup("quad", "dimmer"), FxTiming(CycleLength.ONE_BEAT))
        effect.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
        effect.updateDynamics { it.copy(runs = 2, parts = 2) }
        rig.engine.addFx(effect)

        // Groups of 2 put the heads at 0, ½, 0, ½ — never ¾, the unshaped last head's offset.
        assertEquals(listOf(200, 40, 200, 40), rig.levels(100), "the odd heads wait half a cycle")
        assertEquals(listOf(200, 200, 200, 200), rig.levels(112), "half a cycle in, all four run")
        assertEquals(listOf(40, 200, 40, 200), rig.levels(148), "the even heads hand back after their own two")
        assertNotNull(rig.engine.getFx(effect.id))
        assertEquals(listOf(40, 40, 40, 40), rig.levels(160), "the odd heads after theirs")
        assertNull(rig.engine.getFx(effect.id), "two cycles past ½, not past ¾, the effect is gone")
    }

    @Test
    fun `an edit to the shape is in place, phase kept, and re-plans the offsets`() {
        val rig = newRig()
        val effect = FxInstance(Level(200u), SliderTarget.forGroup("quad", "dimmer"), FxTiming(CycleLength.ONE_BEAT))
        rig.engine.addFx(effect)
        rig.engine.processBeatTick(tick(10))
        val id = effect.id

        val updated = assertNotNull(rig.engine.updateFx(id, newSpread = 0.5, newParts = 2))
        assertEquals(id, updated.id, "the id is kept")
        assertTrue(updated === effect, "a dynamics-only edit keeps the instance, and so its phase")
        assertEquals(0.5, updated.dynamics.spread)
        assertEquals(2, updated.dynamics.parts)
        assertEquals(1, updated.dynamics.wings, "a field not sent is kept")

        rig.engine.processBeatTick(tick(11))
        val plans = assertNotNull(updated.distributionPlans, "the next pass re-planned")
        assertEquals(SpreadShape(spread = 0.5, parts = 2), plans.shape)
        assertContentEquals(doubleArrayOf(0.0, 0.25, 0.0, 0.25), plans.members.offsets)
    }

    @Test
    fun `ping-pong sweeps the same way whatever the shape, which sets only the offsets`() {
        // The sweep is the Order's own (N − 1)/N: Spread 0 does not freeze it, a little Spread does
        // not nearly freeze it, and Blocks folding every head to one slot does not either.
        val effect = FxInstance(Level(200u), SliderTarget.forGroup("quad", "dimmer"), FxTiming(CycleLength.ONE_BEAT))
        val base = FxDynamics(timing = Timing.CYCLE, order = SpreadOrder.PING_PONG)
        val shapes = listOf(
            base.copy(spread = 0.0),
            base.copy(spread = 0.01),
            base.copy(spread = 0.5),
            base.copy(segments = 8),
            base.copy(parts = 2, wings = 2),
        )
        for (n in 0L until 24L) {
            val expected = effect.calculatePhaseForMember(tick(n), 8, base, 0.0)
            for (dyn in shapes) {
                assertEquals(expected, effect.calculatePhaseForMember(tick(n), 8, dyn, 0.0), "tick $n under $dyn")
            }
        }
        // And the sweep itself moves: halfway through the cycle (tick 3 of a quarter beat's 6) it is
        // at its turnaround, far from where it started.
        assertTrue(
            abs(effect.calculatePhaseForMember(tick(0), 8, base, 0.0) - effect.calculatePhaseForMember(tick(3), 8, base, 0.0)) > 0.1,
        )
    }

    /** A member at an index and position, as a group or element list gives one. */
    private class Member(override val index: Int, override val normalizedPosition: Double) : DistributionMemberInfo
}
