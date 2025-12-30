package uk.me.cormack.lighting7.fixture

/**
 * Marker interface for fixture classes that belong to a multi-mode fixture family.
 *
 * This associates a fixture class with its channel mode, enabling runtime
 * queries about mode-specific capabilities and documentation generation.
 *
 * Multi-mode fixture families are implemented as sealed class hierarchies where:
 * - The sealed base class contains shared functionality and common enums
 * - Each mode is a separate subclass with its own @FixtureType annotation
 * - Each subclass implements only the traits available in that mode
 *
 * Example:
 * ```kotlin
 * sealed class MyFixture(...) : DmxFixture(...), MultiModeFixtureFamily<MyFixtureMode> {
 *
 *     @FixtureType("my-fixture-6ch")
 *     class Mode6Ch(...) : MyFixture(...), FixtureWithDimmer {
 *         override val mode = MyFixtureMode.MODE_6CH
 *         // 6-channel specific properties...
 *     }
 *
 *     @FixtureType("my-fixture-12ch")
 *     class Mode12Ch(...) : MyFixture(...), FixtureWithDimmer, FixtureWithColour {
 *         override val mode = MyFixtureMode.MODE_12CH
 *         // 12-channel specific properties...
 *     }
 * }
 * ```
 *
 * @param M The channel mode enum/sealed class for this fixture family
 */
interface MultiModeFixtureFamily<M : DmxChannelMode> {
    /** The channel mode this fixture instance is configured for */
    val mode: M

    /**
     * Fixture family name (shared across all modes).
     * Defaults to the enclosing class name for nested classes, or the class name itself.
     */
    val familyName: String
        get() = this::class.java.enclosingClass?.simpleName
            ?: this::class.simpleName
            ?: "Unknown"
}
