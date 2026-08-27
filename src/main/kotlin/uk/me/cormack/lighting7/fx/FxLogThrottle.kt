package uk.me.cormack.lighting7.fx

import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-cause log throttle for the FX engine's hot paths, shared between [FxEngine]'s tick
 * loops and [CascadePublisher]'s publish paths so one fault keeps one suppression history
 * wherever it is reported from.
 *
 * Entries are never swept, so every key must be drawn from something the *rig* bounds — a
 * fixture key, a group name, an effect type — and never from an effect id: ids come from a
 * monotonic counter, so a broken effect re-spawned on every GO would leave a new permanent
 * entry behind each time. Keying on the stable identity also throttles the fault properly
 * across those re-spawns, which is what an operator watching the same effect fail out of
 * every cue actually wants.
 */
internal class FxLogThrottle(private val logger: Logger) {
    private class Throttle {
        /** `System.nanoTime()` of the last emitted line; 0 until the first one. */
        val lastLogNanos = AtomicLong(0L)
        val suppressed = AtomicLong(0L)
    }

    private val throttles = ConcurrentHashMap<String, Throttle>()

    /**
     * Log a fault at most once per [LOG_THROTTLE_NANOS] per [key].
     *
     * The beat pass runs at up to 120 Hz and the wall-clock pass at 50 Hz, so a fault that
     * recurs every tick — a script effect that throws on every `calculate`, an effect
     * pointing at a fixture that has gone away — would otherwise write thousands of lines a
     * minute and bury everything else. Suppressed repeats are counted and reported on the
     * next line that does get through, so the log still says "this is happening constantly"
     * rather than looking like an isolated blip.
     *
     * [message] is only evaluated when the line is actually emitted. Faults whose real report
     * comes from somewhere else pass [debug] and cost nothing at all when debug is off.
     */
    fun log(
        key: String,
        error: Throwable? = null,
        debug: Boolean = false,
        message: () -> String,
    ) {
        if (debug && !logger.isDebugEnabled) return
        val throttle = throttles.getOrPut(key) { Throttle() }
        // nanoTime, not currentTimeMillis: this is an elapsed-time comparison, and an NTP
        // correction that steps the wall clock backwards would otherwise make every later
        // `now - last` negative and silence this key until real time caught up again.
        val now = System.nanoTime()
        val last = throttle.lastLogNanos.get()
        // The CAS also resolves the two tick loops racing on one key: the loser suppresses.
        if ((last != 0L && now - last < LOG_THROTTLE_NANOS) ||
            !throttle.lastLogNanos.compareAndSet(last, now)
        ) {
            throttle.suppressed.incrementAndGet()
            return
        }
        val suppressed = throttle.suppressed.getAndSet(0L)
        // "since the last report" rather than "in the last 10s": the gap is only the throttle
        // window when the fault is continuous. A burst that stopped an hour ago and recurred
        // once would otherwise be reported as if it were still constant.
        val text = if (suppressed > 0L) "${message()} (+$suppressed since the last report)" else message()
        when {
            debug -> logger.debug(text)
            error != null -> logger.warn(text, error)
            else -> logger.warn(text)
        }
    }

    companion object {
        /** How often one recurring fault may write a log line. */
        const val LOG_THROTTLE_NANOS = 10_000_000_000L
    }
}
