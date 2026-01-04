package uk.me.cormack.lighting7.fixture.trait

import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Trait for targets that have dimmer (brightness) control.
 *
 * This is the unified trait that works for both individual fixtures and groups.
 * Implement this interface to indicate that a target has dimmable capability.
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * val fixture: WithDimmer = ...
 * fixture.dimmer.value = 255u
 *
 * // Group (via extension property)
 * val group: FixtureGroup<HexFixture> = ...
 * group.dimmer.value = 255u  // Sets all members
 * ```
 */
interface WithDimmer {
    /**
     * Dimmer (brightness) slider.
     * Typically 0 = off, 255 = full brightness.
     */
    val dimmer: Slider
}
