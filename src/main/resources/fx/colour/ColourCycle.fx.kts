/*---
id: ColourCycle
name: Colour Cycle
category: colour
outputType: COLOUR
effectMode: STANDARD
compatibleProperties: [rgbColour]
parameters:
  - name: colours
    type: colourList
    default: "#ff0000,#00ff00,#0000ff"
    description: Comma-separated colours. Each entry is a literal (hex, name or extended) or a colour-template reference, tmpl:{uuid}
  - name: fadeRatio
    type: double
    default: "0.5"
    description: Crossfade ratio (0 = hard cut, 1 = full crossfade)
---*/

val colours = params.colourList("colours")
val fadeRatio = params.double("fadeRatio")

if (colours.isEmpty()) {
    FxOutput.Colour(ExtendedColour.BLACK)
} else if (colours.size == 1) {
    FxOutput.Colour(colours[0])
} else {
    val segmentSize = 1.0 / colours.size
    val segmentIndex = (phase / segmentSize).toInt().coerceIn(0, colours.size - 1)
    val segmentPhase = (phase - segmentIndex * segmentSize) / segmentSize

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
