/*---
id: Flicker
name: Flicker
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer, uv]
parameters:
  - name: low
    type: ubyte
    default: "100"
    description: The lowest level it flickers to
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: high
  - name: high
    type: ubyte
    default: "255"
    description: The highest level it flickers to
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
---*/

val low = params.ubyte("low")
val high = params.ubyte("high")
val random = sin(phase * 127.0) * cos(phase * 311.0)
val normalized = (random + 1.0) / 2.0
val value = (low.toInt() + (high.toInt() - low.toInt()) * normalized)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
