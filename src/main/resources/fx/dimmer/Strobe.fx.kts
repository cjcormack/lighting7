/*---
id: Strobe
name: Strobe
category: dimmer
outputType: SLIDER
effectMode: STANDARD
compatibleProperties: [dimmer, uv]
parameters:
  - name: offValue
    type: ubyte
    default: "0"
    description: Value when off
  - name: onValue
    type: ubyte
    default: "255"
    description: Value when on
  - name: onRatio
    type: double
    default: "0.1"
    description: Portion of cycle that is on
---*/

val offValue = params.ubyte("offValue")
val onValue = params.ubyte("onValue")
val onRatio = params.double("onRatio")
val value = if (phase < onRatio) onValue else offValue
FxOutput.Slider(value)
