package uk.me.cormack.lighting7.fixture.property

import java.awt.Color

/**
 * Interface for controlling RGB colour properties.
 *
 * This is the unified interface that works for both individual fixtures and groups:
 * - For individual fixtures: `value` always returns the actual colour
 * - For groups: `value` returns the uniform colour if all members match, or null if non-uniform
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * fixture.rgbColour.value = Color.RED
 * val color = fixture.rgbColour.value  // Always non-null for single fixtures
 *
 * // Group
 * group.rgbColour.value = Color.GREEN  // Sets all members
 * val color = group.rgbColour.value    // null if members have different colours
 * ```
 */
interface Colour {
    /**
     * Red channel slider.
     */
    val redSlider: Slider

    /**
     * Green channel slider.
     */
    val greenSlider: Slider

    /**
     * Blue channel slider.
     */
    val blueSlider: Slider

    /**
     * Current colour value.
     *
     * For single fixtures, this always returns the actual colour.
     * For groups, returns the uniform colour if all members have the same colour,
     * or null if members have different colours.
     *
     * Setting this value applies to all underlying channels (for groups, all members).
     */
    var value: Color?

    /**
     * Fade to the target colour over the specified duration.
     *
     * For groups, all members fade simultaneously.
     *
     * @param colour Target colour to fade to
     * @param fadeMs Duration of the fade in milliseconds
     */
    fun fadeToColour(colour: Color, fadeMs: Long)
}

/**
 * Extended colour interface for aggregate operations (used by groups).
 *
 * Provides access to individual member colours and uniformity checks.
 */
interface AggregateColour : Colour {
    /**
     * Red channel as aggregate slider.
     */
    override val redSlider: AggregateSlider

    /**
     * Green channel as aggregate slider.
     */
    override val greenSlider: AggregateSlider

    /**
     * Blue channel as aggregate slider.
     */
    override val blueSlider: AggregateSlider

    /**
     * Colours from all members in group order.
     * For nested groups, this returns the colours from direct children
     * (which may themselves be aggregate values).
     */
    val memberValues: List<Color?>

    /**
     * True if all members have the same colour.
     */
    val isUniform: Boolean
        get() = memberValues.filterNotNull().distinct().size <= 1

    /**
     * Number of members in this aggregate.
     */
    val memberCount: Int
        get() = memberValues.size
}
