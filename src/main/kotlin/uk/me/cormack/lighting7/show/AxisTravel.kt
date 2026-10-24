package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import kotlin.math.roundToInt

internal data class AxisDmx(val coarse: UByte, val fine: UByte?)

/**
 * A travel-degree position to its axis's DMX: the same mapping as the position template's
 * (`PaletteResolver.degreesToDmx`, and the Stage view's `degreesToDmx`) — the fraction along the
 * annotated range, flipped for an inverted axis, laid over the coarse slider's own `min..max` —
 * carried to 16 bits when [fine] is given, as the Stage view reads it back (`coarse + fine / 256`).
 */
internal fun travelToDmx(degrees: Double, attribute: Fixture.Attribute, coarse: DmxSlider, fine: DmxSlider?): AxisDmx {
    val degMin = attribute.degMin!!
    val degMax = attribute.degMax!!
    val fraction = ((degrees - degMin) / (degMax - degMin)).coerceIn(0.0, 1.0)
    val effective = if (attribute.inverted) 1.0 - fraction else fraction
    val min = coarse.min.toInt()
    val max = coarse.max.toInt()
    val exact = min + effective * (max - min)
    if (fine == null) return AxisDmx(exact.roundToInt().coerceIn(min, max).toUByte(), null)
    val sixteen = (exact * 256).roundToInt().coerceIn(min * 256, max * 256)
    val fineValue = (sixteen % 256).coerceIn(fine.min.toInt(), fine.max.toInt())
    return AxisDmx((sixteen / 256).toUByte(), fineValue.toUByte())
}

/**
 * [travelToDmx] backwards: an axis's DMX — the coarse byte and, where the head has one, the fine —
 * to its travel degrees. With no fine, the coarse byte is taken as exact, which is what
 * [travelToDmx] rounds a coarse-only head to.
 */
internal fun dmxToTravel(coarse: UByte, fine: UByte?, attribute: Fixture.Attribute, coarseSlider: DmxSlider): Double {
    val degMin = attribute.degMin!!
    val degMax = attribute.degMax!!
    val min = coarseSlider.min.toInt()
    val max = coarseSlider.max.toInt()
    val exact = coarse.toInt() + (fine?.toInt() ?: 0) / 256.0
    val effective = ((exact - min) / (max - min).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val fraction = if (attribute.inverted) 1.0 - effective else effective
    return degMin + fraction * (degMax - degMin)
}
