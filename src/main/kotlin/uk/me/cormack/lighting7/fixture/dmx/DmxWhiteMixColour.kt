package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fixture.property.Colour
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.property.WhiteMix
import java.awt.Color

/**
 * A [Colour] driven through a warm and a cold white channel rather than RGB (venue-visit plan
 * D8): a requested colour is written as [WhiteMix]'s nearest mix, and [value] reads the colour
 * the two channels make, so a head whose sliders were set directly still answers what it shows.
 *
 * It owns no channels of its own — [warm] and [cold] are the head's own white sliders, which stay
 * for direct control. Its red, green and blue sliders are views of [value]'s components: writing
 * one asks for the colour with that component changed, which lands as the nearest mix like any
 * other colour.
 */
class DmxWhiteMixColour(
    val warm: DmxSlider,
    val cold: DmxSlider,
    val mix: WhiteMix,
) : Colour {

    override val redSlider: Slider = ComponentSlider(Color::getRed) { c, v -> Color(v, c.green, c.blue) }
    override val greenSlider: Slider = ComponentSlider(Color::getGreen) { c, v -> Color(c.red, v, c.blue) }
    override val blueSlider: Slider = ComponentSlider(Color::getBlue) { c, v -> Color(c.red, c.green, v) }

    override var value: Color?
        get() {
            val w = warm.value ?: return null
            val c = cold.value ?: return null
            return mix.colourOf(w, c)
        }
        set(newValue) {
            if (newValue == null) return
            val levels = mix.levelsFor(newValue)
            warm.value = levels.warm
            cold.value = levels.cold
        }

    override fun fadeToColour(colour: Color, fadeMs: Long, curve: EasingCurve) {
        val levels = mix.levelsFor(colour)
        warm.fadeToValue(levels.warm, fadeMs, curve)
        cold.fadeToValue(levels.cold, fadeMs, curve)
    }

    private inner class ComponentSlider(
        private val component: (Color) -> Int,
        private val with: (Color, Int) -> Color,
    ) : Slider {
        override var value: UByte?
            get() = this@DmxWhiteMixColour.value?.let { component(it).toUByte() }
            set(newValue) {
                if (newValue == null) return
                val current = this@DmxWhiteMixColour.value ?: Color.BLACK
                this@DmxWhiteMixColour.value = with(current, newValue.toInt())
            }

        override fun fadeToValue(value: UByte, fadeMs: Long, curve: EasingCurve) {
            val current = this@DmxWhiteMixColour.value ?: Color.BLACK
            fadeToColour(with(current, value.toInt()), fadeMs, curve)
        }
    }
}
