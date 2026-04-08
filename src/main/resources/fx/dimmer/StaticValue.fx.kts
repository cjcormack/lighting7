/*---
id: StaticValue
name: Static Value
category: dimmer
outputType: SLIDER
effectMode: STANDARD
defaultStepTiming: true
compatibleProperties: [dimmer, uv]
parameters:
  - name: value
    type: ubyte
    default: "255"
    description: The static value to output
---*/

val value = params.ubyte("value")

if (context.groupSize <= 1 || !context.hasDistributionSpread) {
    FxOutput.Slider(value)
} else {
    val window = 1.0 / context.numDistinctSlots
    val base = context.basePhase(phase)

    if (context.trianglePhase) {
        val dist = abs(base - context.distributionOffset)
        if (dist < window / 2.0) FxOutput.Slider(value) else FxOutput.Slider(0u)
    } else {
        val dist = (base - context.distributionOffset + 1.0) % 1.0
        if (dist < window) FxOutput.Slider(value) else FxOutput.Slider(0u)
    }
}
