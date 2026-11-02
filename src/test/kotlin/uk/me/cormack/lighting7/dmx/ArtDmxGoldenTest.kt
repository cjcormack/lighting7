package uk.me.cormack.lighting7.dmx

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * [DefaultArtNetTransport] sends exactly the datagrams artnet4j 0.6.2 sent for the same calls,
 * captured once on a loopback receiver before artnet4j left the build. The captures, the calls
 * that made them and how they were made are in `src/test/resources/dmx/artdmx/`.
 *
 * Where the capture departs from the Art-Net 4 specification it is kept, because the rig's nodes
 * accept what artnet4j sent: Sequence wraps from 255 to 0 (the specification reserves 0 for "not
 * sequenced" and wraps to 1), Physical is always 2, the port-address is `subnet shl 4 or universe`
 * unmasked (with subnet 0, the flat universe number), and an empty frame goes out with Length 0
 * (the specification's minimum is 2). One departure from artnet4j is deliberate: it failed a frame
 * over 512 channels with an index error after spending a sequence number; this transport refuses
 * one before.
 */
class ArtDmxGoldenTest {
    @Serializable
    private data class Case(val name: String, val send: String, val subnet: Int, val universe: Int, val length: Int, val seed: Int)

    @Serializable
    private data class Cases(val cases: List<Case>)

    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest
    fun close() = opened.forEach { runCatching { it.close() } }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/dmx/artdmx/$name")) { "missing dmx/artdmx/$name" }.readText()

    private fun hex(text: String): ByteArray =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun receiver(): DatagramSocket =
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also {
            it.soTimeout = 2000
            opened += it
        }

    private fun receive(rx: DatagramSocket): ByteArray {
        val p = DatagramPacket(ByteArray(2048), 2048)
        rx.receive(p)
        return p.data.copyOf(p.length)
    }

    private fun transport(rx: DatagramSocket, bindPort: Int = 0): DefaultArtNetTransport =
        DefaultArtNetTransport(
            bindPort = bindPort,
            destinationPort = rx.localPort,
            broadcastAddress = InetAddress.getLoopbackAddress(),
        ).also {
            it.start()
            opened += AutoCloseable { it.stop() }
        }

    private fun sourcePort(rx: DatagramSocket): Int = DatagramPacket(ByteArray(64), 64).also { rx.receive(it) }.port

    @Test
    fun `every captured frame is sent byte for byte, in order`() {
        val cases = Json.decodeFromString<Cases>(resource("cases.json")).cases
        val rx = receiver()
        val transport = transport(rx)
        for (case in cases) {
            val data = ByteArray(case.length) { ((case.seed + it * 37) and 0xFF).toByte() }
            when (case.send) {
                "broadcast" -> transport.broadcastDmx(case.subnet, case.universe, data)
                "unicast" -> transport.unicastDmx("127.0.0.1", case.subnet, case.universe, data)
                else -> error("unknown send ${case.send}")
            }
            assertContentEquals(hex(resource("${case.name}.hex")), receive(rx), case.name)
        }
    }

    @Test
    fun `Sequence counts from 1 and wraps through 0, as captured`() {
        val expected = resource("sequence.txt").split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }
        val rx = receiver()
        val transport = transport(rx)
        val actual = expected.map {
            transport.broadcastDmx(0, 0, ByteArray(512))
            receive(rx)[12].toInt() and 0xFF
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `a frame over 512 channels is refused without spending a sequence number`() {
        val rx = receiver()
        val transport = transport(rx)
        assertFailsWith<IllegalArgumentException> { transport.broadcastDmx(0, 0, ByteArray(513)) }
        transport.broadcastDmx(0, 0, ByteArray(2))
        assertEquals(1, receive(rx)[12].toInt())
    }

    @Test
    fun `a send that fails, or an address that does not resolve, costs the frame and throws nothing`() {
        val rx = receiver()
        val transport = transport(rx)
        transport.unicastDmx("[", 0, 0, ByteArray(2))
        transport.broadcastDmx(0, 0, ByteArray(2))
        assertEquals(1, receive(rx)[12].toInt())
        transport.stop()
        transport.broadcastDmx(0, 0, ByteArray(2))
    }

    @Test
    fun `transports share their port, and send from an ephemeral one when it is held without reuse`() {
        val rx = receiver()
        val first = transport(rx)
        val port = checkNotNull(first.localPort)
        val second = transport(rx, bindPort = port)
        first.unicastDmx("127.0.0.1", 0, 0, ByteArray(2))
        assertEquals(port, sourcePort(rx))
        second.unicastDmx("127.0.0.1", 0, 0, ByteArray(2))
        assertEquals(port, sourcePort(rx))

        val holder = DatagramSocket(InetSocketAddress(0)).also { opened += it }
        assertFalse(holder.reuseAddress)
        val fallback = transport(rx, bindPort = holder.localPort)
        fallback.unicastDmx("127.0.0.1", 0, 0, ByteArray(2))
        assertNotEquals(holder.localPort, sourcePort(rx))
    }

    @Test
    fun `stop releases the port`() {
        val rx = receiver()
        val transport = transport(rx)
        val port = checkNotNull(transport.localPort)
        transport.stop()
        DatagramSocket(InetSocketAddress(port)).close()
    }
}
