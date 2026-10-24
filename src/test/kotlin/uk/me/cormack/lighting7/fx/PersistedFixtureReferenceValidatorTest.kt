package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import uk.me.cormack.lighting7.models.AssignmentHealth

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
    fun `live fixture + valid attribute returns Ok`() {
        val fixtures = fixturesWithHexGroup()
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, TargetRef.Fixture("hex-1"), "dimmer",
            ),
        )
    }

    @Test
    fun `a cell target is valid for a cue row, and still missing for a binding`() {
        // A Mode48Ch bar keeps every channel on its heads. A cue may name one head
        // (`allowElements`); a control-surface binding resolves through the register alone, so
        // the same key must keep reading as a missing fixture there.
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar", "Bar", firstChannel = 1))
        }
        val cell = TargetRef.Fixture("bar.pixel-2")

        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(fixtures, cell, "white", allowElements = true),
        )
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(fixtures, cell, "colour", allowElements = true),
            "the colour alias resolves on the head's own catalogue",
        )
        assertIs<AssignmentHealth.MissingAttribute>(
            PersistedFixtureReferenceValidator.validateTargetedReference(fixtures, cell, "dimmer", allowElements = true),
            "a head answers from its own properties — it has no dimmer",
        )
        assertIs<AssignmentHealth.MissingFixture>(
            PersistedFixtureReferenceValidator.validateTargetedReference(fixtures, cell, "white"),
        )
    }

    @Test
    fun `live group + valid attribute returns Ok`() {
        val fixtures = fixturesWithHexGroup()
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, TargetRef.Group("front-wash"), "rgbColour",
            ),
        )
    }

    @Test
    fun `colour alias canonicalises to rgbColour for lookup`() {
        val fixtures = fixturesWithHexGroup()
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, TargetRef.Fixture("hex-1"), "colour",
            ),
        )
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                fixtures, TargetRef.Fixture("hex-1"), "color",
            ),
        )
    }

    @Test
    fun `unknown fixture returns MissingFixture carrying the looked-up key`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, TargetRef.Fixture("hex-renamed"), "dimmer",
        )
        val missing = assertIs<AssignmentHealth.MissingFixture>(health)
        assertEquals("hex-renamed", missing.fixtureKey)
    }

    @Test
    fun `unknown group returns MissingGroup`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, TargetRef.Group("non-existent"), "dimmer",
        )
        val missing = assertIs<AssignmentHealth.MissingGroup>(health)
        assertEquals("non-existent", missing.groupName)
    }

    @Test
    fun `fixture exists but attribute removed returns MissingAttribute`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, TargetRef.Fixture("hex-1"), "nonsenseAttribute",
        )
        val missing = assertIs<AssignmentHealth.MissingAttribute>(health)
        assertEquals("hex-1", missing.targetKey)
        assertEquals("nonsenseAttribute", missing.attribute)
    }

    @Test
    fun `group-level missing attribute returns MissingAttribute referencing the group key`() {
        val fixtures = fixturesWithHexGroup()
        val health = PersistedFixtureReferenceValidator.validateTargetedReference(
            fixtures, TargetRef.Group("front-wash"), "nonsense",
        )
        val missing = assertIs<AssignmentHealth.MissingAttribute>(health)
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
                beforeRename, TargetRef.Fixture("hex-1"), "dimmer",
            ),
        )

        val afterRename = Fixtures().apply {
            register { addFixture(HexFixture(universe, "hex-renamed", "Hex 1", firstChannel = 1)) }
        }
        val deadHealth = PersistedFixtureReferenceValidator.validateTargetedReference(
            afterRename, TargetRef.Fixture("hex-1"), "dimmer",
        )
        assertIs<AssignmentHealth.MissingFixture>(deadHealth)

        // Re-binding to the new key resolves cleanly.
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validateTargetedReference(
                afterRename, TargetRef.Fixture("hex-renamed"), "dimmer",
            ),
        )
    }

    // ── Preset-style preset-local reference ───────────────────────────────

    @Test
    fun `preset with valid fixtureType + attribute returns Ok`() {
        // "hex" is the typeKey for HexFixture (WithDimmer, WithColour, WithUv, WithStrobe).
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetAttributeReference("hex", "dimmer"),
        )
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetAttributeReference("hex", "rgbColour"),
        )
    }

    @Test
    fun `preset with unknown fixtureType returns Ok — validator prefers no false-positive`() {
        // Unknown type keys should pass — apply-time warn log is the backstop. Marking these
        // dead in the UI when the code simply lacks the profile yet would be misleading.
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetAttributeReference(
                "not-a-real-type-key", "dimmer",
            ),
        )
    }

    @Test
    fun `preset with unknown attribute on known fixtureType returns MissingAttribute`() {
        val health = PersistedFixtureReferenceValidator.validatePresetAttributeReference(
            "hex", "nonsenseAttribute",
        )
        val missing = assertIs<AssignmentHealth.MissingAttribute>(health)
        assertEquals("hex", missing.targetKey)
        assertEquals("nonsenseAttribute", missing.attribute)
    }

    @Test
    fun `preset colour alias canonicalises`() {
        // All three aliases should match the underlying rgbColour descriptor.
        assertTrue(
            PersistedFixtureReferenceValidator.validatePresetAttributeReference("hex", "colour")
                is AssignmentHealth.Ok,
        )
        assertTrue(
            PersistedFixtureReferenceValidator.validatePresetAttributeReference("hex", "color")
                is AssignmentHealth.Ok,
        )
        assertTrue(
            PersistedFixtureReferenceValidator.validatePresetAttributeReference("hex", "rgbColour")
                is AssignmentHealth.Ok,
        )
    }

    // ── Preset element-scoped reference (per-head) ────────────────────────

    @Test
    fun `preset element-scoped pan on multi-head bar returns Ok`() {
        // 14-channel SlenderBeam has 4 BasicHeads, each with pan/tilt — element-group
        // descriptors include "pan" and "tilt" as common properties.
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetAttributeReference(
                "slender-beam-bar-quad-14ch", "pan", elementKey = "head-0",
            ),
        )
    }

    @Test
    fun `preset element-scoped position synthetic name validates via pan+tilt`() {
        assertEquals(
            AssignmentHealth.Ok,
            PersistedFixtureReferenceValidator.validatePresetAttributeReference(
                "slender-beam-bar-quad-14ch", "position", elementKey = "head-1",
            ),
        )
    }

    @Test
    fun `preset element-scoped on single-head fixture returns MissingAttribute`() {
        // "hex" has no elements — no elementGroupAttributes → any element-scoped
        // assignment is dead.
        val health = PersistedFixtureReferenceValidator.validatePresetAttributeReference(
            "hex", "dimmer", elementKey = "head-0",
        )
        assertIs<AssignmentHealth.MissingAttribute>(health)
    }

    @Test
    fun `preset element-scoped unknown attribute on multi-head returns MissingAttribute`() {
        val health = PersistedFixtureReferenceValidator.validatePresetAttributeReference(
            "slender-beam-bar-quad-14ch", "nonsense", elementKey = "head-0",
        )
        assertIs<AssignmentHealth.MissingAttribute>(health)
    }
}
