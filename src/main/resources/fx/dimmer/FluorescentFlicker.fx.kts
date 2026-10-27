/*---
id: FluorescentFlicker
name: Fluorescent Flicker
family: intensity
outputType: SLIDER
fxMode: STATEFUL
timingSource: WALL_CLOCK
compatibleAttributes: [dimmer, uv]
parameters:
  - name: high
    type: ubyte
    default: "255"
    description: Normal on brightness
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
  - name: low
    type: ubyte
    default: "0"
    description: Off/flicker brightness
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: high
  - name: burstMs
    type: int
    default: "800"
    description: Total duration of the flicker burst in milliseconds
    label: Burst
    min: 0
    max: 5000
    step: 10
    unit: ms
  - name: speed
    type: double
    default: "0.3"
    description: How fast the on/off cycles are during a flicker (lower = faster)
    label: Speed
    min: 0.05
    max: 0.95
    step: 0.01
    unit: ratio
    role: detail
---*/

val onLevel = params.ubyte("high")
val offLevel = params.ubyte("low")
val flickerDurationMs = params.int("burstMs")
val flickerSpeed = params.double("speed").coerceIn(0.05, 0.95)

// State machine: IDLE (on) or FLICKERING
var elapsedMs = (state.getOrPut("elapsedMs") { 0L } as Number).toLong()
var isFlickering = state.getOrPut("isFlickering") { true } as Boolean

elapsedMs += deltaMs
state["elapsedMs"] = elapsedMs

if (isFlickering) {
    if (elapsedMs >= flickerDurationMs) {
        // Flicker burst complete, return to steady on
        isFlickering = false
        elapsedMs = 0L
        state["isFlickering"] = false
        state["elapsedMs"] = 0L
        FxOutput.Slider(onLevel)
    } else {
        // During flicker: rapid on/off with pseudo-random timing
        val progress = elapsedMs.toDouble() / flickerDurationMs
        // More stable toward the end of the flicker (simulating tube catching)
        val instability = (1.0 - progress * progress)
        val noise = sin(elapsedMs * 0.047) * cos(elapsedMs * 0.031)
        val isOn = noise > (-0.5 + instability * flickerSpeed)
        val value = if (isOn) onLevel else offLevel
        FxOutput.Slider(value)
    }
} else {
    // Steady on state - the trigger system will restart the effect for another flicker
    FxOutput.Slider(onLevel)
}
