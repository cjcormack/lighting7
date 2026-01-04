package uk.me.cormack.lighting7.fixture.trait

import uk.me.cormack.lighting7.fixture.property.Strobe

/**
 * Trait for targets that have strobe control.
 *
 * This is the unified trait that works for both individual fixtures and groups.
 * Implement this interface to indicate that a target has strobe capability.
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * val fixture: WithStrobe = ...
 * fixture.strobe.fullOn()         // No strobe
 * fixture.strobe.strobe(128u)     // Medium strobe speed
 *
 * // Group (via extension property)
 * val group: FixtureGroup<HexFixture> = ...
 * group.strobe.strobe(200u)       // Fast strobe on all fixtures
 * ```
 */
interface WithStrobe {
    /**
     * Strobe control.
     */
    val strobe: Strobe
}
