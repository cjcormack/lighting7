package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The curve and order maths of a spread (busk-further plan D9), as a table over n = 1, 2, 3, 8. */
class FanPlanTest {

    private fun fractions(n: Int, curve: FanCurve, runs: Int = 1, order: SpreadOrder = SpreadOrder.NORMAL) =
        FanPlan.fractions(n, order, curve, runs).map { Math.round(it * 1000.0) / 1000.0 }

    @Test
    fun `LINE runs from 0 to 1 and one head sits at from`() {
        assertEquals(listOf(0.0), fractions(1, FanCurve.LINE))
        assertEquals(listOf(0.0, 1.0), fractions(2, FanCurve.LINE))
        assertEquals(listOf(0.0, 0.5, 1.0), fractions(3, FanCurve.LINE))
        assertEquals((0 until 8).map { Math.round(it / 7.0 * 1000.0) / 1000.0 }, fractions(8, FanCurve.LINE))
    }

    @Test
    fun `MIRROR keeps the centre at from and the ends at to`() {
        // One head sits at x = 0: an end, so MIRROR gives it *to* where ARROW and LINE give it *from*.
        assertEquals(listOf(1.0), fractions(1, FanCurve.MIRROR))
        assertEquals(listOf(1.0, 1.0), fractions(2, FanCurve.MIRROR))
        assertEquals(listOf(1.0, 0.0, 1.0), fractions(3, FanCurve.MIRROR))
        val eight = fractions(8, FanCurve.MIRROR)
        assertEquals(eight, eight.reversed(), "symmetric")
        assertEquals(1.0, eight.first())
        assertEquals(0.143, eight[3])
    }

    @Test
    fun `ARROW keeps the ends at from and the centre at to`() {
        assertEquals(listOf(0.0), fractions(1, FanCurve.ARROW))
        assertEquals(listOf(0.0, 0.0), fractions(2, FanCurve.ARROW))
        assertEquals(listOf(0.0, 1.0, 0.0), fractions(3, FanCurve.ARROW))
        val eight = fractions(8, FanCurve.ARROW)
        assertEquals(eight, eight.reversed())
        assertEquals(0.0, eight.first())
        assertEquals(0.857, eight[3])
    }

    @Test
    fun `MIRROR_OUT is two mirrored fans meeting at the centre, the centre head shared when odd`() {
        assertEquals(listOf(1.0), fractions(1, FanCurve.MIRROR_OUT), "one head is an outer end, as under MIRROR")
        assertEquals(listOf(1.0, 1.0), fractions(2, FanCurve.MIRROR_OUT))
        assertEquals(listOf(1.0, 0.0, 1.0), fractions(3, FanCurve.MIRROR_OUT))
        assertEquals(listOf(1.0, 0.5, 0.0, 0.5, 1.0), fractions(5, FanCurve.MIRROR_OUT))
        assertEquals(listOf(1.0, 0.667, 0.333, 0.0, 0.0, 0.333, 0.667, 1.0), fractions(8, FanCurve.MIRROR_OUT))
        assertEquals(
            listOf(0.0, 0.333, 0.667, 1.0, 1.0, 0.667, 0.333, 0.0),
            fractions(8, FanCurve.MIRROR_OUT, order = SpreadOrder.REVERSE),
            "REVERSE turns both wings inside out together",
        )
        assertEquals(listOf(1.0, 0.0, 1.0, 1.0, 0.0, 1.0), fractions(6, FanCurve.MIRROR_OUT, runs = 2), "each run has its own pair of wings")
        assertFailsWith<IllegalStateException> { FanCurve.MIRROR_OUT.at(0.5) }
    }

