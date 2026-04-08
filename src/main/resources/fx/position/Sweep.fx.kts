/*---
id: Sweep
name: Sweep
category: position
outputType: POSITION
effectMode: STANDARD
compatibleProperties: [pan, tilt]
parameters:
  - name: startPan
    type: ubyte
    default: "64"
    description: Starting pan
  - name: startTilt
    type: ubyte
    default: "128"
    description: Starting tilt
  - name: endPan
    type: ubyte
    default: "192"
    description: Ending pan
  - name: endTilt
    type: ubyte
    default: "128"
    description: Ending tilt
  - name: curve
    type: easingCurve
    default: "SINE_IN_OUT"
    description: Easing curve
  - name: pingPong
    type: boolean
    default: "true"
    description: Return to start
---*/

val startPan = params.ubyte("startPan")
val startTilt = params.ubyte("startTilt")
val endPan = params.ubyte("endPan")
val endTilt = params.ubyte("endTilt")
val curve = params.easingCurve("curve")
val pingPong = params.boolean("pingPong")

val effectivePhase = if (pingPong && phase > 0.5) {
    1.0 - (phase - 0.5) * 2
} else if (pingPong) {
    phase * 2
} else {
    phase
}
val easedPhase = curve.apply(effectivePhase)
val pan = (startPan.toInt() + (endPan.toInt() - startPan.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
val tilt = (startTilt.toInt() + (endTilt.toInt() - startTilt.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
