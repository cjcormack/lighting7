package uk.me.cormack.lighting7.fixture

import java.awt.Color

abstract class FixtureColour<T: FixtureSlider>(
    val redSlider: T,
    val greenSlider: T,
    val blueSlider: T,
): FixtureMultiSlider<T> {
    override val sliders = mapOf(
        "red" to redSlider,
        "green" to greenSlider,
        "blue" to blueSlider,
    )

    var value: Color
    get() {
        val redLevel = redSlider.value.toInt()
        val greenLevel = greenSlider.value.toInt()
        val blueLevel = blueSlider.value.toInt()

        return Color(redLevel, greenLevel, blueLevel)
    }
    set(value) {
        redSlider.value = value.red.toUByte()
        greenSlider.value = value.green.toUByte()
        blueSlider.value = value.blue.toUByte()
    }

    fun fadeToColour(rgbColor: Color, fadeMs: Long) {
        redSlider.fadeToValue(rgbColor.red.toUByte(), fadeMs)
        greenSlider.fadeToValue(rgbColor.green.toUByte(), fadeMs)
        blueSlider.fadeToValue(rgbColor.blue.toUByte(), fadeMs)
    }
}
