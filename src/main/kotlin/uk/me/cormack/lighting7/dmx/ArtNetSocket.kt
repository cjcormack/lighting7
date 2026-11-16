package uk.me.cormack.lighting7.dmx

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/** The header every Art-Net packet starts with: its ID and the protocol version the desk speaks. */
internal object ArtNetHeader {
    private val ID = "Art-Net\u0000".toByteArray(Charsets.US_ASCII)
    const val PROTOCOL_VERSION = 14

    fun writeId(into: ByteArray) = ID.copyInto(into)

    fun hasId(data: ByteArray, offset: Int): Boolean = ID.indices.all { data[offset + it] == ID[it] }
}

/**
 * The desk's one Art-Net socket (cloud-p3 plan D4): every universe sends through it and [ArtNetPoller]
 * reads it, since a controller's unicast ArtPollReply lands only on the socket bound to 6454.
 *
 * Bound to the wildcard address on [bindPort] with `SO_REUSEADDR`. If the port is taken it sends from
 * an ephemeral port and is not [listening] until [retryBind] wins the port back.
 */
class ArtNetSocket(val bindPort: Int = ART_NET_PORT) : AutoCloseable {
    companion object {
        const val ART_NET_PORT = 6454

        private val log = LoggerFactory.getLogger(ArtNetSocket::class.java)

        /** The process's socket on 6454, opened by the first transport that starts. */
        val shared: ArtNetSocket by lazy { ArtNetSocket() }
    }

    private val lock = Any()

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var closed = false

    /** Open on [bindPort] itself, where replies arrive; false before [open], on the fallback, or with no socket. */
    @Volatile
    var listening: Boolean = false
        private set

    /** The port it sends from, once open. */
    val localPort: Int? get() = socket?.localPort

    /** Binds on first call; null once closed or when no socket could be had at all. */
    fun open(): DatagramSocket? {
        if (!closed) socket?.let { return it }
        synchronized(lock) {
            if (closed) return null
            socket?.let { return it }
            val opened = try {
                bound(bindPort).also { listening = true }
            } catch (e: SocketException) {
                log.warn("Art-Net port {} is unavailable; sending from an ephemeral port", bindPort, e)
                try {
                    bound(0)
                } catch (e: SocketException) {
                    log.error("No socket for Art-Net output", e)
                    null
                }
            }
            socket = opened
            return opened
        }
    }

    /**
     * On the ephemeral fallback, tries [bindPort] again and, if it binds, sends and reads there from
     * now on. True once [listening].
     */
    fun retryBind(): Boolean {
        if (listening) return true
        synchronized(lock) {
            if (closed || listening) return listening
            val regained = try {
                bound(bindPort)
            } catch (_: SocketException) {
                return false
            }
            val fallback = socket
            socket = regained
            listening = true
            fallback?.close()
            log.info("Art-Net port {} is free again; sending and reading on it", bindPort)
            return true
        }
    }

    fun send(packet: DatagramPacket) {
        (open() ?: throw IOException("no Art-Net socket")).send(packet)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            listening = false
            socket?.close()
            socket = null
        }
    }

    private fun bound(port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(port))
        } catch (e: SocketException) {
            s.close()
            throw e
        }
        return s
    }
}
