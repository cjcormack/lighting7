/*---
id: ColourFlicker
name: Colour Flicker
family: colour
outputType: COLOUR
fxMode: STANDARD
compatibleAttributes: [rgbColour]
parameters:
  - name: baseColour
    type: colour
    default: "#ff0000"
    description: Base colour to vary around
    label: Colour
  - name: variation
    type: int
    default: "50"
    description: Maximum channel variation (0-255)
    label: Variation
    min: 0
    max: 255
    step: 1
    unit: dmx
---*/

val baseColour = params.colour("baseColour")
val variation = params.int("variation")

fun vary(base: Int, seed: Double): Int {
    val v = (sin(phase * seed) * variation).toInt()
    return (base + v).coerceIn(0, 255)
}

FxOutput.Colour(ExtendedColour(
    color = Color(
        vary(baseColour.color.red, 127.0),
        vary(baseColour.color.green, 211.0),
        vary(baseColour.color.blue, 311.0),
    ),
    white = vary(baseColour.white.toInt(), 173.0).toUByte(),
    amber = vary(baseColour.amber.toInt(), 239.0).toUByte(),
    uv = vary(baseColour.uv.toInt(), 283.0).toUByte(),
))
