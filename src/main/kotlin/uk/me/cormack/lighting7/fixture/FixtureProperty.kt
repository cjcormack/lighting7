package uk.me.cormack.lighting7.fixture

/**
 * Composition rule determining how multiple contributors to the same property merge.
 *
 * Highest-Takes-Precedence (HTP): output = max(contributors × fade). Used for intensity-like
 * properties where stacked cues should brighten.
 * Last-Takes-Precedence (LTP): output = highest-priority contributor, fades linearly. Used for
 * colour, position, settings — anything where combining values produces nonsense.
 * UNSET: sentinel for the [FixtureProperty.composition] annotation parameter meaning "inherit
 * the category default". Never seen on a resolved [Fixture.Property].
 *
 * See `docs/lighting-composition-model.md` for the full rule table and rationale.
 */
enum class CompositionRule {
    HTP,
    LTP,
    UNSET,
}

/**
 * Categories for fixture properties, used for UI grouping, display, and composition.
 *
 * Each category carries a [defaultComposition] rule used by Layer 3 when merging property
 * assignments from active cues. Individual properties can override this via
 * [FixtureProperty.composition].
 */
enum class PropertyCategory(val defaultComposition: CompositionRule) {
    DIMMER(CompositionRule.HTP),
    COLOUR(CompositionRule.LTP),
    PAN(CompositionRule.LTP),
    TILT(CompositionRule.LTP),
    PAN_FINE(CompositionRule.LTP),
    TILT_FINE(CompositionRule.LTP),
    UV(CompositionRule.HTP),
    STROBE(CompositionRule.HTP),
    AMBER(CompositionRule.LTP),
    WHITE(CompositionRule.LTP),
    SPEED(CompositionRule.LTP),
    SETTING(CompositionRule.LTP),
    OTHER(CompositionRule.LTP),
}

/**
 * Movement axis for pan/tilt sliders. The 3D stage view uses this together with
 * [FixtureProperty.degMin]/[FixtureProperty.degMax]/[FixtureProperty.inverted] to
 * convert raw DMX values into degrees and rotate the moving-head model.
 */
enum class PanTiltAxis {
    NONE,
    PAN,
    TILT;

    fun serialized(): String? = if (this == NONE) null else name
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
 * @param category The property category for UI grouping and default composition
 * @param composition Override the category's default composition rule for this specific property.
 *                    Leave as [CompositionRule.UNSET] (the default) to inherit from the category.
 *                    Use when a fixture's property needs different composition than its category
 *                    suggests (e.g. a DIMMER-classed channel that is really a shutter enum).
 * @param bundleWithColour If true, this slider will be bundled with the main colour property
 *                         (used for white, amber, UV channels that extend RGB)
 * @param compactDisplay If set to PRIMARY or SECONDARY, this property will be promoted
 *                        to the compact fixture card display
 * @param axis Movement axis for moving-head sliders. Defaults to [PanTiltAxis.NONE].
 *             Used together with [degMin]/[degMax]/[inverted] by the 3D stage view to
 *             rotate the head model from live DMX.
 * @param degMin Slider min in degrees (mapped to the slider's DMX min). Defaults to
 *               [Double.NaN] meaning "unset" — Kotlin annotations can't carry null Double
 *               defaults, so NaN is the sentinel and is converted to null at reflect time.
 * @param degMax Slider max in degrees (mapped to the slider's DMX max). Defaults [Double.NaN].
 * @param inverted Reverse the direction of the slider→degrees mapping. Used for fixtures
 *                  whose tilt is mechanically inverted from the DMX-up = stage-up convention.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class FixtureProperty(
    val description: String = "",
    val category: PropertyCategory = PropertyCategory.OTHER,
    val composition: CompositionRule = CompositionRule.UNSET,
    val bundleWithColour: Boolean = false,
    val compactDisplay: CompactDisplayRole = CompactDisplayRole.NONE,
    val axis: PanTiltAxis = PanTiltAxis.NONE,
    val degMin: Double = Double.NaN,
    val degMax: Double = Double.NaN,
    val inverted: Boolean = false,
)

/** Resolved composition rule: annotation override takes precedence, else the category default. */
fun FixtureProperty.resolveComposition(): CompositionRule =
    composition.takeUnless { it == CompositionRule.UNSET } ?: category.defaultComposition
