package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The rate cap on a socket of the public listener (cloud-p1 plan D14), where a remote session's
 * frames cross the internet. `channelState` is held to [hz] frames a second: each frame carries only
 * the channels that changed, so frames sent meanwhile are merged, each (universe, channel) at its
 * latest level, and a merged frame is a snapshot if any frame in it was. `provenanceState` is a whole
 * snapshot each time, so only its latest is kept, at half the rate. The first frame after a quiet
 * spell goes at once; the rest wait out the interval. Everything else the socket sends is untouched.
 *
 * LAN sockets have none ([forSocket]).
 */
internal class RemoteFrameCap(
    private val scope: CoroutineScope,
    hz: Int,
    private val send: suspend (OutMessage) -> Unit,
) {
    init {
        require(hz > 0) { "hz must be positive" }
    }

    private val channelIntervalMs = 1000L / hz
    private val provenanceIntervalMs = 2 * channelIntervalMs

    private val lock = Any()
    private val channels = LinkedHashMap<Long, ChannelState>()
    private var snapshot = false
    private var channelJob: Job? = null
    private var provenance: ProvenanceStateOutMessage? = null
    private var provenanceJob: Job? = null

    fun channelState(message: ChannelStateOutMessage) {
        synchronized(lock) {
            if (message.snapshot) snapshot = true
            message.channels.forEach { channels[key(it)] = it }
            if (channelJob?.isActive != true) channelJob = start { drainChannels() }
        }
    }

    fun provenance(message: ProvenanceStateOutMessage) {
        synchronized(lock) {
            provenance = message
            if (provenanceJob?.isActive != true) provenanceJob = start { drainProvenance() }
        }
    }

    /** Assigned before it runs, so a sender that starts it inline still finds it active. */
    private fun start(block: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.LAZY) { block() }.also { it.start() }

    private suspend fun drainChannels() {
        while (true) {
            val frame = synchronized(lock) {
                if (channels.isEmpty() && !snapshot) {
                    channelJob = null
                    return
                }
                ChannelStateOutMessage(channels.values.toList(), snapshot).also {
                    channels.clear()
                    snapshot = false
                }
            }
            send(frame)
            delay(channelIntervalMs)
        }
    }

    private suspend fun drainProvenance() {
        while (true) {
            val frame = synchronized(lock) {
                provenance.also { provenance = null } ?: run {
                    provenanceJob = null
                    return
                }
            }
            send(frame)
            delay(provenanceIntervalMs)
        }
    }

    private fun key(channel: ChannelState): Long = (channel.universe.toLong() shl 32) or (channel.id.toLong() and 0xffffffffL)

    companion object {
        /** The cap for a socket: none on the desk's own listener, or with [hz] 0. */
        fun forSocket(remote: Boolean, hz: Int, scope: CoroutineScope, send: suspend (OutMessage) -> Unit): RemoteFrameCap? =
            if (remote && hz > 0) RemoteFrameCap(scope, hz, send) else null
    }
}
