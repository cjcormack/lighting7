package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory

/**
 * The console attribute mask — Intensity / Position / Colour / Beam — used by Record,
 * Include and Update to scope which properties an operation touches.
 *
 * Deliberately coarser than [PropertyCategory]. A category-level mask would be
 * *fixture-dependent*: the same physical attribute is annotated differently across heads
 * (a gobo wheel is a `DmxFixtureSetting` on one fixture and a plain slider on another, and
 * [CueAssignmentResolver.parseAssignmentValue]'s own comment notes the GOBO/SETTING split is
 * "only a labelling choice"). Ticking `GOBO` would then silently miss a gobo modelled as
 * `SETTING`. I/P/C/B collapses exactly that instability, and it is the vocabulary the
 * programmer redesign proposal (§2, §3.4) is written in.
 */
enum class PropertyMaskGroup {
    INTENSITY,
    POSITION,
    COLOUR,
    BEAM,
}

/**
 * Which mask group a property category belongs to.
 *
 * Exhaustive on purpose: adding a [PropertyCategory] is a compile error here until it is
 * classified, rather than silently falling into a default bucket and quietly changing what
 * an unmasked-looking Record writes.
 */
fun PropertyCategory.maskGroup(): PropertyMaskGroup = when (this) {
    // Intensity-shaped. STROBE lives here rather than in BEAM because it is an intensity
    // modulation (HTP, like DIMMER) and operators reach for it alongside level, not
    // alongside gobos.
    PropertyCategory.DIMMER,
    PropertyCategory.STROBE,
    -> PropertyMaskGroup.INTENSITY

    PropertyCategory.PAN,
    PropertyCategory.TILT,
    PropertyCategory.PAN_FINE,
    PropertyCategory.TILT_FINE,
    -> PropertyMaskGroup.POSITION

    // The emitter categories are all colour: UV/amber/white are extra emitters of the same
    // mixed colour, and recording "the colour" without them would record half a look.
    PropertyCategory.COLOUR,
    PropertyCategory.AMBER,
    PropertyCategory.WHITE,
    PropertyCategory.UV,
    -> PropertyMaskGroup.COLOUR

    PropertyCategory.GOBO,
    PropertyCategory.GOBO_ROTATION,
    PropertyCategory.PRISM,
    PropertyCategory.PRISM_ROTATION,
    PropertyCategory.FOCUS,
    PropertyCategory.ZOOM,
    PropertyCategory.IRIS,
    PropertyCategory.FROST,
    PropertyCategory.LED_MACRO,
    PropertyCategory.MOVEMENT_MACRO,
    PropertyCategory.SPEED,
    PropertyCategory.SETTING,
    PropertyCategory.OTHER,
    -> PropertyMaskGroup.BEAM
}

/**
 * The mask group for one property on one fixture, or null when the property does not resolve
 * there (a renamed or removed property — the caller reports it as a skip rather than guessing).
 *
 * `position` is the synthetic pan/tilt pair: it has no `@FixtureProperty` annotation to read a
 * category from, so it is answered before the lookup — the same special case
 * `CueAssignmentResolver.parseAssignmentValue` and `fixtureCategoryFor` already make. `colour` /
 * `color` / `rgbColour` need no case: [canonicalPropertyName] collapses them onto the
 * annotated `COLOUR` property.
 */
fun maskGroupForProperty(fixture: GroupableFixture, propertyName: String): PropertyMaskGroup? {
    if (propertyName.equals("position", ignoreCase = true)) return PropertyMaskGroup.POSITION
    val canonical = canonicalPropertyName(propertyName)
    return PropertyChannelWriter.resolveProperty(fixture, canonical)?.category?.maskGroup()
}

/**
 * Parse a wire mask (`["COLOUR", "POSITION"]`) into a filter set.
 *
 * Null or empty means "no mask" — every property is in scope. An unknown token throws so the
 * route answers 400 rather than silently recording more than the operator asked for; a mask
 * that quietly widens is the failure mode worth being loud about.
 */
fun parseMaskGroups(raw: List<String>?): Set<PropertyMaskGroup>? {
    if (raw.isNullOrEmpty()) return null
    val groups = raw.map { token ->
        val trimmed = token.trim()
        PropertyMaskGroup.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown mask '$trimmed' — expected one of ${PropertyMaskGroup.entries.joinToString { it.name }}"
            )
    }.toSet()
    // A mask naming every group is the same as no mask; collapsing it keeps the "is this
    // masked?" branches in Record from having to special-case the full set.
    return if (groups.size == PropertyMaskGroup.entries.size) null else groups
}

/** True when [group] passes [mask]. A null mask passes everything; a null group never does. */
fun maskAllows(mask: Set<PropertyMaskGroup>?, group: PropertyMaskGroup?): Boolean =
    mask == null || (group != null && group in mask)
