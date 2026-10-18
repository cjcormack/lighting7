package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import kotlin.math.roundToInt

internal data class AxisDmx(val coarse: UByte, val fine: UByte?)

/**
 * A travel-degree position to its axis's DMX: the same mapping as the position template's
 * (`TemplateResolver.degreesToDmx`, and the Stage view's `degreesToDmx`) — the fraction along the
 * annotated range, flipped for an inverted axis, laid over the coarse slider's own `min..max` —
 * carried to 16 bits when [fine] is given, as the Stage view reads it back (`coarse + fine / 256`).
 */
internal fun travelToDmx(degrees: Double, property: Fixture.Property, coarse: DmxSlider, fine: DmxSlider?): AxisDmx {
    val degMin = property.degMin!!
    val degMax = property.degMax!!
    val fraction = ((degrees - degMin) / (degMax - degMin)).coerceIn(0.0, 1.0)
    val effective = if (property.inverted) 1.0 - fraction else fraction
    val min = coarse.min.toInt()
    val max = coarse.max.toInt()
    val exact = min + effective * (max - min)
    if (fine == null) return AxisDmx(exact.roundToInt().coerceIn(min, max).toUByte(), null)
    val sixteen = (exact * 256).roundToInt().coerceIn(min * 256, max * 256)
    val fineValue = (sixteen % 256).coerceIn(fine.min.toInt(), fine.max.toInt())
    return AxisDmx((sixteen / 256).toUByte(), fineValue.toUByte())
}
