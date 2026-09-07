package uk.me.cormack.lighting7.state

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [BuskPageState] — the desk's showing busk page, the fact a hardware *next page* button and a tab
 * click both move. Pure: the page list is a lambda, so the whole of it is testable without a show.
 */
class BuskPageStateTest {

    private val one = UUID.randomUUID()
    private val two = UUID.randomUUID()
    private val three = UUID.randomUUID()

    private var pages = listOf(1 to one, 2 to two, 3 to three)
    private fun state() = BuskPageState { pages }

    @Test
    fun `step wraps in both directions`() {
        // Wrapping rather than clamping, because these two are the only page controls a surface
        // has: a `next` that stops at the last page leaves the operator with no way back but a
        // mouse, on the one surface whose point is not needing one.
        val s = state()
        s.set(3)
        s.next()
        assertEquals(1, s.pageId.value)
        s.prev()
        assertEquals(3, s.pageId.value)
    }

    @Test
    fun `a step with nothing showing lands on the first page`() {
        val s = state()
        assertNull(s.pageId.value)
        s.next()
        assertEquals(1, s.pageId.value)
    }

    @Test
    fun `a step with no pages is a no-op rather than a crash`() {
        pages = emptyList()
        val s = state()
        s.next()
        assertNull(s.pageId.value)
    }

    @Test
    fun `set ignores a page that is not this project's`() {
        val s = state()
        s.set(1)
        s.set(99)
        assertEquals(1, s.pageId.value)
    }

    @Test
    fun `setByUuid is how a binding addresses a page`() {
        // A binding carries a uuid because an int id does not survive a clone; everything else on
        // this class speaks ids, which is what `?page=` and the page routes use.
        val s = state()
        s.setByUuid(two)
        assertEquals(2, s.pageId.value)
        s.setByUuid(UUID.randomUUID())
        assertEquals(2, s.pageId.value)
    }

    @Test
    fun `reconcile drops a deleted page and does not choose a replacement`() {
        // Deliberately null rather than the first page: the busk view already resolves a page it
        // cannot find against the list it fetched, so saying nothing leaves each client on its own
        // fallback instead of dragging every client onto a page none of them asked for.
        val s = state()
        s.set(2)
        pages = listOf(1 to one, 3 to three)
        s.reconcile()
        assertNull(s.pageId.value)
    }

    @Test
    fun `reconcile leaves a page that still exists`() {
        val s = state()
        s.set(2)
        pages = listOf(2 to two, 3 to three)
        s.reconcile()
        assertEquals(2, s.pageId.value)
    }
}
