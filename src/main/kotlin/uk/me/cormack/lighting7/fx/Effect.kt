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
 * Determines the timing source for an effect.
 *
 * BEAT effects are synchronized to the Master Clock's BPM-based ticks (24 per beat).
 * WALL_CLOCK effects run on a fixed-interval timer independent of BPM, suitable for
 * ambient/atmospheric effects (candle flicker, fluorescent flicker, etc.) that should
 * not be tied to the musical beat grid.
 */
enum class TimingSource {
    /** Synchronized to the Master Clock's BPM-based ticks */
    BEAT,
    /** Fixed-interval wall-clock timer (independent of BPM) */
    WALL_CLOCK,
}

/**
 * Determines how an effect's script is evaluated and what provided properties it receives.
 */
enum class EffectMode {
    /** Pure function of phase: receives (phase, context, params) → FxOutput */
    STANDARD,
    /** Maintains state across ticks: receives (tick, deltaMs, context, params, state) → FxOutput */
    STATEFUL,
    /** Produces multiple output types: receives (phase, context, params) → Map<FxOutputType, FxOutput> */
    COMPOSITE,
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
     * Default step-timing mode for new instances of this effect.
     *
     * When step timing is enabled and the effect is distributed across N elements,
     * the beat division is multiplied by N so that each element gets one full
     * beat-division of time. For example, a quarter-note (1 beat) with 4 heads
     * means each head is active for 1 beat, giving a total chase time of 4 beats.
     *
     * When `false` (default), the beat division controls the total cycle time.
     * A quarter-note with 4 heads means the entire sweep completes in 1 beat.
     *
     * This is the default for new [FxInstance]s; the actual value can be overridden
     * per-instance via [FxInstance.stepTiming].
     */
    val defaultStepTiming: Boolean get() = false

    /**
     * Calculate the output value for the given phase.
     *
     * @param phase Position in the effect cycle, from 0.0 (start) to 1.0 (end)
     * @param context Information about the distribution group (size, member index)
     * @return The calculated output value
     */
    fun calculate(phase: Double, context: EffectContext = EffectContext.SINGLE): FxOutput
}

/**
 * A stateful effect that receives tick-level timing instead of just a phase.
 *
 * Unlike [Effect], which is a pure function of phase, stateful effects can maintain
 * internal state that evolves over time. This enables non-periodic effects like
 * candle flicker, organic drift, or effects that accumulate state.
 *
 * The FX engine detects this interface and calls [calculateStateful] instead of
 * [calculate] during processing.
 *
 * Example:
 * ```
 * class CandleFlicker(...) : StatefulEffect {
 *     private var currentLevel = 180.0
 *
 *     override fun calculateStateful(tick, deltaMs, context): FxOutput {
 *         currentLevel += (Random.nextGaussian() * 15)
 *         return FxOutput.Slider(currentLevel.coerceIn(100.0, 220.0).toUByte())
 *     }
 * }
 * ```
 */
interface StatefulEffect : Effect {
    /**
     * Called once when the effect is first added to the engine.
     * Use this to set up initial state.
     */
    fun initialize() {}

    /**
     * Calculate the output value using tick-level timing.
     *
     * @param tick The current clock tick with beat position and timestamp
     * @param deltaMs Milliseconds since the last tick (0 on first tick)
     * @param context Information about the distribution group (size, member index)
     * @return The calculated output value
     */
    fun calculateStateful(
        tick: MasterClock.ClockTick,
        deltaMs: Long,
        context: EffectContext = EffectContext.SINGLE,
    ): FxOutput

    /**
     * Fallback for code that calls [calculate] directly.
     * Returns a neutral value for the output type since stateful effects
     * require tick information to produce meaningful output.
     */
    override fun calculate(phase: Double, context: EffectContext): FxOutput {
        return when (outputType) {
            FxOutputType.SLIDER -> FxOutput.Slider(0u)
            FxOutputType.COLOUR -> FxOutput.Colour(ExtendedColour.BLACK)
            FxOutputType.POSITION -> FxOutput.Position(128u, 128u)
        }
    }
}

