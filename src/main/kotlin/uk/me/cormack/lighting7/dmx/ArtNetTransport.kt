package uk.me.cormack.lighting7.dmx

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Transport seam for ArtNet packet I/O. Production is [DefaultArtNetTransport]; tests inject
 * a recording fake to assert what the controller transmits and in what order — in
 * particular, that the very first frame after construction overlays the [ParkSource].
 */
interface ArtNetTransport {
    fun start()
    fun stop()
    fun broadcastDmx(subnet: Int, universe: Int, dmxData: ByteArray)
    fun unicastDmx(address: String, subnet: Int, universe: Int, dmxData: ByteArray)
}

/**
 * Sends ArtDMX, written from Artistic Licence's Art-Net 4 specification. It only sends; nothing
 * reads the socket. `ArtDmxGoldenTest` pins every byte, including where they depart from the
 * specification:
 * - Sequence counts every packet this transport builds, from 1, and wraps from 255 to 0 rather
 *   than to 1.
 * - Physical is always 2.
 * - The port-address is `subnet shl 4 or universe`, unmasked. With subnet 0 that is the flat
 *   universe number, which is how universes from 16 up are addressed, so it must not be split
 *   into nibbles.
 * - Length is the data's, rounded up to even and zero-padded, and may be 0.
 *
 * A failed send or an address that does not resolve costs that frame, never the universe: it is
 * logged once until a send succeeds again, and not thrown.
 *
 * Each transport binds the wildcard address on [bindPort] with `SO_REUSEADDR`, so every
 * universe's transport shares 6454 with the others and with any reusing listener on the
 * machine. If the port cannot be bound, this one sends from an ephemeral port instead. [stop]
 * closes whichever it bound.
 */
internal class DefaultArtNetTransport(
    private val bindPort: Int = ART_NET_PORT,
    private val destinationPort: Int = ART_NET_PORT,
    private val broadcastAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)),
) : ArtNetTransport {
    companion object {
        const val ART_NET_PORT = 6454
        const val MAX_DMX_LENGTH = 512
        private const val HEADER_LENGTH = 18
        private const val OP_DMX = 0x5000
        private const val PROTOCOL_VERSION = 14
        private const val PHYSICAL = 2
        private val ID = "Art-Net\u0000".toByteArray(Charsets.US_ASCII)
        private val log = LoggerFactory.getLogger(DefaultArtNetTransport::class.java)
    }

    @Volatile
    private var socket: DatagramSocket? = null

    /** The port this transport sends from, once started. */
    internal val localPort: Int? get() = socket?.localPort

    private val packet = ByteArray(HEADER_LENGTH + MAX_DMX_LENGTH)
    private var sequence = 0
    private var failing = false

    override fun start() {
        socket = try {
            bound(bindPort)
        } catch (e: SocketException) {
            log.warn("Art-Net port {} is unavailable; sending from an ephemeral port", bindPort, e)
            try {
                bound(0)
            } catch (e: SocketException) {
                log.error("No socket for Art-Net output", e)
                null
            }
        }
    }

    override fun stop() {
        socket?.close()
    }

    override fun broadcastDmx(subnet: Int, universe: Int, dmxData: ByteArray) {
        send(null, subnet, universe, dmxData)
    }

    override fun unicastDmx(address: String, subnet: Int, universe: Int, dmxData: ByteArray) {
        send(address, subnet, universe, dmxData)
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

    /** [address] null is a broadcast. */
    private fun send(address: String?, subnet: Int, universe: Int, dmxData: ByteArray) {
        require(dmxData.size <= MAX_DMX_LENGTH) { "ArtDMX carries at most $MAX_DMX_LENGTH channels, not ${dmxData.size}" }
        val socket = checkNotNull(socket) { "transport has no socket" }
        val length = (dmxData.size + 1) and 1.inv()
        val portAddress = (subnet shl 4) or universe
        synchronized(packet) {
            try {
                val to = if (address == null) broadcastAddress else InetAddress.getByName(address)
                sequence = (sequence + 1) and 0xFF
                ID.copyInto(packet)
                packet[8] = OP_DMX.toByte()
                packet[9] = (OP_DMX shr 8).toByte()
                packet[10] = 0
                packet[11] = PROTOCOL_VERSION.toByte()
                packet[12] = sequence.toByte()
                packet[13] = PHYSICAL.toByte()
                packet[14] = portAddress.toByte()
                packet[15] = (portAddress shr 8).toByte()
                packet[16] = (length shr 8).toByte()
                packet[17] = length.toByte()
                dmxData.copyInto(packet, HEADER_LENGTH)
                packet.fill(0, HEADER_LENGTH + dmxData.size, HEADER_LENGTH + length)
                socket.send(DatagramPacket(packet, HEADER_LENGTH + length, to, destinationPort))
                if (failing) {
                    failing = false
                    log.info("Art-Net output to {} resumed", address ?: "broadcast")
                }
            } catch (e: IOException) {
                if (!failing) {
                    failing = true
                    log.warn("Art-Net output to {} failed; dropping frames until it recovers", address ?: "broadcast", e)
                }
            }
        }
    }
}
