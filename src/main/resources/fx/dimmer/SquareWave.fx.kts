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
  - name: max
    type: ubyte
    default: "255"
    description: Maximum value
  - name: dutyCycle
    type: double
    default: "0.5"
    description: Ratio of time spent at max (0.0-1.0)
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val dutyCycle = params.double("dutyCycle")
val value = if (phase < dutyCycle) max else min
FxOutput.Slider(value)
