package uk.me.cormack.lighting7.fixture.hue

import uk.me.cormack.lighting7.fixture.FixtureWithColour
import java.awt.Color

class HueFixtureWithColour(
    val controller: HueController,
    val lightId: Int,
    val maxBrightness: Int = 254
): FixtureWithColour {
    override val whiteSupport: Boolean = false
    override val amberSupport: Boolean = false
    override val uvSupport: Boolean = false

    override var rgbColor: Color
        get() = throw NotImplementedError()
        set(value) {
            controller.setHueLightColor(lightId, value, maxBrightness)
        }

    override var whiteLevel: UByte
        get() = 0u
        set(value) {}

    override var amberLevel: UByte
        get() = 0u
        set(value) {}

    override var uvLevel: UByte
        get() = 0u
        set(value) {}

    override fun fadeToColour(rgbColor: Color, fadeMs: Long) {
        controller.setHueLightColor(lightId, rgbColor, maxBrightness, fadeMs)
    }

    override fun fadeToWhiteLevel(level: UByte, fadeMs: Long) {}

    override fun fadeToAmberLevel(level: UByte, fadeMs: Long) {}

    override fun fadeToUvLevel(level: UByte, fadeMs: Long) {}
}
