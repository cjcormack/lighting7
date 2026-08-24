package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.fx.*
import java.awt.Color
import kotlin.math.pow

/**
 * Lightning strike effect — a bright flash, with a matching colour shift on offer.
 *
 * Derives a dimmer spike and a white-to-blue colour transition from one phase, both
 * decaying together. **Only the dimmer half is applied**: composites are
 * primary-output-only ([CompositeEffect]), so the COLOUR entry is computed and dropped.
 * For a lightning-coloured wash, run a colour effect as a second instance on the same
 * speed master.
 *
 * Phase breakdown:
 * - 0.0–0.05: Flash — dimmer snaps to max, colour is bright white
 * - 0.05–0.3: Decay — dimmer fades down, colour shifts to blue
 * - 0.3–1.0: Dark — dimmer at min, colour at ambient
 *
 * @param maxBrightness Peak brightness during flash (default 255)
 * @param minBrightness Base brightness between strikes (default 0)
 * @param flashColour Colour during the flash (default white)
 * @param decayColour Colour during decay (default blue)
 * @param ambientColour Colour between strikes (default black)
 */
data class LightningStrike(
    val maxBrightness: UByte = 255u,
    val minBrightness: UByte = 0u,
    val flashColour: ExtendedColour = ExtendedColour.fromColor(Color.WHITE),
    val decayColour: ExtendedColour = ExtendedColour.fromColor(Color(80, 100, 255)),
    val ambientColour: ExtendedColour = ExtendedColour.BLACK,
) : CompositeEffect {
    override val name = "Lightning Strike"
    override val outputType = FxOutputType.SLIDER
    override val parameters get() = mapOf(
        "maxBrightness" to maxBrightness.toString(),
        "minBrightness" to minBrightness.toString(),
        "flashColour" to flashColour.toSerializedString(),
        "decayColour" to decayColour.toSerializedString(),
        "ambientColour" to ambientColour.toSerializedString(),
    )

    companion object {
        private const val FLASH_END = 0.05
        private const val DECAY_END = 0.3
    }

    override fun calculateComposite(
        phase: Double,
        context: EffectContext,
    ): Map<FxOutputType, FxOutput> {
        val range = maxBrightness.toInt() - minBrightness.toInt()

        return when {
            // Flash phase: snap to full brightness, white colour
            phase < FLASH_END -> mapOf(
                FxOutputType.SLIDER to FxOutput.Slider(maxBrightness),
                FxOutputType.COLOUR to FxOutput.Colour(flashColour),
            )

            // Decay phase: exponential fade, colour shifts to blue
            phase < DECAY_END -> {
                val decayProgress = (phase - FLASH_END) / (DECAY_END - FLASH_END)
                val decayCurve = (1.0 - decayProgress).pow(2.0) // Quadratic decay
                val brightness = (minBrightness.toInt() + range * decayCurve)
                    .toInt().coerceIn(0, 255).toUByte()
                val colour = blendExtendedColours(flashColour, decayColour, decayProgress)
                mapOf(
                    FxOutputType.SLIDER to FxOutput.Slider(brightness),
                    FxOutputType.COLOUR to FxOutput.Colour(colour),
                )
            }

            // Dark phase: at minimum, ambient colour
            else -> mapOf(
                FxOutputType.SLIDER to FxOutput.Slider(minBrightness),
                FxOutputType.COLOUR to FxOutput.Colour(ambientColour),
            )
        }
    }
}
