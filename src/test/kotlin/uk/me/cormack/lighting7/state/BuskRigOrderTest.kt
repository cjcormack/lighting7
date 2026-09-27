package uk.me.cormack.lighting7.state

import org.junit.Test
import uk.me.cormack.lighting7.fx.SpreadOver
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.BuskRigFixture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The effective rig order (busk-further plan §3.3), pinned against
 * `src/test/resources/busk/rigOrder.fixture.json` — the file the client's `buskRig.test.ts` pins
 * `effectiveRig` against, so the desk's *Next* and the band's fallback cannot disagree.
 */
class BuskRigOrderTest {

    private val file = BuskRigFixture.rigOrderFile()
    private val fixtures = BuskRigFixture.fixtures(file.fixtures, file.groups)

    private fun order(rig: String) = BuskRigOrder(fixtures, BuskRigFixture.rig(file.rigs.getValue(rig)))

    @Test
    fun `every fixture case matches the steps at both granularities and the sort`() {
        assertTrue(file.cases.isNotEmpty())
        for (case in file.cases) {
            val order = order(case.rig)
            assertEquals(case.stepsHeads, order.steps(SpreadOver.HEADS), "${case.note}: steps over heads")
            assertEquals(case.stepsCells, order.steps(SpreadOver.CELLS), "${case.note}: steps over cells")
            assertEquals(case.sortOutput, order.sort(case.sortInput), "${case.note}: sort")
        }
    }

    @Test
    fun `positions place a fixture's cells after it and before the next fixture`() {
        val positions = order("empty").positions()
        val bar1 = positions.getValue(CueTargetDto("fixture", "bar-1"))
        val first = positions.getValue(CueTargetDto("fixture", "bar-1.pixel-0"))
        val last = positions.getValue(CueTargetDto("fixture", "bar-1.pixel-11"))
        val bar2 = positions.getValue(CueTargetDto("fixture", "bar-2"))
        assertTrue(bar1 < first && first < last && last < bar2)
    }

    /**
     * An infrastructure fixture is not in the empty rig's fixture half — the client's
     * `effectiveRig` drops it the same way, so *All* and *Next* never land on a power dimmer.
     * Everything else in the fallback is unchanged and in the same order.
     */
    @Test
    fun `the empty rig's fallback leaves an infrastructure fixture out`() {
        val before = order("empty").steps(SpreadOver.HEADS)
        val hex2 = listOf(CueTargetDto("fixture", "hex-2"))
        assertTrue(hex2 in before, "the file's empty rig steps hex-2 as a fixture")

        fixtures.setPatchMetadata("hex-2", Fixtures.FixturePatchMetadata(gelCode = null, infrastructure = true))
        val after = order("empty")
        assertEquals(before - setOf(hex2), after.steps(SpreadOver.HEADS))
    }

    /**
     * Ordering is not offering: with no group reaching it, an infrastructure fixture a spread names
     * directly still sorts where the fixture list has it, not last.
     */
    @Test
    fun `an infrastructure fixture a caller names still sorts in list order`() {
        val ungrouped = BuskRigFixture.fixtures(file.fixtures, emptyList())
        ungrouped.setPatchMetadata("hex-2", Fixtures.FixturePatchMetadata(gelCode = null, infrastructure = true))
        val order = BuskRigOrder(ungrouped, BuskRigFixture.rig(file.rigs.getValue("empty")))
        assertTrue(listOf(CueTargetDto("fixture", "hex-2")) !in order.steps(SpreadOver.HEADS), "not offered")
        assertEquals(
            listOf("hex-1", "hex-2", "bar-1"),
            order.sort(listOf("bar-1", "hex-2", "hex-1").map { CueTargetDto("fixture", it) }).map { it.key },
        )
    }

    @Test
    fun `halves cut contiguous runs and give the first runs the remainder`() {
        val cells = (0 until 12).map { CueTargetDto("fixture", "c$it") }
        assertEquals(listOf(3, 3, 2, 2, 2), BuskRigOrder.halves(cells, 5).map { it.size })
        assertEquals(cells, BuskRigOrder.halves(cells, 5).flatten(), "no cell is lost or reordered")
        assertEquals(listOf(6, 6), BuskRigOrder.halves(cells, 2).map { it.size })
        assertEquals(12, BuskRigOrder.halves(cells, 40).size, "a split past the cell count is one cell per run")
        assertEquals(emptyList(), BuskRigOrder.halves(emptyList(), 3))
    }

    @Test
    fun `sort is stable and leaves unplaced heads last in the order given`() {
        val out = order("built").sort(
            listOf(CueTargetDto("fixture", "z"), CueTargetDto("fixture", "hex-2"), CueTargetDto("fixture", "a"), CueTargetDto("fixture", "hex-1")),
        )
        assertEquals(listOf("hex-1", "hex-2", "z", "a"), out.map { it.key })
    }
}
