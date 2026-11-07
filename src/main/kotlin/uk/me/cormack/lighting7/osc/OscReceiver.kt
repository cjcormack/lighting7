package uk.me.cormack.lighting7.osc

import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.time.Instant

/** An allowlisted sender, by its row id, its name and its canonical IP literal. */
data class OscSender(val id: Int, val name: String, val address: String)

/** One item off the wire for the collector: who sent it, when, and what [OscPacket] made of it. */
data class OscInbound(val at: Instant, val sender: OscSender, val item: OscPacket.Item)

/**
 * The UDP socket and its reading thread, `osc-1` (OSC plan D8). The thread reads a datagram, drops
 * it uncounted-against-the-log if its source is not an allowlisted [OscSender] ([onRefused]), parses
 * the rest and hands each message to [deliver] — and nothing more: it never touches the database or
 * the show, so a slow show never backs up the socket and a flood never reaches the show.
 *
 * [deliver] must not block; the service's is a `trySend` into a `DROP_OLDEST` channel.
 */
internal class OscReceiver(
    val bindAddress: InetAddress?,
    val port: Int,
    private val senders: () -> Map<String, OscSender>,
    private val deliver: (OscInbound) -> Unit,
    private val onRefused: (address: String) -> Unit,
) : AutoCloseable {
    @Volatile
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    /** Opens the socket and starts reading. Throws when it cannot bind — the port in use among the causes. */
    fun start() {
        val address = if (bindAddress == null) InetSocketAddress(port) else InetSocketAddress(bindAddress, port)
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = false
            s.bind(address)
        } catch (e: Exception) {
            s.close()
            throw e
        }
        socket = s
        thread = Thread({ run(s) }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /** The port actually bound: [port], or the one the system chose for a test's port 0. */
    val boundPort: Int get() = socket?.localPort ?: -1

    private fun run(s: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!s.isClosed) {
            try {
                packet.setLength(buffer.size)
                s.receive(packet)
                // Without a link-local address's `%scope`, which the allowlist's literals never carry.
                val source = InetAddress.getByAddress(packet.address.address).hostAddress
                val sender = senders()[source]
                if (sender == null) {
                    onRefused(source)
                    continue
                }
                val at = Instant.now()
                for (item in OscPacket.parse(buffer, packet.length)) deliver(OscInbound(at, sender, item))
            } catch (_: SocketException) {
                // The socket was closed under the receive: a stop or a restart.
            } catch (e: Exception) {
                logger.warn("OSC receive failed: {}", e.toString())
            }
        }
    }

    override fun close() {
        socket?.close()
        thread?.join(1_000)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OscReceiver::class.java)
        const val THREAD_NAME = "osc-1"

        /** The largest UDP payload over IPv4. */
        private const val MAX_DATAGRAM = 65_507
    }
}
