/*---
id: Flicker
name: Flicker
category: dimmer
outputType: SLIDER
effectMode: STANDARD
compatibleProperties: [dimmer, uv]
parameters:
  - name: min
    type: ubyte
    default: "100"
    description: Minimum value
  - name: max
    type: ubyte
    default: "255"
    description: Maximum value
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val random = sin(phase * 127.0) * cos(phase * 311.0)
val normalized = (random + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
