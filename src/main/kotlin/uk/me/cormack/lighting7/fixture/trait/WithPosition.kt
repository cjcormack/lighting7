package uk.me.cormack.lighting7.fixture.trait

import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Trait for targets that have pan/tilt position control.
 *
 * This is the unified trait that works for both individual fixtures and groups.
 * Implement this interface to indicate that a target has position capability.
 * Typically implemented by moving head fixtures and scanners.
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * val fixture: WithPosition = ...
 * fixture.pan.value = 128u   // Center pan
 * fixture.tilt.value = 64u   // Tilt down
 *
 * // Group (via extension property)
 * val group: FixtureGroup<MovingHeadFixture> = ...
 * group.pan.value = 128u     // Center all fixtures
 * ```
 */
interface WithPosition {
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
}
