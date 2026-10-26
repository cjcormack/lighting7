/*---
id: DimmerStrobe
name: Dimmer Strobe
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer, uv]
parameters:
  - name: offValue
    type: ubyte
    default: "0"
    description: Value when off
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: onValue
  - name: onValue
    type: ubyte
    default: "255"
    description: Value when on
    label: High
    min: 0
    max: 255
    step: 1
    unit: %
  - name: onRatio
    type: double
    default: "0.1"
    description: Portion of cycle that is on
    label: Width
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    role: detail
---*/

val offValue = params.ubyte("offValue")
val onValue = params.ubyte("onValue")
val onRatio = params.double("onRatio")
val value = if (phaseBefore(phase, onRatio)) onValue else offValue
FxOutput.Slider(value)
