package uk.me.cormack.lighting7.dmx

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free sliding-window counter for outbound packet rate.
 *
 * Maintains [windowSeconds] one-second buckets keyed by `epochSecond % windowSeconds`.
 * On [record], a stale bucket is CAS-reset before its count is incremented.
 * [packetsPerSecond] averages the buckets whose recorded second falls in the last
 * `windowSeconds` whole seconds (the in-progress second is excluded so partial
 * counts don't depress the rate).
 */
class PacketRateCounter(val windowSeconds: Int = 30) {
    private val buckets = AtomicLongArray(windowSeconds)
    private val bucketSeconds = AtomicLongArray(windowSeconds)
    private val totalPackets = AtomicLong(0)

    val total: Long get() = totalPackets.get()

    fun record(nowMs: Long = System.currentTimeMillis()) {
        totalPackets.incrementAndGet()
        val nowSec = nowMs / 1000L
        val idx = (nowSec % windowSeconds).toInt()
        val existingSec = bucketSeconds.get(idx)
        if (existingSec != nowSec) {
            if (bucketSeconds.compareAndSet(idx, existingSec, nowSec)) {
                buckets.set(idx, 0)
            }
        }
        buckets.incrementAndGet(idx)
    }

    fun packetsPerSecond(nowMs: Long = System.currentTimeMillis()): Double {
        val nowSec = nowMs / 1000L
        val from = nowSec - windowSeconds
        var sum = 0L
        var validBuckets = 0
        for (i in 0 until windowSeconds) {
            val sec = bucketSeconds.get(i)
            if (sec in from until nowSec) {
                sum += buckets.get(i)
                validBuckets++
            }
        }
        return if (validBuckets > 0) sum.toDouble() / validBuckets else 0.0
    }
}
