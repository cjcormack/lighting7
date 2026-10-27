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
  - name: between
    type: choice
    default: "FADE"
    description: How each colour gives way to the next
    label: Between
    options: [CUT: Cut, FADE: Fade, SMOOTH: Smooth]
  - name: fade
    type: double
    default: "0.5"
    description: Share of each colour's step spent crossing to the next
    label: Fade
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    shownWhen: between = [FADE]
presets:
  - name: Colour Fade
    attribute: rgbColour
    parameters: {colours: "#ff0000,#0000ff", between: FADE, fade: 1}
    cycle: 4.0
  - name: Colour Wave
    attribute: rgbColour
    parameters: {colours: "#000000,#ffffff", between: SMOOTH}
---*/

val colours = params.colourList("colours")
val between = params.choice("between")

if (colours.isEmpty()) {
    FxOutput.Colour(ExtendedColour.BLACK)
} else if (colours.size == 1) {
    FxOutput.Colour(colours[0])
} else {
    val segmentIndex = stepIndex(phase, colours.size)
    val segmentPhase = stepWithin(phase, colours.size)

    val currentColour = colours[segmentIndex]
    val nextColour = colours[(segmentIndex + 1) % colours.size]

    val colour = when (between) {
        "CUT" -> currentColour
        "SMOOTH" -> blendExtendedColours(currentColour, nextColour, (1.0 - cos(segmentPhase * PI)) / 2.0)
        else -> {
            val fade = params.double("fade")
            val holdPortion = 1.0 - fade
            if (segmentPhase < holdPortion) {
                currentColour
            } else {
                blendExtendedColours(currentColour, nextColour, (segmentPhase - holdPortion) / fade)
            }
        }
    }

    FxOutput.Colour(colour)
}
