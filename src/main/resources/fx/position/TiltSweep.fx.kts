/*---
id: TiltSweep
name: Tilt Sweep
category: position
outputType: POSITION
effectMode: STANDARD
defaultDirection: BOUNCE
defaultBeatDivision: 2.0
compatibleProperties: [position]
parameters:
  - name: startTilt
    type: ubyte
    default: "64"
    description: Starting tilt
  - name: endTilt
    type: ubyte
    default: "192"
    description: Ending tilt
  - name: pan
    type: ubyte
    default: "128"
    description: Fixed pan position
  - name: curve
    type: easingCurve
    default: "SINE_IN_OUT"
    description: Easing curve
---*/

val startTilt = params.ubyte("startTilt")
val endTilt = params.ubyte("endTilt")
val pan = params.ubyte("pan")
val curve = params.easingCurve("curve")
val easedPhase = curve.apply(phase)
val tilt = (startTilt.toInt() + (endTilt.toInt() - startTilt.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
