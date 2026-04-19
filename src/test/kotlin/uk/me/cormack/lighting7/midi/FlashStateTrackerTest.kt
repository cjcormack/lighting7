package uk.me.cormack.lighting7.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashStateTrackerTest {

    @Test
    fun `pressed returns true on first press and false on retrigger`() {
        val t = FlashStateTracker()
        assertTrue(t.pressed(1))
        assertFalse(t.pressed(1))
    }

    @Test
    fun `clearPress returns true when binding was active`() {
        val t = FlashStateTracker()
        t.pressed(1)
        assertTrue(t.clearPress(1))
        assertFalse(t.clearPress(1))
    }

    @Test
    fun `overlapping presses are tracked independently`() {
        val t = FlashStateTracker()
        t.pressed(1)
        t.pressed(2)
        assertEquals(2, t.activeCount)
        t.clearPress(1)
        assertEquals(1, t.activeCount)
        assertFalse(t.isActive(1))
        assertTrue(t.isActive(2))
    }

    @Test
    fun `clearAll drops all presses`() {
        val t = FlashStateTracker()
        t.pressed(1)
        t.pressed(2)
        t.pressed(3)
        t.clearAll()
        assertEquals(0, t.activeCount)
        assertFalse(t.isActive(1))
    }
}
