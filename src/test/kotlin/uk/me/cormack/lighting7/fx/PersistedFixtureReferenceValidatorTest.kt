package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [PersistedFixtureReferenceValidator] — the shared dead-reference check used by
 * cue Phase 6 diagnostics (and eventually control-surface Phase 7 binding validation).
 */
class PersistedFixtureReferenceValidatorTest {

    private val universe = Universe(0, 0)

    private fun fixturesWithHexGroup(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            createGroup<HexFixture>("front-wash") {
                addSpread(listOf(hex1, hex2))
            }
        }
        return fixtures
    }

    // ── Cue-style targeted reference ──────────────────────────────────────

    @Test
    fun `live fixture + valid property returns Ok`() {
        val fixtures = fixturesWithHexGroup()
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, "fixture", "hex-1", "dimmer",
            ),
        )
    }

    @Test
    fun `live group + valid property returns Ok`() {
        val fixtures = fixturesWithHexGroup()
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, "group", "front-wash", "rgbColour",
            ),
        )
    }

    @Test
    fun `colour alias canonicalises to rgbColour for lookup`() {
        val fixtures = fixturesWithHexGroup()
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, "fixture", "hex-1", "colour",
            ),
        )
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, "fixture", "hex-1", "color",
            ),
        )
    }

    @Test
    fun `unknown fixture returns MissingFixture carrying the looked-up key`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, "fixture", "hex-renamed", "dimmer",
        )
        val missing = assertIs<AssignmentHealth.MissingFixture>(health)
        assertEquals("hex-renamed", missing.fixtureKey)
    }

    @Test
    fun `unknown group returns MissingGroup`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, "group", "non-existent", "dimmer",
        )
        val missing = assertIs<AssignmentHealth.MissingGroup>(health)
        assertEquals("non-existent", missing.groupName)
    }

    @Test
    fun `fixture exists but property removed returns MissingProperty`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, "fixture", "hex-1", "nonsenseProperty",
        )
        val missing = assertIs<AssignmentHealth.MissingProperty>(health)
        assertEquals("hex-1", missing.targetKey)
        assertEquals("nonsenseProperty", missing.propertyName)
    }

    @Test
    fun `group-level missing property returns MissingProperty referencing the group key`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, "group", "front-wash", "nonsense",
        )
        val missing = assertIs<AssignmentHealth.MissingProperty>(health)
        assertEquals("front-wash", missing.targetKey)
    }

    @Test
    fun `fixture renamed → dead → rebound transitions Ok → MissingFixture → Ok`() {
        // Rename is expressed as a fresh Fixtures snapshot with the new key — the validator
        // is stateless, so health transitions follow the patch state exactly.
        val beforeRename = Fixtures().apply {
            register { addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1)) }
        }
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                beforeRename, "fixture", "hex-1", "dimmer",
            ),
        )

        val afterRename = Fixtures().apply {
            register { addFixture(HexFixture(universe, "hex-renamed", "Hex 1", firstChannel = 1)) }
        }
        val deadHealth = PersistedFixtureReferenceValidator.validateTargetedReference(
            afterRename, "fixture", "hex-1", "dimmer",
        )
        assertIs<AssignmentHealth.MissingFixture>(deadHealth)

        // Re-binding to the new key resolves cleanly.
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                afterRename, "fixture", "hex-renamed", "dimmer",
            ),
        )
    }

    // ── Preset-style preset-local reference ───────────────────────────────

    @Test
    fun `preset with valid fixtureType + property returns Ok`() {
        // "hex" is the typeKey for HexFixture (WithDimmer, WithColour, WithUv, WithStrobe).
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetPropertyReference("hex", "dimmer"),
        )
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetPropertyReference("hex", "rgbColour"),
        )
    }

    @Test
    fun `preset with null fixtureType returns Ok — legacy backfill window`() {
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetPropertyReference(null, "dimmer"),
        )
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetPropertyReference("", "dimmer"),
        )
    }

    @Test
    fun `preset with unknown fixtureType returns Ok — validator prefers no false-positive`() {
        // Unknown type keys should pass — apply-time warn log is the backstop. Marking these
        // dead in the UI when the code simply lacks the profile yet would be misleading.
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetPropertyReference(
                "not-a-real-type-key", "dimmer",
            ),
        )
    }

    @Test
    fun `preset with unknown property on known fixtureType returns MissingProperty`() {
        val health = PersistedFixtureReferenceValidator.validatePresetPropertyReference(
            "hex", "nonsenseProperty",
        )
        val missing = assertIs<AssignmentHealth.MissingProperty>(health)
        assertEquals("hex", missing.targetKey)
        assertEquals("nonsenseProperty", missing.propertyName)
    }

    @Test
    fun `preset colour alias canonicalises`() {
        // All three aliases should match the underlying rgbColour descriptor.
        assertTrue(
            PersistedFixtureReferenceValidator.validatePresetPropertyReference("hex", "colour")
                is AssignmentHealth.Ok,
        )
        assertTrue(
            PersistedFixtureReferenceValidator.validatePresetPropertyReference("hex", "color")
                is AssignmentHealth.Ok,
        )
        assertTrue(
            PersistedFixtureReferenceValidator.validatePresetPropertyReference("hex", "rgbColour")
                is AssignmentHealth.Ok,
        )
    }
}
