package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.models.Timing
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fixture.dmx.VarytecEasymoveXl60SpotFixture
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import uk.me.cormack.lighting7.fx.KeyStackLayer.HeldBackReason
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sampler (FX editor plan §3.2, P3) against the stage: each case adds FX to an engine, samples
 * them before the first pass, then drives the engine a pass at a time through `FxEngineTickShims`
 * and asserts the sampler's *Result* is the committed DMX at every sampled tick.
 *
 * Every master runs at 125 BPM, 20 ms a tick, so a sample's offset is a whole number of ticks; the
 * ticker passes are 20 ms apart, so a wall-clock FX's clock and a sample's offset agree too.
 */
class FxSamplerParityTest {

    private val universe = Universe(0, 0)
    private val hexes = (0 until 8).map { "hex-$it" }

    private class Rig(val controller: MockDmxController, val fixtures: Fixtures, val store: ProgrammerStore, val engine: FxEngine) {
        /** A head as the controller holds it now, read through a transaction that is never applied. */
        fun head(key: String): GroupableFixture =
            fixtures.withTransaction(ControllerTransaction(fixtures.controllers)).untypedGroupableFixture(key)
    }

    private fun rig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val hexFixtures = hexes.mapIndexed { i, key -> addFixture(HexFixture(universe, key, key, 1 + i * 12)) }
            createGroup<HexFixture>("hexes") { addSpread(hexFixtures) }
            addFixture(VarytecEasymoveXl60SpotFixture.Mode11Ch(universe, "vary", "Vary", 121))
            val bars = (0 until 2).map { b ->
                addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar-$b", "Bar $b", 201 + b * 48))
            }
            createGroup<LedLightbar12PixelFixture.Mode48Ch>("bars") { addSpread(bars) }
        }
        val store = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
        )
        return Rig(controller, fixtures, store, engine)
    }

    private var palettes = PaletteRegistry { null }

    private fun fresh(instance: FxInstance): Fx =
        registry.createFxWithPalettes(palettes, instance.fxTypeId, instance.effect.parameters)

    private fun fx(
        id: String,
        target: FxTarget,
        division: Double = CycleLength.ONE_BAR,
        blend: BlendMode = BlendMode.OVERRIDE,
        params: Map<String, String> = emptyMap(),
        configure: FxInstance.() -> Unit = {},
    ): FxInstance {
        val effect = registry.createFxWithPalettes(palettes, id, params)
        return FxInstance(effect, target, FxTiming(cycle = division), blend).apply {
            timingSource = registry.getRegistration(id)!!.timingSource
            configure()
        }
    }

    private fun pulse(
        target: FxTarget,
        division: Double = CycleLength.ONE_BAR,
        blend: BlendMode = BlendMode.OVERRIDE,
        configure: FxInstance.() -> Unit = {},
    ): FxInstance = fx("Wave", target, division, blend, mapOf("shape" to "PULSE", "curve" to "QUAD_OUT"), configure)

    private fun FxInstance.dynamics(transform: (FxDynamics) -> FxDynamics) = apply { updateDynamics(transform) }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = (n % ticksPerBeat).toInt(),
            phase = (n % ticksPerBeat).toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = T0 + n * MS_PER_TICK.toLong(),
        )
    }

    private val clock = FxSampleClock(ticks = { START }, msPerTicks = { MS_PER_TICK }, rateScales = { 1.0 })

    /** How a case's passes are woken. */
    private enum class Wake { BEAT, TICKER, BOTH }

    /**
     * Adds [fx] to [rig], samples them over [passes] ticks before any pass, drives one pass a tick,
     * and asserts every sampled row of every attribute against the committed DMX. Answers the samples.
     */
    private fun assertParity(
        rig: Rig,
        fx: List<FxInstance>,
        passes: Int = 192,
        wake: Wake = Wake.BEAT,
        include: FxSampleRequest.Include = FxSampleRequest.Include.STACK,
    ): FxSamples {
        fx.forEach { rig.engine.addFx(it) }
        val offsets = DoubleArray(passes) { it * MS_PER_TICK }
        val samples = rig.engine.sampler(::fresh).sampleAt(fx, include, null, clock, offsets, MS_PER_TICK)
        assertTrue(
            samples.attributes.any { a -> a.rows.any { r -> (0 until passes).map { valueAt(r.result, it) }.toSet().size > 1 } },
            "the result moves, so agreeing with the stage means something",
        )
        for (s in 0 until passes) {
            val t = START + s
            val nowMs = T0 + s * MS_PER_TICK.toLong()
            when (wake) {
                Wake.BEAT -> rig.engine.processBeatTick(tick(t))
                Wake.TICKER -> rig.engine.processWallClockTick(nowMs)
                Wake.BOTH -> rig.engine.processCoalescedTick(tick(t), nowMs)
            }
            for (attribute in samples.attributes) {
                for (row in attribute.rows) {
                    assertEquals(
                        committed(rig.head(row.key), attribute.attribute),
                        valueAt(row.result, s),
                        "${row.key}.${attribute.attribute} at sample $s",
                    )
                }
            }
        }
        return samples
    }

    /** What the controller holds for [attribute] on [head], in [valueAt]'s shape. */
    private fun committed(head: GroupableFixture, attribute: String): List<Int> = when (attribute) {
        "dimmer" -> listOf((head as WithDimmer).dimmer.value!!.toInt())
        "uv" -> listOf((head as WithUv).uv.value!!.toInt())
        "rgbColour" -> {
            val rgb = (head as WithColour).rgbColour.value!!
            listOfNotNull(
                rgb.red, rgb.green, rgb.blue,
                (head as? WithWhite)?.white?.value?.toInt(),
                (head as? WithAmber)?.amber?.value?.toInt(),
                (head as? WithUv)?.uv?.value?.toInt(),
            )
        }
        "position" -> listOf((head as WithPosition).pan.value!!.toInt(), head.tilt.value!!.toInt())
        else -> error("no reader for $attribute")
    }

    /** One sample of a [FxSampleSeries], unsigned, planes in [committed]'s order. */
    private fun valueAt(series: FxSampleSeries, s: Int): List<Int> {
        fun ByteArray.at() = this[s].toInt() and 0xFF
        return when (series) {
            is FxSampleSeries.Bytes -> listOf(series.values.at())
            is FxSampleSeries.Colour -> listOfNotNull(
                series.red.at(), series.green.at(), series.blue.at(),
                series.white?.at(), series.amber?.at(), series.uv?.at(),
            )
            is FxSampleSeries.Position -> listOf(series.pan.at(), series.tilt.at())
            is FxSampleSeries.Steps -> valueAt(series.values, s)
        }
    }

    private val orders = listOf(
        SpreadOrder.NORMAL,
        SpreadOrder.UNIFIED,
        SpreadOrder.CENTRE_OUT,
        SpreadOrder.CENTRE_IN,
        SpreadOrder.RANDOM(),
        SpreadOrder.PING_PONG,
        SpreadOrder.REVERSE,
        SpreadOrder.POSITIONAL,
    )

    @Test
    fun `Sine Wave on a group under every Order, with Segments, Wings, Parts and Spread set`() {
        assertEquals(SpreadOrder.availableOrders.size, orders.size, "every Order is here")
        for (order in orders) {
            val sine = fx("Wave", SliderTarget.forGroup("hexes", "dimmer")) {
                this.order = order
            }.dynamics { it.copy(spread = 0.75, wings = 2, segments = 2, parts = 4) }
            val samples = assertParity(rig(), listOf(sine))
            assertEquals(hexes, samples.rows, "$order: every head, in the group's order")
        }
    }

    @Test
    fun `Runs 2 with Direction Bounce holds underneath before and after each head's runs`() {
        val pulse = fx("Wave", SliderTarget.forGroup("hexes", "dimmer"), division = CycleLength.ONE_BAR)
            .dynamics { it.copy(runs = 2, direction = FxDirection.BOUNCE) }
        val samples = assertParity(rig(), listOf(pulse), passes = 24 * 4 * 4)
        val last = samples.attribute("dimmer")!!.row("hex-7")!!.fx.single()
        assertTrue(last.holding.first().first == 0, "the last head waits for its offset to come round")
        assertTrue(last.holding.last().last == samples.offsetsMs.size - 1, "and holds once its runs are done")
    }

    @Test
    fun `Timing Per head`() {
        val chase = pulse(SliderTarget.forGroup("hexes", "dimmer"), division = CycleLength.ONE_BEAT) {
            updateDynamics { it.copy(timing = Timing.HEAD) }
        }
        assertParity(rig(), listOf(chase), passes = 24 * 8 + 12)
    }

    @Test
    fun `two Intensity FX, Replace under Within`() {
        val sine = fx("Wave", SliderTarget.forGroup("hexes", "dimmer")) { priority = 1 }
        val pulse = pulse(SliderTarget.forGroup("hexes", "dimmer"), division = CycleLength.TWO_BEATS, blend = BlendMode.MULTIPLY) {
            priority = 2
        }
        val samples = assertParity(rig(), listOf(pulse, sine))
        assertEquals(listOf(sine, pulse), samples.fx.map { it.fx }, "in apply order, whatever order they were asked in")
    }

    @Test
    fun `a Circle Around a programmer position`() {
        val rig = rig()
        rig.store.put(ProgrammerOwner.WEB, "vary", "position", CueAssignmentResolver.AttributeValue.Position(100u, 150u))
        val circle = fx("Path", PositionTarget("vary"), blend = BlendMode.ADDITIVE) {
            priority = FxEngine.localFxPriority(0)
        }
        val samples = assertParity(rig, listOf(circle))
        assertEquals(FxOutput.Position(100u, 150u), samples.attribute("position")!!.row("vary")!!.underneath)
    }

    @Test
    fun `Colour Cycle under Amount 50 percent`() {
        val cycle = fx("ColourCycle", ColourTarget.forGroup("hexes"), params = mapOf("colours" to "#ff0000,#00ff80;w200,#0000ff")) {
            order = SpreadOrder.NORMAL
        }.dynamics { it.copy(amount = 0.5) }
        val samples = assertParity(rig(), listOf(cycle))
        val series = samples.attribute("rgbColour")!!.row("hex-0")!!.result as FxSampleSeries.Colour
        assertNotNull(series.white, "a hex's white is a plane of its colour")
    }

    @Test
    fun `Palette Steps over two palettes`() {
        val red = UUID.nameUUIDFromBytes("red".toByteArray())
        val blue = UUID.nameUUIDFromBytes("blue".toByteArray())
        val snapshots = mapOf(red to "#ff0000", blue to "#0000ff;a90").mapValues { (uuid, colour) ->
            PaletteSnapshot(
                paletteId = 1, paletteUuid = uuid, name = if (uuid == red) "Red" else "Blue", fadeDurationMs = null,
                rows = listOf(PaletteRowEntry(null, "rgbColour", parsePaletteIntent(colour)!!.serialize())),
            )
        }
        palettes = PaletteRegistry { snapshots[it] }
        val refs = listOf(red, blue).joinToString(",") { serializePaletteColourRef(it) }
        val steps = fx(PALETTE_STEPS_ID, ColourTarget.forGroup("hexes"), params = mapOf("palettes" to refs, "fadeRatio" to "0.5"))
        val samples = assertParity(rig(), listOf(steps))
        val own = samples.attribute("rgbColour")!!.row("hex-0")!!.fx.single().output as FxSampleSeries.Steps
        assertEquals(listOf("Red", "Blue"), own.labels)
        assertEquals(setOf(0, 1), own.indices.toSet(), "both steps are shown over the window")
    }

    @Test
    fun `a Seconds FX`() {
        val sine = fx("Wave", SliderTarget.forGroup("hexes", "dimmer"), division = 1.5) {
            applyTimingChoice(TimingSource.BEAT, seconds = true)
        }
        assertParity(rig(), listOf(sine), wake = Wake.TICKER)
    }

    @Test
    fun `a multi-element fixture under each element mode and filter`() {
        for (mode in ElementMode.entries) {
            for (filter in ElementFilter.entries) {
                val wave = fx("ColourWave", ColourTarget.forGroup("bars"), division = CycleLength.ONE_BAR) {
                    elementMode = mode
                    elementFilter = filter
                }
                val samples = assertParity(rig(), listOf(wave), passes = 48)
                assertTrue(samples.rows.all { it.startsWith("bar-") && "." in it }, "$mode $filter: the rows are heads")
            }
        }
    }

    @Test
    fun `a held-back FX is sampled but left out of the result`() {
        val rig = rig()
        rig.store.put(ProgrammerOwner.WEB, "hex-0", "dimmer", CueAssignmentResolver.AttributeValue.Slider(100u))
        val sine = fx("Wave", SliderTarget.forGroup("hexes", "dimmer"))
        val samples = assertParity(rig, listOf(sine))
        val held = samples.attribute("dimmer")!!.row("hex-0")!!
        assertEquals(HeldBackReason.PROGRAMMER, held.fx.single().heldBack)
        assertTrue((held.result as FxSampleSeries.Bytes).values.all { it.toInt() and 0xFF == 100 }, "the programmer's value stands")
        assertTrue((held.fx.single().output as FxSampleSeries.Bytes).values.toSet().size > 1, "while the FX's own output moves")
        assertNull(samples.attribute("dimmer")!!.row("hex-1")!!.fx.single().heldBack)
    }

    @Test
    fun `a Local FX sits over a palette layer's FX on one key`() {
        val local = pulse(SliderTarget("hex-0", "dimmer")) { priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE }
        val layer = fx("Wave", SliderTarget("hex-0", "dimmer")) {
            priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
            programmerLayerFxKey = ProgrammerLayerFxKey(
                1,
                StoredFx(
                    fxType = "Wave", family = "intensity", attribute = "dimmer", cycle = CycleLength.ONE_BAR,
                    blendMode = "OVERRIDE", order = "NORMAL", phaseOffset = 0.0, elementMode = null,
                    elementFilter = null, timing = null, parameters = emptyMap(), speedMasterUuid = null,
                    rateSpeedMasterUuid = null,
                ),
                FxTargetsRef.of(listOf(TargetRef.Fixture("hex-0"))),
            )
        }
        val samples = assertParity(rig(), listOf(local, layer))
        assertEquals(listOf(layer, local), samples.fx.map { it.fx }, "the Local FX applies last, though it was added first")
    }

    @Test
    fun `a beat Sine Wave with a wall-clock FX Within it on one dimmer`() {
        val beat = fx("Wave", SliderTarget("hex-0", "dimmer")) { priority = 1 }
        val wall = fx("Wave", SliderTarget("hex-0", "dimmer"), division = 0.7, blend = BlendMode.MULTIPLY) {
            applyTimingChoice(TimingSource.BEAT, seconds = true)
            priority = 2
        }
        assertParity(rig(), listOf(beat, wall), wake = Wake.BOTH)
    }

    @Test
    fun `a stateful FX is representative, and sampling it twice gives the same answer`() {
        val rig = rig()
        val candle = fx("CandleFlicker", SliderTarget.forGroup("hexes", "dimmer"), division = 1.0)
        rig.engine.addFx(candle)
        val sampler = rig.engine.sampler(::fresh)
        val request = FxSampleRequest(listOf(candle), window = FxSampleWindow.Cycles(4.0))
        val first = sampler.sample(request, clock)
        val second = sampler.sample(request, clock)
        assertTrue(first.fx.single().representative)
        for (key in hexes) {
            assertContentEquals(
                (first.attribute("dimmer")!!.row(key)!!.result as FxSampleSeries.Bytes).values,
                (second.attribute("dimmer")!!.row(key)!!.result as FxSampleSeries.Bytes).values,
            )
        }
        assertTrue((first.attribute("dimmer")!!.row("hex-0")!!.result as FxSampleSeries.Bytes).values.toSet().size > 1, "it flickers")
    }

    @Test
    fun `a paused FX alone on a head leaves its last frame, and Result shows it`() {
        val rig = rig()
        val sine = fx("Wave", SliderTarget.forGroup("hexes", "dimmer"))
        val id = rig.engine.addFx(sine)
        for (s in 0 until 20) rig.engine.processBeatTick(tick(START + s))
        rig.engine.pauseFx(id)

        val frame = hexes.map { committed(rig.head(it), "dimmer") }
        assertTrue(frame.toSet().size > 1, "the heads stopped at different levels")
        val samples = rig.engine.sampler(::fresh).sampleAt(
            listOf(sine), FxSampleRequest.Include.STACK, null, clock, DoubleArray(48) { it * MS_PER_TICK }, MS_PER_TICK,
        )
        for (s in 0 until 48) {
            rig.engine.processBeatTick(tick(START + 20 + s))
            for (key in hexes) {
                val row = samples.attribute("dimmer")!!.row(key)!!
                assertEquals(committed(rig.head(key), "dimmer"), valueAt(row.result, s), "$key at sample $s")
            }
        }
        assertEquals(hexes.map { committed(rig.head(it), "dimmer") }, frame, "the stage kept the frame throughout")
        assertEquals(listOf(0 until 48), samples.attribute("dimmer")!!.row("hex-0")!!.fx.single().holding)
    }

    @Test
    fun `sampling changes nothing on an instance or the controller`() {
        // Two rigs run alike; one is sampled part-way. Every field the pass owns is unchanged by it,
        // and the two rigs still agree channel for channel afterwards, the stateful FX's state included.
        fun running(rig: Rig): List<FxInstance> {
            val beat = fx("Wave", SliderTarget.forGroup("hexes", "dimmer")).dynamics { it.copy(runs = 3) }
            val candle = fx("CandleFlicker", SliderTarget.forGroup("hexes", "uv"), division = 1.0)
            val wall = fx("Path", PositionTarget("vary"), division = 2.0) {
                applyTimingChoice(TimingSource.BEAT, seconds = true)
            }.dynamics { it.copy(runs = 2) }
            return listOf(beat, candle, wall).onEach { rig.engine.addFx(it) }
        }
        fun Rig.pass(s: Int) = engine.processCoalescedTick(tick(START + s), T0 + s * MS_PER_TICK.toLong())
        val sampled = rig()
        val untouched = rig()
        val fx = running(sampled)
        running(untouched)
        for (s in 0 until 30) {
            sampled.pass(s)
            untouched.pass(s)
        }
        fx.first().updateEnvelope { FxEnvelope.constant(0.8) }

        data class Fields(
            val runsStartTick: Long, val runsStartScaledMs: Double, val runsPending: Boolean, val runsEvaluated: Boolean,
            val lastPhase: Double, val accumulated: Double, val envelope: FxEnvelope, val stateful: Map<String, FxOutput>?,
            val dynamics: FxDynamics, val expansion: Any?, val plans: Any?,
        )
        fun FxInstance.fields() = Fields(
            runsStartTick, runsStartScaledMs, runsPending, runsEvaluated, lastPhase, accumulatedScaledMs, envelope,
            statefulOutputs?.toMap(), dynamics, expansion, distributionPlans,
        )
        val before = fx.map { it.fields() }
        val channels = sampled.controller.currentValues.toMap()

        val sampler = sampled.engine.sampler(::fresh)
        sampler.sample(FxSampleRequest(fx, window = FxSampleWindow.Cycles(3.0)))
        sampler.sample(FxSampleRequest(listOf(fx.last()), include = FxSampleRequest.Include.ONLY, window = FxSampleWindow.Seconds(4.0)))

        assertEquals(before, fx.map { it.fields() })
        assertEquals(channels, sampled.controller.currentValues.toMap())

        untouched.engine.getActiveFx().first { it.fxTypeId == "Wave" && it.target is SliderTarget }
            .updateEnvelope { FxEnvelope.constant(0.8) }
        for (s in 30 until 90) {
            sampled.pass(s)
            untouched.pass(s)
            assertEquals(untouched.controller.currentValues.toMap(), sampled.controller.currentValues.toMap(), "pass $s")
        }
    }

    @Test
    fun `a draft runs on the master it names, which only adding it would bind`() {
        val rig = rig()
        val master2 = UUID.nameUUIDFromBytes("master-2".toByteArray())
        rig.engine.speedMasters.load(
            listOf(
                SpeedMasterSnapshot(UUID.nameUUIDFromBytes("master-1".toByteArray()), 1, "Master 1", 125.0, SpeedMasterSource.MANUAL, null, null, null, null),
                SpeedMasterSnapshot(master2, 2, "Master 2", 62.5, SpeedMasterSource.MANUAL, null, null, null, null),
            ),
        )
        val draft = fx("Wave", SliderTarget("hex-0", "dimmer")) { speedMasterUuid = master2 }
        val slowClock = FxSampleClock(ticks = { START }, msPerTicks = { if (it == 1) 2 * MS_PER_TICK else MS_PER_TICK }, rateScales = { 1.0 })
        val samples = rig.engine.sampler(::fresh).sample(FxSampleRequest(listOf(draft), window = FxSampleWindow.Cycles(1.0)), slowClock)
        assertEquals(2 * CycleLength.ONE_BAR * MasterClock.TICKS_PER_BEAT * MS_PER_TICK / 48, samples.sampleMs)
    }

    @Test
    fun `a draft folds where adding it would put it`() {
        val rig = rig()
        val running = pulse(SliderTarget("hex-0", "dimmer"))
        rig.engine.addFx(running)
        val draft = fx("Wave", SliderTarget("hex-0", "dimmer"))
        val samples = rig.engine.sampler(::fresh).sampleAt(
            listOf(draft), FxSampleRequest.Include.STACK, null, clock, DoubleArray(96) { it * MS_PER_TICK }, MS_PER_TICK,
        )
        assertEquals(listOf(running, draft), samples.fx.map { it.fx }, "after every running FX of its priority")
        val row = samples.attribute("dimmer")!!.row("hex-0")!!
        assertContentEquals((row.fx.last().output as FxSampleSeries.Bytes).values, (row.result as FxSampleSeries.Bytes).values)

        val local = pulse(SliderTarget("hex-0", "dimmer")) { priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE }
        rig.engine.addFx(local)
        val localDraft = fx("Wave", SliderTarget("hex-0", "dimmer")) { priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE }
        val stacked = rig.engine.sampler(::fresh).sample(FxSampleRequest(listOf(localDraft)), clock)
        assertEquals(listOf(running, local, localDraft), stacked.fx.map { it.fx }, "a Local draft at the top of its lane")
        assertNull(stacked.attribute("dimmer")!!.row("hex-0")!!.fx.last().heldBack)
    }

    @Test
    fun `an FX whose object cannot be built holds, and the rest are still sampled`() {
        val rig = rig()
        val gone = pulse(SliderTarget("hex-0", "dimmer"))
        rig.engine.addFx(gone)
        val sine = fx("Wave", SliderTarget("hex-0", "dimmer"), blend = BlendMode.MAX)
        val sampler = rig.engine.sampler { if (it === gone) error("Unknown effect type") else fresh(it) }
        val samples = sampler.sample(FxSampleRequest(listOf(sine)), clock)
        val row = samples.attribute("dimmer")!!.row("hex-0")!!
        assertEquals(listOf(0 until samples.offsetsMs.size), row.fx.first().holding)
        assertContentEquals((row.fx.last().output as FxSampleSeries.Bytes).values, (row.result as FxSampleSeries.Bytes).values)
    }

    @Test
    fun `the window takes 48 samples a cycle and caps what it is asked for`() {
        val rig = rig()
        val sine = fx("Wave", SliderTarget.forGroup("hexes", "dimmer"))
        rig.engine.addFx(sine)
        val sampler = rig.engine.sampler(::fresh)

        val two = sampler.sample(FxSampleRequest(listOf(sine), window = FxSampleWindow.Cycles(2.0)), clock)
        assertEquals(96, two.offsetsMs.size)
        assertEquals(CycleLength.ONE_BAR * MasterClock.TICKS_PER_BEAT * MS_PER_TICK / 48, two.sampleMs)
        assertTrue(two.truncated.isEmpty())

        val long = sampler.sample(FxSampleRequest(listOf(sine), window = FxSampleWindow.Cycles(100.0)), clock)
        assertEquals(FxSampler.MAX_SAMPLES, long.offsetsMs.size)
        assertEquals(setOf(FxSamples.Truncation.SAMPLES), long.truncated)

        val many = (0 until 10).map { pulse(SliderTarget(hexes[it % 8], "uv")) }
        val capped = sampler.sample(FxSampleRequest(many, include = FxSampleRequest.Include.ONLY), clock)
        assertEquals(FxSampler.MAX_FX, capped.fx.size)
        assertTrue(FxSamples.Truncation.FX in capped.truncated)

        val rows = (0 until 120).map { "missing-$it" }
        val narrowed = sampler.sample(FxSampleRequest(listOf(sine), rows = rows), clock)
        assertEquals(FxSampler.MAX_ROWS, narrowed.rows.size)
        assertTrue(FxSamples.Truncation.ROWS in narrowed.truncated)
    }

    companion object {
        private const val T0 = 1_000_000L
        private const val START = 1_000L
        private const val MS_PER_TICK = 20.0

        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }
}
