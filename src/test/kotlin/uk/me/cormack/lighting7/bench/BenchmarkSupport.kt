package uk.me.cormack.lighting7.bench

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/**
 * Shared helpers for opt-in benchmark harnesses (`FxEngineBenchmark`,
 * `BenchmarkSetValues`). Both report p50/p99/mean tick durations + per-sample
 * allocation bytes via `println` to stdout — these helpers are the format
 * authority so output stays consistent across harnesses.
 */

data class BenchmarkStats(
    val p50Ns: Long,
    val p99Ns: Long,
    val meanNs: Long,
    val allocBytesPerSample: Long,
)

/**
 * Sort [timings], print a summary line, and return the stats. [sampleName] controls
 * the unit label — pluralised in the count column (`${sampleName}s=N`) and used as-is
 * in the alloc column (`allocBytes/$sampleName=W`). Pass `"tick"` for FX-engine
 * harnesses and `"iter"` for transaction-style harnesses.
 *
 * [allocBytes] is the total allocated-bytes delta for the measured window
 * (typically from [allocatedBytes]); pass `-1L` to suppress the per-sample
 * allocation column.
 */
fun summarize(
    label: String,
    timings: LongArray,
    allocBytes: Long,
    sampleName: String = "iter",
): BenchmarkStats {
    val sorted = timings.copyOf().also { it.sort() }
    val p50 = sorted[sorted.size / 2]
    val p99 = sorted[((sorted.size * 99) / 100).coerceAtMost(sorted.size - 1)]
    val mean = sorted.sum() / sorted.size
    val perSample = if (allocBytes >= 0) allocBytes / sorted.size else -1L
    println(
        "[$label] ${sampleName}s=${sorted.size} p50=${p50 / 1_000}µs p99=${p99 / 1_000}µs " +
            "mean=${mean / 1_000}µs allocBytes/$sampleName=$perSample",
    )
    return BenchmarkStats(p50, p99, mean, perSample)
}

/**
 * Current thread's allocated-bytes counter from `ThreadMXBean`. Returns `-1L`
 * if the JVM doesn't support it. Call before / after a measured window and
 * subtract for the delta.
 */
fun allocatedBytes(): Long {
    val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return -1L
    if (!bean.isThreadAllocatedMemorySupported) return -1L
    if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
    @Suppress("DEPRECATION")
    val tid = Thread.currentThread().id
    return bean.getThreadAllocatedBytes(tid)
}
