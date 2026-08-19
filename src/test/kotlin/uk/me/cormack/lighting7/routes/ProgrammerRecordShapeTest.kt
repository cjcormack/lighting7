package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [collapseRecordingToAssignments] — the programmer-driven twin of
 * [captureCueAssignmentsFromSnapshot], and the place the §7 "group entries" decision lives.
 *
 * The rule under test: a `sourceGroup` hint only *nominates* a group row. It is emitted iff
 * every member holds an entry for that property with the same value. That is what makes a
 * stale hint harmless — it degrades to fixture rows rather than asserting a value for members
 * the operator never set.
 */
class ProgrammerRecordShapeTest {

    private val universe = Universe(0, 0)

    private fun fixtures(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            val hex3 = addFixture(HexFixture(universe, "hex-3", "Hex 3", firstChannel = 25))
            createGroup<HexFixture>("front-wash") { addSpread(listOf(hex1, hex2)) }
            createGroup<HexFixture>("everything") { addSpread(listOf(hex1, hex2, hex3)) }
        }
        return fixtures
    }

    private fun entry(
        key: String,
        property: String = "dimmer",
        value: Int = 100,
        group: String? = null,
    ) = RecordEntry(
        fixtureKey = key,
        propertyName = property,
        value = CueAssignmentResolver.PropertyValue.Slider(value.toUByte()),
        sourceGroup = group,
        maskGroup = PropertyMaskGroup.INTENSITY,
    )

    @Test
    fun `no hints - every entry emits as a fixture row`() {
        val out = collapseRecordingToAssignments(
            listOf(entry("hex-1", value = 100), entry("hex-2", value = 200)),
            fixtures(),
        )
        assertEquals(0, out.groupRows)
        assertTrue(out.rows.all { it.targetType == "fixture" })
        assertEquals(
            setOf("hex-1" to "100", "hex-2" to "200"),
            out.rows.map { it.targetKey to it.value }.toSet(),
        )
    }

    @Test
    fun `uniform hinted group collapses to one row`() {
        val out = collapseRecordingToAssignments(
            listOf(
                entry("hex-1", value = 150, group = "front-wash"),
                entry("hex-2", value = 150, group = "front-wash"),
            ),
            fixtures(),
        )
        assertEquals(1, out.rows.size, "members must not be re-emitted alongside the group row")
        assertEquals(1, out.groupRows)
        val row = out.rows.single()
        assertEquals("group", row.targetType)
        assertEquals("front-wash", row.targetKey)
        assertEquals("150", row.value)
    }

    @Test
    fun `one member overridden - falls back to fixture rows`() {
        // This is the case a hint-trusting implementation would get wrong: emitting
        // front-wash@150 would silently change hex-2, which the operator set to 80.
        val out = collapseRecordingToAssignments(
            listOf(
                entry("hex-1", value = 150, group = "front-wash"),
                entry("hex-2", value = 80, group = "front-wash"),
            ),
            fixtures(),
        )
        assertEquals(0, out.groupRows)
        assertEquals(2, out.rows.size)
        assertTrue(out.rows.all { it.targetType == "fixture" })
    }

    @Test
    fun `member missing from the recording - falls back to fixture rows`() {
        // Half a group busked (or the other half masked out) is not a group decision.
        val out = collapseRecordingToAssignments(
            listOf(entry("hex-1", value = 150, group = "front-wash")),
            fixtures(),
        )
        assertEquals(0, out.groupRows)
        assertEquals(listOf("hex-1"), out.rows.map { it.targetKey })
    }

    @Test
    fun `stale hint naming an unknown group degrades to fixture rows`() {
        val out = collapseRecordingToAssignments(
            listOf(entry("hex-1", value = 150, group = "deleted-group")),
            fixtures(),
        )
        assertEquals(0, out.groupRows)
        assertEquals("fixture", out.rows.single().targetType)
    }

    @Test
    fun `overlapping groups emit the covered members only once`() {
        // hex-1/hex-2 are in both groups. Two group rows asserting the same values would
        // compose identically but read as two separate operator decisions on the cue card.
        val out = collapseRecordingToAssignments(
            listOf(
                entry("hex-1", value = 150, group = "front-wash"),
                entry("hex-2", value = 150, group = "everything"),
                entry("hex-3", value = 150, group = "everything"),
            ),
            fixtures(),
        )
        assertEquals(1, out.groupRows, "one group covers all three; the nested hint adds nothing")
        assertEquals(1, out.rows.size)
        assertEquals("everything", out.rows.single().targetKey)
    }

    @Test
    fun `output is deterministic regardless of entry order`() {
        // A store walk has no inherent order. Two identical Records producing differently
        // ordered rows would make cue diffs unreadable.
        val base = listOf(
            entry("hex-3", property = "dimmer", value = 10),
            entry("hex-1", property = "strobe", value = 20),
            entry("hex-1", property = "dimmer", value = 30),
        )
        val a = collapseRecordingToAssignments(base, fixtures()).rows
        val b = collapseRecordingToAssignments(base.reversed(), fixtures()).rows
        assertEquals(a.map { it.targetKey to it.propertyName }, b.map { it.targetKey to it.propertyName })
        assertEquals(listOf(0, 1, 2), a.map { it.sortOrder })
    }

    @Test
    fun `group rows sort before the fixture rows they do not cover`() {
        val out = collapseRecordingToAssignments(
            listOf(
                entry("hex-1", value = 150, group = "front-wash"),
                entry("hex-2", value = 150, group = "front-wash"),
                entry("hex-3", value = 42),
            ),
            fixtures(),
        )
        assertEquals(listOf("group", "fixture"), out.rows.map { it.targetType })
        assertEquals(listOf("front-wash", "hex-3"), out.rows.map { it.targetKey })
    }

    // ─── Palette references ────────────────────────────────────────────────

    private val paletteA: java.util.UUID =
        java.util.UUID.fromString("2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02")
    private val paletteB: java.util.UUID =
        java.util.UUID.fromString("9b7e2c10-4a5d-4c88-b0f3-1de4a7c93b55")

    private fun refEntry(
        key: String,
        palette: java.util.UUID,
        property: String = "dimmer",
        value: Int = 100,
        group: String? = null,
    ) = entry(key, property, value, group).copy(paletteUuid = palette)

    @Test
    fun `a recorded ref entry stores the reference, not the literal it resolved to`() {
        // Without this, busking a palette and hitting Record silently detaches the new cue from the
        // palette: it would store today's value and every later palette edit would skip it, because
        // activeCuesReferencingPalette matches on the ref string.
        val out = collapseRecordingToAssignments(listOf(refEntry("hex-1", paletteA)), fixtures())
        assertEquals(
            uk.me.cormack.lighting7.fx.paletteRefValue(paletteA), out.rows.single().value,
        )
    }

    @Test
    fun `a literal entry still stores its literal`() {
        val out = collapseRecordingToAssignments(listOf(entry("hex-1", value = 100)), fixtures())
        assertEquals("100", out.rows.single().value)
    }

    @Test
    fun `members referencing the same palette collapse to one group ref row`() {
        val out = collapseRecordingToAssignments(
            listOf(
                refEntry("hex-1", paletteA, group = "front-wash"),
                refEntry("hex-2", paletteA, group = "front-wash"),
            ),
            fixtures(),
        )
        assertEquals(1, out.groupRows)
        val row = out.rows.single()
        assertEquals("front-wash", row.targetKey)
        assertEquals(uk.me.cormack.lighting7.fx.paletteRefValue(paletteA), row.value)
    }

    @Test
    fun `members referencing different palettes do not collapse`() {
        // Same resolved value, different palettes. Collapsing would emit one group row naming one
        // of the two and silently rebind the other member to it.
        val out = collapseRecordingToAssignments(
            listOf(
                refEntry("hex-1", paletteA, value = 100, group = "front-wash"),
                refEntry("hex-2", paletteB, value = 100, group = "front-wash"),
            ),
            fixtures(),
        )
        assertEquals(0, out.groupRows, "identical values from different palettes are not uniform")
        assertEquals(2, out.rows.size)
        assertTrue(out.rows.all { it.targetKey.startsWith("hex-") })
    }

    @Test
    fun `a ref and a literal with the same value do not collapse`() {
        val out = collapseRecordingToAssignments(
            listOf(
                refEntry("hex-1", paletteA, value = 100, group = "front-wash"),
                entry("hex-2", value = 100, group = "front-wash"),
            ),
            fixtures(),
        )
        assertEquals(0, out.groupRows)
        assertEquals(2, out.rows.size)
    }
}
