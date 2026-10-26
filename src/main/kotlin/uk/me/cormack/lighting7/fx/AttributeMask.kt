package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.AttributeCategory

/**
 * The console attribute mask — Intensity / Position / Colour / Beam — used by Record,
 * Include and Update to scope which properties an operation touches.
 *
 * Deliberately coarser than [AttributeCategory]. A category-level mask would be
 * *fixture-dependent*: the same physical attribute is annotated differently across heads
 * (a gobo wheel is a `DmxFixtureSetting` on one fixture and a plain slider on another, and
 * [CueAssignmentResolver.parseAssignmentValue]'s own comment notes the GOBO/SETTING split is
 * "only a labelling choice"). Ticking `GOBO` would then silently miss a gobo modelled as
 * `SETTING`. I/P/C/B collapses exactly that instability, and it is the vocabulary the
 * programmer redesign proposal (§2, §3.4) is written in.
 */
enum class AttributeFamily {
    INTENSITY,
    POSITION,
    COLOUR,
    BEAM,
}

/**
 * Which mask group a property category belongs to.
 *
 * Exhaustive on purpose: adding a [AttributeCategory] is a compile error here until it is
 * classified, rather than silently falling into a default bucket and quietly changing what
 * an unmasked-looking Record writes.
 */
fun AttributeCategory.family(): AttributeFamily = when (this) {
    // Intensity-shaped. STROBE lives here rather than in BEAM because it is an intensity
    // modulation (HTP, like DIMMER) and operators reach for it alongside level, not
    // alongside gobos.
    AttributeCategory.DIMMER,
    AttributeCategory.STROBE,
    -> AttributeFamily.INTENSITY

    AttributeCategory.PAN,
    AttributeCategory.TILT,
    AttributeCategory.PAN_FINE,
    AttributeCategory.TILT_FINE,
    -> AttributeFamily.POSITION

    // The emitter categories are all colour: UV/amber/white are extra emitters of the same
    // mixed colour, and recording "the colour" without them would record half a look.
    AttributeCategory.COLOUR,
    AttributeCategory.AMBER,
    AttributeCategory.WHITE,
    AttributeCategory.UV,
    -> AttributeFamily.COLOUR

    AttributeCategory.GOBO,
    AttributeCategory.GOBO_ROTATION,
    AttributeCategory.GOBO_ROTATION_MODE,
    AttributeCategory.PRISM,
    AttributeCategory.PRISM_ROTATION,
    AttributeCategory.FOCUS,
    AttributeCategory.ZOOM,
    AttributeCategory.IRIS,
    AttributeCategory.FROST,
    AttributeCategory.SHUTTER,
    AttributeCategory.SHUTTER_ROTATION,
    AttributeCategory.LED_MACRO,
    AttributeCategory.MOVEMENT_MACRO,
    AttributeCategory.RING,
    AttributeCategory.RING_SPEED,
    AttributeCategory.SPEED,
    AttributeCategory.SETTING,
    AttributeCategory.OTHER,
    -> AttributeFamily.BEAM
}

/**
 * The mask group for one property on one fixture, or null when the property does not resolve
 * there (a renamed or removed property — the caller reports it as a skip rather than guessing).
 *
 * `position` is the synthetic pan/tilt pair: it has no `@FixtureAttribute` annotation to read a
 * category from, so it is answered before the lookup — the same special case
 * `CueAssignmentResolver.parseAssignmentValue` and `fixtureCategoryFor` already make. `colour` /
 * `color` / `rgbColour` need no case: [canonicalAttributeName] collapses them onto the
 * annotated `COLOUR` property.
 */
fun familyForAttribute(fixture: GroupableFixture, attribute: String): AttributeFamily? {
    if (attribute.equals("position", ignoreCase = true)) return AttributeFamily.POSITION
    val canonical = canonicalAttributeName(attribute)
    return AttributeChannelWriter.resolveAttribute(fixture, canonical)?.category?.family()
}

/**
 * Parse a wire mask (`["COLOUR", "POSITION"]`) into a filter set.
 *
 * Null or empty means "no mask" — every property is in scope. An unknown token throws so the
 * route answers 400 rather than silently recording more than the operator asked for; a mask
 * that quietly widens is the failure mode worth being loud about.
 */
