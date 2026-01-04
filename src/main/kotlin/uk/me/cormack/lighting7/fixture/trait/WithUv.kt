package uk.me.cormack.lighting7.fixture.trait

import uk.me.cormack.lighting7.fixture.property.Slider

/**
 * Trait for targets that have UV (ultraviolet) control.
 *
 * This is the unified trait that works for both individual fixtures and groups.
 * Implement this interface to indicate that a target has UV capability.
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * val fixture: WithUv = ...
 * fixture.uv.value = 200u    // High UV output
 *
 * // Group (via extension property)
 * val group: FixtureGroup<HexFixture> = ...
 * group.uv.value = 128u      // Medium UV on all fixtures
 * ```
 */
interface WithUv {
    /**
     * UV (ultraviolet) slider.
     * Typically 0 = off, 255 = full UV output.
     */
    val uv: Slider
}
