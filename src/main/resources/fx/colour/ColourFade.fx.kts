/*---
id: ColourFade
name: Colour Fade
category: colour
outputType: COLOUR
effectMode: STANDARD
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
  - name: pingPong
    type: boolean
    default: "true"
    description: Fade back to start colour
---*/

val fromColour = params.colour("fromColour")
val toColour = params.colour("toColour")
val pingPong = params.boolean("pingPong")

val ratio = if (pingPong && phase > 0.5) {
    1.0 - (phase - 0.5) * 2
} else if (pingPong) {
    phase * 2
} else {
    phase
}

FxOutput.Colour(blendExtendedColours(fromColour, toColour, ratio))
