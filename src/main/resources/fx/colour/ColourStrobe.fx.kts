/*---
id: ColourStrobe
name: Colour Strobe
category: colour
outputType: COLOUR
effectMode: STANDARD
compatibleProperties: [rgbColour]
parameters:
  - name: onColour
    type: colour
    default: "#ffffff"
    description: Colour when on
  - name: offColour
    type: colour
    default: "#000000"
    description: Colour when off
  - name: onRatio
    type: double
    default: "0.1"
    description: Portion of cycle that is on
---*/

val onColour = params.colour("onColour")
val offColour = params.colour("offColour")
val onRatio = params.double("onRatio")
val colour = if (phase < onRatio) onColour else offColour
FxOutput.Colour(colour)
