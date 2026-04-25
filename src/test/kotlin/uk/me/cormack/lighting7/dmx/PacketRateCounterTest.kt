package uk.me.cormack.lighting7.dmx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacketRateCounterTest {

    @Test
    fun `total tracks every record`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        repeat(5) { counter.record(0L) }
        assertEquals(5, counter.total)
    }

    @Test
    fun `empty window reports zero`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        assertEquals(0.0, counter.packetsPerSecond(System.currentTimeMillis()))
    }

    @Test
    fun `current second is excluded from rate`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        val nowMs = 100_000L
        repeat(40) { counter.record(nowMs) }
        // Querying at the same second the writes happened: nothing in the closed window yet.
        assertEquals(0.0, counter.packetsPerSecond(nowMs))
    }

    @Test
    fun `completed second contributes to rate`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        // Drop 40 packets at second t=100, then read at t=101 — the t=100 bucket is now closed.
        val tWrite = 100_000L
        val tRead = 101_000L
        repeat(40) { counter.record(tWrite) }
        // One bucket with 40, averaged over the buckets we actually have in-window: 40.0.
        assertEquals(40.0, counter.packetsPerSecond(tRead))
    }

    @Test
    fun `averages across multiple recorded seconds`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        repeat(40) { counter.record(100_000L) }
        repeat(80) { counter.record(101_000L) }
        // Read at t=102: two closed buckets — 40 and 80. Average = 60.
        assertEquals(60.0, counter.packetsPerSecond(102_000L))
    }

    @Test
    fun `bucket from outside window is ignored`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        // Write at t=100 and look back from t=200. The t=100 bucket is older than 30s
        // — it should not contribute to the rate.
        repeat(40) { counter.record(100_000L) }
        assertEquals(0.0, counter.packetsPerSecond(200_000L))
    }

    @Test
    fun `stale bucket is reset before reuse`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        // Write 40 packets at t=100, then 10 packets at t=130 — buckets collide on `t % 30`.
        // The stale t=100 count must NOT carry over into t=130's bucket.
        repeat(40) { counter.record(100_000L) }
        repeat(10) { counter.record(130_000L) }
        // Read at t=131: only t=130 bucket counts (with 10 packets), not 40 + 10 = 50.
        assertEquals(10.0, counter.packetsPerSecond(131_000L))
        // Total still reflects every record across both seconds.
        assertEquals(50, counter.total)
    }

    @Test
    fun `concurrent records do not lose count`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        val threads = 8
        val perThread = 5_000
        val nowMs = 500_000L
        val workers = (0 until threads).map {
            Thread {
                repeat(perThread) { counter.record(nowMs) }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals((threads * perThread).toLong(), counter.total)
        // After advancing one second, the rate should reflect every recorded packet.
        assertEquals((threads * perThread).toDouble(), counter.packetsPerSecond(nowMs + 1_000L))
    }

    @Test
    fun `older bucket survives newer write to a different bucket`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        repeat(40) { counter.record(100_000L) }
        repeat(10) { counter.record(101_000L) }
        // Read at t=102 should include both seconds: average = (40 + 10) / 2 = 25.
        assertEquals(25.0, counter.packetsPerSecond(102_000L))
    }

    @Test
    fun `rate stays positive for partial-window readings`() {
        val counter = PacketRateCounter(windowSeconds = 30)
        repeat(40) { counter.record(100_000L) }
        // After only one closed second, the rate is the count of that single bucket
        // (averaging over 1 valid bucket — not over the full 30s window).
        val rate = counter.packetsPerSecond(101_000L)
        assertTrue(rate > 0.0, "expected positive rate, got $rate")
    }
}
