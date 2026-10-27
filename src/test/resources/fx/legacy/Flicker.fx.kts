/*---
id: Flicker
name: Flicker
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer, uv]
parameters:
  - name: min
    type: ubyte
    default: "100"
    description: Minimum value
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: max
  - name: max
    type: ubyte
    default: "255"
    description: Maximum value
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val random = sin(phase * 127.0) * cos(phase * 311.0)
val normalized = (random + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
