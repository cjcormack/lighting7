package uk.me.cormack.lighting7.fx

import java.awt.Color

/**
 * Base interface for all effect types.
 *
 * Effects are pure functions: given a phase (0.0-1.0), they return an output value.
 * This allows effects to be stateless and easily composable.
 *
 * Example implementation:
 * ```
 * data class SineWave(val min: UByte = 0u, val max: UByte = 255u) : Effect {
 *     override val name = "Sine Wave"
 *     override val outputType = FxOutputType.SLIDER
 *
 *     override fun calculate(phase: Double): FxOutput {
 *         val sineValue = sin(phase * 2 * PI)
 *         val normalized = (sineValue + 1.0) / 2.0
 *         val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized).toInt().toUByte()
 *         return FxOutput.Slider(value)
 *     }
 * }
 * ```
 */
interface Effect {
    /** Human-readable name for this effect */
    val name: String

    /** The type of output this effect produces */
    val outputType: FxOutputType

    /**
     * Returns the current parameters of this effect as a serializable map.
     * Keys are parameter names, values are string representations.
     * This allows running effects to report their configuration for UI display/editing.
     */
    val parameters: Map<String, String>
        get() = emptyMap()

    /**
     * Calculate the output value for the given phase.
     *
     * @param phase Position in the effect cycle, from 0.0 (start) to 1.0 (end)
     * @return The calculated output value
     */
    fun calculate(phase: Double): FxOutput
}

/** Serialize a Color to a hex string (e.g., "#ff0000") */
internal fun Color.toHexString(): String = "#%02x%02x%02x".format(red, green, blue)

/**
 * Types of output an effect can produce.
 */
enum class FxOutputType {
    /** Single DMX value (0-255) for sliders/dimmers */
    SLIDER,
    /** RGB color value */
    COLOUR,
    /** Pan/tilt position values */
    POSITION
}

/**
 * Output value from an effect calculation.
 */
sealed interface FxOutput {
    /**
     * Single slider/dimmer value.
     */
    data class Slider(val value: UByte) : FxOutput

    /**
     * RGB color value.
     */
    data class Colour(val color: Color) : FxOutput

    /**
     * Pan/tilt position values.
     */
    data class Position(val pan: UByte, val tilt: UByte) : FxOutput
}
