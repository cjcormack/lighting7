package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Each head later by a step (fx-settings plan D6) and Width one head (D8), run through the sampler,
 * which composes as the tick pass does.
 */
class SpreadUnitStepTest {

    private val universe = Universe(0, 0)

    private class Rig(val engine: FxEngine)

    /** [heads] hexes in a group `row`, in that order. */
    private fun rig(heads: Int): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val hexes = (0 until heads).map { addFixture(HexFixture(universe, "hex-$it", "Hex $it", 1 + 12 * it)) }
            createGroup<HexFixture>("row") { addSpread(hexes) }
        }
        val store = ProgrammerStore()
        return Rig(
            FxEngine(
                fixtures = fixtures,
                speedMasters = SpeedMasterBank(),
                programmerStore = store,
                layerResolver = LayerResolver(CueAssignmentResolver(), store),
            ),
        )
    }

    private fun FxInstance.on(order: SpreadOrder, dyn: (FxDynamics) -> FxDynamics): FxInstance = apply {
        timingSource = registry.getRegistration(fxTypeId)!!.timingSource
        this.order = order
        updateDynamics(dyn)
    }

    private val clock = run {
        val ticksPerCycle = (MasterClock.TICKS_PER_BEAT * CycleLength.ONE_BAR).toLong()
        FxSampleClock(ticks = { 10 * ticksPerCycle }, msPerTicks = { 20.0 }, rateScales = { 1.0 })
    }

    private fun sample(engine: FxEngine, fx: FxInstance, attribute: String) =
        engine.sampler { registry.createFx(it.fxTypeId, it.effect.parameters) }
            .sample(FxSampleRequest(listOf(fx), FxSampleRequest.Include.ONLY, window = FxSampleWindow.Cycles(2.0)), clock)
            .attribute(attribute)!!.rows

    /** Colour Cycle red, green, blue at a cut over [heads] hexes: each head's colour index per sample. */
    private fun colourCycle(heads: Int, unit: SpreadUnit): List<List<Int>> {
        val engine = rig(heads).engine
        val fx = FxInstance(
            registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#00ff00,#0000ff", "between" to "CUT")),
            ColourTarget(FxTargetRef.group("row")),
            FxTiming(cycle = CycleLength.ONE_BAR),
        ).on(SpreadOrder.NORMAL) { it.copy(spread = 1.0, spreadUnit = unit) }
        return sample(engine, fx, "rgbColour").map { row ->
            val series = row.result as FxSampleSeries.Colour
            (0 until 2 * PER_CYCLE).map { i ->
                when {
                    series.red[i] != 0.toByte() -> 0
                    series.green[i] != 0.toByte() -> 1
                    else -> 2
                }
            }
        }
    }

    private fun changesOf(steps: List<Int>) = (1 until steps.size).filter { steps[it] != steps[it - 1] }

    @Test
    fun `four heads, three colours, one step each, change colour together, each one behind`() {
        val rows = colourCycle(4, SpreadUnit.STEP)
        val boundaries = (1 until 6).map { it * PER_CYCLE / 3 }
        for ((head, steps) in rows.withIndex()) {
            assertEquals(boundaries, changesOf(steps), "head $head changes colour on every step")
            assertEquals(Math.floorMod(-head, 3), steps[0], "head $head starts $head colours behind")
        }
        // The case that started the plan: at 100 % of the cycle the edges stagger.
        assertNotEquals(boundaries, changesOf(colourCycle(4, SpreadUnit.CYCLE)[1]))
    }

    @Test
    fun `a fifth head keeps the chase on its steps`() {
        val rows = colourCycle(5, SpreadUnit.STEP)
        assertEquals(5, rows.size)
        for ((head, steps) in rows.withIndex()) {
            assertEquals((1 until 6).map { it * PER_CYCLE / 3 }, changesOf(steps), "head $head")
            assertEquals(Math.floorMod(-head, 3), steps[0], "head $head")
        }
    }

    @Test
    fun `a swap to more colours re-plans the offsets in steps`() {
        val engine = rig(4).engine
        val fx = FxInstance(
            registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#00ff00,#0000ff")),
            ColourTarget(FxTargetRef.group("row")),
            FxTiming(cycle = CycleLength.ONE_BAR),
        ).on(SpreadOrder.NORMAL) { it.copy(spreadUnit = SpreadUnit.STEP) }
        engine.addFx(fx)
        assertEquals(listOf(0.0, 1.0 / 3, 2.0 / 3, 1.0), engine.paintedHeads(fx).map { it.offset }, "three colours")
        val four = registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#00ff00,#0000ff,#ffffff"))
        val edited = assertNotNull(engine.updateFx(fx.id, newFx = four))
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75), engine.paintedHeads(edited).map { it.offset }, "four colours")
    }

    @Test
    fun `Width one head lights one head at a time over 3, 4 and 8 heads`() {
        for (heads in listOf(3, 4, 8)) {
            for (shape in listOf("SQUARE", "PULSE")) {
                val engine = rig(heads).engine
                val fx = FxInstance(
                    registry.createFx(
                        "Wave",
                        mapOf("shape" to shape, "widthUnit" to "HEAD", "low" to "0", "high" to "255", "attack" to "0", "hold" to "1"),
                    ),
                    SliderTarget(FxTargetRef.group("row"), "dimmer"),
                    FxTiming(cycle = CycleLength.ONE_BAR),
                ).on(SpreadOrder.NORMAL) { it }
                val rows = sample(engine, fx, "dimmer").map { (it.result as FxSampleSeries.Bytes).values }
                assertEquals(heads, rows.size)
                for (i in 0 until 2 * PER_CYCLE) {
                    val lit = rows.count { it[i] != 0.toByte() }
                    assertEquals(1, lit, "$shape over $heads heads, sample $i: $lit lit")
                }
            }
        }
    }

    @Test
    fun `Width as a share of the cycle is unchanged`() {
        val square = registry.createFx("Wave", mapOf("shape" to "SQUARE", "width" to "0.5"))
        val context = FxContext(groupSize = 4, memberIndex = 0, numDistinctSlots = 4)
        assertEquals(255.toUByte(), (square.calculate(0.4, context) as FxOutput.Slider).value)
        assertEquals(0.toUByte(), (square.calculate(0.6, context) as FxOutput.Slider).value)
    }

    @Test
    fun `an FX declares its steps from the list it names, and only the step FX declare any`() {
        assertEquals(3, registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#00ff00,#0000ff")).stepCount)
        assertEquals(2, registry.createFx("ColourCycle", mapOf("colours" to "#ff0000, ,#0000ff")).stepCount, "a blank is no step")
        assertEquals(3, registry.createFx("SlotSteps", mapOf("slots" to "GOBO_1,GOBO_2,GOBO_3")).stepCount)
        assertEquals(null, registry.createFx("Wave", emptyMap()).stepCount)
        val stepping = registry.getLibrary().filter { it.stepsFrom != null }.associate { it.name to it.stepsFrom }
        assertEquals(mapOf("ColourCycle" to "colours", "PaletteSteps" to "palettes", "SlotSteps" to "slots"), stepping)
    }

    companion object {
        private const val PER_CYCLE = FxSampler.SAMPLES_PER_CYCLE

        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }
}
