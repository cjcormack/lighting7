package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An effect that throws out of every pass must not be retried 120 times a second forever: the
 * engine gives it [FxEngine.MAX_CONSECUTIVE_TICK_FAILURES] consecutive failed passes and then
 * pauses it, keeping the instance so the operator can fix the definition and resume.
 *
 * The run must be *consecutive* — a fault that clears itself (a fixture missing for the length
 * of a patch reload) must not accumulate towards a pause across the good ticks in between.
 */
class FxTickFailureTest {

    private val universe = Universe(0, 0)

    /** Throws on `calculate` while [failing] is set; otherwise emits a constant level. */
    private class FlakyEffect(@Volatile var failing: Boolean = true) : Effect {
        override val name = "Flaky"
        override val outputType = FxOutputType.SLIDER
        var calls = 0
            private set

        override fun calculate(phase: Double, context: EffectContext): FxOutput {
            calls++
            if (failing) throw IllegalStateException("effect blew up")
            return FxOutput.Slider(200u)
        }
    }

    private data class Rig(val controller: MockDmxController, val engine: FxEngine)

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
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    private fun flakyInstance(effect: FlakyEffect, timingSource: TimingSource) = FxInstance(
        effect = effect,
        target = SliderTarget("hex-a", "dimmer"),
        timing = FxTiming(beatDivision = BeatDivision.QUARTER),
    ).also { it.timingSource = timingSource }

    @Test
    fun `a beat effect that throws every pass is paused, not retried forever`() {
        val rig = newRig()
        val effect = FlakyEffect()
        val id = rig.engine.addEffect(flakyInstance(effect, TimingSource.BEAT))

        // One short of the threshold: still running, and still being called.
        for (n in 0L until (FxEngine.MAX_CONSECUTIVE_TICK_FAILURES - 1).toLong()) {
            rig.engine.processBeatTick(tick(n))
        }
        assertTrue(
            rig.engine.getEffect(id)!!.isRunning,
            "a short run of failures must not pause the effect — transient faults clear",
        )
        assertEquals(FxEngine.MAX_CONSECUTIVE_TICK_FAILURES - 1, effect.calls)

        rig.engine.processBeatTick(tick(FxEngine.MAX_CONSECUTIVE_TICK_FAILURES.toLong()))

        assertFalse(
            rig.engine.getEffect(id)!!.isRunning,
            "the effect must be paused once it has failed MAX_CONSECUTIVE_TICK_FAILURES passes",
        )

        // Paused, not removed: the instance survives for the operator to fix and resume, and
        // the engine stops calling it.
        val callsWhenPaused = effect.calls
        repeat(10) { rig.engine.processBeatTick(tick(500)) }
        assertEquals(callsWhenPaused, effect.calls, "a paused effect is not evaluated")
    }

    @Test
    fun `a wall-clock effect that throws every pass is paused too`() {
        val rig = newRig()
        val effect = FlakyEffect()
        val id = rig.engine.addEffect(flakyInstance(effect, TimingSource.WALL_CLOCK))

        repeat(FxEngine.MAX_CONSECUTIVE_TICK_FAILURES) { rig.engine.processWallClockTick() }

        assertFalse(rig.engine.getEffect(id)!!.isRunning, "the wall-clock path pauses on the same rule")
    }

    @Test
    fun `pausing an effect that died mid-group does not freeze the members it did paint`() {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val hexes = listOf(
                addFixture(HexFixture(universe, "hex-a", "Hex A", 1)),
                addFixture(HexFixture(universe, "hex-b", "Hex B", 13)),
            )
            createGroup<HexFixture>("wash") { addSpread(hexes) }
        }
        val engine = FxEngine(fixtures = fixtures)

        // Paints member 0 and blows up on member 1 — the shape a script effect indexing a
        // parameter list by member takes. Each pass therefore leaves hex-a painted.
        val effect = object : Effect {
            override val name = "Half Broken"
            override val outputType = FxOutputType.SLIDER
            override fun calculate(phase: Double, context: EffectContext): FxOutput {
                if (context.memberIndex > 0) throw IllegalStateException("no parameter for member")
                return FxOutput.Slider(200u)
            }
        }
        val id = engine.addEffect(
            FxInstance(
                effect = effect,
                target = SliderTarget.forGroup("wash", "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
            ),
        )

        for (n in 0L until (FxEngine.MAX_CONSECUTIVE_TICK_FAILURES - 1).toLong()) {
            engine.processBeatTick(tick(n))
        }
        assertEquals(200u.toUByte(), controller.currentValues[1], "hex-a is painted while it runs")

        engine.processBeatTick(tick(FxEngine.MAX_CONSECUTIVE_TICK_FAILURES.toLong()))

        assertFalse(engine.getEffect(id)!!.isRunning, "the effect is paused")
        assertEquals(
            0u.toUByte(), controller.currentValues[1],
            "the half-applied frame must not be left frozen on stage — later passes skip a " +
                "paused effect's reset, so nothing would ever move it",
        )
    }

    @Test
    fun `one good pass clears the failure run`() {
        val rig = newRig()
        val effect = FlakyEffect()
        val id = rig.engine.addEffect(flakyInstance(effect, TimingSource.BEAT))

        // Fail almost to the threshold, recover for one pass, then fail again for as long.
        // Nothing here should pause: no run is ever long enough.
        repeat(2) { round ->
            for (n in 0L until (FxEngine.MAX_CONSECUTIVE_TICK_FAILURES - 1).toLong()) {
                rig.engine.processBeatTick(tick(round * 1_000L + n))
            }
            effect.failing = false
            rig.engine.processBeatTick(tick(round * 1_000L + FxEngine.MAX_CONSECUTIVE_TICK_FAILURES))
            assertEquals(
                200u.toUByte(), rig.controller.currentValues[1],
                "the pass that succeeded must actually paint — the effect is still live",
            )
            effect.failing = true
        }

        assertTrue(
            rig.engine.getEffect(id)!!.isRunning,
            "failures either side of a good pass are two short runs, not one long one",
        )
    }
}
