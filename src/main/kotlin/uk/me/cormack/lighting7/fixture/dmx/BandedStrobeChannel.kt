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
 * For fixtures whose shutter channel hides bands above the strobe band that
 * composition must not reach (the MAC 250's reset and lamp, the Robe ColorSpot
 * 575's pulses and random strobe), pass [max] equal to `strobeMax` so neither
 * raw `value` writes nor [strobe] calls can wander into them. Bands above the
 * clamp are then only reachable via raw transaction writes — and the MAC 250's
 * lamp and reset bands only as fixture commands, which the desk holds and whose
 * bands the output refuses to anything else (`state/CommandOutput.kt`).
 *
 * Some fixtures interpret an input intensity of 0 as "no strobe, just keep
 * the LED open" rather than the slowest strobe step. Set
 * [zeroIntensityIsFullOn] to short-circuit `strobe(0)` to [fullOn] for those.
 *
 * A higher intensity is always a faster strobe. Where the band runs the other way on the channel —
 * the MAC 250's "strobe, fast → slow" — set [fastToSlow], so `strobe(255)` writes [strobeMin] (the
 * fastest) and `strobe(1)` near [strobeMax]; `StrobeBandsTest` holds every writer to its declared
 * rates.
 *
 * @param strobeMin Lower bound of the linear strobe band.
 * @param strobeMax Upper bound of the linear strobe band.
 * @param fullOnValue Value written by [fullOn] (typically 0 or a dedicated
 *                    "shutter open" level outside the strobe band).
 * @param max Slider clamp for the underlying [DmxSlider]; defaults to 255 but
 *            should be set to `strobeMax` when the channel has dangerous bands
 *            above the strobe range.
 * @param zeroIntensityIsFullOn If true, `strobe(0u)` writes [fullOnValue]
 *                              instead of the slowest strobe.
 * @param fastToSlow The band's fastest rate is at [strobeMin]: intensity runs
 *                   down the band rather than up it.
 */
open class BandedStrobeChannel(
    transaction: ControllerTransaction?,
    universe: Universe,
    channelNo: Int,
    private val strobeMin: UByte,
    private val strobeMax: UByte,
    override val fullOnValue: UByte = 0u,
    max: UByte = 255u,
    private val zeroIntensityIsFullOn: Boolean = false,
    private val fastToSlow: Boolean = false,
) : DmxSlider(transaction, universe, channelNo, max = max), Strobe {
    override fun fullOn() {
        value = fullOnValue
    }

    override fun strobe(intensity: UByte) {
        value = strobeLevel(intensity)
    }

    /**
     * The channel value [strobe] writes for [intensity] — pure, so `StrobeBandsTest` can hold every
     * level it reaches inside the property's declared strobe bands without a transaction.
     */
    fun strobeLevel(intensity: UByte): UByte {
        if (zeroIntensityIsFullOn && intensity == 0u.toUByte()) return fullOnValue
        val span = (strobeMax - strobeMin).toFloat()
        val step = (span / 255F * intensity.toFloat()).roundToInt()
        return (if (fastToSlow) strobeMax.toInt() - step else strobeMin.toInt() + step).toUByte()
    }
}
