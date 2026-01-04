package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * DMX implementation of Slider for controlling a single DMX channel.
 *
 * For single fixtures, `value` always returns the actual channel value (never null).
 * The nullable return type in the interface is for group compatibility.
 *
 * @param transaction The controller transaction context (required for read/write)
 * @param universe The DMX universe containing this channel
 * @param channelNo The channel number within the universe
 * @param min Minimum allowed value (clamped on set)
 * @param max Maximum allowed value (clamped on set)
 */
open class DmxSlider(
    val transaction: ControllerTransaction?,
    val universe: Universe,
    val channelNo: Int,
    val min: UByte = 0u,
    val max: UByte = 255u,
) : Slider {

    private val nonNullTransaction: ControllerTransaction
        get() = checkNotNull(transaction) {
            "Attempted to use fixture outside of a transaction"
        }

    /**
     * Current channel value.
     *
     * For single fixtures, this always returns the actual value (never null).
     * Setting to null is ignored.
     */
    override var value: UByte?
        get() = nonNullTransaction.getValue(universe, channelNo)
        set(newValue) {
            if (newValue != null) {
                val clamped = maxOf(min, minOf(newValue, max))
                nonNullTransaction.setValue(universe, channelNo, clamped)
            }
        }

    /**
     * Fade to the target value over the specified duration.
     *
     * @param value Target value to fade to (will be clamped to min/max range)
     * @param fadeMs Duration of the fade in milliseconds
     */
    override fun fadeToValue(value: UByte, fadeMs: Long) {
        val clamped = maxOf(min, minOf(value, max))
        nonNullTransaction.setValue(universe, channelNo, clamped, fadeMs)
    }
}
