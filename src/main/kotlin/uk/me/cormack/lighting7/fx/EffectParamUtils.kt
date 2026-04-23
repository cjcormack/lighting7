package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve
import java.awt.Color

/**
 * Utility functions for parsing effect parameters from string maps.
 *
 * These are used by effect registration factories to deserialize parameter
 * values from the API/WebSocket string representation into typed values.
 */

fun String.toUByteParam(): UByte? = toIntOrNull()?.coerceIn(0, 255)?.toUByte()

fun String.toEasingCurveParam(): EasingCurve? = try {
    EasingCurve.valueOf(this.uppercase())
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Parse a colour string into a plain RGB [Color].
 *
 * Delegates to [parseExtendedColour] and extracts just the RGB component.
 */
fun parseColor(colorString: String): Color {
    return parseExtendedColour(colorString).color
}

/** Parse a serialised palette (`List<String>`, as stored on cues / presets / stacks) to [ExtendedColour]s. */
fun List<String>.toPaletteColours(): List<ExtendedColour> = map { parseExtendedColour(it) }

/**
 * Parse a colour string into an [ExtendedColour].
 *
 * Supported formats:
 * - Named colours: "red", "green", "blue", "yellow", "cyan", "magenta", "orange", "pink", "white", "black"
 * - Hex: "#FF0000", "FF0000", "#F00"
 * - Extended: "#ff0000;w128;a64;uv200" (semicolons separate optional W/A/UV channels)
 */
fun parseExtendedColour(colorString: String): ExtendedColour {
    val parts = colorString.split(";")
    val rgbPart = parts[0].trim()

    val baseColor = when (rgbPart.lowercase()) {
        "red" -> Color.RED
        "green" -> Color.GREEN
        "blue" -> Color.BLUE
        "yellow" -> Color.YELLOW
        "cyan" -> Color.CYAN
        "magenta" -> Color.MAGENTA
        "orange" -> Color.ORANGE
        "pink" -> Color.PINK
        "white" -> Color.WHITE
        "black" -> Color.BLACK
        else -> {
            val hex = rgbPart.removePrefix("#")
            when (hex.length) {
                6 -> Color(hex.toInt(16))
                3 -> {
                    val r = hex.substring(0, 1).toInt(16)
                    val g = hex.substring(1, 2).toInt(16)
                    val b = hex.substring(2, 3).toInt(16)
                    Color(r * 17, g * 17, b * 17)
                }
                else -> Color.WHITE
            }
        }
    }

    var white: UByte = 0u
    var amber: UByte = 0u
    var uv: UByte = 0u

    for (i in 1 until parts.size) {
        val part = parts[i].trim().lowercase()
        when {
            part.startsWith("uv") -> uv = part.removePrefix("uv").toIntOrNull()?.coerceIn(0, 255)?.toUByte() ?: 0u
            part.startsWith("w") -> white = part.removePrefix("w").toIntOrNull()?.coerceIn(0, 255)?.toUByte() ?: 0u
            part.startsWith("a") -> amber = part.removePrefix("a").toIntOrNull()?.coerceIn(0, 255)?.toUByte() ?: 0u
        }
    }

    return ExtendedColour(baseColor, white, amber, uv)
}
