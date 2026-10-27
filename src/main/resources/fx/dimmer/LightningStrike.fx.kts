/*---
id: LightningStrike
name: Lightning Strike
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer]
parameters:
  - name: high
    type: ubyte
    default: "255"
    description: Peak brightness during flash
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
  - name: low
    type: ubyte
    default: "0"
    description: Base brightness between strikes
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: high
---*/

val maxBrightness = params.ubyte("high")
val minBrightness = params.ubyte("low")

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
