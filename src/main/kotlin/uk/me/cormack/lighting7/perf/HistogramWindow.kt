package uk.me.cormack.lighting7.perf

/**
 * What a [LatencyHistogram] recorded since the previous [take], read by diffing its buckets — never
 * by resetting it, so the operator's own reads of the same histogram (`/api/rest/perf`) are
 * untouched. Percentiles interpolate within a log2 bucket; the max is exact.
 */
class HistogramWindow(private val histogram: LatencyHistogram) {
    data class Summary(val count: Long, val p50Nanos: Long, val p95Nanos: Long, val maxNanos: Long)

    private val previous = LongArray(histogram.bucketCount)
    private val delta = LongArray(histogram.bucketCount)
    private var resets = histogram.resets

    /** The window since the last call, or null if nothing was recorded in it. */
    fun take(): Summary? {
        var total = 0L
        var top = -1
        val generation = histogram.resets
        val reset = generation != resets
        resets = generation
        for (i in previous.indices) {
            val current = histogram.bucketCount(i)
            // After a reset, everything in it is new.
            val d = if (!reset && current >= previous[i]) current - previous[i] else current
            previous[i] = current
            delta[i] = d
            total += d
            if (d > 0) top = i
        }
        val windowMax = histogram.takeWindowMaxNanos()
        if (total == 0L) return null
        val max = maxOf(windowMax, histogram.bucketLowerBound(top))
        return Summary(total, percentile(total, 0.50).coerceAtMost(max), percentile(total, 0.95).coerceAtMost(max), max)
    }

    private fun percentile(total: Long, p: Double): Long {
        val rank = Math.ceil(total * p).toLong().coerceAtLeast(1)
        var before = 0L
        for (i in delta.indices) {
            val n = delta[i]
            if (n == 0L) continue
            if (before + n >= rank) {
                val lo = histogram.bucketLowerBound(i)
                val hi = histogram.bucketUpperBound(i)
                return lo + ((hi - lo).toDouble() * (rank - before) / n).toLong()
            }
            before += n
        }
        return 0
    }
}
