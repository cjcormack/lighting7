/*---
id: CandleFlicker
name: Candle Flicker
category: dimmer
outputType: SLIDER
effectMode: STATEFUL
compatibleProperties: [dimmer, uv]
parameters:
  - name: baseLevel
    type: ubyte
    default: "180"
    description: The average brightness level
  - name: min
    type: ubyte
    default: "100"
    description: Minimum value floor
  - name: max
    type: ubyte
    default: "230"
    description: Maximum value ceiling
  - name: smoothing
    type: double
    default: "0.85"
    description: Smoothing factor - higher values give slower, smoother drift
---*/

val baseLevel = params.ubyte("baseLevel").toDouble()
val min = params.ubyte("min")
val max = params.ubyte("max")
val smoothing = params.double("smoothing").coerceIn(0.0, 0.99)

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
    val range = max.toInt() - min.toInt()
    target = baseLevel + noise * range * 0.4
}

// Smooth toward target
currentLevel = currentLevel * smoothing + target * (1.0 - smoothing)

state["currentLevel"] = currentLevel
state["target"] = target
state["ticksSinceTargetChange"] = ticksSinceTargetChange

val value = currentLevel.toInt().coerceIn(min.toInt(), max.toInt()).toUByte()
FxOutput.Slider(value)
