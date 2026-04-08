/*---
id: RainbowCycle
name: Rainbow Cycle
category: colour
outputType: COLOUR
effectMode: STANDARD
compatibleProperties: [rgbColour]
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
