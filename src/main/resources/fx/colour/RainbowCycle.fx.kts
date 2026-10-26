/*---
id: RainbowCycle
name: Rainbow Cycle
family: colour
outputType: COLOUR
fxMode: STANDARD
compatibleAttributes: [rgbColour]
parameters:
  - name: saturation
    type: double
    default: "1.0"
    description: Colour saturation (0.0-1.0)
    label: Saturation
    min: 0
    max: 1
    step: 0.01
    unit: ratio
  - name: brightness
    type: double
    default: "1.0"
    description: Colour brightness (0.0-1.0)
    label: Brightness
    min: 0
    max: 1
    step: 0.01
    unit: ratio
---*/

val saturation = params.float("saturation")
val brightness = params.float("brightness")
val colour = Color.getHSBColor(phase.toFloat(), saturation, brightness)
FxOutput.Colour(colour)
