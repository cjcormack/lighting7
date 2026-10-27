package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Fixtures, the third element mode (FX editor follow-ups plan D7): over four three-zone Shehds, each
 * fixture is one unit, the spread counts fixtures, and every zone of a fixture takes its output.
 * Elements and Each fixture are as they were.
 */
class ElementModeWholeTest {

    private val universe = Universe(0, 0)
    private val shehds = (0 until 4).map { "sh-$it" }
    private fun zonesOf(key: String) = (1..3).map { "$key.zone-$it" }

    private class Rig(val controller: MockDmxController, val engine: FxEngine)

    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val units = shehds.mapIndexed { i, key -> addFixture(ShehdsLed19RgbwFixture.Mode24Ch(universe, key, key, 1 + i * 24)) }
            createGroup<ShehdsLed19RgbwFixture.Mode24Ch>("shehds") { addSpread(units) }
        }
        val store = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
        )
        return Rig(controller, engine)
    }

    /** Paints its phase as red, never 0. */
    private class PhaseRed : Fx {
        override val name = "Phase Red"
        override val outputType = FxOutputType.COLOUR
        override fun calculate(phase: Double, context: FxContext): FxOutput =
            FxOutput.Colour(ExtendedColour(java.awt.Color(10 + (phase * 200).toInt(), 0, 0)))
    }

    private fun colourOver(ref: FxTargetRef, mode: ElementMode, filter: ElementFilter = ElementFilter.ALL) =
        FxInstance(PhaseRed(), ColourTarget(ref), FxTiming(cycle = 4.0), BlendMode.OVERRIDE).apply {
            order = SpreadOrder.NORMAL
            elementMode = mode
            elementFilter = filter
        }

    private fun groupFx(mode: ElementMode, filter: ElementFilter = ElementFilter.ALL) =
        colourOver(FxTargetRef.group("shehds"), mode, filter)

    /** A zone's red is the first of its four channels, which start eight into the fixture. */
    private fun Rig.redOf(zoneKey: String): Int {
        val fixture = shehds.indexOf(zoneKey.substringBefore('.'))
        val zone = zoneKey.substringAfterLast('-').toInt() - 1
        return controller.getValue(1 + fixture * 24 + 8 + zone * 4).toInt()
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(n, n / ticksPerBeat, tickInBeat, tickInBeat.toDouble() / ticksPerBeat, 1_000_000L + n * 20L)
    }

    @Test
    fun `under Fixtures the spread counts the four fixtures and each fixture's zones share its place`() {
        val rig = newRig()
        val effect = groupFx(ElementMode.WHOLE)
        rig.engine.addFx(effect)

        val heads = rig.engine.paintedHeads(effect)
        assertEquals(shehds.flatMap(::zonesOf), heads.map { it.key })
        assertEquals(List(12) { 4 }, heads.map { it.groupSize })
        assertEquals(shehds.indices.flatMap { i -> List(3) { i / 4.0 } }, heads.map { it.offset })
    }

    @Test
    fun `Elements and Each fixture spread as they did`() {
        val rig = newRig()
        val flat = groupFx(ElementMode.FLAT)
        val perFixture = groupFx(ElementMode.PER_FIXTURE)
        rig.engine.addFx(flat)
        rig.engine.addFx(perFixture)

        assertEquals((0 until 12).map { it / 12.0 }, rig.engine.paintedHeads(flat).map { it.offset })
        assertEquals(List(12) { 12 }, rig.engine.paintedHeads(flat).map { it.groupSize })
        assertEquals((0 until 12).map { (it % 3) / 3.0 }, rig.engine.paintedHeads(perFixture).map { it.offset })
        assertEquals(List(12) { 3 }, rig.engine.paintedHeads(perFixture).map { it.groupSize })
    }

    @Test
    fun `a tick under Fixtures paints every zone of a fixture alike and the fixtures apart`() {
        val rig = newRig()
        rig.engine.addFx(groupFx(ElementMode.WHOLE))
        rig.engine.processBeatTick(tick(5))

        val perFixture = shehds.map { key -> zonesOf(key).map { rig.redOf(it) } }
        for ((i, reds) in perFixture.withIndex()) assertEquals(1, reds.toSet().size, "${shehds[i]}'s zones: $reds")
        assertEquals(4, perFixture.map { it.first() }.toSet().size, "every fixture its own phase: $perFixture")
    }

    /** Paints how many times it has been stepped as red, so every extra step shows, at a fixed green. */
    private class StepCounter(private val green: Int = 0) : StatefulFx {
        override val name = "Step Counter"
        override val outputType = FxOutputType.COLOUR
        private var steps = 0
        override fun calculate(phase: Double, context: FxContext): FxOutput = FxOutput.Colour(ExtendedColour(java.awt.Color.BLACK))
        override fun calculateStateful(tick: MasterClock.ClockTick, deltaMs: Long, context: FxContext): FxOutput {
            steps = (steps + 1) % 250
            return FxOutput.Colour(ExtendedColour(java.awt.Color(steps, green, 0)))
        }
    }

    /** A zone's green, one channel after its red. */
    private fun Rig.greenOf(zoneKey: String): Int {
        val fixture = shehds.indexOf(zoneKey.substringBefore('.'))
        val zone = zoneKey.substringAfterLast('-').toInt() - 1
        return controller.getValue(2 + fixture * 24 + 8 + zone * 4).toInt()
    }

    @Test
    fun `an edit naming some of a fixture's split copies lands on those alone`() {
        val rig = newRig()
        val effect = FxInstance(StepCounter(), ColourTarget(FxTargetRef.group("shehds")), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
            .apply { elementMode = ElementMode.WHOLE }
        rig.engine.addFx(effect)
        val copies = rig.engine.headCopies(effect)
        rig.engine.removeFx(effect.id)
        copies.forEach { rig.engine.addFx(it) }
        // Zone 2 alone, then zone 1 alone: neither reaches the zones it did not name.
        for (zone in listOf("sh-0.zone-2", "sh-1.zone-1")) {
            val copy = copies.single { it.target.targetKey == zone }
            rig.engine.updateFx(copy.id, FxEngine.FxEdit(newFx = StepCounter(green = 200)))
        }

        rig.engine.processBeatTick(tick(5))
        assertEquals(listOf(0, 200, 0), zonesOf("sh-0").map { rig.greenOf(it) })
        assertEquals(listOf(200, 0, 0), zonesOf("sh-1").map { rig.greenOf(it) })
        assertEquals(1, listOf("sh-0.zone-1", "sh-0.zone-3").map { rig.redOf(it) }.toSet().size, "the unedited zones still step together")
    }

    @Test
    fun `a stateful FX under Fixtures split into one copy per zone keeps each fixture's zones alike`() {
        val rig = newRig()
        val effect = FxInstance(StepCounter(), ColourTarget(FxTargetRef.group("shehds")), FxTiming(cycle = 4.0), BlendMode.OVERRIDE)
            .apply { elementMode = ElementMode.WHOLE }
        rig.engine.addFx(effect)
        val copies = rig.engine.headCopies(effect)
        assertEquals(12, copies.size)
        rig.engine.removeFx(effect.id)
        copies.forEach { rig.engine.addFx(it) }

        for (n in 5L..7L) {
            rig.engine.processBeatTick(tick(n))
            for (key in shehds) assertEquals(1, zonesOf(key).map { rig.redOf(it) }.toSet().size, "$key's zones at tick $n")
        }
    }

    @Test
    fun `a lone Shehds under Fixtures is one unit, under Elements its zones chase`() {
        val whole = newRig()
        whole.engine.addFx(colourOver(FxTargetRef.fixture("sh-0"), ElementMode.WHOLE))
        whole.engine.processBeatTick(tick(5))
        assertEquals(1, zonesOf("sh-0").map { whole.redOf(it) }.toSet().size)

        val flat = newRig()
        flat.engine.addFx(colourOver(FxTargetRef.fixture("sh-0"), ElementMode.FLAT))
        flat.engine.processBeatTick(tick(5))
        assertEquals(3, zonesOf("sh-0").map { flat.redOf(it) }.toSet().size)
    }

    @Test
    fun `the element filter picks which zones each fixture paints, and moves no fixture's place`() {
        val rig = newRig()
        val effect = groupFx(ElementMode.WHOLE, ElementFilter.ODD)
        rig.engine.addFx(effect)

        val heads = rig.engine.paintedHeads(effect)
        assertEquals(shehds.flatMap { listOf("$it.zone-1", "$it.zone-3") }, heads.map { it.key })
        assertEquals(shehds.indices.flatMap { i -> List(2) { i / 4.0 } }, heads.map { it.offset })
    }

    @Test
    fun `a selection of the four fixtures under Fixtures is the group's spread`() {
        val rig = newRig()
        val selection = FxTargetsRef.Selection(shehds.map { TargetRef.Fixture(it) })
        val effect = colourOver(FxTargetRef.SelectionRef(selection), ElementMode.WHOLE)
        rig.engine.addFx(effect)

        assertEquals(shehds.indices.flatMap { i -> List(3) { i / 4.0 } }, rig.engine.paintedHeads(effect).map { it.offset })
    }

    @Test
    fun `sampled under Fixtures, Colour Cycle moves one fixture a beat and a fixture's zones are equal at every sample`() {
        for (fadeRatio in listOf("0", "0.5")) {
            val rig = newRig()
            val between = if (fadeRatio == "0") mapOf("between" to "CUT") else mapOf("between" to "FADE", "fade" to fadeRatio)
            val cycle = FxInstance(
                registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#00ff00,#0000ff,#ffffff") + between),
                ColourTarget(FxTargetRef.group("shehds")),
                FxTiming(cycle = 4.0),
                BlendMode.OVERRIDE,
            ).apply {
                timingSource = registry.getRegistration("ColourCycle")!!.timingSource
                order = SpreadOrder.NORMAL
                elementMode = ElementMode.WHOLE
            }
            val ticksPerCycle = (MasterClock.TICKS_PER_BEAT * 4.0).toLong()
            val clock = FxSampleClock(ticks = { 10 * ticksPerCycle }, msPerTicks = { 20.0 }, rateScales = { 1.0 })
            val rows = rig.engine.sampler { registry.createFx(it.fxTypeId, it.effect.parameters) }
                .sample(FxSampleRequest(listOf(cycle), FxSampleRequest.Include.ONLY, window = FxSampleWindow.Cycles(2.0)), clock)
                .attribute("rgbColour")!!.rows
            assertEquals(shehds.flatMap(::zonesOf), rows.map { it.key })

            fun coloursOf(row: FxSampledRow): List<Triple<Byte, Byte, Byte>> {
                val series = row.result as FxSampleSeries.Colour
                return (0 until 2 * FxSampler.SAMPLES_PER_CYCLE).map { Triple(series.red[it], series.green[it], series.blue[it]) }
            }
            val byFixture = rows.groupBy { it.key.substringBefore('.') }
            for ((fixture, zones) in byFixture) {
                assertEquals(1, zones.map(::coloursOf).toSet().size, "$fixture's zones at fade $fadeRatio")
                assertEquals(1, zones.map { it.fx.single().offset }.toSet().size)
                assertEquals(1, zones.map { it.fx.single().starts }.toSet().size)
            }
            val firsts = shehds.map { coloursOf(byFixture.getValue(it).first()) }
            assertEquals(4, firsts.toSet().size, "each fixture its own colours at fade $fadeRatio")
            // One beat on, each fixture shows the colour the one before it showed.
            val beat = FxSampler.SAMPLES_PER_CYCLE / 4
            for (i in 1 until 4) assertEquals(firsts[i - 1][beat], firsts[i][2 * beat], "fade $fadeRatio")
            assertNotEquals(firsts[0][0], firsts[1][0])
        }
    }

    @Test
    fun `a stored FX without a mode reads as Each fixture, a started one as Fixtures`() {
        assertEquals(ElementMode.PER_FIXTURE, FxSpecCoercion.Lenient.elementMode(null) { "test" })
        assertEquals(ElementMode.PER_FIXTURE, ElementMode.STORED_DEFAULT)
        assertEquals(ElementMode.WHOLE, ElementMode.NEW_FX_DEFAULT)
        assertEquals(ElementMode.WHOLE, FxSpecCoercion.Strict.elementMode("WHOLE"))
    }

    @Test
    fun `a stored row keeps a lone fixture's mode only when it is Fixtures`() {
        assertEquals("WHOLE", ElementMode.stored(overMembers = false, ElementMode.WHOLE))
        assertEquals(null, ElementMode.stored(overMembers = false, ElementMode.FLAT))
        assertEquals(null, ElementMode.stored(overMembers = false, ElementMode.PER_FIXTURE))
        assertEquals("PER_FIXTURE", ElementMode.stored(overMembers = true, ElementMode.PER_FIXTURE))
    }

    companion object {
        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }
}
