/*---
id: SquareWave
name: Square Wave
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer, uv]
parameters:
  - name: min
    type: ubyte
    default: "0"
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
  - name: dutyCycle
    type: double
    default: "0.5"
    description: Ratio of time spent at max (0.0-1.0)
    label: Width
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    role: detail
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val dutyCycle = params.double("dutyCycle")
val value = if (phaseBefore(phase, dutyCycle)) max else min
FxOutput.Slider(value)
