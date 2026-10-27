/*---
id: PanSweep
name: Pan Sweep
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
  - name: endPan
    type: ubyte
    default: "192"
    description: Ending pan
    label: End pan
    min: 0
    max: 255
    step: 1
    unit: position
  - name: tilt
    type: ubyte
    default: "128"
    description: Fixed tilt position
    label: Tilt
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
val endPan = params.ubyte("endPan")
val tilt = params.ubyte("tilt")
val curve = params.easingCurve("curve")
val easedPhase = curve.apply(phase)
val pan = (startPan.toInt() + (endPan.toInt() - startPan.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
