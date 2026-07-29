package uk.me.cormack.lighting7.dmx

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free sliding-window counter for outbound packet rate.
 *
 * Maintains [windowSeconds] one-second buckets keyed by `epochSecond % windowSeconds`.
 * [packetsPerSecond] averages the buckets whose recorded second falls in the last
 * `windowSeconds` whole seconds (the in-progress second is excluded so partial
 * counts don't depress the rate).
 *
 * Each slot packs the bucket's second into the high 32 bits and its count into the low 32
 * (`(second shl 32) or count`). That packing is what makes the two things [record] has to do
 * — discard a stale bucket, then count this packet — a *single* compare-and-set. Holding the
 * second and the count in separate arrays cannot be made correct without a lock: one thread
 * would reset the count while another had already incremented it, silently dropping packets
 * from the rate while [total] still counted them. It also means a reader sees a consistent
 * (second, count) pair from one read rather than risking a new second paired with an old
 * count.
 */
class PacketRateCounter(val windowSeconds: Int = 30) {
    private val buckets = AtomicLongArray(windowSeconds)
    private val totalPackets = AtomicLong(0)

    val total: Long get() = totalPackets.get()

    fun record(nowMs: Long = System.currentTimeMillis()) {
        totalPackets.incrementAndGet()
        val nowSec = nowMs / 1000L
        val idx = (nowSec % windowSeconds).toInt()
        while (true) {
            val current = buckets.get(idx)
            // Same second: bump the count in the low half. Different second: the bucket is
            // stale (this slot last held `nowSec - windowSeconds * n`), so replace it wholesale
            // with a fresh count of 1. Either way `updated` is derived from the `current` we
            // just read, so a losing CAS simply retries against whichever write won and no
            // increment is lost.
            val updated = if (secondOf(current) == nowSec) current + 1 else pack(nowSec, 1)
            if (buckets.compareAndSet(idx, current, updated)) return
        }
    }

    fun packetsPerSecond(nowMs: Long = System.currentTimeMillis()): Double {
        val nowSec = nowMs / 1000L
        val from = nowSec - windowSeconds
        var sum = 0L
        var validBuckets = 0
        for (i in 0 until windowSeconds) {
            val packed = buckets.get(i)
            if (secondOf(packed) in from until nowSec) {
                sum += countOf(packed)
                validBuckets++
            }
        }
        return if (validBuckets > 0) sum.toDouble() / validBuckets else 0.0
    }

    private companion object {
        // `count` is bounded by the packets written into a single one-second bucket, so it
        // cannot approach 2^32 and carry into the second — ArtNet output runs at tens of
        // packets per second per universe.
        fun pack(second: Long, count: Long): Long = (second shl 32) or count

        fun secondOf(packed: Long): Long = packed ushr 32

        fun countOf(packed: Long): Long = packed and 0xFFFF_FFFFL
    }
}
