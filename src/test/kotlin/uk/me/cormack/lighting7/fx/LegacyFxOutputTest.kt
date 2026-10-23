package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.sync.LegacyNames
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P4 of the terminology plan: a migrated show looks the same. Each FX §4.2 rewrites — a Breathe, a
 * `pingPong` sweep over a group with a spread and runs — is run tick by tick through the engine's own
 * phase, once as the old FX on its stored fields and once as today's FX on the fields
 * [LegacyNames.rewriteFx] writes, and every head's output compared.
 *
 * The old FX are their `.fx.kts` bodies as they were, which is why they are written out here.
 */
class LegacyFxOutputTest {

    private companion object {
        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInEffects(it) }
        }
        const val HEADS = 6
    }

    /** The old `pingPong` remap the five FX each wrote out. */
    private fun pingPong(phase: Double) = if (phase > 0.5) 1.0 - (phase - 0.5) * 2 else phase * 2

    /** [today] as it ran with `pingPong` on: today's body over the remapped phase. */
    private fun withPingPong(today: Effect) = object : Effect {
        override val name = "old ${today.name}"
        override val outputType = today.outputType
        override fun calculate(phase: Double, context: EffectContext) = today.calculate(pingPong(phase), context)
    }

    /** Breathe's body, `min + (max − min) sin²(πφ)`. */
    private fun breathe(min: Int, max: Int) = object : Effect {
        override val name = "Breathe"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: EffectContext): FxOutput {
            val s = sin(phase * PI)
            return FxOutput.Slider((min + (max - min) * s * s).toInt().coerceIn(0, 255).toUByte())
        }
    }

    private fun dynamicsOf(fx: LegacyNames.StoredFx) = FxDynamics(
        stepTiming = false,
        phaseOffset = fx.phaseOffset,
        distributionStrategy = DistributionStrategy.byName(fx.distribution)!!,
        direction = EffectDirection.byName(fx.direction)!!,
        nShot = fx.nShot,
        spread = fx.spread,
    )

    private fun tick(n: Long) = MasterClock.ClockTick(
        tickNumber = n,
        beatNumber = n / MasterClock.TICKS_PER_BEAT,
        tickInBeat = (n % MasterClock.TICKS_PER_BEAT).toInt(),
        phase = (n % MasterClock.TICKS_PER_BEAT).toDouble() / MasterClock.TICKS_PER_BEAT,
        timestampMs = n * 20L,
    )

    /** Every head's output of [effect] on [fx]'s fields at every tick in [ticks], null where it holds. */
    private fun run(effect: Effect, fx: LegacyNames.StoredFx, heads: Int, ticks: LongRange): List<List<FxOutput?>> {
        val dynamics = dynamicsOf(fx)
        val instance = FxInstance(effect, SliderTarget("front", "dimmer"), FxTiming(beatDivision = fx.beatDivision))
        val plan = DistributionPlan.forElements(dynamics.distributionStrategy, heads, SpreadShape(spread = dynamics.spread))
        return ticks.map { n ->
            (0 until heads).map { i ->
                val phase = instance.calculatePhaseForMember(tick(n), heads, dynamics, plan.offsets[i])
                if (phase.isNaN()) null else effect.calculate(phase, plan.contexts[i])
            }
        }
    }

    private fun FxOutput.levels(): List<Int> = when (this) {
        is FxOutput.Slider -> listOf(value.toInt())
        is FxOutput.Position -> listOf(pan.toInt(), tilt.toInt())
        is FxOutput.Colour -> color.color.let { listOf(it.red, it.green, it.blue) }
        else -> error("not compared: $this")
    }

    /** The two runs agree on whether each head holds, and its levels to within a step of rounding. */
    private fun assertSameOutput(old: List<List<FxOutput?>>, new: List<List<FxOutput?>>, what: String) {
        var compared = 0
        for ((t, heads) in old.withIndex()) {
            for ((i, before) in heads.withIndex()) {
                val after = new[t][i]
                assertEquals(before == null, after == null, "$what: tick $t head $i holds in one run only")
                if (before == null || after == null) continue
                val drift = before.levels().zip(after.levels()).maxOf { (a, b) -> abs(a - b) }
                assertTrue(drift <= 1, "$what: tick $t head $i was ${before.levels()}, is ${after.levels()}")
                compared++
            }
        }
        assertTrue(compared > 0, "$what compared nothing")
    }

    @Test
    fun `a Breathe and the Sine Wave it becomes land the same levels`() {
        val stored = LegacyNames.StoredFx("Breathe", "dimmer", 2.0, "LINEAR", 0.4, mapOf("min" to "20", "max" to "230"), "BACKWARD", null, 0.5)
        val rewritten = LegacyNames.rewriteFx(stored, LegacyNames.Context({ null }, { null }, absentPingPongIsOn = true))!!.fx
        val ticks = 0L until (MasterClock.TICKS_PER_BEAT * 2 * 3).toLong()
        assertSameOutput(
            run(breathe(20, 230), stored, HEADS, ticks),
            run(registry.createEffect(rewritten.effectType, rewritten.parameters), rewritten, HEADS, ticks),
            "Breathe",
        )
    }

    @Test
    fun `a pingPong Sweep over a group with a spread and two runs bounces the same way`() {
        val params = mapOf(
            "startPan" to "10", "startTilt" to "40", "endPan" to "245", "endTilt" to "200",
            "curve" to "SINE_IN_OUT", "pingPong" to "true",
        )
        val stored = LegacyNames.StoredFx("Sweep", "position", 4.0, "LINEAR", 0.3, params, "FORWARD", 2, 0.75)
        val rewritten = LegacyNames.rewriteFx(stored, LegacyNames.Context({ null }, { null }, absentPingPongIsOn = true))!!.fx
        assertEquals("BOUNCE", rewritten.direction)
        val today = registry.createEffect("Sweep", rewritten.parameters)
        // Past both runs and the stagger, so the hold after the last run is compared too.
        val ticks = 0L until (MasterClock.TICKS_PER_BEAT * 4 * 4).toLong()
        assertSameOutput(run(withPingPong(today), stored, HEADS, ticks), run(today, rewritten, HEADS, ticks), "Sweep")
    }

    @Test
    fun `a pingPong Colour Fade forever, reversed and offset, bounces the same way`() {
        val params = mapOf("fromColour" to "#ff0000", "toColour" to "#0000ff", "pingPong" to "true")
        val stored = LegacyNames.StoredFx("ColourFade", "colour", 1.0, "REVERSE", 0.8, params, "BACKWARD", null, 1.0)
        val rewritten = LegacyNames.rewriteFx(stored, LegacyNames.Context({ null }, { null }, absentPingPongIsOn = true))!!.fx
        val today = registry.createEffect("ColourFade", rewritten.parameters)
        val ticks = 0L until (MasterClock.TICKS_PER_BEAT * 5).toLong()
        assertSameOutput(run(withPingPong(today), stored, HEADS, ticks), run(today, rewritten, HEADS, ticks), "ColourFade")
    }
}
