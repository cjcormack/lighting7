/*---
id: Ramp
name: Ramp
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
  - name: curve
    type: easingCurve
    default: "LINEAR"
    description: Easing curve
    label: Curve
    role: detail
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val curve = params.easingCurve("curve")
val tri = if (phase < 0.5) phase * 2.0 else 2.0 * (1.0 - phase)
val eased = curve.apply(tri)
val value = (min.toInt() + (max.toInt() - min.toInt()) * eased)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
