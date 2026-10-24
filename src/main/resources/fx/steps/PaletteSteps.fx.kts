/*---
id: PaletteSteps
name: Palette Steps
family: steps
outputType: STEPS
fxMode: STANDARD
compatibleProperties: [dimmer, strobe, rgbColour, position, zoom, focus, iris, frost, prism]
parameters:
  - name: palettes
    type: paletteList
    default: ""
    description: Comma-separated generic value palettes of one family, pal:{uuid} each — the steps, each resolved on every head for itself
  - name: fadeRatio
    type: double
    default: "0.5"
    description: Crossfade ratio (0 = hard cut, 1 = full crossfade)
---*/

stepsAt(phase, params.paletteSteps("palettes"), params.double("fadeRatio"))
