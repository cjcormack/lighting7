/*---
id: FocusPull
name: Focus Pull
category: beam
outputType: SLIDER
effectMode: STANDARD
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
  - name: pingPong
    type: boolean
    default: "true"
    description: Pull back to the start
---*/

val start = params.ubyte("start")
val end = params.ubyte("end")
val curve = params.easingCurve("curve")
val pingPong = params.boolean("pingPong")

val effectivePhase = if (pingPong && phase > 0.5) {
    1.0 - (phase - 0.5) * 2
} else if (pingPong) {
    phase * 2
} else {
    phase
}
val eased = curve.apply(effectivePhase)
val value = (start.toInt() + (end.toInt() - start.toInt()) * eased)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
