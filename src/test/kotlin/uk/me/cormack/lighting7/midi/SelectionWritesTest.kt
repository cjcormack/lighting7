package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionWritesTest {

    private val universe = Universe(0, 0)

    private fun fixtures(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val h1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val h2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13, maxDimmerLevel = 200u))
            addFixture(HexFixture(universe, "hex-3", "Hex 3", firstChannel = 25))
            createGroup<HexFixture>("front-wash") { addSpread(listOf(h1, h2)) }
        }
        return fixtures
    }

    @Test
    fun `an empty selection yields no writes`() {
        assertTrue(SelectionWrites.forTargets(fixtures(), emptyList(), "dimmer", 127u).isEmpty())
    }

    @Test
    fun `a group fans to its members with the group as source and each member's own range`() {
        val writes = SelectionWrites.forTargets(
            fixtures(), listOf(CueTargetDto("group", "front-wash")), "dimmer", 127u,
        )
        assertEquals(listOf("hex-1", "hex-2"), writes.map { it.fixture.targetKey })
        assertTrue(writes.all { it.sourceGroup == "front-wash" })
        assertEquals(255u.toUByte(), assertIs<CueAssignmentResolver.PropertyValue.Slider>(writes[0].value).value)
        assertEquals(200u.toUByte(), assertIs<CueAssignmentResolver.PropertyValue.Slider>(writes[1].value).value)
    }

    @Test
    fun `a fixture and a group in one selection become one write list with no head twice`() {
        val writes = SelectionWrites.forTargets(
            fixtures(),
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("group", "front-wash"), CueTargetDto("fixture", "hex-3")),
            "dimmer", 0u,
        )
        assertEquals(listOf("hex-1", "hex-2", "hex-3"), writes.map { it.fixture.targetKey })
        assertNull(writes[0].sourceGroup, "hex-1 was named directly, before the group")
        assertEquals("front-wash", writes[1].sourceGroup)
    }

    @Test
    fun `a head without the property and a target that no longer resolves are skipped`() {
        val writes = SelectionWrites.forTargets(
            fixtures(),
            listOf(CueTargetDto("fixture", "hex-9"), CueTargetDto("group", "gone"), CueTargetDto("fixture", "hex-1")),
            "tilt", 64u,
        )
        assertTrue(writes.isEmpty(), "no Hex has a tilt, and the ghosts contribute nothing")
        val colour = SelectionWrites.forTargets(fixtures(), listOf(CueTargetDto("fixture", "hex-1")), "rgbColour", 64u)
        assertEquals(1, colour.size)
        assertIs<CueAssignmentResolver.PropertyValue.Colour>(colour.single().value)
    }
}
