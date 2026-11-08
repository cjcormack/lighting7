package uk.me.cormack.lighting7.osc

import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.time.Instant

/**
 * An allowlisted sender, by its row id, its name and its canonical IP literal, and whether it is
 * answered with a `/lighting7/reply`.
 */
data class OscSender(val id: Int, val name: String, val address: String, val replies: Boolean = false)

/**
 * One item off the wire for the collector: who sent it, when, and what [OscPacket] made of it;
 * [replyTo] where its reply goes, null when its sender takes none.
 */
data class OscInbound(
    val at: Instant,
    val sender: OscSender,
    val item: OscPacket.Item,
    val replyTo: OscReplyTo? = null,
)

/**
 * The UDP socket and its reading thread, `osc-1` (OSC plan D8). The thread reads a datagram, drops
 * it uncounted-against-the-log if its source is not an allowlisted [OscSender] ([onRefused]), parses
 * the rest and hands each message to [deliver] — and nothing more: it never touches the database or
 * the show, so a slow show never backs up the socket and a flood never reaches the show.
 *
 * [deliver] must not block; the service's is a `trySend` into a `DROP_OLDEST` channel. A sender
 * with replies on is answered from this socket, so to the port it sent from, by `osc-reply`.
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
    private var replies: OscReplies<DatagramPacket>? = null

    /** Opens the socket and starts reading. Throws when it cannot bind — the port in use among the causes. */
    fun start() {
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = false
            s.bind(oscSocketAddress(bindAddress, port))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        socket = s
        replies = OscReplies(REPLY_THREAD_NAME) { s.send(it) }
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
                val source = canonicalIp(packet.address)
                val sender = senders()[source]
                if (sender == null) {
                    onRefused(source)
                    continue
                }
                val at = Instant.now()
                val replyTo = if (sender.replies) replyTo(packet.address, packet.port) else null
                for (item in OscPacket.parse(buffer, packet.length)) deliver(OscInbound(at, sender, item, replyTo))
            } catch (_: SocketException) {
                // The socket was closed under the receive: a stop or a restart.
            } catch (e: Exception) {
                logger.warn("OSC receive failed: {}", e.toString())
            }
        }
    }

    private fun replyTo(address: InetAddress, port: Int): OscReplyTo {
        val queue = replies ?: return OscReplyTo {}
        return OscReplyTo { packet -> queue.offer(DatagramPacket(packet, packet.size, address, port)) }
    }

    override fun close() {
        replies?.close()
        socket?.close()
        thread?.join(1_000)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OscReceiver::class.java)
        const val THREAD_NAME = "osc-1"
        const val REPLY_THREAD_NAME = "osc-reply"

        /** The largest UDP payload over IPv4. */
        private const val MAX_DATAGRAM = 65_507
    }
}

/** [bind] and [port] as a socket address; a null [bind] is every interface. */
internal fun oscSocketAddress(bind: InetAddress?, port: Int): InetSocketAddress =
    if (bind == null) InetSocketAddress(port) else InetSocketAddress(bind, port)

/** [address] as the allowlist spells it: without a link-local `%scope`, which its literals never carry. */
internal fun canonicalIp(address: InetAddress): String = InetAddress.getByAddress(address.address).hostAddress
