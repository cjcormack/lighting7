package uk.me.cormack.lighting7.fixture.trait

import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Trait for targets that have a dedicated amber (A) colour channel.
 *
 * This is the unified trait that works for both individual fixtures and groups.
 * Implement this interface to indicate that a target has an amber colour capability
 * bundled with its RGB colour (e.g. RGBWA / RGBWA-UV fixtures).
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * val fixture: WithAmber = ...
 * fixture.amber.value = 200u      // High amber output
 *
 * // Group (via extension property)
 * val group: FixtureGroup<HexFixture> = ...
 * group.amber.value = 128u        // Medium amber on all fixtures
 * ```
 */
interface WithAmber {
    /**
     * Amber (A) slider.
     * Typically 0 = off, 255 = full amber output.
     */
    val amber: Slider
}
