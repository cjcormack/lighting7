package uk.me.cormack.lighting7.perf

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free log2-bucketed latency histogram.
 *
 * Each bucket `i` covers `[2^i, 2^(i+1)) ns`. Records up to ~2^[bucketCount]-1 ns
 * (default 32 → ~4.29 s upper bound); observations larger than that pile into the
 * top bucket but [maxNanos] still tracks the actual peak.
 *
 * `record` is a single AtomicLong increment per call plus a CAS on max — safe to
 * call from any thread on the hot path. Percentile reads walk all buckets but
 * are O(bucketCount) and hold no locks.
 */
class LatencyHistogram(val bucketCount: Int = 32) {
    init {
        require(bucketCount in 1..63) { "bucketCount must be 1..63, was $bucketCount" }
    }

    private val buckets = AtomicLongArray(bucketCount)
    private val countAtomic = AtomicLong(0)
    private val sumNanosAtomic = AtomicLong(0)
    private val maxNanosAtomic = AtomicLong(0)

    val count: Long get() = countAtomic.get()
    val sumNanos: Long get() = sumNanosAtomic.get()
    val maxNanos: Long get() = maxNanosAtomic.get()

    /** Record a non-negative observation. Negatives are silently dropped. */
    fun record(nanos: Long) {
        if (nanos < 0) return
        val idx = bucketIndex(nanos)
        buckets.incrementAndGet(idx)
        countAtomic.incrementAndGet()
        sumNanosAtomic.addAndGet(nanos)
        var prev = maxNanosAtomic.get()
        while (nanos > prev && !maxNanosAtomic.compareAndSet(prev, nanos)) {
            prev = maxNanosAtomic.get()
        }
    }

    fun reset() {
        for (i in 0 until bucketCount) buckets.set(i, 0)
        countAtomic.set(0)
        sumNanosAtomic.set(0)
        maxNanosAtomic.set(0)
    }

    /**
     * Approximate percentile by cumulative bucket walk. Returns the right boundary of
     * the bucket containing the target observation, capped by the recorded max so the
     * top bucket never reports an inflated value. Concurrent [record] / [reset] calls
     * can shift bucket counts mid-walk; the result remains a valid bucket boundary but
     * may not reflect the exact `count` snapshot used to compute the target index.
     */
    fun percentileNanos(percentile: Double): Long {
        require(percentile in 0.0..100.0) { "percentile must be 0..100" }
        val total = countAtomic.get()
        if (total == 0L) return 0
        val target = ((total * percentile) / 100.0).toLong().coerceAtLeast(1)
        var cumulative = 0L
        for (i in 0 until bucketCount) {
            cumulative += buckets.get(i)
            if (cumulative >= target) {
                val rightBound = bucketUpperBound(i)
                return minOf(rightBound, maxNanosAtomic.get())
            }
        }
        return maxNanosAtomic.get()
    }

    fun snapshot(): LatencyHistogramSnapshot {
        val totalCount = countAtomic.get()
        val sum = sumNanosAtomic.get()
        val max = maxNanosAtomic.get()
        val bucketSnapshot = (0 until bucketCount).map { i ->
            BucketCount(
                upperBoundNanos = bucketUpperBound(i),
                count = buckets.get(i),
            )
        }
        return LatencyHistogramSnapshot(
            count = totalCount,
            sumNanos = sum,
            maxNanos = max,
            meanNanos = if (totalCount > 0) sum / totalCount else 0,
            p50Nanos = percentileNanos(50.0),
            p95Nanos = percentileNanos(95.0),
            p99Nanos = percentileNanos(99.0),
            buckets = bucketSnapshot,
        )
    }

    private fun bucketIndex(nanos: Long): Int {
        if (nanos <= 1L) return 0
        val raw = 63 - java.lang.Long.numberOfLeadingZeros(nanos)
        return if (raw >= bucketCount) bucketCount - 1 else raw
    }

    private fun bucketUpperBound(i: Int): Long = if (i >= 62) Long.MAX_VALUE else (1L shl (i + 1))
}

@Serializable
data class BucketCount(
    val upperBoundNanos: Long,
    val count: Long,
)

@Serializable
data class LatencyHistogramSnapshot(
    val count: Long,
    val sumNanos: Long,
    val maxNanos: Long,
    val meanNanos: Long,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val buckets: List<BucketCount>,
)
