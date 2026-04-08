/*---
id: Breathe
name: Breathe
category: dimmer
outputType: SLIDER
effectMode: STANDARD
compatibleProperties: [dimmer, uv]
parameters:
  - name: min
    type: ubyte
    default: "0"
    description: Minimum value
  - name: max
    type: ubyte
    default: "255"
    description: Maximum value
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val sineValue = sin(phase * PI)
val normalized = sineValue * sineValue
val value = (min.toInt() + (max.toInt() - min.toInt()) * normalized)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
