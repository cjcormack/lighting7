package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.fx.StackRanks.Band
import uk.me.cormack.lighting7.fx.StackRanks.Place
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Latest GO wins, in two bands (busk-stack plan D5, P3): each band's stacks ranked dense by operator GO. */
class StackRanksTest {
    private val ranks = StackRanks()

    @Test
    fun `an operator GO takes the top of its band and the stacks above close up`() {
        ranks.takeTop(1, Band.SHOW)
        ranks.takeTop(2, Band.SHOW)
        val moved = ranks.takeTop(1, Band.SHOW)
        assertEquals(mapOf(1 to Place(Band.SHOW, 2), 2 to Place(Band.SHOW, 1)), moved)
    }

    @Test
    fun `a follow keeps the stack where it is`() {
        ranks.takeTop(1, Band.SHOW)
        ranks.takeTop(2, Band.SHOW)
        assertTrue(ranks.keep(1, Band.SHOW).isEmpty())
        assertEquals(Place(Band.SHOW, 1), ranks.placeOf(1))
    }

    @Test
    fun `the busk band sits above every show rank, and a band change takes the new band's top`() {
        ranks.takeTop(5, Band.BUSK)
        ranks.takeTop(1, Band.SHOW)
        assertTrue(stackPriority(ranks.placeOf(5), 0) > stackPriority(ranks.placeOf(1), 999))
        val moved = ranks.takeTop(5, Band.SHOW)
        assertEquals(Place(Band.SHOW, 2), moved[5])
        assertEquals(5, ranks.stackAt(stackPriority(ranks.placeOf(5), 3)))
    }

    @Test
    fun `a removal keeps the ranks dense`() {
        ranks.takeTop(1, Band.SHOW)
        ranks.takeTop(2, Band.SHOW)
        ranks.takeTop(3, Band.SHOW)
        assertEquals(mapOf(3 to Place(Band.SHOW, 2)), ranks.remove(2).filterKeys { it == 3 })
        assertNull(ranks.placeOf(2))
        assertNull(ranks.stackAt(stackPriority(Place(Band.SHOW, 3), 0)), "nothing holds rank 3 any more")
    }

    @Test
    fun `a priority reads back to its stack, and none for a loose or bare one`() {
        ranks.takeTop(7, Band.BUSK)
        assertEquals(7, ranks.stackAt(stackPriority(ranks.placeOf(7), 12)))
        assertNull(ranks.stackAt(CueHolds.loosePriority(1)))
        assertNull(ranks.stackAt(stackPriority(null, 3)))
    }

    @Test
    fun `a restore keeps each band's order, renumbered dense`() {
        ranks.restore(mapOf(4 to Place(Band.SHOW, 5), 9 to Place(Band.SHOW, 2), 6 to Place(Band.BUSK, 3)))
        assertEquals(Place(Band.SHOW, 1), ranks.placeOf(9))
        assertEquals(Place(Band.SHOW, 2), ranks.placeOf(4))
        assertEquals(Place(Band.BUSK, 1), ranks.placeOf(6))
    }

    @Test
    fun `a full band refuses one more stack and moves nothing`() {
        for (id in 1..StackRanks.MAX_RANK) ranks.takeTop(id, Band.BUSK)
        assertFailsWith<CueStackLimitException> { ranks.takeTop(5_000, Band.BUSK) }
        assertNull(ranks.placeOf(5_000))
        assertTrue(ranks.takeTop(1, Band.BUSK).isNotEmpty(), "a stack already in it still GOs")
        assertTrue(stackPriority(Place(Band.BUSK, StackRanks.MAX_RANK), 999) < CueHolds.LOOSE_BAND)
    }
}
