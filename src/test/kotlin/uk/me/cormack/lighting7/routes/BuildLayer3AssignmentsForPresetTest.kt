package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.SlenderBeamBarQuadFixture
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.fx.PaletteCascade
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [buildLayer3AssignmentsForPreset] — fans preset-local (propertyName, value) pairs
 * across the cue-preset-application's targets, mirrors the cue-side specificity tagging.
 */
class BuildLayer3AssignmentsForPresetTest {

    private val universe = Universe(0, 0)
    private val cueId = 42
    private val priority = 3_002_001
    private val presetId = 9

    private fun fixturesWithTwoHexesInAGroup(): Fixtures {
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

    @Test
    fun `fixture target fans each assignment into one row`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "180"),
            ),
            applyTargets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
        )
        assertEquals(1, out.size)
        val a = out.single()
        assertEquals("hex-1", a.targetKey)
        assertEquals("dimmer", a.propertyName)
        assertEquals(cueId, a.cueId)
        assertEquals(priority, a.priority)
        assertEquals(false, a.targetIsGroup)
        val v = assertIs<Layer3Resolver.PropertyValue.Slider>(a.value)
        assertEquals(180u.toUByte(), v.value)
    }

    @Test
    fun `group target expands preset rows to per-member, marked targetIsGroup`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "150"),
            ),
            applyTargets = listOf(CueTargetDto(type = "group", key = "front-wash")),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.targetIsGroup })
        assertEquals(setOf("hex-1", "hex-2"), out.map { it.targetKey }.toSet())
    }

    @Test
    fun `multiple assignments x multiple targets produces product rows`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "128"),
                FxPresetPropertyAssignmentDto(propertyName = "colour", value = "#00FF00"),
            ),
            applyTargets = listOf(
                CueTargetDto(type = "fixture", key = "hex-1"),
                CueTargetDto(type = "fixture", key = "hex-2"),
            ),
        )
        assertEquals(4, out.size)
        // Colour property name canonicalises to rgbColour
        assertEquals(setOf("dimmer", "rgbColour"), out.map { it.propertyName }.toSet())
        assertEquals(setOf("hex-1", "hex-2"), out.map { it.targetKey }.toSet())
    }

    @Test
    fun `missing target is logged and skipped, other targets still emit`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "100"),
            ),
            applyTargets = listOf(
                CueTargetDto(type = "fixture", key = "does-not-exist"),
                CueTargetDto(type = "fixture", key = "hex-1"),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("hex-1", out.single().targetKey)
    }

    @Test
    fun `unknown property on fixture is skipped but doesn't break other assignments`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "nonsense", value = "1"),
                FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "128"),
            ),
            applyTargets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
        )
        assertEquals(1, out.size)
        assertEquals("dimmer", out.single().propertyName)
    }

    private fun paletteRefPreset(value: String = "P1") = listOf(
        FxPresetPropertyAssignmentDto(propertyName = "colour", value = value),
    )
    private val hex1Target = listOf(CueTargetDto(type = "fixture", key = "hex-1"))

    @Test
    fun `palette cascade - preset palette wins over cue and global`() {
        val out = buildLayer3AssignmentsForPreset(
            fixturesWithTwoHexesInAGroup(), cueId, priority, presetId,
            paletteRefPreset(), hex1Target,
            cascade = PaletteCascade(
                preset = listOf(ExtendedColour(Color(10, 20, 30))),
                cue = listOf(ExtendedColour(Color(40, 50, 60))),
                global = listOf(ExtendedColour(Color(70, 80, 90))),
            ),
        )
        val v = assertIs<Layer3Resolver.PropertyValue.Colour>(out.single().value)
        assertEquals(Color(10, 20, 30), v.value.color)
    }

    @Test
    fun `palette cascade - falls through to cue palette when preset empty`() {
        val out = buildLayer3AssignmentsForPreset(
            fixturesWithTwoHexesInAGroup(), cueId, priority, presetId,
            paletteRefPreset(), hex1Target,
            cascade = PaletteCascade(
                cue = listOf(ExtendedColour(Color(40, 50, 60))),
                global = listOf(ExtendedColour(Color(70, 80, 90))),
            ),
        )
        val v = assertIs<Layer3Resolver.PropertyValue.Colour>(out.single().value)
        assertEquals(Color(40, 50, 60), v.value.color)
    }

    @Test
    fun `palette cascade - falls through to global when preset and cue empty`() {
        val out = buildLayer3AssignmentsForPreset(
            fixturesWithTwoHexesInAGroup(), cueId, priority, presetId,
            paletteRefPreset(), hex1Target,
            cascade = PaletteCascade(global = listOf(ExtendedColour(Color(70, 80, 90)))),
        )
        val v = assertIs<Layer3Resolver.PropertyValue.Colour>(out.single().value)
        assertEquals(Color(70, 80, 90), v.value.color)
    }

    @Test
    fun `palette cascade - no palettes falls through to static parser returning white`() {
        val out = buildLayer3AssignmentsForPreset(
            fixturesWithTwoHexesInAGroup(), cueId, priority, presetId,
            paletteRefPreset(), hex1Target,
        )
        val v = assertIs<Layer3Resolver.PropertyValue.Colour>(out.single().value)
        assertEquals(Color.WHITE, v.value.color)
    }

    @Test
    fun `palette cascade - hex colour values ignore palette`() {
        val out = buildLayer3AssignmentsForPreset(
            fixturesWithTwoHexesInAGroup(), cueId, priority, presetId,
            paletteRefPreset(value = "#00FF00"), hex1Target,
            cascade = PaletteCascade(preset = listOf(ExtendedColour(Color(10, 20, 30)))),
        )
        val v = assertIs<Layer3Resolver.PropertyValue.Colour>(out.single().value)
        assertEquals(Color(0, 255, 0), v.value.color)
    }

    @Test
    fun `empty inputs produce empty output`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        assertTrue(
            buildLayer3AssignmentsForPreset(
                fixtures, cueId, priority, presetId,
                presetAssignments = emptyList(),
                applyTargets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
            ).isEmpty()
        )
        assertTrue(
            buildLayer3AssignmentsForPreset(
                fixtures, cueId, priority, presetId,
                presetAssignments = listOf(
                    FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "1"),
                ),
                applyTargets = emptyList(),
            ).isEmpty()
        )
    }

    // ─── Element-scoped (per-head) assignments ─────────────────────────

    private fun fixturesWithTwoQuadBarsInAGroup(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val bar1 = addFixture(SlenderBeamBarQuadFixture.Mode14Ch(universe, "bar-1", "Bar 1", firstChannel = 1))
            val bar2 = addFixture(SlenderBeamBarQuadFixture.Mode14Ch(universe, "bar-2", "Bar 2", firstChannel = 15))
            createGroup<SlenderBeamBarQuadFixture.Mode14Ch>("bars") {
                addSpread(listOf(bar1, bar2))
            }
        }
        return fixtures
    }

    @Test
    fun `element-scoped fixture target emits row keyed on element`() {
        val fixtures = fixturesWithTwoQuadBarsInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "pan", value = "200", elementKey = "head-1"),
            ),
            applyTargets = listOf(CueTargetDto(type = "fixture", key = "bar-1")),
        )
        assertEquals(1, out.size)
        val row = out.single()
        assertEquals("bar-1.head-1", row.targetKey)
        assertEquals("pan", row.propertyName)
        assertEquals(false, row.targetIsGroup)
        val v = assertIs<Layer3Resolver.PropertyValue.Slider>(row.value)
        assertEquals(200u.toUByte(), v.value)
    }

    @Test
    fun `element-scoped group target expands to one row per member element`() {
        val fixtures = fixturesWithTwoQuadBarsInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "pan", value = "100", elementKey = "head-0"),
            ),
            applyTargets = listOf(CueTargetDto(type = "group", key = "bars")),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.targetIsGroup })
        assertEquals(setOf("bar-1.head-0", "bar-2.head-0"), out.map { it.targetKey }.toSet())
    }

    @Test
    fun `element-scoped assignment accepts full element-key path too`() {
        val fixtures = fixturesWithTwoQuadBarsInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "tilt", value = "50", elementKey = "bar-1.head-2"),
            ),
            applyTargets = listOf(CueTargetDto(type = "fixture", key = "bar-1")),
        )
        assertEquals(1, out.size)
        assertEquals("bar-1.head-2", out.single().targetKey)
    }

    @Test
    fun `element-scoped on non-multi-element fixture is skipped`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "100", elementKey = "head-0"),
            ),
            applyTargets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `element-scoped unknown element on one member, other member still emits`() {
        // Mix a quad bar and a hex in the same group; preset targets element "head-0".
        // The hex has no elements, so its row is skipped; the bar's head-0 row still emits.
        val fixtures = Fixtures()
        fixtures.register {
            val bar = addFixture(SlenderBeamBarQuadFixture.Mode14Ch(universe, "bar-1", "Bar 1", firstChannel = 1))
            val hex = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 100))
            createGroup<uk.me.cormack.lighting7.fixture.Fixture>("mixed") {
                add(bar)
                add(hex)
            }
        }
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "pan", value = "80", elementKey = "head-0"),
            ),
            applyTargets = listOf(CueTargetDto(type = "group", key = "mixed")),
        )
        assertEquals(1, out.size)
        assertEquals("bar-1.head-0", out.single().targetKey)
    }

    @Test
    fun `element-scoped with null elementKey keeps old fixture-level behaviour`() {
        // Sanity: elementKey=null on an element-capable fixture goes straight to the parent,
        // not any head. (Preserves backwards compatibility for existing presets.)
        val fixtures = fixturesWithTwoQuadBarsInAGroup()
        val out = buildLayer3AssignmentsForPreset(
            fixtures, cueId, priority, presetId,
            presetAssignments = listOf(
                FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "128", elementKey = null),
            ),
            applyTargets = listOf(CueTargetDto(type = "fixture", key = "bar-1")),
        )
        assertEquals(1, out.size)
        assertEquals("bar-1", out.single().targetKey)
    }
}
