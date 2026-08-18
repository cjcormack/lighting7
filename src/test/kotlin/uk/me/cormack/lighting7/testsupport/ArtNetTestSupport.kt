package uk.me.cormack.lighting7.testsupport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import uk.me.cormack.lighting7.dmx.ArtNetTransport
import uk.me.cormack.lighting7.dmx.ParkSource
import java.util.concurrent.ConcurrentHashMap

/** A single Art-Net frame captured by [RecordingTransport], with its sending thread. */
sealed class Frame {
    abstract val data: ByteArray
    abstract val threadName: String

    data class Broadcast(
        val subnet: Int,
        val universe: Int,
        override val data: ByteArray,
        override val threadName: String,
    ) : Frame()

    data class Unicast(
        val address: String,
        val subnet: Int,
        val universe: Int,
        override val data: ByteArray,
        override val threadName: String,
    ) : Frame()
}

/**
 * Test transport that records every frame in send order. `Channel.UNLIMITED` is FIFO and
 * never blocks senders, so a test's `frames.receive()` returns frames in the exact order
 * the controller emitted them — that's how we assert "no earlier frame was recorded"
 * without polling.
 *
 * [stopped] completes when the controller releases the transport. Awaiting it gives tests
 * a happens-before edge against teardown, so "a closed controller emits nothing further"
 * can be asserted without racing the shutdown.
 */
class RecordingTransport : ArtNetTransport {
    val frames = Channel<Frame>(Channel.UNLIMITED)
    val stopped = CompletableDeferred<Unit>()

    override fun start() {}

    override fun stop() {
        stopped.complete(Unit)
    }

    override fun broadcastDmx(subnet: Int, universe: Int, dmxData: ByteArray) {
        frames.trySend(
            Frame.Broadcast(subnet, universe, dmxData.copyOf(), Thread.currentThread().name),
        )
    }

    override fun unicastDmx(address: String, subnet: Int, universe: Int, dmxData: ByteArray) {
        frames.trySend(
            Frame.Unicast(address, subnet, universe, dmxData.copyOf(), Thread.currentThread().name),
        )
    }
}

/** In-memory [ParkSource] whose park map tests can mutate between assertions. */
class MutableParkSource : ParkSource {
    private val parkedByUniverse = ConcurrentHashMap<Int, ConcurrentHashMap<Int, UByte>>()

    fun park(universe: Int, channel: Int, value: UByte) {
        parkedByUniverse.getOrPut(universe) { ConcurrentHashMap() }[channel] = value
    }

    override fun getParkedValue(universe: Int, channel: Int): UByte? =
        parkedByUniverse[universe]?.get(channel)

    override fun isParked(universe: Int, channel: Int): Boolean =
        parkedByUniverse[universe]?.containsKey(channel) == true

    override fun universeView(universe: Int): Map<Int, UByte>? = parkedByUniverse[universe]
}
