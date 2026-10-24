package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One compose pass (FX editor plan §3.4): a beat wake and a ticker wake each run one pass over every
 * running FX, so a beat FX and a wall-clock FX on one key blend, whatever woke the pass, while what
 * counts passes — a stateful FX's steps, the tick-failure run, an N-shot's end — keeps to the FX's
 * own clock.
 *
 * Every pass is driven through `FxEngineTickShims` with an explicit time, so nothing here waits on
 * the real clock.
 */
class FxComposePassTest {

    private val universe = Universe(0, 0)

    private data class Rig(val controller: MockDmxController, val engine: FxEngine) {
        val dimmer: Int get() = controller.currentValues.getValue(1).toInt()
    }

    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", 1))
        }
        return Rig(controller, FxEngine(fixtures = fixtures))
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = (n % ticksPerBeat).toInt(),
            phase = (n % ticksPerBeat).toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = T0 + n * 20L,
        )
    }

    private fun fx(id: String, vararg params: Pair<String, String>): Fx =
        registry.createFx(id, params.toMap())

    private fun instance(
        effect: Fx,
        blendMode: BlendMode,
        timingSource: TimingSource,
        division: Double = 1.0,
        priority: Int = 0,
        attribute: String = "dimmer",
    ) = FxInstance(effect, SliderTarget("hex-a", attribute), FxTiming(cycle = division), blendMode).also {
        it.timingSource = timingSource
        it.priority = priority
    }

    /**
     * An FX of [timingSource]'s clock on the head's UV, so a pass that clock wakes has work to do
     * (one with nothing running on a clock that woke it is skipped).
     */
    private fun Rig.withCompanionOn(timingSource: TimingSource) = engine.addFx(
        if (timingSource == TimingSource.BEAT) instance(fx("Pulse"), BlendMode.OVERRIDE, timingSource, attribute = "uv")
        else instance(fx("CandleFlicker"), BlendMode.OVERRIDE, timingSource, attribute = "uv"),
    )

    /** One pass of a schedule: which clocks woke it, the master's tick, and the time. */
    private data class Pass(val beat: Boolean, val ticker: Boolean, val tick: Long, val atMs: Long)

    private fun Rig.run(pass: Pass) = when {
        pass.beat && pass.ticker -> engine.processCoalescedTick(tick(pass.tick), pass.atMs)
        pass.beat -> engine.processBeatTick(tick(pass.tick), pass.atMs)
        else -> engine.processWallClockTick(pass.atMs)
    }

    /**
     * Beat ticks every 21 ms and the ticker every 20 ms, so the two drift through each other: some
     * passes a beat woke, some the ticker woke, and every so often both at once.
     */
    private val schedule: List<Pass> = buildList {
        var beat = 0L
        var nextBeatMs = T0 + 3
        var nextTickerMs = T0 + 20
        while (size < 120) {
            when {
                nextBeatMs == nextTickerMs -> {
                    add(Pass(beat = true, ticker = true, tick = ++beat, atMs = nextBeatMs))
                    nextBeatMs += 21
                    nextTickerMs += 20
                }
                nextBeatMs < nextTickerMs -> {
                    add(Pass(beat = true, ticker = false, tick = ++beat, atMs = nextBeatMs))
                    nextBeatMs += 21
                }
                else -> {
                    add(Pass(beat = false, ticker = true, tick = beat, atMs = nextTickerMs))
                    nextTickerMs += 20
                }
            }
        }
    }

    /**
     * Runs [schedule] over a rig holding a beat Sine Wave and a wall-clock Candle Flicker on one
     * dimmer, and checks every pass against [expected] of the two FX's own outputs: the Sine Wave
     * at the pass's master tick, the Candle Flicker as a twin stepped once per ticker-woken pass.
     */
    private fun assertComposes(
        sine: FxInstance,
        candle: FxInstance,
        sineDivision: Double,
        sineParams: Map<String, String> = emptyMap(),
        expected: (sine: Int, candle: Int) -> Int,
    ) {
        assertTrue(schedule.any { it.beat && !it.ticker } && schedule.any { it.ticker && !it.beat })
        assertTrue(schedule.any { it.beat && it.ticker }, "the schedule coalesces some wakes")

        val rig = newRig()
        rig.engine.addFx(sine)
        rig.engine.addFx(candle)

        val sineTwin = registry.createFx("SineWave", sineParams)
        val sineClock = FxInstance(sineTwin, SliderTarget("hex-a", "dimmer"), FxTiming(cycle = sineDivision))
        val candleTwin = fx("CandleFlicker") as StatefulFx
        var candleSteps = 0L

        // The ticker wakes first, so the Candle Flicker's first step is a ticker pass's.
        rig.engine.processWallClockTick(T0)
        var candleLevel = candleTwin.step(candleSteps++)

        for ((i, pass) in schedule.withIndex()) {
            rig.run(pass)
            if (pass.ticker) candleLevel = candleTwin.step(candleSteps++)
            val sineLevel =
                (sineTwin.calculate(sineClock.calculatePhase(tick(pass.tick)), FxContext.SINGLE)
                    as FxOutput.Slider).value.toInt()
            assertEquals(expected(sineLevel, candleLevel), rig.dimmer, "pass $i ($pass): sine $sineLevel, candle $candleLevel")
        }
    }

    private fun StatefulFx.step(n: Long): Int =
        (calculateStateful(
            MasterClock.ClockTick(tickNumber = n, beatNumber = 0, tickInBeat = 0, phase = 0.0, timestampMs = 0L),
            20L,
            FxContext.SINGLE,
        ) as FxOutput.Slider).value.toInt()

    @Test
    fun `a Candle Flicker Within a Sine Wave on one dimmer composes on every pass`() {
        assertComposes(
            sine = instance(fx("SineWave"), BlendMode.OVERRIDE, TimingSource.BEAT, division = 1.0),
            candle = instance(fx("CandleFlicker"), BlendMode.MULTIPLY, TimingSource.WALL_CLOCK),
            sineDivision = 1.0,
        ) { sine, candle -> sine * candle / 255 }
    }

    @Test
    fun `a wall-clock FX Added onto a beat FX composes on every pass`() {
        assertComposes(
            sine = instance(fx("SineWave", "max" to "100"), BlendMode.OVERRIDE, TimingSource.BEAT, division = 2.0),
            candle = instance(fx("CandleFlicker"), BlendMode.ADDITIVE, TimingSource.WALL_CLOCK),
            sineDivision = 2.0,
            sineParams = mapOf("max" to "100"),
        ) { sine, candle -> (sine + candle).coerceAtMost(255) }
    }

    @Test
    fun `a beat FX at Max over a wall-clock FX composes on every pass`() {
        // The Candle Flicker first in apply order this time: the beat FX is the one on top.
        assertComposes(
            sine = instance(fx("SineWave"), BlendMode.MAX, TimingSource.BEAT, division = 1.0, priority = 1),
            candle = instance(fx("CandleFlicker"), BlendMode.OVERRIDE, TimingSource.WALL_CLOCK),
            sineDivision = 1.0,
        ) { sine, candle -> maxOf(sine, candle) }
    }

    @Test
    fun `a wall-clock FX's cycle is as long however many beats wake the pass`() {
        // A Sine Wave on Seconds with a 2 s cycle; one rig is woken by the ticker alone, the other
        // by the ticker and a fast master's every tick in between.
        fun rigWith(): Pair<Rig, FxInstance> {
            val rig = newRig()
            val sine = instance(fx("SineWave"), BlendMode.OVERRIDE, TimingSource.WALL_CLOCK, division = 2.0)
            rig.engine.addFx(sine)
            return rig to sine
        }
        val (tickerOnly, quiet) = rigWith()
        val (busy, woken) = rigWith()
        // A beat FX keeps the beat wakes doing work on the busy rig.
        busy.engine.addFx(instance(fx("Pulse"), BlendMode.MAX, TimingSource.BEAT))

        tickerOnly.engine.processWallClockTick(T0)
        busy.engine.processWallClockTick(T0)
        var beat = 0L
        for (n in 1..100) {
            val at = T0 + n * 20L
            for (offset in longArrayOf(4, 9, 13, 17)) busy.engine.processBeatTick(tick(++beat), at - 20 + offset)
            tickerOnly.engine.processWallClockTick(at)
            busy.engine.processWallClockTick(at)
            assertEquals(quiet.accumulatedScaledMs, woken.accumulatedScaledMs, "at ${n * 20} ms")
        }
        assertEquals(2_000.0, woken.accumulatedScaledMs, "2 s of real time is 2 s on its clock")
        assertEquals(0.0, woken.calculateWallClockPhase(woken.dynamics), 1e-9, "one whole cycle, back at its start")
    }

    @Test
    fun `a stateful wall-clock FX steps only on passes the ticker woke`() {
        val rig = newRig()
        val candle = instance(fx("CandleFlicker"), BlendMode.OVERRIDE, TimingSource.WALL_CLOCK)
        rig.engine.addFx(candle)
        rig.withCompanionOn(TimingSource.BEAT)

        rig.engine.processWallClockTick(T0)
        val afterTicker = rig.dimmer
        rig.controller.setValue(1, 0u)
        repeat(5) { rig.engine.processBeatTick(tick(it.toLong() + 1), T0 + 2 + it) }
        assertEquals(afterTicker, rig.dimmer, "beat-woken passes repaint its last output rather than step it")
    }

    @Test
    fun `tick failures count only the passes the FX's own clock woke`() {
        val limit = FxEngine.MAX_CONSECUTIVE_TICK_FAILURES

        val beatRig = newRig()
        val beatFx = beatRig.engine.addFx(instance(Throwing(), BlendMode.OVERRIDE, TimingSource.BEAT))
        beatRig.withCompanionOn(TimingSource.WALL_CLOCK)
        beatRig.engine.processBeatTick(tick(1), T0)
        repeat(limit * 2) { beatRig.engine.processWallClockTick(T0 + 20L * (it + 1)) }
        assertTrue(beatRig.engine.getFx(beatFx)!!.isRunning, "ticker-woken passes do not count against a beat FX")
        repeat(limit - 2) { beatRig.engine.processBeatTick(tick(it + 2L), T0 + 5_000 + it) }
        assertTrue(beatRig.engine.getFx(beatFx)!!.isRunning, "one short of the limit")
        beatRig.engine.processBeatTick(tick(limit + 1L), T0 + 9_000)
        assertFalse(beatRig.engine.getFx(beatFx)!!.isRunning, "paused on its master's ${limit}th failing pass")

        val wallRig = newRig()
        val wallFx = wallRig.engine.addFx(instance(Throwing(), BlendMode.OVERRIDE, TimingSource.WALL_CLOCK))
        wallRig.withCompanionOn(TimingSource.BEAT)
        repeat(limit - 1) { wallRig.engine.processWallClockTick(T0 + 20L * it) }
        repeat(limit * 2) { wallRig.engine.processBeatTick(tick(it.toLong()), T0 + 5_000 + it) }
        assertTrue(wallRig.engine.getFx(wallFx)!!.isRunning, "beat-woken passes do not count against a wall-clock FX")
        wallRig.engine.processWallClockTick(T0 + 9_000)
        assertFalse(wallRig.engine.getFx(wallFx)!!.isRunning, "paused on the ticker's ${limit}th failing pass")
    }

    @Test
    fun `a beat N-shot ends on a beat-woken pass`() {
        val rig = newRig()
        val pulse = instance(fx("Pulse"), BlendMode.OVERRIDE, TimingSource.BEAT, division = 1.0)
        pulse.updateDynamics { it.copy(runs = 1) }
        rig.engine.addFx(pulse)
        rig.withCompanionOn(TimingSource.WALL_CLOCK)

        rig.engine.processBeatTick(tick(100), T0 + 5)
        assertEquals(100L, pulse.runsStartTick, "its count starts on its master's tick")
        for (n in 101L until 124L) {
            rig.engine.processBeatTick(tick(n), T0 + 5 + (n - 100) * 21)
            rig.engine.processWallClockTick(T0 + 15 + (n - 100) * 21)
            assertNotNull(rig.engine.getFx(pulse.id), "still in its one cycle at tick $n")
        }
        rig.engine.processBeatTick(tick(124), T0 + 600)
        assertNull(rig.engine.getFx(pulse.id), "one beat after it started, it is done")
    }

    @Test
    fun `a beat FX given a count mid-run goes on painting on ticker-woken passes`() {
        val rig = newRig()
        val pulse = instance(fx("Pulse", "min" to "200", "max" to "200"), BlendMode.OVERRIDE, TimingSource.BEAT)
        rig.engine.addFx(pulse)
        rig.withCompanionOn(TimingSource.WALL_CLOCK)
        rig.engine.processBeatTick(tick(10), T0)
        assertEquals(200, rig.dimmer)

        rig.engine.updateFx(pulse.id, newRuns = 2)
        rig.engine.processWallClockTick(T0 + 10)
        assertEquals(200, rig.dimmer, "the ticker-woken pass starts its count and paints it")
        assertEquals(10L, rig.engine.getFx(pulse.id)!!.runsStartTick, "from the tick the master is on")
    }

    @Test
    fun `a wall-clock N-shot ends on a ticker-woken pass`() {
        val rig = newRig()
        val sine = instance(fx("SineWave"), BlendMode.OVERRIDE, TimingSource.WALL_CLOCK, division = 1.0)
        sine.updateDynamics { it.copy(runs = 1) }
        rig.engine.addFx(sine)
        rig.withCompanionOn(TimingSource.BEAT)

        rig.engine.processWallClockTick(T0)
        rig.engine.processWallClockTick(T0 + 980)
        // A beat wakes the pass after its one second has run out: it holds, and is not yet ended.
        rig.engine.processBeatTick(tick(1), T0 + 1_005)
        assertNotNull(rig.engine.getFx(sine.id), "a beat-woken pass does not end a wall-clock N-shot")
        rig.engine.processWallClockTick(T0 + 1_020)
        assertNull(rig.engine.getFx(sine.id), "the next ticker-woken pass does")
    }

    @Test
    fun `a pass with nothing running on a clock that woke it is skipped, and its time kept`() {
        val rig = newRig()
        val sine = instance(fx("SineWave"), BlendMode.OVERRIDE, TimingSource.WALL_CLOCK, division = 2.0)
        rig.engine.addFx(sine)

        rig.engine.processWallClockTick(T0)
        val level = rig.dimmer
        rig.controller.setValue(1, 0u)
        rig.engine.processBeatTick(tick(1), T0 + 10)
        assertEquals(0, rig.dimmer, "a beat-woken pass with no beat FX running paints nothing")
        assertEquals(0.0, sine.accumulatedScaledMs, "and advances nothing")
        rig.engine.processWallClockTick(T0 + 20)
        assertEquals(20.0, sine.accumulatedScaledMs, "the next pass advances the whole 20 ms")
        assertTrue(rig.dimmer > level, "and paints")
    }

    /** Throws on every evaluation. */
    private class Throwing : Fx {
        override val name = "Throwing"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput = error("effect blew up")
    }

    companion object {
        private const val T0 = 1_000_000L

        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }
}
