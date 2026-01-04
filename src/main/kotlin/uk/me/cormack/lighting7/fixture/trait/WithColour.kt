package uk.me.cormack.lighting7.fixture.trait

import uk.me.cormack.lighting7.fixture.property.Colour

/**
 * Trait for targets that have RGB colour control.
 *
 * This is the unified trait that works for both individual fixtures and groups.
 * Implement this interface to indicate that a target has colour capability.
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * val fixture: WithColour = ...
 * fixture.rgbColour.value = Color.RED
 *
 * // Group (via extension property)
 * val group: FixtureGroup<HexFixture> = ...
 * group.rgbColour.value = Color.GREEN  // Sets all members
 * ```
 */
interface WithColour {
    /**
     * RGB colour control.
     */
    val rgbColour: Colour
}