    @Test
    fun `runs cuts the order into that many fans, the first fans longer when it does not divide`() {
        assertEquals(listOf(0.0, 1.0, 0.0, 1.0), fractions(4, FanCurve.LINE, runs = 2), "two fans of two")
        assertEquals(listOf(0.0, 0.5, 1.0, 0.0, 1.0), fractions(5, FanCurve.LINE, runs = 2), "a fan of three then a fan of two")
        assertEquals(listOf(0.0, 0.5, 1.0, 0.0, 0.5, 1.0, 0.0, 1.0), fractions(8, FanCurve.LINE, runs = 3))
        assertEquals(listOf(1.0, 0.0, 1.0, 1.0, 1.0), fractions(5, FanCurve.MIRROR, runs = 2), "each fan has its own centre")
        assertEquals(listOf(0.0, 0.0, 0.0), fractions(3, FanCurve.LINE, runs = 7), "more runs than heads is one head per fan")
        assertFailsWith<IllegalArgumentException> { FanPlan.fractions(3, SpreadOrder.NORMAL, FanCurve.LINE, 0) }
    }

    @Test
    fun `REVERSE runs the order the other way and UNIFIED puts every head at from`() {
        assertEquals(listOf(1.0, 0.5, 0.0), fractions(3, FanCurve.LINE, order = SpreadOrder.REVERSE))
        assertEquals(listOf(0.0, 0.0, 0.0), fractions(3, FanCurve.LINE, order = SpreadOrder.UNIFIED))
    }

    @Test
    fun `CENTRE_OUT folds so the ends share a position and RANDOM is a seeded permutation`() {
        val centre = fractions(8, FanCurve.LINE, order = SpreadOrder.CENTRE_OUT)
        assertEquals(centre.first(), centre.last())
        assertEquals(1.0, centre.max())
        val a = fractions(8, FanCurve.LINE, order = SpreadOrder.RANDOM(7))
        val b = fractions(8, FanCurve.LINE, order = SpreadOrder.RANDOM(7))
        assertEquals(a, b, "same seed, same order")
        assertEquals(fractions(8, FanCurve.LINE).sorted(), a.sorted(), "a permutation of the linear positions")
    }

    @Test
    fun `positions normalise the strategy's phase spacing so the far end reaches 1`() {
        val positions = FanPlan.positions(4, SpreadOrder.NORMAL).toList()
        assertEquals(listOf(0.0, 1.0 / 3, 2.0 / 3, 1.0), positions)
        assertTrue(FanPlan.positions(0, SpreadOrder.NORMAL).isEmpty())
    }

    private fun fan(n: Int, curve: FanCurve = FanCurve.LINE, runs: Int = 1) =
        FanPlan.fanWeights(n, SpreadOrder.NORMAL, curve, runs).map { Math.round(it * 1000.0) / 1000.0 }

    @Test
    fun `a LINE fan is symmetric about the middle of the order`() {
        assertEquals(listOf(-1.0, -0.667, -0.333, 0.0, 0.333, 0.667, 1.0), fan(7))
        // Even: no head on the middle, so the two around it move by a step each way.
        assertEquals(listOf(-1.0, -0.6, -0.2, 0.2, 0.6, 1.0), fan(6))
        assertEquals(listOf(-1.0, 1.0), fan(2))
    }

    @Test
    fun `a fan of one head, or a part of one head, has nothing to fan`() {
        assertEquals(listOf(0.0), fan(1))
        assertEquals(listOf(0.0), fan(1, FanCurve.MIRROR))
        assertEquals(listOf(-1.0, 1.0, 0.0), fan(3, runs = 2))
    }

    @Test
    fun `runs are each their own fan, and other curves read against the midline`() {
        assertEquals(listOf(-1.0, -0.333, 0.333, 1.0, -1.0, -0.333, 0.333, 1.0), fan(8, runs = 2))
        assertEquals(listOf(1.0, -1.0, 1.0), fan(3, FanCurve.MIRROR))
    }

    @Test
    fun `curves and over parse by name, case-insensitively`() {
        assertEquals(FanCurve.MIRROR_OUT, FanCurve.byName("mirror_out"))
        assertEquals(FanOver.CELLS, FanOver.byName(" cells "))
        assertEquals(null, FanCurve.byName("zigzag"))
    }
}
