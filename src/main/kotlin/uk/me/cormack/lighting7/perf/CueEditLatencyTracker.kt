package uk.me.cormack.lighting7.perf

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Latency tracker for the cue-edit set-property hot path. One tracker per
 * [State][uk.me.cormack.lighting7.state.State]; multi-session interleaving lands all
 * observations in the same live histogram, with `lastSessionEnded` capturing the most recent
 * end-of-session boundary so the snapshot survives a session close.
 *
 * Not transactional: [onBeginEdit] / [reset][LatencyHistogram.reset] races with concurrent
 * [measure] calls can leave count and bucket sums transiently inconsistent. Acceptable for
 * post-session inspection — sessions begin and end on operator action, not on the hot path.
 */
class CueEditLatencyTracker {
    private val live = LatencyHistogram()
    private val activeRef = AtomicBoolean(false)
    private val lastSessionRef = AtomicReference<LatencyHistogramSnapshot?>(null)

    val sessionActive: Boolean get() = activeRef.get()

    fun onBeginEdit() {
        live.reset()
        activeRef.set(true)
    }

    fun onEndEdit() {
        lastSessionRef.set(live.snapshot())
        activeRef.set(false)
    }

    /**
     * Wrap a [block] and record its wall-clock duration into [live]. Exceptions still record
     * — a failed transaction is part of what the operator wants to see.
     */
    fun <T> measure(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            live.record(System.nanoTime() - start)
        }
    }

    fun snapshot(): CueEditHistogramSnapshot = CueEditHistogramSnapshot(
        sessionActive = activeRef.get(),
        live = live.snapshot(),
        lastSessionEnded = lastSessionRef.get(),
    )
}

@Serializable
data class CueEditHistogramSnapshot(
    val sessionActive: Boolean,
    val live: LatencyHistogramSnapshot,
    val lastSessionEnded: LatencyHistogramSnapshot?,
)
