/*---
id: ColourCycle
name: Colour Cycle
family: colour
outputType: COLOUR
fxMode: STANDARD
stepsFrom: colours
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
    options: [CUT: Cut, FADE: Fade, SMOOTH: Smooth, SPLIT: Split]
  - name: fade
    type: double
    default: "0.5"
    description: Share of each colour's step spent crossing to the next, or in its split with it
    label: Fade
    min: 0
    max: 1
    step: 0.01
    unit: ratio
    shownWhen: between = [FADE, SPLIT]
presets:
  - name: Colour Fade
    attribute: rgbColour
    parameters: {colours: "#ff0000,#0000ff", between: FADE, fade: 1}
    cycle: 4.0
  - name: Colour Wave
    attribute: rgbColour
    parameters: {colours: "#000000,#ffffff", between: SMOOTH}
---*/

// Steps, so each head lands the colours as its own colour does: a mix takes each whole, a colour
// wheel its nearest slot (wheel-colour-chase plan D1, D2).
val colours = params.colourSteps("colours")

when (params.choice("between")) {
    "CUT" -> stepsAt(phase, colours)
    "SMOOTH" -> stepsAt(phase, colours, 1.0, StepEase.COSINE)
    "SPLIT" -> stepsAt(phase, colours, params.double("fade"), split = true)
    else -> stepsAt(phase, colours, params.double("fade"), clampFade = false)
}