/**
 * A composite effect that produces outputs for multiple property types simultaneously.
 *
 * Unlike [Effect], which targets a single output type, composite effects can coordinate
 * multiple properties together (e.g., a lightning strike that controls dimmer + colour).
 *
 * The [outputType] should be set to the primary output type. The [outputTypes] set
 * declares all output types this effect can produce.
 *
 * When applied via the FX engine, each output type is routed to its corresponding
 * [FxTarget] stored in [FxInstance.compositeTargets].
 *
 * Example:
 * ```
 * class LightningStrike : CompositeEffect {
 *     override val outputTypes = setOf(FxOutputType.SLIDER, FxOutputType.COLOUR)
 *
 *     override fun calculateComposite(phase: Double, context: EffectContext): Map<FxOutputType, FxOutput> {
 *         val intensity = if (phase < 0.1) 255 else (255 * (1.0 - phase)).toInt()
 *         return mapOf(
 *             FxOutputType.SLIDER to FxOutput.Slider(intensity.toUByte()),
 *             FxOutputType.COLOUR to FxOutput.Colour(blendExtendedColours(white, blue, phase))
 *         )
 *     }
 * }
 * ```
 */
interface CompositeEffect : Effect {
    /** All output types this effect produces. */
    val outputTypes: Set<FxOutputType>

    /**
     * Calculate outputs for all target property types.
     *
     * @param phase Position in the effect cycle, from 0.0 (start) to 1.0 (end)
     * @param context Information about the distribution group (size, member index)
     * @return Map of output type to calculated value
     */
    fun calculateComposite(
        phase: Double,
        context: EffectContext = EffectContext.SINGLE,
    ): Map<FxOutputType, FxOutput>

    /**
     * Default implementation returns the primary output type from the composite map.
     */
    override fun calculate(phase: Double, context: EffectContext): FxOutput =
        calculateComposite(phase, context)[outputType]
            ?: error("CompositeEffect did not produce output for primary type $outputType")
}

/** Serialize a Color to a hex string (e.g., "#ff0000") */
internal fun Color.toHexString(): String = "#%02x%02x%02x".format(red, green, blue)

// --- Palette reference support ---

/** Regex for palette reference syntax: P followed by one or more digits (1-indexed) */
private val PALETTE_REF_REGEX = Regex("^P(\\d+)$", RegexOption.IGNORE_CASE)

/** Check if a colour string is a palette reference (e.g., "P1", "P2") or the all-palette wildcard "P*". */
fun isPaletteRef(value: String): Boolean {
    val trimmed = value.trim()
    return PALETTE_REF_REGEX.matchEntire(trimmed) != null || trimmed.equals("P*", ignoreCase = true)
}

/** Check if a colour string is the "all palette colours" wildcard ("P*"). */
fun isAllPaletteRef(value: String): Boolean = value.trim().equals("P*", ignoreCase = true)

/**
 * Resolve a colour string that may be a palette reference.
 *
 * - `"P1"`, `"P2"`, etc. → resolved from the palette (1-indexed, wrapping via modulo)
 * - Anything else → parsed as a normal extended colour via [parseExtendedColour]
 *
 * If the palette is empty and the value is a palette ref, falls back to [parseExtendedColour]
 * which will return WHITE for unrecognised input.
 */
fun resolveColour(value: String, palette: List<ExtendedColour>): ExtendedColour {
    val match = PALETTE_REF_REGEX.matchEntire(value.trim())
    if (match != null && palette.isNotEmpty()) {
        val index = match.groupValues[1].toInt()
        return palette[(index - 1).mod(palette.size)]
    }
    return parseExtendedColour(value)
}

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

    /**
     * Scale this output by a multiplier (0.0–1.0) for crossfade transitions.
     *
     * - Slider: scales the value toward 0
     * - Colour: scales RGB/W/A/UV toward black
     * - Position: no scaling (position snaps, doesn't fade)
     */
    fun scaled(multiplier: Double): FxOutput {
        if (multiplier >= 1.0) return this
        if (multiplier <= 0.0) return when (this) {
            is Slider -> Slider(0u)
            is Colour -> Colour(ExtendedColour.fromColor(Color.BLACK))
            is Position -> this // Position doesn't fade
        }
        return when (this) {
            is Slider -> Slider((value.toInt() * multiplier).toInt().coerceIn(0, 255).toUByte())
            is Colour -> {
                val c = color.color
                val scaledColor = Color(
                    (c.red * multiplier).toInt().coerceIn(0, 255),
                    (c.green * multiplier).toInt().coerceIn(0, 255),
                    (c.blue * multiplier).toInt().coerceIn(0, 255),
                )
                val scaledExt = ExtendedColour(
                    scaledColor,
                    white = (color.white.toInt() * multiplier).toInt().coerceIn(0, 255).toUByte(),
                    amber = (color.amber.toInt() * multiplier).toInt().coerceIn(0, 255).toUByte(),
                    uv = (color.uv.toInt() * multiplier).toInt().coerceIn(0, 255).toUByte(),
                )
                Colour(scaledExt)
            }
            is Position -> this // Position doesn't fade
        }
    }
}
