package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Shared validator for persisted `(targetType, targetKey, propertyName)` references.
 *
 * Used by cue Phase 6 property-assignment diagnostics today; control-surface Phase 7 will
 * wrap the same functions for binding validation. Stateless — callers supply the current
 * [Fixtures] snapshot each call so validation follows patch changes without cache plumbing.
 */
object PersistedFixtureReferenceValidator {

    /**
     * Validate a cue or binding reference against the currently-loaded patch. Used for
     * target-bearing assignments: `targetType` is `"fixture"` or `"group"`, `targetKey` is
     * the fixture key or group name. Returns [AssignmentHealth.Ok] iff the target exists
     * and exposes [propertyName] as a known annotated property (including the synthetic
     * `"position"` compound and `"colour"` / `"color"` → `rgbColour` aliases).
     */
    fun validateTargetedReference(
        fixtures: Fixtures,
        targetType: String,
        targetKey: String,
        propertyName: String,
    ): AssignmentHealth {
        val canonical = canonicalPropertyName(propertyName)
        val referenceFixture: Fixture = when (targetType) {
            "group" -> {
                val group = try {
                    fixtures.untypedGroup(targetKey)
                } catch (_: IllegalStateException) {
                    return AssignmentHealth.MissingGroup(targetKey)
                }
                group.fixtures.filterIsInstance<Fixture>().firstOrNull()
                    ?: return AssignmentHealth.MissingGroup(targetKey)
            }
            else -> try {
                fixtures.untypedFixture(targetKey)
            } catch (_: IllegalStateException) {
                return AssignmentHealth.MissingFixture(targetKey)
            }
        }
        if (!fixtureSupportsProperty(referenceFixture, canonical)) {
            return AssignmentHealth.MissingProperty(targetKey, propertyName)
        }
        return AssignmentHealth.Ok
    }

    /**
     * Validate a preset property assignment. Preset assignments are target-less — they're
     * keyed by `propertyName` only — so the check is against the preset's declared
     * [fixtureTypeKey]. An unknown type key is treated as valid rather than producing a
     * false positive — the preset will fail at apply time with its own warn log.
     */
    fun validatePresetPropertyReference(
        fixtureTypeKey: String,
        propertyName: String,
    ): AssignmentHealth {
        val canonical = canonicalPropertyName(propertyName)
        val typeInfo = FixtureTypeRegistry.typeInfoForKey(fixtureTypeKey)
            ?: return AssignmentHealth.Ok
        val matches = typeInfo.properties.any { it.name == canonical }
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
