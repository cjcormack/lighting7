package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fx.Effect
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxOutputType
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sine wave oscillation for dimmer/slider values.
 *
 * Produces smooth, symmetric oscillation between min and max values.
 *
 * @param min Minimum value (default 0)
 * @param max Maximum value (default 255)
 */
data class SineWave(
    val min: UByte = 0u,
    val max: UByte = 255u
) : Effect {
    override val name = "Sine Wave"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString())

    override fun calculate(phase: Double): FxOutput {
        // Full sine cycle: 0 -> 1 -> 0 over phase 0..1
        val sineValue = sin(phase * 2 * PI)
        val normalized = (sineValue + 1.0) / 2.0  // Map -1..1 to 0..1
        val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized).toInt()
            .coerceIn(0, 255).toUByte()
        return FxOutput.Slider(value)
    }
}

/**
 * Ramp up then snap down (sawtooth wave).
 *
 * Value increases from min to max over the cycle, then snaps back to min.
 *
 * @param min Minimum value (default 0)
 * @param max Maximum value (default 255)
 * @param curve Easing curve for the ramp (default LINEAR)
 */
data class RampUp(
    val min: UByte = 0u,
    val max: UByte = 255u,
    val curve: EasingCurve = EasingCurve.LINEAR
) : Effect {
    override val name = "Ramp Up"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString(), "curve" to curve.name)

    override fun calculate(phase: Double): FxOutput {
        val easedPhase = curve.apply(phase)
        val value = (min.toInt() + (max.toInt() - min.toInt()) * easedPhase).toInt()
            .coerceIn(0, 255).toUByte()
        return FxOutput.Slider(value)
    }
}

/**
 * Ramp down then snap up (inverted sawtooth).
 *
 * Value decreases from max to min over the cycle, then snaps back to max.
 *
 * @param min Minimum value (default 0)
 * @param max Maximum value (default 255)
 * @param curve Easing curve for the ramp (default LINEAR)
 */
data class RampDown(
    val min: UByte = 0u,
    val max: UByte = 255u,
    val curve: EasingCurve = EasingCurve.LINEAR
) : Effect {
    override val name = "Ramp Down"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString(), "curve" to curve.name)

    override fun calculate(phase: Double): FxOutput {
        val easedPhase = curve.apply(1.0 - phase)
        val value = (min.toInt() + (max.toInt() - min.toInt()) * easedPhase).toInt()
            .coerceIn(0, 255).toUByte()
        return FxOutput.Slider(value)
    }
}

/**
 * Triangle wave - ramps up then ramps down.
 *
 * Value increases from min to max in the first half, then decreases back to min.
 *
 * @param min Minimum value (default 0)
 * @param max Maximum value (default 255)
 * @param curve Easing curve for both ramps (default LINEAR)
 */
data class Triangle(
    val min: UByte = 0u,
    val max: UByte = 255u,
    val curve: EasingCurve = EasingCurve.LINEAR
) : Effect {
    override val name = "Triangle"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString(), "curve" to curve.name)

    override fun calculate(phase: Double): FxOutput {
        val trianglePhase = if (phase < 0.5) {
            phase * 2  // First half: 0 -> 1
        } else {
            (1.0 - phase) * 2  // Second half: 1 -> 0
        }
        val easedPhase = curve.apply(trianglePhase)
        val value = (min.toInt() + (max.toInt() - min.toInt()) * easedPhase).toInt()
            .coerceIn(0, 255).toUByte()
        return FxOutput.Slider(value)
    }
}

/**
 * Pulse effect: quick fade up, hold, quick fade down.
 *
 * Creates an attack-hold-release envelope useful for beat-synced effects.
 *
 * @param min Minimum value (default 0)
 * @param max Maximum value (default 255)
 * @param attackRatio Portion of cycle for attack (default 0.1)
 * @param holdRatio Portion of cycle for hold at max (default 0.3)
 * @param curve Easing curve for attack/release (default QUAD_OUT)
 */
