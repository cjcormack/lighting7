package uk.me.cormack.lighting7.osc

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * OSC over TCP: OSC 1.1's SLIP framing on the UDP listener's port, held to D8 as [OscReceiver] is.
 * `osc-tcp` accepts; a connection from an address not on the allowlist is closed unread and reported
 * to [onRefused]. Each connection reads on its own thread, joins frames split across reads, parses
 * them and hands each message to [deliver] — never the database or the show. Its sender is looked up
 * again for every frame, so a sender taken off the list while connected is closed at its next frame,
 * and a reply switch moved takes effect at once. A sender with replies on is answered on its own
 * connection, from a writer thread of its own.
 *
 * At most [MAX_CONNECTIONS] are open: a new one past that closes the connection idle longest, so a
 * peer that vanished without closing (a machine powered off, a cable pulled) never locks a reconnect
 * out. Keepalive closes such a connection eventually too.
 */
internal class OscTcpListener(
    val bindAddress: InetAddress?,
    val port: Int,
    private val senders: () -> Map<String, OscSender>,
    private val deliver: (OscInbound) -> Unit,
    private val onRefused: (address: String) -> Unit,
) : AutoCloseable {
    @Volatile
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val nextConnection = AtomicInteger(1)

    /** Binds and starts accepting. Throws when it cannot bind. */
    fun start() {
        val s = ServerSocket()
        try {
            s.bind(oscSocketAddress(bindAddress, port))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        server = s
        thread = Thread({ accept(s) }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    val boundPort: Int get() = server?.localPort ?: -1

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                logger.warn("OSC TCP accept failed: {}", e.toString())
                continue
            }
            val source = canonicalIp(socket.inetAddress)
            if (senders()[source] == null) {
                runCatching { socket.close() }
                onRefused(source)
                continue
            }
            if (connections.size >= MAX_CONNECTIONS) {
                connections.minByOrNull { it.lastActive }?.let { idle ->
                    logger.debug("OSC TCP: closing the connection from {} idle longest, for one from {}", idle.source, source)
                    idle.close()
                }
            }
            val connection = try {
                socket.keepAlive = true
                // Replies are small frames sent one at a time, which Nagle would hold for the peer's ACK.
                socket.tcpNoDelay = true
                Connection(socket, source, nextConnection.getAndIncrement())
            } catch (_: Exception) {
                runCatching { socket.close() }
                continue
            }
            connections += connection
            connection.start()
        }
    }

    /** Close every connection whose sender is no longer on the allowlist. */
    fun closeUnlisted() {
        val allowed = senders()
        for (connection in connections) if (allowed[connection.source] == null) connection.close()
    }

    override fun close() {
        server?.close()
        thread?.join(1_000)
        for (connection in connections) connection.close()
    }

    private inner class Connection(private val socket: Socket, val source: String, number: Int) {
        /** When this connection last read anything, in nanos. */
        @Volatile
        var lastActive: Long = System.nanoTime()

        private val replies = OscReplies<ByteArray>("$REPLY_THREAD_PREFIX$number") { packet ->
            socket.getOutputStream().apply {
                write(OscSlip.encode(packet))
                flush()
            }
        }
        private val replyTo = OscReplyTo { replies.offer(it) }
        private val reader = Thread({ read() }, "$READER_THREAD_PREFIX$number").apply { isDaemon = true }

        fun start() = reader.start()

        private fun read() {
            try {
                val input: InputStream = socket.getInputStream()
                val decoder = OscSlip.Decoder(MAX_FRAME)
                val buffer = ByteArray(8_192)
                while (!socket.isClosed) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        lingerForReplies()
                        break
                    }
                    lastActive = System.nanoTime()
                    decoder.feed(buffer, read, ::frame) { frame(null) }
                }
            } catch (_: SocketException) {
                // Closed under the read: a stop, a restart, the peer gone or its sender removed.
            } catch (e: Exception) {
                logger.warn("OSC TCP read from {} failed: {}", source, e.toString())
            } finally {
                close()
            }
        }

        /**
         * The peer has finished sending but may still be reading: a client that writes, shuts its
         * output and waits for the answer. Keep the connection open long enough for the replies to
         * what it sent to be written; one the collector has not reached by then is not.
         */
        private fun lingerForReplies() {
            if (senders()[source]?.replies != true) return
            try {
                Thread.sleep(REPLY_LINGER_MS)
            } catch (_: InterruptedException) {
                // Closed meanwhile.
            }
        }

        /** One frame, or null for one longer than [MAX_FRAME]. */
        private fun frame(bytes: ByteArray?) {
            val sender = senders()[source]
            if (sender == null) {
                close()
                return
            }
            val at = Instant.now()
            val reply = if (sender.replies) replyTo else null
            val items = if (bytes == null) listOf(OscPacket.Item.Bad(null, "frame longer than $MAX_FRAME bytes")) else OscPacket.parse(bytes)
            for (item in items) deliver(OscInbound(at, sender, item, reply))
        }

        fun close() {
            if (!connections.remove(this)) return
            replies.close()
            runCatching { socket.close() }
            if (Thread.currentThread() !== reader) reader.interrupt()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OscTcpListener::class.java)
        const val THREAD_NAME = "osc-tcp"
        const val READER_THREAD_PREFIX = "osc-tcp-"
        const val REPLY_THREAD_PREFIX = "osc-tcp-reply-"

        /** Connections open at once; a QLab workspace needs one per patch. */
        const val MAX_CONNECTIONS = 8

        /** As long as the largest UDP datagram, so a message fits either way or neither. */
        const val MAX_FRAME = 65_507

        /** How long a connection whose peer has stopped sending stays open for its replies. */
        const val REPLY_LINGER_MS = 2_000L
    }
}
