package uk.me.cormack.lighting7.dmx

/**
 * Represents a change to a DMX channel value with optional fade and easing.
 *
 * @param newValue The target DMX value (0-255)
 * @param fadeMs Duration of the fade in milliseconds (0 for instant)
 * @param curve The easing curve to use for the fade (defaults to LINEAR)
 */
data class ChannelChange(
    val newValue: UByte,
    val fadeMs: Long,
    val curve: EasingCurve = EasingCurve.LINEAR
)
