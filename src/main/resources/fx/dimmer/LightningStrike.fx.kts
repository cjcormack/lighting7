/*---
id: LightningStrike
name: Lightning Strike
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer]
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
val p = cyclePhase(phase)

when {
    // Flash: snap to full brightness
    phaseBefore(p, FLASH_END) -> FxOutput.Slider(maxBrightness)

    // Decay: quadratic fade
    phaseBefore(p, DECAY_END) -> {
        val decayProgress = (p - FLASH_END) / (DECAY_END - FLASH_END)
        val decayCurve = (1.0 - decayProgress).pow(2.0)
        FxOutput.Slider(
            (minBrightness.toInt() + range * decayCurve).toInt().coerceIn(0, 255).toUByte()
        )
    }

    // Dark: at minimum
    else -> FxOutput.Slider(minBrightness)
}