fun parseFamilies(raw: List<String>?): Set<AttributeFamily>? {
    if (raw.isNullOrEmpty()) return null
    val groups = raw.map { token ->
        val trimmed = token.trim()
        AttributeFamily.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown mask '$trimmed' — expected one of ${AttributeFamily.entries.joinToString { it.name }}"
            )
    }.toSet()
    // A mask naming every group is the same as no mask; collapsing it keeps the "is this
    // masked?" branches in Record from having to special-case the full set.
    return if (groups.size == AttributeFamily.entries.size) null else groups
}

/**
 * The lenient twin of [parseFamilies], for a wire with no reply channel — the desk selection's
 * `selection.set` frame. An unknown name is **dropped** rather than refused, as the client's
 * `parseAttributeMask` drops one; a list that leaves nothing, or names every group, is no mask.
 * Never used where a 400 is possible: dropping cannot widen a mask short of dropping everything,
 * and a frame with no reply has nowhere to put a refusal.
 */
fun parseFamiliesLenient(raw: List<String>?): Set<AttributeFamily>? {
    if (raw.isNullOrEmpty()) return null
    val groups = raw.mapNotNullTo(LinkedHashSet()) { token ->
        val trimmed = token.trim()
        AttributeFamily.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
    }
    return if (groups.isEmpty() || groups.size == AttributeFamily.entries.size) null else groups
}

/** The wire spelling of a mask: the names in declaration order, so two masks compare as lists. */
fun Set<AttributeFamily>.toMaskNames(): List<String> =
    AttributeFamily.entries.filter { it in this }.map { it.name }

/** The layer spelling of a mask — `"INTENSITY,COLOUR"`, what `CueComposer` parses back with [parseFamilies]. */
fun Set<AttributeFamily>.toAttributeMask(): String = toMaskNames().joinToString(",")

/** True when [group] passes [mask]. A null mask passes everything; a null group never does. */
fun maskAllows(mask: Set<AttributeFamily>?, group: AttributeFamily?): Boolean =
    mask == null || (group != null && group in mask)

/**
 * The attribute family an effect's library [fxFamily] belongs to.
 *
 * The FX family vocabulary is the library's own (`"intensity"` / `"colour"` / `"position"` /
 * `"beam"` / `"steps"` / `"controls"`). `"steps"`, `"controls"` and anything
 * unrecognised answer null rather than defaulting into BEAM, so an unknown family under-banks
 * rather than mis-banks: a Steps FX's family is what it steps ([stepsFxFamily]).
 *
 * One map for a Look's families, a palette's (fx-templates D4) and the press's family replacement:
 * two copies would be two answers to "which column of the busk view does this belong in".
 */
internal fun attributeFamilyForFxFamily(fxFamily: String): AttributeFamily? = when (fxFamily.lowercase()) {
    "intensity" -> AttributeFamily.INTENSITY
    "colour", "color" -> AttributeFamily.COLOUR
    "position" -> AttributeFamily.POSITION
    "beam" -> AttributeFamily.BEAM
    else -> null
}

/** Wave's registry id (fx-settings plan D2): the level waves as one FX. */
const val WAVE_ID = "Wave"

/** The library family Wave is filed under, and stored with, on every attribute it drives. */
const val WAVE_FAMILY = "intensity"

/**
 * The attribute family an FX of library [fxFamily] is in while it drives [attribute] (fx-settings
 * plan D5): Beam on a beam attribute — Wave, an Intensity FX, on zoom, iris, frost or focus — else
 * its library family's ([attributeFamilyForFxFamily]). A dimmer or UV channel is the Intensity FX's
 * own, as it always was for the speed masters and an FX palette's bank. Null where the library
 * family names none (`steps`, `controls`), which [attribute] does not change.
 *
 * The one reading for the speed-master routing ([familyMasterUuidFor]), an FX palette's family, a
 * Look's families and the Timeline; the client's `fxAttributeFamily` (`lib/attributeFamily.ts`) is
 * its twin. A stored FX keeps its library family; this is read over it.
 */
internal fun fxAttributeFamily(fxFamily: String, attribute: String?): AttributeFamily? {
    val library = attributeFamilyForFxFamily(fxFamily) ?: return null
    val driven = attribute?.takeIf { it.isNotBlank() }?.let { PaletteAttribute.ofOrNull(it)?.family }
    return if (driven == AttributeFamily.BEAM) AttributeFamily.BEAM else library
}

/** [fxAttributeFamily] in the library's own words — what a speed master's `family` says. */
internal fun fxFamilyOnAttribute(fxFamily: String, attribute: String?): String =
    if (fxAttributeFamily(fxFamily, attribute) == AttributeFamily.BEAM) "beam" else fxFamily
