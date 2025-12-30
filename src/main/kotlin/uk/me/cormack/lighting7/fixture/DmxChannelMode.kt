package uk.me.cormack.lighting7.fixture

/**
 * Represents a DMX channel mode configuration for multi-mode fixtures.
 *
 * Many professional lighting fixtures support multiple DMX personality modes,
 * each using a different number of channels and exposing different capabilities.
 * The mode is typically set via DIP switches on the fixture and is fixed at
 * construction time.
 *
 * Implement this interface as an enum or sealed class to define the available
 * modes for a fixture family.
 *
 * Example:
 * ```kotlin
 * enum class MyFixtureMode(
 *     override val channelCount: Int,
 *     override val modeName: String
 * ) : DmxChannelMode {
 *     MODE_6CH(6, "6-Channel"),
 *     MODE_12CH(12, "12-Channel Full")
 * }
 * ```
 */
interface DmxChannelMode {
    /** Number of DMX channels used in this mode */
    val channelCount: Int

    /** Human-readable name for this mode (e.g., "14-Channel", "Full Control") */
    val modeName: String
}
