package uk.me.cormack.lighting7.fixture

/**
 * Categories for fixture properties, used for UI grouping and display.
 */
enum class PropertyCategory {
    DIMMER,
    COLOUR,
    POSITION,
    UV,
    STROBE,
    AMBER,
    WHITE,
    SPEED,
    SETTING,
    OTHER
}

/**
 * Marks a property as a controllable fixture property.
 *
 * @param description Human-readable description for display
 * @param category The property category for UI grouping
 * @param bundleWithColour If true, this slider will be bundled with the main colour property
 *                         (used for white, amber, UV channels that extend RGB)
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureProperty(
    val description: String = "",
    val category: PropertyCategory = PropertyCategory.OTHER,
    val bundleWithColour: Boolean = false
)
