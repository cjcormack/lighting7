/*---
id: FocusPull
name: Focus Pull
category: beam
outputType: SLIDER
effectMode: STANDARD
defaultDirection: BOUNCE
defaultBeatDivision: 2.0
compatibleProperties: [focus]
parameters:
  - name: start
    type: ubyte
    default: "64"
    description: Focus at the start of the pull
  - name: end
    type: ubyte
    default: "192"
    description: Focus at the end of the pull
  - name: curve
    type: easingCurve
    default: "SINE_IN_OUT"
    description: Easing curve
---*/

val start = params.ubyte("start")
val end = params.ubyte("end")
val curve = params.easingCurve("curve")
val eased = curve.apply(phase)
val value = (start.toInt() + (end.toInt() - start.toInt()) * eased)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
