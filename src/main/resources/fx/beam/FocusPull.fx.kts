/*---
id: FocusPull
name: Focus Pull
family: beam
outputType: SLIDER
fxMode: STANDARD
defaultDirection: BOUNCE
defaultCycle: 2.0
compatibleAttributes: [focus]
parameters:
  - name: start
    type: ubyte
    default: "64"
    description: Focus at the start of the pull
    label: Start
    min: 0
    max: 255
    step: 1
    unit: %
  - name: end
    type: ubyte
    default: "192"
    description: Focus at the end of the pull
    label: End
    min: 0
    max: 255
    step: 1
    unit: %
  - name: curve
    type: easingCurve
    default: "SINE_IN_OUT"
    description: Easing curve
    label: Curve
    role: detail
---*/

val start = params.ubyte("start")
val end = params.ubyte("end")
val curve = params.easingCurve("curve")
val eased = curve.apply(phase)
val value = (start.toInt() + (end.toInt() - start.toInt()) * eased)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
