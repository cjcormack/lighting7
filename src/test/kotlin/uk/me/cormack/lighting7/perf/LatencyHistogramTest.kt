package uk.me.cormack.lighting7.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LatencyHistogramTest {

    @Test
    fun `count and sum track every record`() {
        val h = LatencyHistogram()
        h.record(100)
        h.record(200)
        h.record(300)
        assertEquals(3, h.count)
        assertEquals(600, h.sumNanos)
        assertEquals(300, h.maxNanos)
    }

    @Test
    fun `negative observations are dropped`() {
        val h = LatencyHistogram()
        h.record(-1)
        h.record(100)
        assertEquals(1, h.count)
        assertEquals(100, h.sumNanos)
    }

    @Test
    fun `bucket index follows log2`() {
        val h = LatencyHistogram()
        // Buckets:
        //  0 → [1,2)
        //  1 → [2,4)
        //  2 → [4,8)
        // 10 → [1024,2048)  ≈ [1,2) µs
        // 20 → [1048576, 2097152) ≈ [1,2) ms
        h.record(1)        // bucket 0
        h.record(3)        // bucket 1
        h.record(1500)     // bucket 10
        h.record(2_000_000) // bucket 20
        val snap = h.snapshot()
        assertEquals(1, snap.buckets[0].count)
        assertEquals(1, snap.buckets[1].count)
        assertEquals(1, snap.buckets[10].count)
        assertEquals(1, snap.buckets[20].count)
        assertEquals(0, snap.buckets[2].count)
    }

    @Test
    fun `percentiles return zero for empty histogram`() {
        val h = LatencyHistogram()
        assertEquals(0, h.percentileNanos(50.0))
        assertEquals(0, h.percentileNanos(99.0))
    }

    @Test
    fun `percentiles approximate correctly`() {
        val h = LatencyHistogram()
        // 99 records in bucket 10 (1024-2048ns) and 1 in bucket 20 (1-2ms)
        repeat(99) { h.record(1500) }
        h.record(2_000_000)
        // p50 falls in bucket 10 → bound 2048 ns, capped by max (2_000_000) → 2048
        assertEquals(2048L, h.percentileNanos(50.0))
        // p99 falls inside bucket 10 (cumulative=99 ≥ 99) → 2048 ns
        assertEquals(2048L, h.percentileNanos(99.0))
        // p100 -> bucket 20 reached, bound 2097152 ns (capped by max 2_000_000)
        assertEquals(2_000_000L, h.percentileNanos(100.0))
        assertEquals(2_000_000L, h.maxNanos)
    }

    @Test
    fun `reset clears state`() {
        val h = LatencyHistogram()
        h.record(1500)
        h.record(3000)
        h.reset()
        assertEquals(0, h.count)
        assertEquals(0, h.sumNanos)
        assertEquals(0, h.maxNanos)
        val snap = h.snapshot()
        assertTrue(snap.buckets.all { it.count == 0L })
    }

    @Test
    fun `observations above bucket capacity pile into top bucket but max stays accurate`() {
        val h = LatencyHistogram(bucketCount = 8)
        // bucket 7 covers [128, 256). Anything ≥ 256 falls into bucket 7 too (top bucket).
        h.record(50)   // bucket 5 (32..64)
        h.record(200)  // bucket 7 (128..256)
        h.record(10_000) // bucket 7 (clamp)
        val snap = h.snapshot()
        assertEquals(1, snap.buckets[5].count)
        assertEquals(2, snap.buckets[7].count)
        assertEquals(10_000, snap.maxNanos)
    }

    @Test
    fun `concurrent records do not lose count`() {
        val h = LatencyHistogram()
        val threads = 8
        val perThread = 5_000
        val workers = (0 until threads).map {
            Thread {
                repeat(perThread) { h.record(1500) }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals((threads * perThread).toLong(), h.count)
    }

    @Test
    fun `snapshot meanNanos handles zero count`() {
        val h = LatencyHistogram()
        val snap = h.snapshot()
        assertEquals(0, snap.count)
        assertEquals(0, snap.meanNanos)
    }
}
