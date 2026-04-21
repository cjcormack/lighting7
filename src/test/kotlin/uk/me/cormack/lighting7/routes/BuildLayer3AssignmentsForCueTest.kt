package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [buildLayer3AssignmentsForCue] — the group-expansion + category-lookup helper that
 * sits between the persisted DTO and the resolver's typed input.
 */
class BuildLayer3AssignmentsForCueTest {

    private val universe = Universe(0, 0)

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

    private fun cueData(vararg assignments: CuePropertyAssignmentDto): CueApplyData =
        CueApplyData(
            cueId = 7,
            cueName = "test",
            palette = emptyList(),
            updateGlobalPalette = false,
            presetApplications = emptyList(),
            adHocEffects = emptyList(),
            propertyAssignments = assignments.toList(),
            cueStackId = 3,
            sortOrder = 2,
        )

    @Test
    fun `fixture target emits one Assignment with the correct category`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "dimmer",
                value = "180",
            ),
        ))
        assertEquals(1, out.size)
        val a = out.single()
        assertEquals("hex-1", a.targetKey)
        assertEquals("dimmer", a.propertyName)
        assertEquals(7, a.cueId)
        assertEquals(false, a.targetIsGroup)
        val v = assertIs<Layer3Resolver.PropertyValue.Slider>(a.value)
        assertEquals(180u.toUByte(), v.value)
        // Priority = cueStackId*1M + sortOrder*1K + 1 = 3_002_001
        assertEquals(3_002_001, a.priority)
    }

    @Test
    fun `group target expands to per-member rows flagged as group-derived`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "group",
                targetKey = "front-wash",
                propertyName = "dimmer",
                value = "150",
            ),
        ))
        assertEquals(2, out.size, "one row per group member; no orphan group-level row")
        assertTrue(out.all { it.targetIsGroup }, "member rows carry the group-derived flag")
        assertEquals(setOf("hex-1", "hex-2"), out.map { it.targetKey }.toSet())
        out.forEach {
            val v = assertIs<Layer3Resolver.PropertyValue.Slider>(it.value)
            assertEquals(150u.toUByte(), v.value)
        }
    }

    @Test
    fun `fixture override wins over group expansion via specificity`() {
        // Cue asserts group dimmer=150 AND fixture hex-1 dimmer=50. Specificity rule: the
        // direct fixture row wins on hex-1 (50); hex-2 keeps the group value (150).
        val fixtures = fixturesWithTwoHexesInAGroup()
        val rows = buildLayer3AssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(targetType = "group", targetKey = "front-wash",
                propertyName = "dimmer", value = "150"),
            CuePropertyAssignmentDto(targetType = "fixture", targetKey = "hex-1",
                propertyName = "dimmer", value = "50"),
        ))
        val resolved = Layer3Resolver().resolve(rows)
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(50u),
            resolved[Layer3Resolver.Key("hex-1", "dimmer")],
            "fixture-level override wins",
        )
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(150u),
            resolved[Layer3Resolver.Key("hex-2", "dimmer")],
            "unaffected member keeps group value",
        )
    }

    @Test
    fun `colour assignment produces a Colour PropertyValue`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "colour",
                value = "#00FF00",
            ),
        ))
        val a = out.single()
        assertEquals("rgbColour", a.propertyName, "canonicalised to the property name ColourTarget uses")
        assertIs<Layer3Resolver.PropertyValue.Colour>(a.value)
    }

    @Test
    fun `missing fixture is logged and skipped, not thrown`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "does-not-exist",
                propertyName = "dimmer",
                value = "100",
            ),
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "dimmer",
                value = "50",
            ),
        ))
        assertEquals(1, out.size, "bad row skipped, good row still emitted")
        assertEquals("hex-1", out.single().targetKey)
    }

    @Test
    fun `unknown property name is skipped`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildLayer3AssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "nonsense",
                value = "100",
            ),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `empty propertyAssignments produces empty output`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        assertTrue(buildLayer3AssignmentsForCue(fixtures, cueData()).isEmpty())
    }

    @Test
    fun `stomp overlap includes group name plus expanded member keys`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val overlap = buildStompOverlapFromAssignments(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "group",
                targetKey = "front-wash",
                propertyName = "colour",
                value = "#FF0000",
            ),
        ))
        // Canonicalised to rgbColour, and the group's two members are expanded in addition to
        // the group-level key.
        assertEquals(setOf(
            uk.me.cormack.lighting7.fx.FxEngine.PropertyKey("front-wash", "rgbColour"),
            uk.me.cormack.lighting7.fx.FxEngine.PropertyKey("hex-1", "rgbColour"),
            uk.me.cormack.lighting7.fx.FxEngine.PropertyKey("hex-2", "rgbColour"),
        ), overlap)
    }
}
