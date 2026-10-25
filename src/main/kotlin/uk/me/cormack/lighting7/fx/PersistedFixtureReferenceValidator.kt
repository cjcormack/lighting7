package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.models.AssignmentHealth

/**
 * Shared validator for persisted `(target, attribute)` references.
 *
 * Used by cue Phase 6 property-assignment diagnostics today; control-surface Phase 7 will
 * wrap the same functions for binding validation. Stateless — callers supply the current
 * [Fixtures] snapshot each call so validation follows patch changes without cache plumbing.
 */
object PersistedFixtureReferenceValidator {

    /**
     * Validate a cue or binding reference against the currently-loaded patch. Returns
     * [AssignmentHealth.Ok] iff the target exists and exposes [attribute] as a known
     * annotated property (including the synthetic `"position"` compound and
     * `"colour"` / `"color"` → `rgbColour` aliases).
     *
     * [allowElements] lets a fixture-typed [target] name a **cell** — an element key of a
     * multi-head fixture, validated against the head's own properties. A cue row may (a cell-target
     * row, which `buildCueAssignmentsForCue` resolves); a control-surface binding may not, since
     * the surface resolves its fixture through the register alone, and a binding reported healthy
     * there would do nothing.
     */
    fun validateTargetedReference(
        fixtures: Fixtures,
        target: TargetRef,
        attribute: String,
        allowElements: Boolean = false,
    ): AssignmentHealth {
        val canonical = canonicalAttributeName(attribute)
        val referenceFixture: GroupableFixture = when (target) {
            is TargetRef.Group -> {
                val group = try {
                    fixtures.untypedGroup(target.key)
                } catch (_: IllegalStateException) {
                    return AssignmentHealth.MissingGroup(target.key)
                }
                group.fixtures.filterIsInstance<Fixture>().firstOrNull()
                    ?: return AssignmentHealth.MissingGroup(target.key)
            }
            is TargetRef.Fixture -> try {
                if (allowElements) fixtures.untypedGroupableFixture(target.key) else fixtures.untypedFixture(target.key)
            } catch (_: IllegalStateException) {
                return AssignmentHealth.MissingFixture(target.key)
            }
        }
        if (!fixtureSupportsAttribute(referenceFixture, canonical)) {
            return AssignmentHealth.MissingAttribute(target.key, attribute)
        }
        return AssignmentHealth.Ok
    }

    /**
     * Validate a preset property assignment. Preset assignments are target-less — they're
     * keyed by `attribute` only — so the check is against the preset's declared
     * [fixtureTypeKey]. An unknown type key is treated as valid rather than producing a
     * false positive — the preset will fail at apply time with its own warn log.
     *
     * When [elementKey] is non-null, the assignment targets an element on a multi-element
     * fixture; validate against the element-group property descriptors instead of the
     * fixture-level properties. These descriptors only include properties common to all
     * elements, which matches the "apply to one head" semantics.
     */
    fun validatePresetAttributeReference(
        fixtureTypeKey: String,
        attribute: String,
        elementKey: String? = null,
    ): AssignmentHealth {
        val canonical = canonicalAttributeName(attribute)
        val typeInfo = FixtureTypeRegistry.typeInfoForKey(fixtureTypeKey)
            ?: return AssignmentHealth.Ok
        val matches = if (elementKey != null) {
            val elementProps = typeInfo.elementGroupAttributes
                ?: return AssignmentHealth.MissingAttribute(fixtureTypeKey, attribute)
            if (canonical.equals("position", ignoreCase = true)) {
                elementProps.any { it.name == "pan" } && elementProps.any { it.name == "tilt" }
            } else {
                elementProps.any { it.name == canonical }
            }
        } else {
            typeInfo.attributes.any { it.name == canonical }
        }
        return if (matches) AssignmentHealth.Ok
        else AssignmentHealth.MissingAttribute(fixtureTypeKey, attribute)
    }

    /** Against the class catalogue, so a head answers from its own `@FixtureAttribute` members. */
    private fun fixtureSupportsAttribute(fixture: GroupableFixture, canonical: String): Boolean {
        val byName = FixtureAttributeCatalogue.of(fixture::class).byName
        if (canonical.equals("position", ignoreCase = true)) {
            return byName["pan"] != null && byName["tilt"] != null
        }
        return byName[canonical] != null
    }
}

/**
 * Canonical form used by the composition model and FX target resolution — RGB bundle
 * aliases collapse to `rgbColour`, everything else passes through unchanged. Shared so
 * route handlers and the validator don't drift apart on the aliasing rule.
 */
fun canonicalAttributeName(attribute: String): String =
    when (attribute.lowercase()) {
        "colour", "color", "rgbcolour" -> "rgbColour"
        else -> attribute
    }
