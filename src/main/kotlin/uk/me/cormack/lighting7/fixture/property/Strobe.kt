package uk.me.cormack.lighting7.fixture.property

/**
 * Interface for controlling strobe effects.
 *
 * This is the unified interface that works for both individual fixtures and groups.
 * Strobe control typically has two modes: full on (no strobe) and strobing at a given intensity.
 *
 * Example usage:
 * ```kotlin
 * // Single fixture
 * fixture.strobe.fullOn()        // Turn strobe off, full brightness
 * fixture.strobe.strobe(128u)    // Medium strobe speed
 *
 * // Group
 * group.strobe.fullOn()          // All fixtures full on
 * group.strobe.strobe(200u)      // All fixtures fast strobe
 * ```
 */
interface Strobe {
    /**
     * Set to full on mode (no strobing).
     * This typically sets the strobe channel to the "no strobe" value
     * while maintaining current dimmer/colour settings.
     */
    fun fullOn()

    /**
     * Enable strobing at the specified intensity/speed.
     *
     * @param intensity Strobe intensity/speed (fixture-dependent, typically 0-255)
     */
    fun strobe(intensity: UByte)
}

/**
 * Extended strobe interface for aggregate operations (used by groups).
 *
 * Note: Strobe doesn't have a readable "value" in the same way as sliders,
 * so aggregate operations are primarily write-only.
 */
interface AggregateStrobe : Strobe {
    /**
     * Number of members in this aggregate.
     */
    val memberCount: Int
}
