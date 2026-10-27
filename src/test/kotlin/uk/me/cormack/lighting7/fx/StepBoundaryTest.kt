package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A step lands on its sample (FX editor follow-ups plan D3): a member whose phase is exactly a step
 * boundary, less its offset, takes the step it reaches rather than the one float rounding leaves it
 * on, so every row of a chase steps on the same samples, in every direction.
 */
class StepBoundaryTest {

    private val universe = Universe(0, 0)

    private val red = Triple<Byte, Byte, Byte>(-1, 0, 0)
    private val blue = Triple<Byte, Byte, Byte>(0, 0, -1)

    /** Colour Cycle red, green, blue at a hard cut over one Shehds' three zones, two one-bar cycles, each row's colour per sample. */
    private fun colourCycleRows(direction: FxDirection): Map<String, List<Triple<Byte, Byte, Byte>>> {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(ShehdsLed19RgbwFixture.Mode24Ch(universe, "sh", "Shehds", 1))
        }
        val store = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
        )
        val params = mapOf("colours" to "#ff0000,#00ff00,#0000ff", "between" to "CUT")
        val cycle = FxInstance(
            registry.createFx("ColourCycle", params),
            ColourTarget(FxTargetRef.fixture("sh")),
            FxTiming(cycle = CycleLength.ONE_BAR),
            BlendMode.OVERRIDE,
        ).apply {
            timingSource = registry.getRegistration("ColourCycle")!!.timingSource
            order = SpreadOrder.NORMAL
            elementMode = ElementMode.PER_FIXTURE
            updateDynamics { it.copy(direction = direction) }
        }
        val ticksPerCycle = (MasterClock.TICKS_PER_BEAT * CycleLength.ONE_BAR).toLong()
        val clock = FxSampleClock(ticks = { 10 * ticksPerCycle }, msPerTicks = { 20.0 }, rateScales = { 1.0 })
        val samples = engine.sampler { registry.createFx(it.fxTypeId, it.effect.parameters) }
            .sample(FxSampleRequest(listOf(cycle), FxSampleRequest.Include.ONLY, window = FxSampleWindow.Cycles(2.0)), clock)
        val rows = samples.attribute("rgbColour")!!.rows
        assertEquals(listOf("sh.zone-1", "sh.zone-2", "sh.zone-3"), rows.map { it.key })
        return rows.associate { row ->
            val series = row.result as FxSampleSeries.Colour
            row.key to (0 until 2 * PER_CYCLE).map { Triple(series.red[it], series.green[it], series.blue[it]) }
        }
    }

    private fun changesOf(colours: List<Triple<Byte, Byte, Byte>>) = (1 until colours.size).filter { colours[it] != colours[it - 1] }

    @Test
    fun `Colour Cycle at a hard cut steps every 16 samples on each of a Shehds' three zones`() {
        for ((key, colours) in colourCycleRows(FxDirection.FORWARD)) {
            assertEquals((1 until 6).map { it * PER_CYCLE / 3 }, changesOf(colours), "$key changes colour every ${PER_CYCLE / 3} samples")
        }
    }

    @Test
    fun `Colour Cycle backward steps every 16 samples, and starts each cycle on its last colour`() {
        for ((key, colours) in colourCycleRows(FxDirection.BACKWARD)) {
            assertEquals((1 until 6).map { it * PER_CYCLE / 3 }, changesOf(colours), "$key changes colour every ${PER_CYCLE / 3} samples")
        }
        assertEquals(blue, colourCycleRows(FxDirection.BACKWARD).getValue("sh.zone-1")[0], "a backward cycle starts on its last colour")
        assertEquals(blue, colourCycleRows(FxDirection.BACKWARD).getValue("sh.zone-1")[PER_CYCLE])
    }

    @Test
    fun `Colour Cycle under Bounce turns back without flashing a step`() {
        val zone = colourCycleRows(FxDirection.BOUNCE).getValue("sh.zone-1")
        assertEquals(listOf(16, 32, 64, 80), changesOf(zone), "the forward cycle ends on blue and the backward one starts on it")
        assertEquals(red, zone[0])
    }

    @Test
    fun `stepIndex at a boundary less a spread's offsets is the step it reaches`() {
        for (n in 2..8) {
            for (ticksPerCycle in listOf(24L, 48L, 96L, 384L)) {
                for (member in 0 until n) {
                    val offset = member.toDouble() / n
                    for (tick in 0 until ticksPerCycle) {
                        val basePhase = tick.toDouble() / ticksPerCycle
                        val phase = ((basePhase - offset) % 1.0 + 1.0) % 1.0
                        val exact = Math.floorMod(floor(tick.toDouble() * n / ticksPerCycle).toInt() - member, n)
                        assertEquals(exact, stepIndex(phase, n), "n=$n member=$member tick=$tick/$ticksPerCycle")
                        val backward = Math.floorMod(n - 1 - exact, n)
                        assertEquals(backward, stepIndex(FxDirection.BACKWARD.apply(phase, 0), n), "backward n=$n member=$member tick=$tick/$ticksPerCycle")
                    }
                }
            }
        }
    }

    @Test
    fun `stepIndex moves no step that is not at a boundary`() {
        for (n in 2..8) {
            for (k in 0 until n) {
                val boundary = k.toDouble() / n
                assertEquals(Math.floorMod(k - 1, n), stepIndex(((boundary - 1e-6) + 1.0) % 1.0, n), "just before $k/$n")
                assertEquals(k, stepIndex(boundary + 1e-6, n), "just after $k/$n")
            }
        }
        assertEquals(0, stepIndex(Math.nextDown(1.0), 3), "a phase a rounding error short of a whole cycle is the first step again")
        assertEquals(2, stepIndex(FxDirection.BACKWARD.apply(0.0, 0), 3), "backward, phase 0 is the end of the cycle")
        assertEquals(0, stepIndex(0.0, 1))
    }

    @Test
    fun `phaseBefore treats a phase rounded under its edge as on it`() {
        val rounded = 0.7 - 0.4
        assertTrue(rounded < 0.3, "the rounding this test is about")
        assertFalse(phaseBefore(rounded, 0.3))
        assertTrue(phaseBefore(0.3 - 1e-6, 0.3))
        assertFalse(phaseBefore(0.3 + 1e-6, 0.3))
    }

    @Test
    fun `an edge at the whole cycle is never crossed, in either direction`() {
        for (phase in listOf(0.0, 0.5, Math.nextDown(1.0))) {
            for (direction in FxDirection.entries) {
                val read = direction.apply(phase, 1)
                assertTrue(phaseBefore(read, 1.0), "$direction at $phase is short of an edge at 1")
                assertFalse(phaseBefore(read, 0.0), "$direction at $phase is past an edge at 0")
            }
        }
    }

    @Test
    fun `Pulse with no release stays lit across the wrap`() {
        val pulse = registry.createFx(
            "Wave",
            mapOf("shape" to "PULSE", "low" to "0", "high" to "200", "attack" to "0.3", "hold" to "0.7", "curve" to "QUAD_OUT"),
        )
        val atStart = (pulse.calculate(0.0, FxContext.SINGLE) as FxOutput.Slider).value
        for (phase in listOf(Math.nextDown(1.0), FxDirection.BACKWARD.apply(0.0, 0), 0.99)) {
            val value = (pulse.calculate(phase, FxContext.SINGLE) as FxOutput.Slider).value
            assertTrue(value == atStart || value == 200.toUByte(), "Pulse at $phase is $value, neither its start nor its hold")
        }
    }

    companion object {
        private const val PER_CYCLE = FxSampler.SAMPLES_PER_CYCLE

        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }
}
