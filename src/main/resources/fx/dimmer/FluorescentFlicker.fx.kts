/*---
id: FluorescentFlicker
name: Fluorescent Flicker
category: dimmer
outputType: SLIDER
effectMode: STATEFUL
timingSource: WALL_CLOCK
compatibleProperties: [dimmer, uv]
parameters:
  - name: onLevel
    type: ubyte
    default: "255"
    description: Normal on brightness
  - name: offLevel
    type: ubyte
    default: "0"
    description: Off/flicker brightness
  - name: flickerDurationMs
    type: int
    default: "800"
    description: Total duration of the flicker burst in milliseconds
  - name: flickerSpeed
    type: double
    default: "0.3"
    description: How fast the on/off cycles are during a flicker (lower = faster)
---*/

val onLevel = params.ubyte("onLevel")
val offLevel = params.ubyte("offLevel")
val flickerDurationMs = params.int("flickerDurationMs")
val flickerSpeed = params.double("flickerSpeed").coerceIn(0.05, 0.95)

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
