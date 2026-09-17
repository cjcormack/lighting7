package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The curve and order maths of a spread (busk-further plan D9), as a table over n = 1, 2, 3, 8. */
class SpreadPlanTest {

    private fun fractions(n: Int, curve: SpreadCurve, parts: Int = 1, order: DistributionStrategy = DistributionStrategy.LINEAR) =
        SpreadPlan.fractions(n, order, curve, parts).map { Math.round(it * 1000.0) / 1000.0 }

    @Test
    fun `LINE runs from 0 to 1 and one head sits at from`() {
        assertEquals(listOf(0.0), fractions(1, SpreadCurve.LINE))
        assertEquals(listOf(0.0, 1.0), fractions(2, SpreadCurve.LINE))
        assertEquals(listOf(0.0, 0.5, 1.0), fractions(3, SpreadCurve.LINE))
        assertEquals((0 until 8).map { Math.round(it / 7.0 * 1000.0) / 1000.0 }, fractions(8, SpreadCurve.LINE))
    }

    @Test
    fun `MIRROR keeps the centre at from and the ends at to`() {
        // One head sits at x = 0: an end, so MIRROR gives it *to* where ARROW and LINE give it *from*.
        assertEquals(listOf(1.0), fractions(1, SpreadCurve.MIRROR))
        assertEquals(listOf(1.0, 1.0), fractions(2, SpreadCurve.MIRROR))
        assertEquals(listOf(1.0, 0.0, 1.0), fractions(3, SpreadCurve.MIRROR))
        val eight = fractions(8, SpreadCurve.MIRROR)
        assertEquals(eight, eight.reversed(), "symmetric")
        assertEquals(1.0, eight.first())
        assertEquals(0.143, eight[3])
    }

    @Test
    fun `ARROW keeps the ends at from and the centre at to`() {
        assertEquals(listOf(0.0), fractions(1, SpreadCurve.ARROW))
        assertEquals(listOf(0.0, 0.0), fractions(2, SpreadCurve.ARROW))
        assertEquals(listOf(0.0, 1.0, 0.0), fractions(3, SpreadCurve.ARROW))
        val eight = fractions(8, SpreadCurve.ARROW)
        assertEquals(eight, eight.reversed())
        assertEquals(0.0, eight.first())
        assertEquals(0.857, eight[3])
    }

    @Test
    fun `WINGS is two mirrored fans meeting at the centre, the centre head shared when odd`() {
        assertEquals(listOf(1.0), fractions(1, SpreadCurve.WINGS), "one head is an outer end, as under MIRROR")
        assertEquals(listOf(1.0, 1.0), fractions(2, SpreadCurve.WINGS))
        assertEquals(listOf(1.0, 0.0, 1.0), fractions(3, SpreadCurve.WINGS))
        assertEquals(listOf(1.0, 0.5, 0.0, 0.5, 1.0), fractions(5, SpreadCurve.WINGS))
        assertEquals(listOf(1.0, 0.667, 0.333, 0.0, 0.0, 0.333, 0.667, 1.0), fractions(8, SpreadCurve.WINGS))
        assertEquals(
            listOf(0.0, 0.333, 0.667, 1.0, 1.0, 0.667, 0.333, 0.0),
            fractions(8, SpreadCurve.WINGS, order = DistributionStrategy.REVERSE),
            "REVERSE turns both wings inside out together",
        )
        assertEquals(listOf(1.0, 0.0, 1.0, 1.0, 0.0, 1.0), fractions(6, SpreadCurve.WINGS, parts = 2), "each part has its own pair of wings")
        assertFailsWith<IllegalStateException> { SpreadCurve.WINGS.at(0.5) }
    }

    @Test
    fun `parts cuts the order into that many fans, the first fans longer when it does not divide`() {
        assertEquals(listOf(0.0, 1.0, 0.0, 1.0), fractions(4, SpreadCurve.LINE, parts = 2), "two fans of two")
        assertEquals(listOf(0.0, 0.5, 1.0, 0.0, 1.0), fractions(5, SpreadCurve.LINE, parts = 2), "a fan of three then a fan of two")
        assertEquals(listOf(0.0, 0.5, 1.0, 0.0, 0.5, 1.0, 0.0, 1.0), fractions(8, SpreadCurve.LINE, parts = 3))
        assertEquals(listOf(1.0, 0.0, 1.0, 1.0, 1.0), fractions(5, SpreadCurve.MIRROR, parts = 2), "each fan has its own centre")
        assertEquals(listOf(0.0, 0.0, 0.0), fractions(3, SpreadCurve.LINE, parts = 7), "more parts than heads is one head per fan")
        assertFailsWith<IllegalArgumentException> { SpreadPlan.fractions(3, DistributionStrategy.LINEAR, SpreadCurve.LINE, 0) }
    }

    @Test
    fun `REVERSE runs the order the other way and UNIFIED puts every head at from`() {
        assertEquals(listOf(1.0, 0.5, 0.0), fractions(3, SpreadCurve.LINE, order = DistributionStrategy.REVERSE))
        assertEquals(listOf(0.0, 0.0, 0.0), fractions(3, SpreadCurve.LINE, order = DistributionStrategy.UNIFIED))
    }

    @Test
    fun `CENTER_OUT folds so the ends share a position and RANDOM is a seeded permutation`() {
        val centre = fractions(8, SpreadCurve.LINE, order = DistributionStrategy.CENTER_OUT)
        assertEquals(centre.first(), centre.last())
        assertEquals(1.0, centre.max())
        val a = fractions(8, SpreadCurve.LINE, order = DistributionStrategy.RANDOM(7))
        val b = fractions(8, SpreadCurve.LINE, order = DistributionStrategy.RANDOM(7))
        assertEquals(a, b, "same seed, same order")
        assertEquals(fractions(8, SpreadCurve.LINE).sorted(), a.sorted(), "a permutation of the linear positions")
    }

    @Test
    fun `positions normalise the strategy's phase spacing so the far end reaches 1`() {
        val positions = SpreadPlan.positions(4, DistributionStrategy.LINEAR).toList()
        assertEquals(listOf(0.0, 1.0 / 3, 2.0 / 3, 1.0), positions)
        assertTrue(SpreadPlan.positions(0, DistributionStrategy.LINEAR).isEmpty())
    }

    @Test
    fun `curves and over parse by name, case-insensitively`() {
        assertEquals(SpreadCurve.WINGS, SpreadCurve.byName("wings"))
        assertEquals(SpreadOver.CELLS, SpreadOver.byName(" cells "))
        assertEquals(null, SpreadCurve.byName("zigzag"))
    }
}
