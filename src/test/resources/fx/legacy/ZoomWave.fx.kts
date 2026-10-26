/*---
id: ZoomWave
name: Zoom Wave
family: beam
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [zoom]
parameters:
  - name: min
    type: ubyte
    default: "64"
    description: Narrowest zoom
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: max
  - name: max
    type: ubyte
    default: "192"
    description: Widest zoom
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val sine = (sin(phase * 2 * PI) + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * sine)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
