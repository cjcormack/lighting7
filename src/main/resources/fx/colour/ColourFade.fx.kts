/*---
id: ColourFade
name: Colour Fade
category: colour
outputType: COLOUR
effectMode: STANDARD
defaultDirection: BOUNCE
defaultBeatDivision: 2.0
compatibleProperties: [rgbColour]
parameters:
  - name: fromColour
    type: colour
    default: "#ff0000"
    description: Starting colour
  - name: toColour
    type: colour
    default: "#0000ff"
    description: Ending colour
---*/

val fromColour = params.colour("fromColour")
val toColour = params.colour("toColour")

FxOutput.Colour(blendExtendedColours(fromColour, toColour, phase))
