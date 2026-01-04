package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.property.Colour
import java.awt.Color

/**
 * DMX implementation of Colour for controlling RGB channels.
 *
 * For single fixtures, `value` always returns the actual colour (never null).
 * The nullable return type in the interface is for group compatibility.
 *
 * @param transaction The controller transaction context
 * @param universe The DMX universe containing the colour channels
 * @param redChannelNo Red channel number
 * @param greenChannelNo Green channel number
 * @param blueChannelNo Blue channel number
 */
class DmxColour(
    val transaction: ControllerTransaction?,
    val universe: Universe,
    redChannelNo: Int,
    greenChannelNo: Int,
    blueChannelNo: Int,
) : Colour {

    override val redSlider: DmxSlider = DmxSlider(transaction, universe, redChannelNo)
    override val greenSlider: DmxSlider = DmxSlider(transaction, universe, greenChannelNo)
    override val blueSlider: DmxSlider = DmxSlider(transaction, universe, blueChannelNo)

    /**
     * Current colour value.
     *
     * For single fixtures, this always returns the actual colour (never null).
     * Setting to null is ignored.
     */
    override var value: Color?
        get() {
            val red = redSlider.value?.toInt() ?: return null
            val green = greenSlider.value?.toInt() ?: return null
            val blue = blueSlider.value?.toInt() ?: return null
            return Color(red, green, blue)
        }
        set(newValue) {
            if (newValue != null) {
                redSlider.value = newValue.red.toUByte()
                greenSlider.value = newValue.green.toUByte()
                blueSlider.value = newValue.blue.toUByte()
            }
        }

    /**
     * Fade to the target colour over the specified duration.
     *
     * @param colour Target colour to fade to
     * @param fadeMs Duration of the fade in milliseconds
     */
    override fun fadeToColour(colour: Color, fadeMs: Long) {
        redSlider.fadeToValue(colour.red.toUByte(), fadeMs)
        greenSlider.fadeToValue(colour.green.toUByte(), fadeMs)
        blueSlider.fadeToValue(colour.blue.toUByte(), fadeMs)
    }
}
