/*---
id: StaticColour
name: Static Colour
category: colour
outputType: COLOUR
effectMode: STANDARD
defaultStepTiming: true
compatibleProperties: [rgbColour]
parameters:
  - name: colour
    type: colour
    default: "#ff0000"
    description: The static colour
---*/

val color = params.colour("colour")

// When distributed across a group with spread, create a chase pattern
// by only being "on" for a window of each cycle.
if (!context.hasDistributionSpread) {
    FxOutput.Colour(color)
} else {
    val window = 1.0 / context.numDistinctSlots
    val base = context.basePhase(phase)

    val active = if (context.trianglePhase) {
        abs(base - context.distributionOffset) < window / 2
    } else {
        val dist = (base - context.distributionOffset + 1.0) % 1.0
        dist < window
    }

    if (active) FxOutput.Colour(color) else FxOutput.Colour(ExtendedColour.BLACK)
}
