/*---
id: Pulse
name: Pulse
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer, uv]
parameters:
  - name: min
    type: ubyte
    default: "0"
    description: Minimum value
  - name: max
    type: ubyte
    default: "255"
    description: Maximum value
  - name: attackRatio
    type: double
    default: "0.1"
    description: Portion of cycle for attack
  - name: holdRatio
    type: double
    default: "0.3"
    description: Portion of cycle for hold at max
  - name: curve
    type: easingCurve
    default: "QUAD_OUT"
    description: Easing curve for attack/release
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val attackRatio = params.double("attackRatio")
val holdRatio = params.double("holdRatio")
val curve = params.easingCurve("curve")
val releaseRatio = 1.0 - attackRatio - holdRatio
val p = cyclePhase(phase)

val value = when {
    phaseBefore(p, attackRatio) -> {
        val attackPhase = p / attackRatio
        curve.interpolate(min.toDouble(), max.toDouble(), attackPhase)
    }
    phaseBefore(p, attackRatio + holdRatio) -> {
        max.toDouble()
    }
    else -> {
        val releasePhase = if (releaseRatio > 0.0) (p - attackRatio - holdRatio) / releaseRatio else 1.0
        curve.interpolate(max.toDouble(), min.toDouble(), releasePhase)
    }
}
FxOutput.Slider(value.toInt().coerceIn(0, 255).toUByte())
