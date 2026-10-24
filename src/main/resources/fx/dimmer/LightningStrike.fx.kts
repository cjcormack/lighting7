/*---
id: LightningStrike
name: Lightning Strike
category: intensity
outputType: SLIDER
effectMode: STANDARD
compatibleProperties: [dimmer]
parameters:
  - name: maxBrightness
    type: ubyte
    default: "255"
    description: Peak brightness during flash
  - name: minBrightness
    type: ubyte
    default: "0"
    description: Base brightness between strikes
---*/

val maxBrightness = params.ubyte("maxBrightness")
val minBrightness = params.ubyte("minBrightness")

val FLASH_END = 0.05
val DECAY_END = 0.3
val range = maxBrightness.toInt() - minBrightness.toInt()

when {
    // Flash: snap to full brightness
    phase < FLASH_END -> FxOutput.Slider(maxBrightness)

    // Decay: quadratic fade
    phase < DECAY_END -> {
        val decayProgress = (phase - FLASH_END) / (DECAY_END - FLASH_END)
        val decayCurve = (1.0 - decayProgress).pow(2.0)
        FxOutput.Slider(
            (minBrightness.toInt() + range * decayCurve).toInt().coerceIn(0, 255).toUByte()
        )
    }

    // Dark: at minimum
    else -> FxOutput.Slider(minBrightness)
}
