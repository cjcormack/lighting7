package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes the latest value submitted for each key, off the caller's thread, at most once per
 * [intervalMs] per pass.
 *
 * For state that is written through at gesture rate: a grand-master fader dragged from a browser
 * moves its level at frame rate, and SQLite has one writer behind a one-connection pool, so a row
 * write per frame would queue ahead of every route. A value superseded before its pass is never
 * written; a failed write is kept and tried again on the next pass.
 */
class LatestWinsWriter<K : Any, V : Any>(
    scope: CoroutineScope,
    private val intervalMs: Long = 250,
    private val write: (K, V) -> Unit,
) {
    private val pending = ConcurrentHashMap<K, V>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in wake) {
                flush()
                delay(intervalMs)
            }
        }
    }

    fun submit(key: K, value: V) {
        pending[key] = value
        wake.trySend(Unit)
    }

    /** Write everything pending now, on the calling thread. */
    @Synchronized
    fun flush() {
        for (key in pending.keys.toList()) {
            val value = pending.remove(key) ?: continue
            try {
                write(key, value)
            } catch (e: Exception) {
                logger.warn("Deferred write for {} failed; retrying on the next pass", key, e)
                // Unless a newer value has been submitted meanwhile, which supersedes this one.
                if (pending.putIfAbsent(key, value) == null) wake.trySend(Unit)
            }
        }
    }

    /** Write everything pending, then end the writer's coroutine; a later [submit] is never written. */
    fun close() {
        wake.close()
        flush()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(LatestWinsWriter::class.java)
    }
}
