package uk.me.cormack.lighting7.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueEditLatencyTrackerTest {

    @Test
    fun `fresh tracker has no live observations and no last snapshot`() {
        val tracker = CueEditLatencyTracker()
        val snap = tracker.snapshot()
        assertFalse(snap.sessionActive)
        assertEquals(0, snap.live.count)
        assertNull(snap.lastSessionEnded)
    }

    @Test
    fun `beginEdit flips active and clears live counts`() {
        val tracker = CueEditLatencyTracker()
        tracker.measure { /* noop pre-session work */ }
        tracker.onBeginEdit()
        assertTrue(tracker.sessionActive)
        assertEquals(0, tracker.snapshot().live.count)
    }

    @Test
    fun `measure records observations into live`() {
        val tracker = CueEditLatencyTracker()
        tracker.onBeginEdit()
        tracker.measure { busyWaitNanos(50_000) }
        val snap = tracker.snapshot()
        assertEquals(1, snap.live.count)
        assertTrue(snap.live.maxNanos >= 50_000, "max=${snap.live.maxNanos}")
    }

    @Test
    fun `endEdit freezes a snapshot to lastSessionEnded`() {
        val tracker = CueEditLatencyTracker()
        tracker.onBeginEdit()
        repeat(5) { tracker.measure { busyWaitNanos(10_000) } }
        tracker.onEndEdit()
        val snap = tracker.snapshot()
        assertFalse(snap.sessionActive)
        val last = assertNotNull(snap.lastSessionEnded)
        assertEquals(5, last.count)
    }

    @Test
    fun `next beginEdit resets live but preserves lastSessionEnded`() {
        val tracker = CueEditLatencyTracker()
        tracker.onBeginEdit()
        repeat(3) { tracker.measure { busyWaitNanos(10_000) } }
        tracker.onEndEdit()
        val firstSnap = tracker.snapshot().lastSessionEnded!!
        assertEquals(3, firstSnap.count)

        tracker.onBeginEdit()
        // live cleared
        assertEquals(0, tracker.snapshot().live.count)
        // lastSessionEnded still reflects the prior session until the next endEdit
        assertEquals(3, tracker.snapshot().lastSessionEnded?.count)

        tracker.measure { busyWaitNanos(10_000) }
        tracker.onEndEdit()
        assertEquals(1, tracker.snapshot().lastSessionEnded?.count)
    }

    @Test
    fun `measure rethrows but still records`() {
        val tracker = CueEditLatencyTracker()
        tracker.onBeginEdit()
        try {
            tracker.measure<Unit> { error("boom") }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(1, tracker.snapshot().live.count)
    }

    private fun busyWaitNanos(nanos: Long) {
        val end = System.nanoTime() + nanos
        @Suppress("ControlFlowWithEmptyBody")
        while (System.nanoTime() < end) {}
    }
}
