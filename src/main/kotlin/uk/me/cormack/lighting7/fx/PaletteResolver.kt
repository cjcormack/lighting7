package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.PropertyCategory
import java.util.UUID

/**
 * The outcome of resolving one stored assignment value for one fixture.
 *
 * @property value the concrete value, or null when the row does not resolve — the caller logs at
 *   warn and skips it, exactly as it already does for an unparsable literal.
 * @property paletteUuid non-null iff the stored value was a named-palette reference, **whatever
 *   the outcome**. Consumers that lift a resolved row back into the programmer read this so the
 *   slot stays a [ProgrammerValue.Ref] rather than hardening: Include and the FX-preset toggle
 *   carry it through [CueAssignmentResolver.Assignment.paletteUuid]. Record captures live programmer
 *   state rather than stored rows, so it reads the identity off the slot instead.
 * @property health why it didn't resolve, or [AssignmentHealth.Ok].
 */
data class AssignmentValueResolution(
    val value: CueAssignmentResolver.PropertyValue?,
    val paletteUuid: UUID?,
    val health: AssignmentHealth,
)

/**
 * Resolve one stored assignment value for one fixture, handling both value forms.
 *
 * This is the single door every consumer goes through — the cue builder, the preset builder,
 * `programmer.set`, and Make Hard — so the ref grammar is interpreted in exactly one place.
 *
 * **The ref check happens before [CueAssignmentResolver.parseAssignmentValue], and that ordering is
 * load-bearing rather than stylistic.** For [PropertyCategory.COLOUR] that function routes to
 * `resolveColour` → `parseExtendedColour`, which answers **white** for anything it doesn't
 * recognise. A `ref:` string reaching it would therefore not fail — it would silently light the
 * fixture white, which is the worst available outcome and invisible in a diff. `parseAssignmentValue`
 * itself is left byte-for-byte unchanged and never sees a `ref:` value; `CueAssignmentResolverTest` pins
 * the white-for-junk behaviour so the reason this ordering exists stays legible.
 *
 * [positionalPalette] is the *other* palette system — the ordered colour list behind `P1` / `P2`
 * (see [PaletteCascade]) — passed straight through to the literal parser. The two are
 * independent: a value is either a `ref:`, or a literal that may be a positional ref.
 *
 * @param registry null when no palette registry is available (a pure-parse caller). A `ref:` then
 *   resolves to [AssignmentHealth.MissingPalette] rather than throwing.
 * @param canonicalProperty must already be canonicalised via [canonicalPropertyName].
 */
fun resolveAssignmentValueForFixture(
    registry: PaletteRegistry?,
    fixtureKey: String,
    canonicalProperty: String,
    category: PropertyCategory,
    rawValue: String,
    positionalPalette: List<ExtendedColour> = emptyList(),
): AssignmentValueResolution {
    val paletteUuid = parsePaletteRef(rawValue)
        ?: return AssignmentValueResolution(
            value = CueAssignmentResolver.parseAssignmentValue(category, canonicalProperty, rawValue, positionalPalette),
            paletteUuid = null,
            health = AssignmentHealth.Ok,
        )

    val expanded = registry?.expanded(paletteUuid)
        ?: return AssignmentValueResolution(
            value = null,
            paletteUuid = paletteUuid,
            health = AssignmentHealth.MissingPalette(paletteUuid.toString()),
        )

    // A palette holds literals only (enforced at the entry write boundary), so this parse cannot
    // recurse into another ref.
    val literal = expanded.literalFor(fixtureKey, canonicalProperty)
        ?: return AssignmentValueResolution(
            value = null,
            paletteUuid = paletteUuid,
            health = AssignmentHealth.MissingPaletteEntry(
                paletteUuid.toString(), fixtureKey, canonicalProperty,
            ),
        )

    val parsed = CueAssignmentResolver.parseAssignmentValue(category, canonicalProperty, literal, positionalPalette)
    return AssignmentValueResolution(
        value = parsed,
        paletteUuid = paletteUuid,
        // A stored literal that doesn't parse is a data bug, not a distinct operator condition
        // worth its own badge — report it as an uncovered entry and let the warn name the literal.
        health = if (parsed != null) {
            AssignmentHealth.Ok
        } else {
            AssignmentHealth.MissingPaletteEntry(paletteUuid.toString(), fixtureKey, canonicalProperty)
        },
    )
}
