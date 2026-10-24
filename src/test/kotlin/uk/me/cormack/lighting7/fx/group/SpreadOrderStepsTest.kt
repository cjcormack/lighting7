package uk.me.cormack.lighting7.fx.group

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [steps]: the order each strategy brings a group's members round, 1 first, as the group sheet
 * shows it (venue-visit plan S7). Shared offsets share a step.
 */
class SpreadOrderStepsTest {

    @Test
    fun `each strategy's steps over six members`() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), SpreadOrder.NORMAL.steps(6))
        assertEquals(listOf(6, 5, 4, 3, 2, 1), SpreadOrder.REVERSE.steps(6))
        assertEquals(listOf(1, 1, 1, 1, 1, 1), SpreadOrder.UNIFIED.steps(6))
        assertEquals(listOf(3, 2, 1, 1, 2, 3), SpreadOrder.CENTRE_OUT.steps(6))
        assertEquals(listOf(1, 2, 3, 3, 2, 1), SpreadOrder.CENTRE_IN.steps(6))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), SpreadOrder.PING_PONG.steps(6))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), SpreadOrder.POSITIONAL.steps(6))
    }

    @Test
    fun `an odd group's centre is its own step`() {
        assertEquals(listOf(3, 2, 1, 2, 3), SpreadOrder.CENTRE_OUT.steps(5))
        assertEquals(listOf(1, 2, 3, 2, 1), SpreadOrder.CENTRE_IN.steps(5))
    }

    @Test
    fun `random is a permutation, the same one a group effect deals at seed 0`() {
        val steps = SpreadOrder.RANDOM().steps(8)
        assertEquals((1..8).toList(), steps.sorted())
        val offsets = SpreadOrder.RANDOM(0).offsets(
            List(8) { i -> object : DistributionMemberInfo { override val index = i; override val normalizedPosition = i / 7.0 } }, 8,
        )
        assertEquals(offsets.map { (it * 8).toInt() + 1 }, steps)
    }

    @Test
    fun `empty and single groups`() {
        assertEquals(emptyList(), SpreadOrder.NORMAL.steps(0))
        assertEquals(listOf(1), SpreadOrder.CENTRE_OUT.steps(1))
        assertEquals(SpreadOrder.availableOrders.toSet(), SpreadOrder.stepsBySize(3).keys)
    }
}
