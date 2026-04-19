package uk.me.cormack.lighting7.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchStateTrackerTest {
    @Test
    fun `setTouched down returns true only on transition`() {
        val tracker = TouchStateTracker()
        assertTrue(tracker.setTouched("dev-1", "fader-1", down = true))
        assertFalse(tracker.setTouched("dev-1", "fader-1", down = true))
    }

    @Test
    fun `setTouched up returns true only if previously touched`() {
        val tracker = TouchStateTracker()
        assertFalse(tracker.setTouched("dev-1", "fader-1", down = false))
        tracker.setTouched("dev-1", "fader-1", down = true)
        assertTrue(tracker.setTouched("dev-1", "fader-1", down = false))
    }

    @Test
    fun `isTouched reflects state across multiple controls and devices`() {
        val tracker = TouchStateTracker()
        tracker.setTouched("dev-a", "fader-1", true)
        tracker.setTouched("dev-b", "fader-1", true)
        assertTrue(tracker.isTouched("dev-a", "fader-1"))
        assertTrue(tracker.isTouched("dev-b", "fader-1"))
        assertFalse(tracker.isTouched("dev-a", "fader-2"))
        assertFalse(tracker.isTouched("dev-c", "fader-1"))
    }

    @Test
    fun `clearDevice only drops state for one device`() {
        val tracker = TouchStateTracker()
        tracker.setTouched("dev-a", "fader-1", true)
        tracker.setTouched("dev-a", "fader-2", true)
        tracker.setTouched("dev-b", "fader-1", true)
        tracker.clearDevice("dev-a")
        assertFalse(tracker.isTouched("dev-a", "fader-1"))
        assertFalse(tracker.isTouched("dev-a", "fader-2"))
        assertTrue(tracker.isTouched("dev-b", "fader-1"))
    }

    @Test
    fun `clearAll drops everything`() {
        val tracker = TouchStateTracker()
        tracker.setTouched("dev-a", "fader-1", true)
        tracker.setTouched("dev-b", "fader-1", true)
        tracker.clearAll()
        assertFalse(tracker.isTouched("dev-a", "fader-1"))
        assertFalse(tracker.isTouched("dev-b", "fader-1"))
    }

    @Test
    fun `prefix collisions are not confused between similarly-named devices`() {
        val tracker = TouchStateTracker()
        tracker.setTouched("dev", "fader-1", true)
        tracker.setTouched("dev-1", "fader-1", true)
        tracker.clearDevice("dev")
        assertFalse(tracker.isTouched("dev", "fader-1"))
        // "dev-1" uses "dev-1|fader-1" key — must NOT be cleared by the "dev|" prefix.
        assertTrue(tracker.isTouched("dev-1", "fader-1"))
        assertEquals(true, tracker.isTouched("dev-1", "fader-1"))
    }
}
