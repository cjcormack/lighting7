package uk.me.cormack.lighting7.fixture.property

/**
 * Interface for controlling pan/tilt position properties.
 *
 * This is the unified interface that works for both individual fixtures and groups.
 * Position is represented as two independent sliders for pan (horizontal) and tilt (vertical).
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * fixture.pan.value = 128u  // Center pan
 * fixture.tilt.value = 64u  // Tilt down
 *
 * // Group
 * group.pan.value = 128u    // Center all fixtures
 * ```
 */
interface Position {
    /**
     * Pan (horizontal) position slider.
     * Typically 0 = full left, 128 = center, 255 = full right.
     */
    val pan: Slider

    /**
     * Tilt (vertical) position slider.
     * Typically 0 = down, 128 = straight, 255 = up.
     */
    val tilt: Slider

    /**
     * Move to the target position over the specified duration.
     *
     * For groups, all members move simultaneously.
     *
     * @param panValue Target pan value
     * @param tiltValue Target tilt value
     * @param fadeMs Duration of the movement in milliseconds
     */
    fun moveTo(panValue: UByte, tiltValue: UByte, fadeMs: Long) {
        pan.fadeToValue(panValue, fadeMs)
        tilt.fadeToValue(tiltValue, fadeMs)
    }

    /**
     * Move pan only to the target value over the specified duration.
     *
     * @param value Target pan value
     * @param fadeMs Duration of the movement in milliseconds
     */
    fun panTo(value: UByte, fadeMs: Long) {
        pan.fadeToValue(value, fadeMs)
    }

    /**
     * Move tilt only to the target value over the specified duration.
     *
     * @param value Target tilt value
     * @param fadeMs Duration of the movement in milliseconds
     */
    fun tiltTo(value: UByte, fadeMs: Long) {
        tilt.fadeToValue(value, fadeMs)
    }
}

/**
 * Extended position interface for aggregate operations (used by groups).
 *
 * Provides access to aggregate pan/tilt sliders with member values.
 */
interface AggregatePosition : Position {
    /**
     * Pan as aggregate slider with member values.
     */
    override val pan: AggregateSlider

    /**
     * Tilt as aggregate slider with member values.
     */
    override val tilt: AggregateSlider
}
