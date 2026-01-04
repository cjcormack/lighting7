package uk.me.cormack.lighting7.fixture.property

/**
 * Interface for controlling a single-value property (e.g., dimmer, UV, pan, tilt).
 *
 * This is the unified interface that works for both individual fixtures and groups:
 * - For individual fixtures: `value` always returns the actual channel value
 * - For groups: `value` returns the uniform value if all members match, or null if non-uniform
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * fixture.dimmer.value = 255u
 * val level = fixture.dimmer.value  // Always non-null for single fixtures
 *
 * // Group
 * group.dimmer.value = 200u         // Sets all members
 * val level = group.dimmer.value    // null if members have different values
 * ```
 */
interface Slider {
    /**
     * Current value of the slider.
     *
     * For single fixtures, this always returns the actual value.
     * For groups, returns the uniform value if all members have the same value,
     * or null if members have different values.
     *
     * Setting this value applies to all underlying channels (for groups, all members).
     */
    var value: UByte?

    /**
     * Fade to the target value over the specified duration.
     *
     * For groups, all members fade simultaneously.
     *
     * @param value Target value to fade to
     * @param fadeMs Duration of the fade in milliseconds
     */
    fun fadeToValue(value: UByte, fadeMs: Long)
}

/**
 * Extended slider interface for aggregate operations (used by groups).
 *
 * Provides access to individual member values and uniformity checks.
 */
interface AggregateSlider : Slider {
    /**
     * Values from all members in group order.
     * For nested groups, this returns the values from direct children
     * (which may themselves be aggregate values).
     */
    val memberValues: List<UByte?>

    /**
     * True if all members have the same value.
     */
    val isUniform: Boolean
        get() = memberValues.filterNotNull().distinct().size <= 1

    /**
     * Minimum value across all members, or null if no values present.
     */
    val minValue: UByte?
        get() = memberValues.filterNotNull().minOrNull()

    /**
     * Maximum value across all members, or null if no values present.
     */
    val maxValue: UByte?
        get() = memberValues.filterNotNull().maxOrNull()

    /**
     * Number of members in this aggregate.
     */
    val memberCount: Int
        get() = memberValues.size
}
