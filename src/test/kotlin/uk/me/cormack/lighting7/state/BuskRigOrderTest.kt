package uk.me.cormack.lighting7.state

import org.junit.Test
import uk.me.cormack.lighting7.fx.SpreadOver
import uk.me.cormack.lighting7.models.CueTargetDto
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
