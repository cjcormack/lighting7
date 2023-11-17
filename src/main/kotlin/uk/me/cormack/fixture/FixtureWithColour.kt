package uk.me.cormack.fixture

import java.awt.Color

@FixtureProperty("Colour")
interface FixtureWithColour {
    val whiteSupport: Boolean
    val amberSupport: Boolean
    val uvSupport: Boolean

    var rgbColor: Color
    var whiteLevel: UByte
    var amberLevel: UByte
    var uvLevel: UByte

    fun fadeToColour(rgbColor: Color, fadeMs: Long)
    fun fadeToWhiteLevel(level: UByte, fadeMs: Long)
    fun fadeToAmberLevel(level: UByte, fadeMs: Long)
    fun fadeToUvLevel(level: UByte, fadeMs: Long)
}
