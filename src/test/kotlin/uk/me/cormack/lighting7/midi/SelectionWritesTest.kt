package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionWritesTest {

    private val universe = Universe(0, 0)

    private fun fixtures(controller: MockDmxController? = null): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            if (controller != null) addController(controller)
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

    @Test
    fun `a colour write is a hue on each head's own current colour`() {
        val controller = MockDmxController(universe)
        val fixtures = fixtures(controller)
        // hex-1's colour is channels 2..4, hex-2's 14..16: a dim blue and a bright half-saturated cyan.
        controller.setValue(4, 200u, 0)
        controller.setValue(15, 255u, 0)
        controller.setValue(16, 255u, 0)
        controller.setValue(14, 128u, 0)
        val writes = SelectionWrites.forTargets(fixtures, listOf(CueTargetDto("group", "front-wash")), "rgbColour", 0u)
        assertEquals(listOf("hex-1", "hex-2"), writes.map { it.fixture.targetKey })
        val hex1 = assertIs<CueAssignmentResolver.PropertyValue.Colour>(writes[0].value).value.color
        val hex2 = assertIs<CueAssignmentResolver.PropertyValue.Colour>(writes[1].value).value.color
        assertEquals(Color(200, 0, 0), hex1, "dim and saturated stays so, at the new hue")
        assertEquals(Color(255, 128, 128), hex2, "bright and pastel stays so, at the new hue")
    }

    @Test
    fun `a colour axis lands on each head's own current colour, and a slider under an axis takes nothing`() {
        val controller = MockDmxController(universe)
        val fixtures = fixtures(controller)
        // hex-1 a dim blue, hex-2 a bright pastel cyan — saturation 0 leaves each a white at its own level.
        controller.setValue(4, 200u, 0)
        controller.setValue(14, 128u, 0)
        controller.setValue(15, 255u, 0)
        controller.setValue(16, 255u, 0)
        val writes = SelectionWrites.forTargets(
            fixtures, listOf(CueTargetDto("group", "front-wash")), "rgbColour", 0u, ColourAxis.SATURATION,
        )
        assertEquals(listOf("hex-1", "hex-2"), writes.map { it.fixture.targetKey })
        assertEquals(Color(200, 200, 200), assertIs<CueAssignmentResolver.PropertyValue.Colour>(writes[0].value).value.color)
        assertEquals(Color(255, 255, 255), assertIs<CueAssignmentResolver.PropertyValue.Colour>(writes[1].value).value.color)

        assertTrue(
            SelectionWrites.forTargets(fixtures, listOf(CueTargetDto("group", "front-wash")), "dimmer", 64u, ColourAxis.SATURATION).isEmpty(),
            "a dimmer has no saturation: every head is skipped",
        )
    }
}
