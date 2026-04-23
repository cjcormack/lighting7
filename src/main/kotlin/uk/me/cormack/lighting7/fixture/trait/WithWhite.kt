package uk.me.cormack.lighting7.fixture.trait

import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Trait for targets that have a dedicated white (W) colour channel.
 *
 * This is the unified trait that works for both individual fixtures and groups.
 * Implement this interface to indicate that a target has a white colour capability
 * bundled with its RGB colour (e.g. RGBW / RGBWA / RGBWA-UV fixtures).
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * val fixture: WithWhite = ...
 * fixture.white.value = 200u      // High white output
 *
 * // Group (via extension property)
 * val group: FixtureGroup<HexFixture> = ...
 * group.white.value = 128u        // Medium white on all fixtures
 * ```
 */
interface WithWhite {
    /**
     * White (W) slider.
     * Typically 0 = off, 255 = full white output.
     */
    val white: Slider
}
