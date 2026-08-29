package uk.me.cormack.lighting7.fx

/**
 * Tap-tempo BPM estimation: averages the intervals between the last few taps, dropping any
 * that are older than [timeoutMs] so a stale tap sequence can't skew a fresh one.
 *
 * Extracted from [MasterClock], which owns one instance per clock — taps arrive from WS
 * handlers, REST routes, scripts and the AI tool on different dispatchers, so the timestamp
 * list is guarded by its own monitor rather than the clock's.
 */
class TapTempo(
    private val maxHistory: Int = 4,
    private val timeoutMs: Long = 2000L,
) {
    private val timestamps = mutableListOf<Long>()

    /**
     * Record a tap at [now] and return the estimated BPM from the recent tap history, or
     * null if there aren't yet enough taps to estimate from.
     */
    fun tap(now: Long = System.currentTimeMillis()): Double? = synchronized(timestamps) {
        timestamps.removeIf { now - it > timeoutMs }
        timestamps.add(now)
        while (timestamps.size > maxHistory) {
            timestamps.removeAt(0)
        }

        if (timestamps.size < 2) return@synchronized null

        val averageIntervalMs = timestamps.zipWithNext { a, b -> b - a }.average()
        if (averageIntervalMs > 0) 60_000.0 / averageIntervalMs else null
    }
}
