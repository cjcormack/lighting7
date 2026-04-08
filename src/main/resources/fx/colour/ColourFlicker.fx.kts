/*---
id: ColourFlicker
name: Colour Flicker
category: colour
outputType: COLOUR
effectMode: STANDARD
compatibleProperties: [rgbColour]
parameters:
  - name: baseColour
    type: colour
    default: "#ff0000"
    description: Base colour to vary around
  - name: variation
    type: int
    default: "50"
    description: Maximum channel variation (0-255)
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
