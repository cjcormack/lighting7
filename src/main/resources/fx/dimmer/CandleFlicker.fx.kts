/*---
id: CandleFlicker
name: Candle Flicker
family: intensity
outputType: SLIDER
fxMode: STATEFUL
timingSource: WALL_CLOCK
compatibleAttributes: [dimmer, uv]
parameters:
  - name: base
    type: ubyte
    default: "180"
    description: The average brightness level
    label: Base
    min: 0
    max: 255
    step: 1
    unit: %
  - name: low
    type: ubyte
    default: "100"
    description: Minimum value floor
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: high
  - name: high
    type: ubyte
    default: "230"
    description: Maximum value ceiling
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
  - name: settle
    type: double
    default: "0.85"
    description: Smoothing factor - higher values give slower, smoother drift
    label: Settle
    min: 0
    max: 0.99
    step: 0.01
    unit: ratio
    role: detail
---*/

val baseLevel = params.ubyte("base").toDouble()
val low = params.ubyte("low")
val high = params.ubyte("high")
val smoothing = params.double("settle").coerceIn(0.0, 0.99)

var currentLevel = state.getOrPut("currentLevel") { baseLevel } as Double
var target = state.getOrPut("target") { baseLevel } as Double
var ticksSinceTargetChange = (state.getOrPut("ticksSinceTargetChange") { 0 } as Number).toInt()

ticksSinceTargetChange++

// Periodically pick a new target using deterministic pseudo-random from tick number
// Change target every ~3-8 ticks for organic feel
val changeThreshold = 3 + ((sin(tick.tickNumber * 0.37) + 1.0) * 2.5).toInt()
if (ticksSinceTargetChange >= changeThreshold) {
    ticksSinceTargetChange = 0
    // Pseudo-random target biased toward baseLevel
    val noise = sin(tick.tickNumber * 127.0) * cos(tick.tickNumber * 311.0)
    val range = high.toInt() - low.toInt()
    target = baseLevel + noise * range * 0.4
}

// Smooth toward target
currentLevel = currentLevel * smoothing + target * (1.0 - smoothing)

state["currentLevel"] = currentLevel
state["target"] = target
state["ticksSinceTargetChange"] = ticksSinceTargetChange

val value = currentLevel.toInt().coerceIn(low.toInt(), high.toInt()).toUByte()
FxOutput.Slider(value)
