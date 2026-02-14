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
 * Roles for promoting a property to the compact fixture card display.
 * Up to two properties can be promoted: one primary (top row) and one secondary (bottom row).
 */
enum class CompactDisplayRole {
    /** Not shown on the compact card (default). */
    NONE,
    /** Shown in the top row of the compact card (best for mode/setting/status). */
    PRIMARY,
    /** Shown in the bottom row of the compact card (best for sliders/levels). */
    SECONDARY;

    /** Serialize to a JSON-friendly string, or null for NONE. */
    fun serialized(): String? = when (this) {
        NONE -> null
        PRIMARY -> "primary"
        SECONDARY -> "secondary"
    }
}

/**
 * Marks a property as a controllable fixture property.
 *
 * @param description Human-readable description for display
 * @param category The property category for UI grouping
 * @param bundleWithColour If true, this slider will be bundled with the main colour property
 *                         (used for white, amber, UV channels that extend RGB)
 * @param compactDisplay If set to PRIMARY or SECONDARY, this property will be promoted
 *                        to the compact fixture card display
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureProperty(
    val description: String = "",
    val category: PropertyCategory = PropertyCategory.OTHER,
    val bundleWithColour: Boolean = false,
    val compactDisplay: CompactDisplayRole = CompactDisplayRole.NONE
)
