package uk.me.cormack.lighting7.fx

import java.awt.Color

/**
 * Context about the group/distribution environment an effect is being calculated within.
 *
 * This allows effects to adapt their behaviour based on how many elements they're
 * distributed across (e.g., static effects can auto-window to `1/groupSize`).
 *
 * @param groupSize Total number of elements being distributed across (1 for a single fixture)
 * @param memberIndex 0-based index of the current element within the group
 * @param distributionOffset The phase offset applied by the distribution strategy for this member (0.0–1.0)
 * @param hasDistributionSpread Whether the distribution strategy produces different offsets
 *        for different members. False for UNIFIED (all offsets 0), true for LINEAR, CENTER_OUT, etc.
 *        Effects use this to decide whether to chase (windowed) or apply uniformly.
 * @param numDistinctSlots Number of unique offset positions in the distribution. Equals
 *        groupSize for asymmetric distributions (LINEAR, REVERSE, RANDOM), but fewer for
 *        symmetric ones (CENTER_OUT, SPLIT) where multiple members share an offset.
 *        Static effects use this for window width: `1/numDistinctSlots`.
 * @param trianglePhase When true, the base phase should be remapped through a triangle wave
 *        before windowing, so the chase sweeps forward then backward (PING_PONG).
 */
data class EffectContext(
    val groupSize: Int,
    val memberIndex: Int,
    val distributionOffset: Double = 0.0,
    val hasDistributionSpread: Boolean = false,
    val numDistinctSlots: Int = groupSize,
    val trianglePhase: Boolean = false
) {
    /**
     * Recover the base (un-shifted) phase from a distribution-shifted phase.
     *
     * Since the member phase is computed as `(clock - distOffset) % 1.0`,
     * recovery adds the offset back: `(phase + distOffset) % 1.0`.
     *
     * Useful for effects that need to window based on absolute cycle position
     * rather than the distribution-shifted position (e.g., static chase effects).
     */
    fun basePhase(shiftedPhase: Double): Double = (shiftedPhase + distributionOffset) % 1.0

    companion object {
        /** Default context for a single fixture (no distribution). */
        val SINGLE = EffectContext(groupSize = 1, memberIndex = 0)
    }
}

/**
 * Base interface for all effect types.
 *
 * Effects are pure functions: given a phase (0.0-1.0) and an [EffectContext],
 * they return an output value. This allows effects to be stateless and easily composable.
 *
 * Example implementation:
 * ```
 * data class SineWave(val min: UByte = 0u, val max: UByte = 255u) : Effect {
 *     override val name = "Sine Wave"
 *     override val outputType = FxOutputType.SLIDER
 *
 *     override fun calculate(phase: Double, context: EffectContext): FxOutput {
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
     * @param context Information about the distribution group (size, member index)
     * @return The calculated output value
     */
    fun calculate(phase: Double, context: EffectContext = EffectContext.SINGLE): FxOutput
}

/** Serialize a Color to a hex string (e.g., "#ff0000") */
internal fun Color.toHexString(): String = "#%02x%02x%02x".format(red, green, blue)

/**
 * Extended colour with optional white, amber, and UV channels.
 *
 * Wraps a standard RGB [Color] with additional channels for fixtures
 * that have dedicated white, amber, or UV LEDs (e.g., RGBWAU fixtures).
 *
 * Serialization format: `#rrggbb[;wNNN][;aNNN][;uvNNN]`
 * - Only non-zero extended channels are included in the serialized form
 * - Backward compatible: plain `#rrggbb` parses as ExtendedColour with W/A/UV = 0
 */
data class ExtendedColour(
    val color: Color,
    val white: UByte = 0u,
    val amber: UByte = 0u,
    val uv: UByte = 0u,
) {
    /** Serialize to the extended colour format */
    fun toSerializedString(): String {
        val hex = color.toHexString()
        val parts = mutableListOf(hex)
        if (white > 0u) parts.add("w${white}")
        if (amber > 0u) parts.add("a${amber}")
        if (uv > 0u) parts.add("uv${uv}")
        return parts.joinToString(";")
    }

    companion object {
        fun fromColor(color: Color) = ExtendedColour(color)
        val BLACK = ExtendedColour(Color.BLACK)
        val WHITE = ExtendedColour(Color.BLACK, white = 255u)
    }
}

/**
 * Linearly interpolate between two [ExtendedColour] values.
 *
 * All channels (R, G, B, W, A, UV) are blended independently.
 */
fun blendExtendedColours(c1: ExtendedColour, c2: ExtendedColour, ratio: Double): ExtendedColour {
    fun lerp(a: Int, b: Int): Int = (a + (b - a) * ratio).toInt().coerceIn(0, 255)
    fun lerpU(a: UByte, b: UByte): UByte = lerp(a.toInt(), b.toInt()).toUByte()

    return ExtendedColour(
        color = Color(
            lerp(c1.color.red, c2.color.red),
            lerp(c1.color.green, c2.color.green),
            lerp(c1.color.blue, c2.color.blue),
        ),
        white = lerpU(c1.white, c2.white),
        amber = lerpU(c1.amber, c2.amber),
        uv = lerpU(c1.uv, c2.uv),
    )
}

/**
 * Types of output an effect can produce.
 */
enum class FxOutputType {
    /** Single DMX value (0-255) for sliders/dimmers */
    SLIDER,
    /** RGB color value (with optional W/A/UV) */
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
     * Extended colour value (RGB + optional W/A/UV).
     */
    data class Colour(val color: ExtendedColour) : FxOutput {
        /** Convenience constructor for plain RGB colours */
        constructor(color: Color) : this(ExtendedColour.fromColor(color))
    }

    /**
     * Pan/tilt position values.
     */
    data class Position(val pan: UByte, val tilt: UByte) : FxOutput
}
