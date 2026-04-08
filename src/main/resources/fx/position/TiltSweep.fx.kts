/*---
id: TiltSweep
name: Tilt Sweep
category: position
outputType: POSITION
effectMode: STANDARD
compatibleProperties: [pan, tilt]
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
  - name: pingPong
    type: boolean
    default: "true"
    description: Return to start
---*/

val startTilt = params.ubyte("startTilt")
val endTilt = params.ubyte("endTilt")
val pan = params.ubyte("pan")
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
val tilt = (startTilt.toInt() + (endTilt.toInt() - startTilt.toInt()) * easedPhase).toInt().coerceIn(0, 255).toUByte()
FxOutput.Position(pan, tilt)
