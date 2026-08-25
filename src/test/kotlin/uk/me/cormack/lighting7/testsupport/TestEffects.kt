package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.fx.Effect
import uk.me.cormack.lighting7.fx.EffectContext
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.blendExtendedColours
import java.awt.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Effect implementations for the engine tests and the FX benchmark.
 *
 * The desk's own effects are the 28 `.fx.kts` resources in `src/main/resources/fx/`, compiled at
 * startup into the [uk.me.cormack.lighting7.fx.FxRegistry]. Engine tests do not want those: they
 * need *an* effect that produces a known value so an assertion can be about the engine — target
 * expansion, blending, priority, provenance — and reaching them through the registry would make
 * every such test pay for script compilation and couple it to effect maths it isn't testing.
 * Behaviour of the real built-ins is covered against the real registry in
 * `fx/BuiltInEffectBehaviourTest`.
 *
 * These are also deliberately **frozen**. [FxEngineBenchmark][uk.me.cormack.lighting7.fx.FxEngineBenchmark]'s
 * scenario 1 is comparable to its recorded baseline only if the work inside the measured loop
 * stays put, and effect maths is inside that loop. Keeping the benchmark's effects here rather
 * than in the registry is what lets the built-ins evolve without silently moving the numbers.
 * Do not "improve" the maths below; add a new type instead.
 *
 * The maths matches the `fx.effects` classes retired by sweep item D7, which is what the
 * pre-existing baselines and assertions were taken against.
 */

/** Sine oscillation between [min] and [max]; phase 0 starts at the midpoint rising. */
data class SineSlider(
    val min: UByte = 0u,
    val max: UByte = 255u,
) : Effect {
    override val name = "Sine Slider"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString())

    override fun calculate(phase: Double, context: EffectContext): FxOutput {
        val normalized = (sin(phase * 2 * PI) + 1.0) / 2.0
        val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized).toInt()
            .coerceIn(0, 255).toUByte()
        return FxOutput.Slider(value)
    }
}

/**
 * A constant slider value that auto-windows across a distributed group: a member emits [value]
 * only while the phase is inside its own slot, and 0 otherwise. That windowing is what makes a
 * "static" level read as a chase when spread over group members, and several engine tests depend
 * on it — hence `defaultStepTiming = true`, matching the built-in `StaticValue`.
 */
data class WindowedSlider(
    val value: UByte = 255u,
) : Effect {
    override val name = "Windowed Slider"
    override val outputType = FxOutputType.SLIDER
    override val defaultStepTiming = true
    override val parameters get() = mapOf("value" to value.toString())

    override fun calculate(phase: Double, context: EffectContext): FxOutput {
        if (context.groupSize <= 1) return FxOutput.Slider(value)
        if (!context.hasDistributionSpread) return FxOutput.Slider(value)

        val window = 1.0 / context.numDistinctSlots
        val base = context.basePhase(phase)

        if (context.trianglePhase) {
            val dist = abs(base - context.distributionOffset)
            return if (dist < window / 2.0) FxOutput.Slider(value) else FxOutput.Slider(0u)
        }

        val dist = (base - context.distributionOffset + 1.0) % 1.0
        return if (dist < window) FxOutput.Slider(value) else FxOutput.Slider(0u)
    }
}

/** [WindowedSlider]'s colour twin: [colour] inside the member's slot, black outside it. */
data class WindowedColour(
    val colour: ExtendedColour,
) : Effect {
    override val name = "Windowed Colour"
    override val outputType = FxOutputType.COLOUR
    override val defaultStepTiming = true
    override val parameters get() = mapOf("colour" to colour.toSerializedString())

    override fun calculate(phase: Double, context: EffectContext): FxOutput {
        if (context.groupSize <= 1) return FxOutput.Colour(colour)
        if (!context.hasDistributionSpread) return FxOutput.Colour(colour)

        val window = 1.0 / context.numDistinctSlots
        val base = context.basePhase(phase)

        // PING_PONG: the triangle remap is already applied at the phase level, so compare by
        // absolute distance — modular wrapping hits a floating-point edge at the far offset.
        if (context.trianglePhase) {
            val dist = abs(base - context.distributionOffset)
            return if (dist < window / 2.0) FxOutput.Colour(colour) else FxOutput.Colour(ExtendedColour.BLACK)
        }

        val dist = (base - context.distributionOffset + 1.0) % 1.0
        return if (dist < window) FxOutput.Colour(colour) else FxOutput.Colour(ExtendedColour.BLACK)
    }
}

/** Full hue rotation over one cycle at fixed [saturation] and [brightness]. */
data class HueSweepColour(
    val saturation: Float = 1.0f,
    val brightness: Float = 1.0f,
) : Effect {
    override val name = "Hue Sweep"
    override val outputType = FxOutputType.COLOUR
    override val parameters
        get() = mapOf("saturation" to saturation.toString(), "brightness" to brightness.toString())

    override fun calculate(phase: Double, context: EffectContext): FxOutput =
        FxOutput.Colour(Color.getHSBColor(phase.toFloat(), saturation, brightness))
}

/**
 * Steps through [colours], holding each for its share of the cycle and crossfading over the last
 * [fadeRatio] of that share. Used by the benchmark's colour scenarios because it does per-tick
 * blending work, which a constant colour does not.
 */
data class SteppedColour(
    val colours: List<ExtendedColour>,
    val fadeRatio: Double = 0.5,
) : Effect {
    override val name = "Stepped Colour"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf(
        "colours" to colours.joinToString(",") { it.toSerializedString() },
        "fadeRatio" to fadeRatio.toString(),
    )

    override fun calculate(phase: Double, context: EffectContext): FxOutput {
        if (colours.isEmpty()) return FxOutput.Colour(ExtendedColour.BLACK)
        if (colours.size == 1) return FxOutput.Colour(colours[0])

        val segmentSize = 1.0 / colours.size
        val segmentIndex = (phase / segmentSize).toInt().coerceIn(0, colours.size - 1)
        val segmentPhase = (phase - segmentIndex * segmentSize) / segmentSize

        val currentColour = colours[segmentIndex]
        val nextColour = colours[(segmentIndex + 1) % colours.size]

        val holdPortion = 1.0 - fadeRatio
        val colour = if (segmentPhase < holdPortion) {
            currentColour
        } else {
            blendExtendedColours(currentColour, nextColour, (segmentPhase - holdPortion) / fadeRatio)
        }
        return FxOutput.Colour(colour)
    }
}
