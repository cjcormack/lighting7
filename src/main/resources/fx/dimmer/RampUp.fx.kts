/*---
id: RampUp
name: Ramp Up
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
  - name: curve
    type: easingCurve
    default: "LINEAR"
    description: Easing curve
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val curve = params.easingCurve("curve")
val eased = curve.apply(phase)
val value = (min.toInt() + (max.toInt() - min.toInt()) * eased)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
