/*---
id: ColourCycle
name: Colour Cycle
family: colour
outputType: COLOUR
fxMode: STANDARD
compatibleAttributes: [rgbColour]
parameters:
  - name: colours
    type: colourList
    default: "#ff0000,#00ff00,#0000ff"
    description: Comma-separated literal colours, each a hex, a name or an extended colour
    label: Colours
  - name: fadeRatio
    type: double
    default: "0.5"
    description: Crossfade ratio (0 = hard cut, 1 = full crossfade)
    label: Fade
    min: 0
    max: 1
    step: 0.01
    unit: ratio
---*/

val colours = params.colourList("colours")
val fadeRatio = params.double("fadeRatio")

if (colours.isEmpty()) {
    FxOutput.Colour(ExtendedColour.BLACK)
} else if (colours.size == 1) {
    FxOutput.Colour(colours[0])
} else {
    val segmentIndex = stepIndex(phase, colours.size)
    val segmentPhase = stepWithin(phase, colours.size)

    val currentColour = colours[segmentIndex]
    val nextColour = colours[(segmentIndex + 1) % colours.size]

    val holdPortion = 1.0 - fadeRatio
    val colour = if (segmentPhase < holdPortion) {
        currentColour
    } else {
        val fadePhase = (segmentPhase - holdPortion) / fadeRatio
        blendExtendedColours(currentColour, nextColour, fadePhase)
    }

    FxOutput.Colour(colour)
}
