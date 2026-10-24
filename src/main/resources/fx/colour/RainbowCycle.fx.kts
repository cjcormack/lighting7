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
  - name: brightness
    type: double
    default: "1.0"
    description: Colour brightness (0.0-1.0)
---*/

val saturation = params.float("saturation")
val brightness = params.float("brightness")
val colour = Color.getHSBColor(phase.toFloat(), saturation, brightness)
FxOutput.Colour(colour)
