package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.*
import java.awt.Color

/**
 * Cycle through a list of colours.
 *
 * Each colour is displayed for an equal portion of the cycle, with optional
 * crossfade between colours.
 *
 * @param colours List of colours to cycle through
 * @param fadeRatio Ratio of each segment spent crossfading (0 = hard cut, 1 = full crossfade)
 */
data class ColourCycle(
    val colours: List<ExtendedColour>,
    val fadeRatio: Double = 0.5
) : Effect {
    override val name = "Colour Cycle"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf(
        "colours" to colours.joinToString(",") { it.toSerializedString() },
        "fadeRatio" to fadeRatio.toString()
    )

    override fun calculate(phase: Double): FxOutput {
        if (colours.isEmpty()) return FxOutput.Colour(ExtendedColour.BLACK)
        if (colours.size == 1) return FxOutput.Colour(colours[0])

        val segmentSize = 1.0 / colours.size
        val segmentIndex = (phase / segmentSize).toInt().coerceIn(0, colours.size - 1)
        val segmentPhase = (phase - segmentIndex * segmentSize) / segmentSize

        val currentColour = colours[segmentIndex]
        val nextColour = colours[(segmentIndex + 1) % colours.size]

        // Calculate fade within segment
        val holdPortion = 1.0 - fadeRatio
        val color = if (segmentPhase < holdPortion) {
            currentColour
        } else {
            val fadePhase = (segmentPhase - holdPortion) / fadeRatio
            blendExtendedColours(currentColour, nextColour, fadePhase)
        }

        return FxOutput.Colour(color)
    }

    companion object {
        /** Primary colours: Red, Green, Blue */
        val PRIMARY = ColourCycle(listOf(Color.RED, Color.GREEN, Color.BLUE).map { ExtendedColour.fromColor(it) })

        /** Warm colours: Red, Orange, Yellow */
        val WARM = ColourCycle(listOf(Color.RED, Color.ORANGE, Color.YELLOW).map { ExtendedColour.fromColor(it) })

        /** Cool colours: Cyan, Blue, Magenta */
        val COOL = ColourCycle(listOf(Color.CYAN, Color.BLUE, Color.MAGENTA).map { ExtendedColour.fromColor(it) })

        /** Fire colours: Red, Orange, Yellow */
        val FIRE = ColourCycle(listOf(
            Color(255, 0, 0),
            Color(255, 100, 0),
            Color(255, 200, 0),
            Color(255, 100, 0)
        ).map { ExtendedColour.fromColor(it) }, fadeRatio = 0.8)
    }
}

/**
 * Rainbow hue rotation.
 *
 * Smoothly cycles through all hues of the colour spectrum.
 * This is inherently RGB-only (HSB rotation), so W/A/UV channels remain 0.
 *
 * @param saturation Colour saturation (0.0-1.0, default 1.0)
 * @param brightness Colour brightness (0.0-1.0, default 1.0)
 */
data class RainbowCycle(
    val saturation: Float = 1.0f,
    val brightness: Float = 1.0f
) : Effect {
    override val name = "Rainbow Cycle"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf("saturation" to saturation.toString(), "brightness" to brightness.toString())

    override fun calculate(phase: Double): FxOutput {
        val color = Color.getHSBColor(phase.toFloat(), saturation, brightness)
        return FxOutput.Colour(color)
    }
}

/**
 * Colour strobe effect synchronized to beat.
 *
 * Alternates between on and off colours.
 *
 * @param onColor Colour when "on" (default WHITE)
 * @param offColor Colour when "off" (default BLACK)
 * @param onRatio Portion of cycle that's "on" (default 0.1)
 */
data class ColourStrobe(
    val onColor: ExtendedColour = ExtendedColour.fromColor(Color.WHITE),
    val offColor: ExtendedColour = ExtendedColour.BLACK,
    val onRatio: Double = 0.1
) : Effect {
    override val name = "Colour Strobe"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf("onColor" to onColor.toSerializedString(), "offColor" to offColor.toSerializedString(), "onRatio" to onRatio.toString())

    override fun calculate(phase: Double): FxOutput {
        val color = if (phase < onRatio) onColor else offColor
        return FxOutput.Colour(color)
    }
}

/**
 * Pulse between two colours.
 *
 * Fades from one colour to another and back.
 *
 * @param colorA First colour (default BLACK)
 * @param colorB Second colour (default WHITE)
 */
data class ColourPulse(
    val colorA: ExtendedColour = ExtendedColour.BLACK,
    val colorB: ExtendedColour = ExtendedColour.fromColor(Color.WHITE)
) : Effect {
    override val name = "Colour Pulse"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf("colorA" to colorA.toSerializedString(), "colorB" to colorB.toSerializedString())

    override fun calculate(phase: Double): FxOutput {
        // Use sine wave for smooth pulse
        val sineValue = kotlin.math.sin(phase * 2 * kotlin.math.PI)
        val ratio = (sineValue + 1.0) / 2.0

        return FxOutput.Colour(blendExtendedColours(colorA, colorB, ratio))
    }
}

/**
 * Colour fade between two colours.
 *
 * Linear transition from one colour to another.
 *
 * @param fromColor Starting colour
 * @param toColor Ending colour
 * @param pingPong If true, fades back to start (default true)
 */
data class ColourFade(
    val fromColor: ExtendedColour,
    val toColor: ExtendedColour,
    val pingPong: Boolean = true
) : Effect {
    override val name = "Colour Fade"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf("fromColor" to fromColor.toSerializedString(), "toColor" to toColor.toSerializedString(), "pingPong" to pingPong.toString())

    override fun calculate(phase: Double): FxOutput {
        val ratio = if (pingPong && phase > 0.5) {
            1.0 - (phase - 0.5) * 2
        } else if (pingPong) {
            phase * 2
        } else {
            phase
        }

        return FxOutput.Colour(blendExtendedColours(fromColor, toColor, ratio))
    }
}

/**
 * Random colour flickering.
 *
 * Produces pseudo-random colour variations around a base colour.
 *
 * @param baseColor The base colour to vary around
 * @param variation Maximum channel variation (0-255, default 50)
 */
data class ColourFlicker(
    val baseColor: ExtendedColour,
    val variation: Int = 50
) : Effect {
    override val name = "Colour Flicker"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf("baseColor" to baseColor.toSerializedString(), "variation" to variation.toString())

    override fun calculate(phase: Double): FxOutput {
        // Pseudo-random but deterministic variations
        fun vary(base: Int, seed: Double): Int {
            val v = (kotlin.math.sin(phase * seed) * variation).toInt()
            return (base + v).coerceIn(0, 255)
        }

        return FxOutput.Colour(ExtendedColour(
            color = Color(
                vary(baseColor.color.red, 127.0),
                vary(baseColor.color.green, 211.0),
                vary(baseColor.color.blue, 311.0),
            ),
            white = vary(baseColor.white.toInt(), 173.0).toUByte(),
            amber = vary(baseColor.amber.toInt(), 239.0).toUByte(),
            uv = vary(baseColor.uv.toInt(), 283.0).toUByte(),
        ))
    }
}

/**
 * Static colour - no animation.
 *
 * Useful as a placeholder or for additive blending.
 *
 * @param color The static colour to output
 */
data class StaticColour(
    val color: ExtendedColour
) : Effect {
    override val name = "Static Colour"
    override val outputType = FxOutputType.COLOUR
    override val parameters get() = mapOf("color" to color.toSerializedString())

    override fun calculate(phase: Double): FxOutput {
        return FxOutput.Colour(color)
    }
}
