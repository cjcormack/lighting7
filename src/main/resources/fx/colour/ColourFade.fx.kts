/*---
id: ColourFade
name: Colour Fade
family: colour
outputType: COLOUR
fxMode: STANDARD
defaultDirection: BOUNCE
defaultCycle: 2.0
compatibleAttributes: [rgbColour]
legacy: true
parameters:
  - name: fromColour
    type: colour
    default: "#ff0000"
    description: Starting colour
    label: From
  - name: toColour
    type: colour
    default: "#0000ff"
    description: Ending colour
    label: To
---*/

val fromColour = params.colour("fromColour")
val toColour = params.colour("toColour")

FxOutput.Colour(blendExtendedColours(fromColour, toColour, phase))
