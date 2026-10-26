/*---
id: Wave
name: Wave
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer, uv, zoom, iris, frost, focus]
parameters:
  - name: shape
    type: choice
    default: "SINE"
    description: The waveform over one cycle
    label: Shape
    options: [SINE: Sine, RAMP_UP: Ramp up, RAMP_DOWN: Ramp down, TRIANGLE: Triangle, SQUARE: Square, PULSE: Pulse]
  - name: low
    type: ubyte
    default: "0"
    description: The level at the bottom of the wave
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: high
  - name: high
    type: ubyte
    default: "255"
    description: The level at the top of the wave
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
  - name: width
    type: double
    default: "0.5"
    description: Share of the cycle a Square spends high
    label: Width
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    shownWhen: shape = [SQUARE]
  - name: attack
    type: double
    default: "0.1"
    description: Share of the cycle a Pulse spends rising
    label: Attack
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    role: detail
    shownWhen: shape = [PULSE]
  - name: hold
    type: double
    default: "0.3"
    description: Share of the cycle a Pulse holds high
    label: Hold
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    role: detail
    shownWhen: shape = [PULSE]
  - name: curve
    type: easingCurve
    default: "LINEAR"
    description: Easing of a ramp, a triangle or a pulse's rise and fall
    label: Curve
    role: detail
    shownWhen: shape = [RAMP_UP, RAMP_DOWN, TRIANGLE, PULSE]
attributeDefaults:
  - attribute: zoom
    parameters: {low: 64, high: 192}
  - attribute: iris
    parameters: {low: 0, high: 160}
  - attribute: frost
    parameters: {low: 0, high: 160}
  - attribute: focus
    parameters: {low: 64, high: 192}
presets:
  - name: Strobe
    attribute: dimmer
    parameters: {shape: SQUARE, width: 0.1}
  - name: Zoom Wave
    attribute: zoom
    parameters: {shape: SINE, low: 64, high: 192}
  - name: Iris Pulse
    attribute: iris
    parameters: {shape: PULSE, low: 0, high: 160, curve: QUAD_OUT}
  - name: Frost Pulse
    attribute: frost
    parameters: {shape: PULSE, low: 0, high: 160, curve: QUAD_OUT}
  - name: Focus Pull
    attribute: focus
    parameters: {shape: RAMP_UP, low: 64, high: 192, curve: SINE_IN_OUT}
    cycle: 2.0
    direction: BOUNCE
---*/

val shape = params.choice("shape")
val low = params.ubyte("low").toDouble()
val high = params.ubyte("high").toDouble()
val curve = params.easingCurve("curve")

fun level(fraction: Double): UByte =
    (low.toInt() + (high.toInt() - low.toInt()) * fraction).toInt().coerceIn(0, 255).toUByte()

val value: UByte = when (shape) {
    "RAMP_UP" -> level(curve.apply(phase))
    "RAMP_DOWN" -> level(curve.apply(1.0 - phase))
    "TRIANGLE" -> level(curve.apply(if (phase < 0.5) phase * 2.0 else 2.0 * (1.0 - phase)))
    "SQUARE" -> if (phaseBefore(phase, params.double("width"))) high.toInt().toUByte() else low.toInt().toUByte()
    "PULSE" -> {
        val attack = params.double("attack")
        val hold = params.double("hold")
        val release = 1.0 - attack - hold
        val p = cyclePhase(phase)
        val pulse = when {
            phaseBefore(p, attack) -> curve.interpolate(low, high, p / attack)
            phaseBefore(p, attack + hold) -> high
            else -> curve.interpolate(high, low, if (release > 0.0) (p - attack - hold) / release else 1.0)
        }
        pulse.toInt().coerceIn(0, 255).toUByte()
    }
    else -> level((sin(phase * 2 * PI) + 1.0) / 2.0)
}
FxOutput.Slider(value)
