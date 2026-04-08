/*---
id: ColourPulse
name: Colour Pulse
category: colour
outputType: COLOUR
effectMode: STANDARD
compatibleProperties: [rgbColour]
parameters:
  - name: colourA
    type: colour
    default: "#000000"
    description: First colour
  - name: colourB
    type: colour
    default: "#ffffff"
    description: Second colour
---*/

val colourA = params.colour("colourA")
val colourB = params.colour("colourB")
val sineValue = sin(phase * 2 * PI)
val ratio = (sineValue + 1.0) / 2.0
FxOutput.Colour(blendExtendedColours(colourA, colourB, ratio))
