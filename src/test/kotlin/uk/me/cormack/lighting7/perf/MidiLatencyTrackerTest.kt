package uk.me.cormack.lighting7.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MidiLatencyTrackerTest {

    @Test
    fun `fresh tracker has zero counts in every pre-allocated bucket`() {
        val snap = MidiLatencyTracker().snapshot()
        assertEquals(MidiLatencyStage.entries.size, snap.buckets.size)
        for (stage in MidiLatencyStage.entries) {
            assertEquals(0, snap.buckets[stage.wireName]?.count)
        }
    }

    @Test
    fun `measure records into the targeted stage bucket`() {
        val tracker = MidiLatencyTracker()
        tracker.measure(MidiLatencyStage.INGRESS_CONTINUOUS) { busyWaitNanos(50_000) }
        val bucket = tracker.snapshot().buckets[MidiLatencyStage.INGRESS_CONTINUOUS.wireName]!!
        assertEquals(1, bucket.count)
        assertTrue(bucket.maxNanos >= 50_000)
    }

    @Test
    fun `multiple stages accumulate independently`() {
        val tracker = MidiLatencyTracker()
        repeat(3) { tracker.measure(MidiLatencyStage.INGRESS_CONTINUOUS) { busyWaitNanos(1_000) } }
        repeat(2) { tracker.measure(MidiLatencyStage.EGRESS_MOTOR) { busyWaitNanos(1_000) } }
        val snap = tracker.snapshot()
        assertEquals(3, snap.buckets[MidiLatencyStage.INGRESS_CONTINUOUS.wireName]?.count)
        assertEquals(2, snap.buckets[MidiLatencyStage.EGRESS_MOTOR.wireName]?.count)
        assertEquals(0, snap.buckets[MidiLatencyStage.INGRESS_BUTTON.wireName]?.count)
    }

    @Test
    fun `snapshot keys are sorted alphabetically for stable JSON output`() {
        val keys = MidiLatencyTracker().snapshot().buckets.keys.toList()
        assertEquals(keys.sorted(), keys)
    }

    @Test
    fun `reset zeroes every bucket`() {
        val tracker = MidiLatencyTracker()
        repeat(5) { tracker.measure(MidiLatencyStage.INGRESS_CONTINUOUS) { busyWaitNanos(1_000) } }
        tracker.reset()
        assertEquals(0, tracker.snapshot().buckets[MidiLatencyStage.INGRESS_CONTINUOUS.wireName]?.count)
    }

    @Test
    fun `measure rethrows but still records`() {
        val tracker = MidiLatencyTracker()
        try {
            tracker.measure<Unit>(MidiLatencyStage.INGRESS_CONTINUOUS) { error("boom") }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(1, tracker.snapshot().buckets[MidiLatencyStage.INGRESS_CONTINUOUS.wireName]?.count)
    }

    @Test
    fun `record accepts pre-measured nanos`() {
        val tracker = MidiLatencyTracker()
        tracker.record(MidiLatencyStage.EGRESS_LED, 12_345)
        val bucket = tracker.snapshot().buckets[MidiLatencyStage.EGRESS_LED.wireName]!!
        assertEquals(1, bucket.count)
        assertEquals(12_345, bucket.maxNanos)
    }

    private fun busyWaitNanos(nanos: Long) {
        val end = System.nanoTime() + nanos
        @Suppress("ControlFlowWithEmptyBody")
        while (System.nanoTime() < end) {}
    }
}
