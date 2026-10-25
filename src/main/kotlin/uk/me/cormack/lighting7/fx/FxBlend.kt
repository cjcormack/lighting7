package uk.me.cormack.lighting7.fx

import java.awt.Color
import kotlin.math.roundToInt

/**
 * An FX's blend and weight (effects plan P3) as pure functions of what is underneath, the FX's
 * output on one head, its [BlendMode] and its weight: what every [FxTarget.applyValueToFixture]
 * writes, and what [FxSampler] folds. One copy, so a preview cannot drift from the stage.
 */
internal object FxBlend {
    /** An FX's weight on one pass (P3): its Amount × its envelope × its stack's FX amount (D10), in `[0, 1]`. */
    fun weight(amount: Double, envelope: Double, stackAmount: Double): Double =
        (amount * envelope * stackAmount).coerceIn(0.0, 1.0)

    /** A byte channel — a slider, or a colour's bundled W, A or UV — blended, then weighed. */
    fun slider(before: UByte, output: UByte, blend: BlendMode, weight: Double): UByte =
        weigh(before, blendByte(before, output, blend), weight)

    /** RGB, blended, then each channel weighed on its own. */
    fun rgb(before: Color, output: Color, blend: BlendMode, weight: Double): Color {
        val blended = blendRgb(before, output, blend)
        return if (weight >= 1.0) blended else Color(
            weigh(before.red, blended.red, weight),
            weigh(before.green, blended.green, weight),
            weigh(before.blue, blended.blue, weight),
        )
    }

    /**
     * One axis of a position. An *Around* FX (ADDITIVE) moves about the centre, 128, so weighed after
     * its blend it has its offset scaled: a circle at 50 % has half its radius about what is underneath.
     */
    fun positionAxis(before: UByte, output: UByte, blend: BlendMode, weight: Double): UByte =
        weigh(before, blendAxis(before, output, blend), weight)

    /** A setting's slot: no blend and no halfway, so the FX's level from half weight up ([weighDiscrete]). */
    fun setting(before: UByte, output: UByte, weight: Double): UByte = if (weighDiscrete(weight)) output else before

    private fun blendByte(base: UByte, effect: UByte, mode: BlendMode): UByte = when (mode) {
        BlendMode.OVERRIDE -> effect
        BlendMode.ADDITIVE -> (base.toInt() + effect.toInt()).coerceIn(0, 255).toUByte()
        BlendMode.MULTIPLY -> ((base.toInt() * effect.toInt()) / 255).toUByte()
        BlendMode.MAX -> maxOf(base, effect)
        BlendMode.MIN -> minOf(base, effect)
    }

    private fun blendAxis(base: UByte, effect: UByte, mode: BlendMode): UByte = when (mode) {
        BlendMode.ADDITIVE -> (base.toInt() + effect.toInt() - 128).coerceIn(0, 255).toUByte()
        else -> blendByte(base, effect, mode)
    }

    private fun blendRgb(base: Color, effect: Color, mode: BlendMode): Color = when (mode) {
        BlendMode.OVERRIDE -> effect
        BlendMode.ADDITIVE -> Color(
            (base.red + effect.red).coerceIn(0, 255),
            (base.green + effect.green).coerceIn(0, 255),
            (base.blue + effect.blue).coerceIn(0, 255),
        )
        BlendMode.MULTIPLY -> Color(
            (base.red * effect.red) / 255,
            (base.green * effect.green) / 255,
            (base.blue * effect.blue) / 255,
        )
        BlendMode.MAX -> Color(
            maxOf(base.red, effect.red),
            maxOf(base.green, effect.green),
            maxOf(base.blue, effect.blue),
        )
        BlendMode.MIN -> Color(
            minOf(base.red, effect.red),
            minOf(base.green, effect.green),
            minOf(base.blue, effect.blue),
        )
    }
}

/**
 * One channel of an effect weighted over what is underneath (effects plan P3): `before + (blended −
 * before) × weight`, rounded, applied after the blend. Weight 1 is the blend as it was; 0 is
 * [before] untouched.
 */
internal fun weigh(before: Int, blended: Int, weight: Double): Int = when {
    weight >= 1.0 -> blended
    weight <= 0.0 -> before
    else -> (before + (blended - before) * weight).roundToInt().coerceIn(0, 255)
}

/** [weigh] for a byte channel. */
internal fun weigh(before: UByte, blended: UByte, weight: Double): UByte =
    if (weight >= 1.0) blended else weigh(before.toInt(), blended.toInt(), weight).toUByte()

/**
 * Whether a discrete channel — a setting's slot — takes an effect at [weight]: from half up. A slot
 * cannot sit between two values, so P3's weight becomes a threshold there, and a Size or (session 4)
 * an envelope moving through 0.5 switches it, as a cue fade switches a discrete value part-way.
 */
internal fun weighDiscrete(weight: Double): Boolean = weight >= 0.5
