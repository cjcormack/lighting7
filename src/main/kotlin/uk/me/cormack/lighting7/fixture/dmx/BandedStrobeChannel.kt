package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.property.Strobe
import kotlin.math.roundToInt

/**
 * Shared [Strobe] implementation for the common case where a fixture's
 * shutter/strobe channel exposes a single linear strobe band `[strobeMin,
 * strobeMax]` plus a separate "full on" / "shutter open" value.
 *
 * `strobe(intensity)` linearly maps the input `0..255` onto `strobeMin..strobeMax`.
 * `fullOn()` writes [fullOnValue].
 *
 * For fixtures whose shutter channel hides Reset / Lamp control bands above
 * the strobe band (e.g. MAC 250, Robe ColorSpot 575), pass [max] equal to
 * `strobeMax` so neither raw `value` writes nor [strobe] calls can wander
 * into those bands. Lamp/reset bands above the clamp are then only reachable
 * via raw transaction writes from explicit safety methods.
 *
 * Some fixtures interpret an input intensity of 0 as "no strobe, just keep
 * the LED open" rather than the slowest strobe step. Set
 * [zeroIntensityIsFullOn] to short-circuit `strobe(0)` to [fullOn] for those.
 *
 * @param strobeMin Lower bound of the linear strobe band.
 * @param strobeMax Upper bound of the linear strobe band.
 * @param fullOnValue Value written by [fullOn] (typically 0 or a dedicated
 *                    "shutter open" level outside the strobe band).
 * @param max Slider clamp for the underlying [DmxSlider]; defaults to 255 but
 *            should be set to `strobeMax` when the channel has dangerous bands
 *            above the strobe range.
 * @param zeroIntensityIsFullOn If true, `strobe(0u)` writes [fullOnValue]
 *                              instead of `strobeMin`.
 */
open class BandedStrobeChannel(
    transaction: ControllerTransaction?,
    universe: Universe,
    channelNo: Int,
    private val strobeMin: UByte,
    private val strobeMax: UByte,
    private val fullOnValue: UByte = 0u,
    max: UByte = 255u,
    private val zeroIntensityIsFullOn: Boolean = false,
) : DmxSlider(transaction, universe, channelNo, max = max), Strobe {
    override fun fullOn() {
        value = fullOnValue
    }

    override fun strobe(intensity: UByte) {
        if (zeroIntensityIsFullOn && intensity == 0u.toUByte()) {
            fullOn()
            return
        }
        val span = (strobeMax - strobeMin).toFloat()
        value = ((span / 255F * intensity.toFloat()).roundToInt() + strobeMin.toInt()).toUByte()
    }
}