data class Pulse(
    val min: UByte = 0u,
    val max: UByte = 255u,
    val attackRatio: Double = 0.1,
    val holdRatio: Double = 0.3,
    val curve: EasingCurve = EasingCurve.QUAD_OUT
) : Effect {
    override val name = "Pulse"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf(
        "min" to min.toString(), "max" to max.toString(),
        "attackRatio" to attackRatio.toString(), "holdRatio" to holdRatio.toString(),
        "curve" to curve.name
    )

    private val releaseRatio = 1.0 - attackRatio - holdRatio

    override fun calculate(phase: Double): FxOutput {
        val value = when {
            phase < attackRatio -> {
                // Attack phase: fade up
                val attackPhase = phase / attackRatio
                curve.interpolate(min.toDouble(), max.toDouble(), attackPhase)
            }
            phase < attackRatio + holdRatio -> {
                // Hold phase: stay at max
                max.toDouble()
            }
            else -> {
                // Release phase: fade down
                val releasePhase = (phase - attackRatio - holdRatio) / releaseRatio
                curve.interpolate(max.toDouble(), min.toDouble(), releasePhase)
            }
        }
        return FxOutput.Slider(value.toInt().coerceIn(0, 255).toUByte())
    }
}

/**
 * Square wave - alternates between min and max.
 *
 * @param min Minimum value (default 0)
 * @param max Maximum value (default 255)
 * @param dutyCycle Ratio of time spent at max (0.0-1.0, default 0.5)
 */
data class SquareWave(
    val min: UByte = 0u,
    val max: UByte = 255u,
    val dutyCycle: Double = 0.5
) : Effect {
    override val name = "Square Wave"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString(), "dutyCycle" to dutyCycle.toString())

    override fun calculate(phase: Double): FxOutput {
        val value = if (phase < dutyCycle) max else min
        return FxOutput.Slider(value)
    }
}

/**
 * Strobe effect synchronized to beat.
 *
 * Quick flash at the start of each cycle.
 *
 * @param offValue Value when off (default 0)
 * @param onValue Value when on (default 255)
 * @param onRatio Portion of cycle that's "on" (default 0.1)
 */
data class Strobe(
    val offValue: UByte = 0u,
    val onValue: UByte = 255u,
    val onRatio: Double = 0.1
) : Effect {
    override val name = "Strobe"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("offValue" to offValue.toString(), "onValue" to onValue.toString(), "onRatio" to onRatio.toString())

    override fun calculate(phase: Double): FxOutput {
        val value = if (phase < onRatio) onValue else offValue
        return FxOutput.Slider(value)
    }
}

/**
 * Random flickering for fire/candle effects.
 *
 * Produces pseudo-random but deterministic flickering based on phase.
 *
 * @param min Minimum value (default 100)
 * @param max Maximum value (default 255)
 */
data class Flicker(
    val min: UByte = 100u,
    val max: UByte = 255u
) : Effect {
    override val name = "Flicker"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString())

    override fun calculate(phase: Double): FxOutput {
        // Use phase to seed pseudo-random but deterministic values
        // This ensures the same phase always produces the same value
        val random = sin(phase * 127.0) * kotlin.math.cos(phase * 311.0)
        val normalized = (random + 1.0) / 2.0

        val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized).toInt()
            .coerceIn(0, 255).toUByte()
        return FxOutput.Slider(value)
    }
}

/**
 * Breathe effect - slow, smooth fade in and out.
 *
 * Uses a sine-squared curve for a natural breathing feel.
 *
 * @param min Minimum value (default 0)
 * @param max Maximum value (default 255)
 */
data class Breathe(
    val min: UByte = 0u,
    val max: UByte = 255u
) : Effect {
    override val name = "Breathe"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("min" to min.toString(), "max" to max.toString())

    override fun calculate(phase: Double): FxOutput {
        // sin² curve for smooth breathing effect
        val sineValue = sin(phase * PI)
        val normalized = sineValue * sineValue  // Square for smoother curve

        val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized).toInt()
            .coerceIn(0, 255).toUByte()
        return FxOutput.Slider(value)
    }
}

/**
 * Static value - no animation.
 *
 * Holds a fixed slider/dimmer value. Useful for pinning a dimmer level
 * or as a base for additive blending.
 *
 * @param value The static value to output (default 255)
 */
data class StaticValue(
    val value: UByte = 255u
) : Effect {
    override val name = "Static Value"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("value" to value.toString())

    override fun calculate(phase: Double): FxOutput {
        return FxOutput.Slider(value)
    }
}

/**
 * Static setting - no animation.
 *
 * Holds a fixed setting level. Functionally identical to StaticValue
 * but uses "level" parameter and a distinct name so the frontend
 * can distinguish setting effects from slider effects.
 *
 * @param level The DMX level for the setting (default 0)
 */
data class StaticSetting(
    val level: UByte = 0u
) : Effect {
    override val name = "StaticSetting"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf("level" to level.toString())

    override fun calculate(phase: Double): FxOutput {
        return FxOutput.Slider(level)
    }
}
