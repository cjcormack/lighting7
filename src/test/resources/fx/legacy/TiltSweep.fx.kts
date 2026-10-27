/*---
id: TiltSweep
name: Tilt Sweep
family: position
outputType: POSITION
fxMode: STANDARD
defaultDirection: BOUNCE
defaultCycle: 2.0
compatibleAttributes: [position]
parameters:
  - name: startTilt
    type: ubyte
    default: "64"
    description: Starting tilt
    label: Start tilt
    min: 0
    max: 255
    step: 1
    unit: position
  - name: endTilt
    type: ubyte
    default: "192"
    description: Ending tilt
    label: End tilt
    min: 0
    max: 255
    step: 1
    unit: position
  - name: pan
    type: ubyte
    default: "128"
    description: Fixed pan position
    label: Pan
    min: 0
    max: 255
    step: 1
    unit: position
  - name: curve
    type: easingCurve
    default: "SINE_IN_OUT"
    description: Easing curve
    label: Curve
    role: detail
---*/

val startTilt = params.ubyte("startTilt")
val endTilt = params.ubyte("endTilt")
val pan = params.ubyte("pan")
val curve = params.easingCurve("curve")
val easedPhase = curve.apply(phase)
val tilt = (startTilt.toInt() + (endTilt.toInt() - startTilt.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
