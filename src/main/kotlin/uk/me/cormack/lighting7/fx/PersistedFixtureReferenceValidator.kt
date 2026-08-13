package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Shared validator for persisted `(target, propertyName)` references.
 *
 * Used by cue Phase 6 property-assignment diagnostics today; control-surface Phase 7 will
 * wrap the same functions for binding validation. Stateless — callers supply the current
 * [Fixtures] snapshot each call so validation follows patch changes without cache plumbing.
 */
object PersistedFixtureReferenceValidator {

    /**
     * Validate a cue or binding reference against the currently-loaded patch. Returns
     * [AssignmentHealth.Ok] iff the target exists and exposes [propertyName] as a known
     * annotated property (including the synthetic `"position"` compound and
     * `"colour"` / `"color"` → `rgbColour` aliases).
     */
    fun validateTargetedReference(
        fixtures: Fixtures,
        target: TargetRef,
        propertyName: String,
    ): AssignmentHealth {
        val canonical = canonicalPropertyName(propertyName)
        val referenceFixture: Fixture = when (target) {
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
                fixtures.untypedFixture(target.key)
            } catch (_: IllegalStateException) {
                return AssignmentHealth.MissingFixture(target.key)
            }
        }
        if (!fixtureSupportsProperty(referenceFixture, canonical)) {
            return AssignmentHealth.MissingProperty(target.key, propertyName)
        }
        return AssignmentHealth.Ok
    }

    /**
     * Validate a preset property assignment. Preset assignments are target-less — they're
     * keyed by `propertyName` only — so the check is against the preset's declared
     * [fixtureTypeKey]. An unknown type key is treated as valid rather than producing a
     * false positive — the preset will fail at apply time with its own warn log.
     *
     * When [elementKey] is non-null, the assignment targets an element on a multi-element
     * fixture; validate against the element-group property descriptors instead of the
     * fixture-level properties. These descriptors only include properties common to all
     * elements, which matches the "apply to one head" semantics.
     */
    fun validatePresetPropertyReference(
        fixtureTypeKey: String,
        propertyName: String,
        elementKey: String? = null,
    ): AssignmentHealth {
        val canonical = canonicalPropertyName(propertyName)
        val typeInfo = FixtureTypeRegistry.typeInfoForKey(fixtureTypeKey)
            ?: return AssignmentHealth.Ok
        val matches = if (elementKey != null) {
            val elementProps = typeInfo.elementGroupProperties
                ?: return AssignmentHealth.MissingProperty(fixtureTypeKey, propertyName)
            if (canonical.equals("position", ignoreCase = true)) {
                elementProps.any { it.name == "pan" } && elementProps.any { it.name == "tilt" }
            } else {
                elementProps.any { it.name == canonical }
            }
        } else {
            typeInfo.properties.any { it.name == canonical }
        }
        return if (matches) AssignmentHealth.Ok
        else AssignmentHealth.MissingProperty(fixtureTypeKey, propertyName)
    }

    private fun fixtureSupportsProperty(fixture: Fixture, canonical: String): Boolean {
        if (canonical.equals("position", ignoreCase = true)) {
            return fixture.fixtureProperty("pan") != null && fixture.fixtureProperty("tilt") != null
        }
        return fixture.fixtureProperty(canonical) != null
    }
}

/**
 * Canonical form used by the composition model and FX target resolution — RGB bundle
 * aliases collapse to `rgbColour`, everything else passes through unchanged. Shared so
 * route handlers and the validator don't drift apart on the aliasing rule.
 */
fun canonicalPropertyName(propertyName: String): String =
    when (propertyName.lowercase()) {
        "colour", "color", "rgbcolour" -> "rgbColour"
        else -> propertyName
    }

/**
 * Health of a stored value's *named-palette reference*, or [AssignmentHealth.Ok] when the value is a
 * literal (or not a reference at all).
 *
 * Read-path only: cue apply reports the same conditions through
 * [resolveAssignmentValueForFixture]'s health and a warn. This exists so the UI can mark a row
 * before it is ever fired, and it adds [AssignmentHealth.PaletteTypeMismatch] — a diagnosis the
 * resolve path deliberately collapses into "no entry", because at resolve time the two are the same
 * outcome.
 *
 * [target] may be a group; coverage is then checked against its first member, matching how
 * [validateTargetedReference] resolves a reference fixture for property checks.
 */
fun validatePaletteReference(
    fixtures: Fixtures,
    registry: PaletteRegistry?,
    target: TargetRef,
    propertyName: String,
    value: String,
): AssignmentHealth {
    val paletteUuid = parsePaletteRef(value) ?: return AssignmentHealth.Ok
    val expanded = registry?.expanded(paletteUuid)
        ?: return AssignmentHealth.MissingPalette(paletteUuid.toString())

    val canonical = canonicalPropertyName(propertyName)
    val referenceFixture: Fixture = when (target) {
        is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.getOrNull()
            ?.fixtures?.filterIsInstance<Fixture>()?.firstOrNull()
            ?: return AssignmentHealth.MissingGroup(target.key)
        is TargetRef.Fixture -> runCatching { fixtures.untypedFixture(target.key) }.getOrNull()
            ?: return AssignmentHealth.MissingFixture(target.key)
    }

    // A wrong-type reference can never have a matching entry, so name the cause rather than
    // reporting the symptom.
    val propertyGroup = maskGroupForProperty(referenceFixture, canonical)
    val paletteType = expanded.snapshot.type
    if (paletteType != null && propertyGroup != null && paletteType != propertyGroup) {
        return AssignmentHealth.PaletteTypeMismatch(
            paletteUuid.toString(), paletteType.name, propertyGroup.name,
        )
    }

    if (expanded.literalFor(referenceFixture.key, canonical) == null) {
        return AssignmentHealth.MissingPaletteEntry(
            paletteUuid.toString(), target.key, canonical,
        )
    }
    return AssignmentHealth.Ok
}
