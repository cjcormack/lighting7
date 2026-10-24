package uk.me.cormack.lighting7.fx

import java.awt.Color

/**
 * Context about the group/distribution environment an effect is being calculated within.
 *
 * This allows effects to adapt their behaviour based on how many elements they're
 * distributed across (e.g., a step chase can auto-window to `1/groupSize`).
 *
 * @param groupSize Total number of elements being distributed across (1 for a single fixture)
 * @param memberIndex 0-based index of the current element within the group
 * @param distributionOffset The phase offset applied by the distribution strategy for this member (0.0–1.0)
 * @param hasDistributionSpread Whether the distribution strategy produces different offsets
 *        for different members. False for UNIFIED (all offsets 0), true for NORMAL, CENTRE_OUT, etc.
 *        Effects use this to decide whether to chase (windowed) or apply uniformly.
 * @param numDistinctSlots Number of unique offset positions in the distribution. Equals
 *        groupSize for asymmetric distributions (NORMAL, REVERSE, RANDOM), but fewer for
 *        symmetric ones (CENTRE_OUT, CENTRE_IN) where multiple members share an offset.
 *        A step chase uses this for window width: `1/numDistinctSlots`.
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
     * The registry id this effect was made under, where the registry made it, which [name] need not
     * match. Null for an effect a script constructs itself.
     */
    val registrationId: String?
        get() = null

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
     * Default [EffectDirection] for new instances: Bounce for an FX written as one way of a there and
     * back (Colour Fade, Focus Pull, the sweeps), else Forward. Read from the frontmatter's
     * `defaultDirection`, as [defaultStepTiming] is from `defaultStepTiming`.
     */
    val defaultDirection: EffectDirection get() = EffectDirection.FORWARD

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
            FxOutputType.STEPS -> FxOutput.Steps(0, 0, 0.0, StepList.EMPTY)
        }
    }
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
    POSITION,
    /**
     * Two steps of a list and how far between them ([FxOutput.Steps], effects plan §3.6): what
     * Palette Steps and Slot Steps emit. Every target accepts it ([FxTarget.accepts]) — the step
     * is resolved per head by the target, into whatever that target applies.
     */
    STEPS,
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
     * Two steps of [steps] and how far between them (effects plan §3.6): at [blend] 0 the head
     * shows step [from], at 1 step [to]. No output says what a step *is* on a given head, which is
     * the point — the **target** resolves each step for the head in hand
     * ([StepList.valueFor]) and blends the two by its property's own rule: a level, a colour or a
     * position in proportion, a setting by snapping. A head that cannot take a step holds what is
     * underneath for it.
     */
    data class Steps(val from: Int, val to: Int, val blend: Double, val steps: StepList) : FxOutput
}
