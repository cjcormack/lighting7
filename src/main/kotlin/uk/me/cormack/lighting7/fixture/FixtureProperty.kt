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
 * Each category carries a [defaultComposition] rule used by Layer 4 when merging property
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

    // Beam-shaping roles. All LTP: these are mechanical selections and positions, so
    // max()-ing two contributors is meaningless — the same reasoning as PAN/TILT. Splitting
    // them out of SETTING/OTHER is what lets the 3D stage view recognise them; it already
    // keys off category this way for pan/tilt. A ZOOM slider reuses the existing
    // degMin/degMax annotation fields, where they mean "full beam angle at DMX min / max";
    // a FOCUS slider declares its range with focusNearM/focusFarM.
    GOBO(CompositionRule.LTP),
    GOBO_ROTATION(CompositionRule.LTP),
    // A wheel's function channel: what its GOBO_ROTATION channel means right now. Its bands are
    // named INDEX, ROTATE_FWD and ROTATE_REV (the Source Four Revolution's front wheel); in index
    // the rotation channel is an angle over `indexDegMax`, in either rotate band a speed up to
    // `rpmMax`, turning the way the band names.
    GOBO_ROTATION_MODE(CompositionRule.LTP),
    PRISM(CompositionRule.LTP),
    PRISM_ROTATION(CompositionRule.LTP),
    FOCUS(CompositionRule.LTP),
    ZOOM(CompositionRule.LTP),
    IRIS(CompositionRule.LTP),
    FROST(CompositionRule.LTP),
    LED_MACRO(CompositionRule.LTP),
    MOVEMENT_MACRO(CompositionRule.LTP),

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
 *                  On a FOCUS slider it reverses the focus range instead: DMX min is far focus.
 * @param focusNearM On a FOCUS slider, the nearest focal distance in metres from the aperture,
 *                   at DMX min (DMX max when [inverted]). [Double.NaN] means "unset", as for
 *                   [degMin]; a slider declares both ends or neither.
 * @param focusFarM On a FOCUS slider, the farthest focal distance in metres from the aperture,
 *                  at DMX max (DMX min when [inverted]). Defaults [Double.NaN].
 * @param fineOf Names the coarse property this one is the low byte of, so the pair decodes as one
 *               16-bit value (coarse × 256 + fine). Empty — the default — on every other property.
 *               Generalises the PAN_FINE / TILT_FINE pairing to any 16-bit pair; pan and tilt keep
 *               theirs. A fine property carries its coarse property's category, and every
 *               Stage-view finder that picks "the" property of a category skips it.
 * @param rpmMax On a GOBO_ROTATION slider whose wheel has a GOBO_ROTATION_MODE channel, the speed in
 *               revolutions per minute at DMX max in a rotate band. Defaults [Double.NaN].
 * @param indexDegMax On the same slider, the angle in degrees at DMX max in the index band (DMX min
 *                    is 0°). Defaults [Double.NaN].
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
    val focusNearM: Double = Double.NaN,
    val focusFarM: Double = Double.NaN,
    val fineOf: String = "",
    val rpmMax: Double = Double.NaN,
    val indexDegMax: Double = Double.NaN,
)

/** Resolved composition rule: annotation override takes precedence, else the category default. */
fun FixtureProperty.resolveComposition(): CompositionRule =
    composition.takeUnless { it == CompositionRule.UNSET } ?: category.defaultComposition
