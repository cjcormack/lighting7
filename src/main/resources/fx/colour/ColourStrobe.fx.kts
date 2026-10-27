/*---
id: ColourStrobe
name: Colour Strobe
family: colour
outputType: COLOUR
fxMode: STANDARD
compatibleAttributes: [rgbColour]
parameters:
  - name: onColour
    type: colour
    default: "#ffffff"
    description: Colour when on
    label: On colour
  - name: offColour
    type: colour
    default: "#000000"
    description: Colour when off
    label: Off colour
  - name: width
    type: double
    default: "0.1"
    description: Portion of cycle that is on
    label: Width
    min: 0
    max: 1
    step: 0.01
    unit: ratio
---*/

val onColour = params.colour("onColour")
val offColour = params.colour("offColour")
val onRatio = params.double("width")
val colour = if (phaseBefore(phase, onRatio)) onColour else offColour
FxOutput.Colour(colour)
