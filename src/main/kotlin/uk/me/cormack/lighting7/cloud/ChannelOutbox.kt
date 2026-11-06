package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import uk.me.cormack.lighting7.cloud.protocol.MAX_FRAME_BYTES
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What one connection of the control channel sends (cloud-p1 plan D16), in two queues. Heartbeats go
 * through a small one that drops the oldest, so a cloud that stops reading costs a stale heartbeat;
 * everything else (`hello`, `capabilities`, replies) through one in which nothing newer displaces
 * anything older, bounded by frames and bytes, whose overflow refuses that one message. Neither ever
 * blocks the sender. [run] writes the second queue first.
 *
 * A frame that cannot be written within [sendTimeout] ends the connection: three heartbeats, or the
 * time the frame takes at [SLOWEST_UPLINK_BYTES_PER_S] where that is longer, so a large reply on a
 * slow uplink is not cut off.
 */
internal class ChannelOutbox(
    private val heartbeat: Duration,
    private val maxReliableFrames: Int = MAX_RELIABLE_FRAMES,
    private val maxReliableBytes: Long = MAX_RELIABLE_BYTES,
    private val write: suspend (String) -> Unit,
) {
    private val health = Channel<String>(HEALTH_CAPACITY, BufferOverflow.DROP_OLDEST)
    private val reliable = Channel<Pair<String, Int>>(Channel.UNLIMITED)
    private val lock = Any()
    private var pendingFrames = 0
    private var pendingBytes = 0L

    /** How [run] stopped. */
    enum class Stop { STALLED, FAILED }

    fun offerHealth(text: String) {
        health.trySend(text)
    }

    /** Queue [text]; false, sending nothing, when it is over a frame or the queue is full. */
    fun offerReliable(text: String): Boolean {
        val size = utf8Size(text)
        if (size > MAX_FRAME_BYTES) return false
        synchronized(lock) {
            if (pendingFrames + 1 > maxReliableFrames || pendingBytes + size > maxReliableBytes) return false
            pendingFrames++
            pendingBytes += size
        }
        return reliable.trySend(text to size).isSuccess
    }

    /** Write until a frame stalls or a write fails; [onEach] runs before each write. */
    suspend fun run(onEach: () -> Unit = {}): Stop {
        while (true) {
            val (text, size, isReliable) = select {
                reliable.onReceive { (text, size) -> Triple(text, size, true) }
                health.onReceive { Triple(it, utf8Size(it), false) }
            }
            onEach()
            val sent = try {
                withTimeoutOrNull(sendTimeout(size)) { write(text) } != null
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return Stop.FAILED
            } catch (e: Exception) {
                return Stop.FAILED
            } finally {
                if (isReliable) {
                    synchronized(lock) {
                        pendingFrames--
                        pendingBytes -= size
                    }
                }
            }
            if (!sent) return Stop.STALLED
        }
    }

    fun sendTimeout(bytes: Int): Duration =
        maxOf(heartbeat * STALL_HEARTBEATS, (bytes * 1000L / SLOWEST_UPLINK_BYTES_PER_S).milliseconds)

    companion object {
        const val HEALTH_CAPACITY = 16
        const val STALL_HEARTBEATS = 3
        const val MAX_RELIABLE_FRAMES = 256
        const val MAX_RELIABLE_BYTES = 2L * MAX_FRAME_BYTES

        /** The slowest uplink a frame's send timeout allows for, 128 KiB/s. */
        const val SLOWEST_UPLINK_BYTES_PER_S = 128L * 1024

        /** [text]'s length in UTF-8, without encoding it. */
        fun utf8Size(text: String): Int {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return bytes
        }
    }
}
