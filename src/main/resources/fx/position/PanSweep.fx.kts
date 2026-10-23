/*---
id: PanSweep
name: Pan Sweep
category: position
outputType: POSITION
effectMode: STANDARD
defaultDirection: BOUNCE
defaultBeatDivision: 2.0
compatibleProperties: [position]
parameters:
  - name: startPan
    type: ubyte
    default: "64"
    description: Starting pan
  - name: endPan
    type: ubyte
    default: "192"
    description: Ending pan
  - name: tilt
    type: ubyte
    default: "128"
    description: Fixed tilt position
  - name: curve
    type: easingCurve
    default: "SINE_IN_OUT"
    description: Easing curve
---*/

val startPan = params.ubyte("startPan")
val endPan = params.ubyte("endPan")
val tilt = params.ubyte("tilt")
val curve = params.easingCurve("curve")
val easedPhase = curve.apply(phase)
val pan = (startPan.toInt() + (endPan.toInt() - startPan.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
