/*---
id: LightningStrike
name: Lightning Strike
category: composite
outputType: SLIDER
effectMode: COMPOSITE
compatibleProperties: [dimmer, rgbColour]
parameters:
  - name: maxBrightness
    type: ubyte
    default: "255"
    description: Peak brightness during flash
  - name: minBrightness
    type: ubyte
    default: "0"
    description: Base brightness between strikes
  - name: flashColour
    type: colour
    default: "#ffffff"
    description: Colour during the flash
  - name: decayColour
    type: colour
    default: "#5064ff"
    description: Colour during decay
  - name: ambientColour
    type: colour
    default: "#000000"
    description: Colour between strikes
---*/

val maxBrightness = params.ubyte("maxBrightness")
val minBrightness = params.ubyte("minBrightness")
val flashColour = params.colour("flashColour")
val decayColour = params.colour("decayColour")
val ambientColour = params.colour("ambientColour")

val FLASH_END = 0.05
val DECAY_END = 0.3
val range = maxBrightness.toInt() - minBrightness.toInt()

when {
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
