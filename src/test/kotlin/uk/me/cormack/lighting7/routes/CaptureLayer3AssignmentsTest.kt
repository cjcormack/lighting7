package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [captureLayer3AssignmentsFromSnapshot] — the pure group-shape-preserving collapse
 * used by `snapshot-from-live` and `/current-state`. Exercises the `FU-BE-GROUP-LAYER3-ROUNDTRIP`
 * round-trip: a group hint with uniform member values collapses to a single group row; any
 * broken uniformity (member override, missing member) falls back to per-fixture rows.
 */
class CaptureLayer3AssignmentsTest {

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

    private fun slider(v: UByte) = Layer3Resolver.PropertyValue.Slider(v)

    @Test
    fun `no group hints - all rows emit as fixture`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val snapshot = mapOf(
            Layer3Resolver.Key.fixture("hex-1", "dimmer") to slider(100u),
            Layer3Resolver.Key.fixture("hex-2", "dimmer") to slider(200u),
        )
        val out = captureLayer3AssignmentsFromSnapshot(snapshot, emptySet(), fixtures)
        assertEquals(2, out.size)
        assertTrue(out.all { it.targetType == "fixture" })
        assertEquals(setOf("hex-1" to "100", "hex-2" to "200"), out.map { it.targetKey to it.value }.toSet())
    }

    @Test
    fun `group hint with uniform member values collapses to a single group row`() {
        // Operator wrote `targetType=group` at 150 via cueEdit; all members composed to 150.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val snapshot = mapOf(
            Layer3Resolver.Key.fixture("hex-1", "dimmer") to slider(150u),
            Layer3Resolver.Key.fixture("hex-2", "dimmer") to slider(150u),
        )
        val hints = setOf("front-wash" to "dimmer")
        val out = captureLayer3AssignmentsFromSnapshot(snapshot, hints, fixtures)
        assertEquals(1, out.size, "group shape preserved, members not re-emitted")
        val row = out.single()
        assertEquals("group", row.targetType)
        assertEquals("front-wash", row.targetKey)
        assertEquals("dimmer", row.propertyName)
        assertEquals("150", row.value)
    }

    @Test
    fun `group hint with broken uniformity falls back to per-fixture rows`() {
        // Another cue overrode hex-1 at the fixture level, so the composed state no longer
        // matches a single group value. We'd rather emit accurate per-fixture rows than a
        // lossy group row.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val snapshot = mapOf(
            Layer3Resolver.Key.fixture("hex-1", "dimmer") to slider(50u),
            Layer3Resolver.Key.fixture("hex-2", "dimmer") to slider(150u),
        )
        val hints = setOf("front-wash" to "dimmer")
        val out = captureLayer3AssignmentsFromSnapshot(snapshot, hints, fixtures)
        assertEquals(2, out.size)
        assertTrue(out.all { it.targetType == "fixture" })
        assertEquals(
            mapOf("hex-1" to "50", "hex-2" to "150"),
            out.associate { it.targetKey to it.value },
        )
    }

    @Test
    fun `group hint with a missing member resolved value skips the group row`() {
        // hex-2 has no composed entry (e.g. because the other cue dropped its contribution
        // mid-edit). We can't emit group-front-wash unambiguously.
        val fixtures = fixturesWithTwoHexesInAGroup()
        val snapshot = mapOf(
            Layer3Resolver.Key.fixture("hex-1", "dimmer") to slider(100u),
        )
        val hints = setOf("front-wash" to "dimmer")
        val out = captureLayer3AssignmentsFromSnapshot(snapshot, hints, fixtures)
        assertEquals(1, out.size)
        assertEquals("fixture", out.single().targetType)
        assertEquals("hex-1", out.single().targetKey)
    }

    @Test
    fun `unknown group hint silently skipped and fixture rows still emitted`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val snapshot = mapOf(
            Layer3Resolver.Key.fixture("hex-1", "dimmer") to slider(100u),
        )
        val hints = setOf("ghost-group" to "dimmer")
        val out = captureLayer3AssignmentsFromSnapshot(snapshot, hints, fixtures)
        assertEquals(1, out.size)
        assertEquals("fixture", out.single().targetType)
        assertEquals("hex-1", out.single().targetKey)
    }

    @Test
    fun `group collapse plus unrelated fixture row round-trips both`() {
        // Uniform group hit for dimmer + an unrelated uncovered uv row (simulates a
        // timed-preset fire that isn't DB-tracked).
        val fixtures = fixturesWithTwoHexesInAGroup()
        val snapshot = mapOf(
            Layer3Resolver.Key.fixture("hex-1", "dimmer") to slider(200u),
            Layer3Resolver.Key.fixture("hex-2", "dimmer") to slider(200u),
            Layer3Resolver.Key.fixture("hex-1", "uv") to slider(50u),
        )
        val hints = setOf("front-wash" to "dimmer")
        val out = captureLayer3AssignmentsFromSnapshot(snapshot, hints, fixtures)
        assertEquals(2, out.size)
        val groupRow = out.first { it.targetType == "group" }
        assertEquals("front-wash", groupRow.targetKey)
        assertEquals("dimmer", groupRow.propertyName)
        assertEquals("200", groupRow.value)
        val fixtureRow = out.first { it.targetType == "fixture" }
        assertEquals("hex-1", fixtureRow.targetKey)
        assertEquals("uv", fixtureRow.propertyName)
        assertEquals("50", fixtureRow.value)
    }

    @Test
    fun `empty snapshot emits empty output regardless of hints`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val out = captureLayer3AssignmentsFromSnapshot(
            emptyMap(),
            setOf("front-wash" to "dimmer"),
            fixtures,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `sortOrder is dense zero-indexed`() {
        val fixtures = fixturesWithTwoHexesInAGroup()
        val snapshot = mapOf(
            Layer3Resolver.Key.fixture("hex-1", "dimmer") to slider(100u),
            Layer3Resolver.Key.fixture("hex-2", "dimmer") to slider(100u),
            Layer3Resolver.Key.fixture("hex-1", "uv") to slider(50u),
        )
        val hints = setOf("front-wash" to "dimmer")
        val out = captureLayer3AssignmentsFromSnapshot(snapshot, hints, fixtures)
        assertEquals(listOf(0, 1), out.map { it.sortOrder })
    }
}
