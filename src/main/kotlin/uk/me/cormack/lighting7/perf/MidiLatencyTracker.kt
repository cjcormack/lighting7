package uk.me.cormack.lighting7.perf

import kotlinx.serialization.Serializable

/**
 * Named stages of the MIDI surface hot path. [wireName] is the camelCase JSON key used in
 * `GET /api/rest/perf/midi-latency` responses; the alphabetic sort there is by [wireName].
 */
enum class MidiLatencyStage(val wireName: String) {
    EGRESS_LED("egressLed"),
    EGRESS_MOTOR("egressMotor"),
    INGRESS_BUTTON("ingressButton"),
    INGRESS_CONTINUOUS("ingressContinuous"),
}

/**
 * Per-process registry of [LatencyHistogram] buckets covering the MIDI surface hot path.
 * Unlike [CueEditLatencyTracker] there's no per-session boundary — MIDI traffic is continuous —
 * so the buckets accumulate until [reset] is called explicitly (operator-driven via
 * `POST /api/rest/perf/midi-latency/reset`).
 *
 * Buckets are pre-allocated at construction so the hot path is one array access + one
 * `LatencyHistogram.record` (a single AtomicLong increment chain). [measure] is inline so the
 * caller's lambda doesn't allocate.
 */
class MidiLatencyTracker {
    @PublishedApi
    internal val histograms: Array<LatencyHistogram> =
        Array(MidiLatencyStage.entries.size) { LatencyHistogram() }

    fun bucket(stage: MidiLatencyStage): LatencyHistogram = histograms[stage.ordinal]

    /** Wrap [block] and record its wall-clock duration into [stage]. Records on exception too. */
    inline fun <T> measure(stage: MidiLatencyStage, block: () -> T): T {
        val h = histograms[stage.ordinal]
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            h.record(System.nanoTime() - start)
        }
    }

    fun record(stage: MidiLatencyStage, nanos: Long) {
        histograms[stage.ordinal].record(nanos)
    }

    fun reset() {
        for (h in histograms) h.reset()
    }

    fun snapshot(): MidiLatencySnapshot = MidiLatencySnapshot(
        buckets = MidiLatencyStage.entries
            .sortedBy { it.wireName }
            .associate { it.wireName to histograms[it.ordinal].snapshot() },
    )
}

@Serializable
data class MidiLatencySnapshot(
    val buckets: Map<String, LatencyHistogramSnapshot>,
)
