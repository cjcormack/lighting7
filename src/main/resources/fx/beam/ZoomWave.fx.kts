/*---
id: ZoomWave
name: Zoom Wave
family: beam
outputType: SLIDER
fxMode: STANDARD
compatibleProperties: [zoom]
parameters:
  - name: min
    type: ubyte
    default: "64"
    description: Narrowest zoom
  - name: max
    type: ubyte
    default: "192"
    description: Widest zoom
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val sine = (sin(phase * 2 * PI) + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * sine)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
