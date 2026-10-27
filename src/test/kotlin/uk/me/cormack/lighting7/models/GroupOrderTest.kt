package uk.me.cormack.lighting7.models

import org.junit.Test
import kotlin.test.assertEquals

/** [GroupOrder.merge] (group-order plan D3): the one rule a stored reorder and a show's order share. */
class GroupOrderTest {

    private fun merge(order: List<String>, wanted: List<String>) = GroupOrder.merge(order, wanted) { it }

    @Test
    fun `the members named fill the places they hold, in the order named`() {
        assertEquals(listOf("c", "b", "a"), merge(listOf("a", "b", "c"), listOf("c", "b", "a")))
        assertEquals(listOf("c", "b", "a"), merge(listOf("a", "b", "c"), listOf("c", "a")), "b keeps its place")
    }

    @Test
    fun `a member the order gained since keeps its place`() {
        assertEquals(listOf("b", "d", "a"), merge(listOf("a", "d", "b"), listOf("b", "a")))
    }

    @Test
    fun `a member the order lost since is skipped, as are a repeat and a stranger`() {
        assertEquals(listOf("c", "a"), merge(listOf("a", "c"), listOf("c", "b", "a")))
        assertEquals(listOf("b", "a"), merge(listOf("a", "b"), listOf("b", "b", "x", "a")))
    }

    @Test
    fun `a hidden member is not in the order it merges over, and an empty order changes nothing`() {
        assertEquals(listOf("c", "a"), merge(listOf("a", "c"), listOf("c", "b", "a")), "b, hidden, filtered first")
        assertEquals(listOf("a", "b", "c"), merge(listOf("a", "b", "c"), emptyList()))
        assertEquals(emptyList(), merge(emptyList(), listOf("a")))
    }
}
