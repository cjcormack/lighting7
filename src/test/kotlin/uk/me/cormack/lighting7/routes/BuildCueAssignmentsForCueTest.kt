package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PaletteCascade
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [buildCueAssignmentsForCue] — the group-expansion + category-lookup helper that
 * sits between the persisted DTO and the resolver's typed input.
 */
class BuildCueAssignmentsForCueTest {

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
        val out = buildCueAssignmentsForCue(fixtures, cueData(
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
        val v = assertIs<CueAssignmentResolver.PropertyValue.Slider>(a.value)
        assertEquals(180u.toUByte(), v.value)
        // Priority = cueStackId*1M + sortOrder*1K + 1 = 3_002_001
        assertEquals(3_002_001, a.priority)
    }

    @Test
    fun `group target expands to per-member rows flagged as group-derived`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(fixtures, cueData(
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
            val v = assertIs<CueAssignmentResolver.PropertyValue.Slider>(it.value)
            assertEquals(150u.toUByte(), v.value)
        }
    }

    @Test
    fun `fixture override wins over group expansion via specificity`() {
        // Cue asserts group dimmer=150 AND fixture hex-1 dimmer=50. Specificity rule: the
        // direct fixture row wins on hex-1 (50); hex-2 keeps the group value (150).
        val fixtures = fixturesWithTwoHexesInAGroup()
        val rows = buildCueAssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(targetType = "group", targetKey = "front-wash",
                propertyName = "dimmer", value = "150"),
            CuePropertyAssignmentDto(targetType = "fixture", targetKey = "hex-1",
                propertyName = "dimmer", value = "50"),
        ))
        val resolved = CueAssignmentResolver().resolve(rows)
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(50u),
            resolved[CueAssignmentResolver.Key.fixture("hex-1", "dimmer")],
            "fixture-level override wins",
        )
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(150u),
            resolved[CueAssignmentResolver.Key.fixture("hex-2", "dimmer")],
            "unaffected member keeps group value",
        )
    }

    @Test
    fun `colour assignment produces a Colour PropertyValue`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "colour",
                value = "#00FF00",
            ),
        ))
        val a = out.single()
        assertEquals("rgbColour", a.propertyName, "canonicalised to the property name ColourTarget uses")
        assertIs<CueAssignmentResolver.PropertyValue.Colour>(a.value)
    }

    @Test
    fun `missing fixture is logged and skipped, not thrown`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(fixtures, cueData(
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
        val out = buildCueAssignmentsForCue(fixtures, cueData(
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
    fun `palette cascade - palette ref resolved against supplied cue palette`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val cascade = PaletteCascade(cue = listOf(ExtendedColour(Color(10, 20, 30))))
        val out = buildCueAssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "colour",
                value = "P1",
            ),
        ), cascade)
        val v = assertIs<CueAssignmentResolver.PropertyValue.Colour>(out.single().value)
        assertEquals(Color(10, 20, 30), v.value.color)
    }

    @Test
    fun `palette cascade - no palette falls through to white`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(fixtures, cueData(
            CuePropertyAssignmentDto(
                targetType = "fixture",
                targetKey = "hex-1",
                propertyName = "colour",
                value = "P1",
            ),
        ))
        val v = assertIs<CueAssignmentResolver.PropertyValue.Colour>(out.single().value)
        assertEquals(Color.WHITE, v.value.color)
    }

    @Test
    fun `empty propertyAssignments produces empty output`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        assertTrue(buildCueAssignmentsForCue(fixtures, cueData()).isEmpty())
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

    // ─── Named-palette references ──────────────────────────────────────────

    private val paletteUuid: java.util.UUID =
        java.util.UUID.fromString("2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02")

    /** A registry over [fixtures] holding [entries] under [paletteUuid]. */
    private fun registryFor(
        fixtures: Fixtures,
        vararg entries: uk.me.cormack.lighting7.fx.LookRowEntry,
    ) = uk.me.cormack.lighting7.fx.LookRegistry(
        fixtures = { fixtures },
        loader = { requested ->
            if (requested != paletteUuid) null else uk.me.cormack.lighting7.fx.LookSnapshot(
                lookId = 1,
                lookUuid = paletteUuid,
                name = "Warm Amber",
                editorFixtureType = null,
                palette = emptyList(),
                effects = emptyList(),
                rows = entries.toList(),
            )
        },
    )

    private fun refAssignment(targetType: String, targetKey: String, propertyName: String = "colour") =
        CuePropertyAssignmentDto(
            targetType = targetType,
            targetKey = targetKey,
            propertyName = propertyName,
            value = uk.me.cormack.lighting7.fx.paletteRefValue(paletteUuid),
        )

    @Test
    fun `a fixture ref resolves to the palette's literal for that fixture`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(
            fixtures,
            cueData(refAssignment("fixture", "hex-1")),
            lookRegistry = registryFor(
                fixtures,
                uk.me.cormack.lighting7.fx.LookRowEntry(
                    uk.me.cormack.lighting7.models.TargetRef.Fixture("hex-1"), "colour", "#ff8800",
                ),
            ),
        )
        val v = assertIs<CueAssignmentResolver.PropertyValue.Colour>(out.single().value)
        assertEquals(ExtendedColour(Color(0xff, 0x88, 0x00)), v.value)
    }

    @Test
    fun `a group ref fans out to per-member literals, not one shared value`() {
        // The whole point of per-fixture palettes: each member can hold a different value.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(
            fixtures,
            cueData(refAssignment("group", "front-wash")),
            lookRegistry = registryFor(
                fixtures,
                uk.me.cormack.lighting7.fx.LookRowEntry(
                    uk.me.cormack.lighting7.models.TargetRef.Fixture("hex-1"), "colour", "#ff8800",
                ),
                uk.me.cormack.lighting7.fx.LookRowEntry(
                    uk.me.cormack.lighting7.models.TargetRef.Fixture("hex-2"), "colour", "#00ff00",
                ),
            ),
        )
        assertEquals(2, out.size)
        assertTrue(out.all { it.targetIsGroup }, "member rows from a group expansion stay marked")
        val byKey = out.associateBy { it.targetKey }
        assertEquals(
            ExtendedColour(Color(0xff, 0x88, 0x00)),
            assertIs<CueAssignmentResolver.PropertyValue.Colour>(byKey.getValue("hex-1").value).value,
        )
        assertEquals(
            ExtendedColour(Color(0x00, 0xff, 0x00)),
            assertIs<CueAssignmentResolver.PropertyValue.Colour>(byKey.getValue("hex-2").value).value,
        )
    }

    @Test
    fun `a member the palette does not cover is skipped, the others still resolve`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(
            fixtures,
            cueData(refAssignment("group", "front-wash")),
            lookRegistry = registryFor(
                fixtures,
                uk.me.cormack.lighting7.fx.LookRowEntry(
                    uk.me.cormack.lighting7.models.TargetRef.Fixture("hex-1"), "colour", "#ff8800",
                ),
            ),
        )
        assertEquals(listOf("hex-1"), out.map { it.targetKey }, "hex-2 is uncovered and skipped")
    }

    @Test
    fun `a ref to a missing palette skips the row rather than lighting it white`() {
        // Guards the ordering hazard: the literal colour parser answers white for junk, so an
        // unintercepted ref would produce a confident wrong colour instead of nothing.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(
            fixtures,
            cueData(refAssignment("fixture", "hex-1")),
            lookRegistry = null,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a positional palette ref is unaffected by the named-ref path`() {
        // Both palette systems coexist; `P1` still indexes the ordered colour list.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = buildCueAssignmentsForCue(
            fixtures,
            cueData(CuePropertyAssignmentDto(
                targetType = "fixture", targetKey = "hex-1", propertyName = "colour", value = "P1",
            )),
            cascade = PaletteCascade(cue = listOf(ExtendedColour(Color(0x11, 0x22, 0x33)))),
            lookRegistry = registryFor(fixtures),
        )
        val v = assertIs<CueAssignmentResolver.PropertyValue.Colour>(out.single().value)
        assertEquals(ExtendedColour(Color(0x11, 0x22, 0x33)), v.value)
    }
}
