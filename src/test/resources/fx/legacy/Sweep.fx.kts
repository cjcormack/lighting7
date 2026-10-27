/*---
id: Sweep
name: Sweep
family: position
outputType: POSITION
fxMode: STANDARD
defaultDirection: BOUNCE
defaultCycle: 2.0
compatibleAttributes: [position]
parameters:
  - name: startPan
    type: ubyte
    default: "64"
    description: Starting pan
    label: Start pan
    min: 0
    max: 255
    step: 1
    unit: position
  - name: startTilt
    type: ubyte
    default: "128"
    description: Starting tilt
    label: Start tilt
    min: 0
    max: 255
    step: 1
    unit: position
  - name: endPan
    type: ubyte
    default: "192"
    description: Ending pan
    label: End pan
    min: 0
    max: 255
    step: 1
    unit: position
  - name: endTilt
    type: ubyte
    default: "128"
    description: Ending tilt
    label: End tilt
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

val startPan = params.ubyte("startPan")
val startTilt = params.ubyte("startTilt")
val endPan = params.ubyte("endPan")
val endTilt = params.ubyte("endTilt")
val curve = params.easingCurve("curve")
val easedPhase = curve.apply(phase)
val pan = (startPan.toInt() + (endPan.toInt() - startPan.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
val tilt = (startTilt.toInt() + (endTilt.toInt() - startTilt.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
