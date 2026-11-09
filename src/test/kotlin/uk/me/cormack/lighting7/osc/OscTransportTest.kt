package uk.me.cormack.lighting7.osc

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.midi.ActiveBankState
import uk.me.cormack.lighting7.midi.EncoderBankState
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.perf.DeskMetrics
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The transports over loopback: `/lighting7/reply` back to a sender that asks for it, over
 * UDP to the port it sent from and over TCP on its own connection, and nothing to one that does not;
 * OSC 1.1's SLIP-framed TCP on the UDP port, from allowed senders only.
 */
class OscTransportTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var state: State
    private lateinit var service: OscService
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        val projectId = seedMinimalProject(state)
        transaction(state.database) { DaoCueStack.new { name = "Act 1"; project = DaoProject[projectId] } }
        val dispatch = SurfaceDispatch(RecordingSurfaceActions(), ActiveBankState(), EncoderBankState())
        service = OscService(state.database, OscHandler(state.database, { projectId }, dispatch), DeskMetrics())
        service.start()
    }

    @After
    fun tearDown() {
        runCatching { service.close() }
        runCatching { state.shutdown() }
    }

    private fun enable(replies: Boolean, address: String = "127.0.0.1"): Int {
        val port = freeOscPort()
        service.update(
            OscSettingsRequest(
                enabled = true, bindAddress = "127.0.0.1", port = port,
                senders = listOf(OscSenderRequest(name = "QLab", address = address, replies = replies)),
            ),
        )
        await("listening on both") { it.listening && it.tcpListening }
        return port
    }

    private fun await(what: String, predicate: (OscStateDto) -> Boolean): OscStateDto = runBlocking {
        try {
            withTimeout(5_000) {
                while (!predicate(service.state.value)) delay(20)
                service.state.value
            }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state is ${service.state.value}", e)
        }
    }

    private fun udpClient() = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 3_000 }

    private fun DatagramSocket.sendTo(port: Int, packet: ByteArray) = send(DatagramPacket(packet, packet.size, loopback, port))

    private fun DatagramSocket.receiveMessage(): Pair<OscMessage, Int> {
        val buffer = ByteArray(2_048)
        val packet = DatagramPacket(buffer, buffer.size)
        receive(packet)
        val item = OscPacket.parse(buffer, packet.length).single() as OscPacket.Item.Message
        return item.message to packet.port
    }

    private fun reply(address: String, outcome: String, detail: String) =
        OscMessage(OscReplies.ADDRESS, listOf(OscArg.Str(address), OscArg.Str(outcome), OscArg.Str(detail)))

    /** Every SLIP frame read from [socket] until [count] have arrived. */
    private fun readFrames(socket: Socket, count: Int): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        val decoder = OscSlip.Decoder(65_507)
        val buffer = ByteArray(2_048)
        val input = socket.getInputStream()
        while (frames.size < count) {
            val read = input.read(buffer)
            if (read < 0) break
            decoder.feed(buffer, read, { frames += it }) {}
        }
        return frames
    }

    @Test
    fun `a sender with replies on is answered over UDP, to the port it sent from, from the listening port`() {
        val port = enable(replies = true)
        udpClient().use { client ->
            client.sendTo(port, OscPacket.encode("/lighting7/stack/act-1/go"))
            client.sendTo(port, OscPacket.encode("/lighting7/stack/act-9/go"))
            val (first, from) = client.receiveMessage()
            assertEquals(reply("/lighting7/stack/act-1/go", "done", "GO Act 1"), first)
            assertEquals(port, from)
            assertEquals(reply("/lighting7/stack/act-9/go", "not_found", "No stack `act-9` in this show"), client.receiveMessage().first)
        }
    }

    @Test
    fun `a sender with replies off is sent nothing, and nor is a reply`() {
        val port = enable(replies = false)
        udpClient().use { client ->
            client.soTimeout = 500
            client.sendTo(port, OscPacket.encode("/lighting7/stack/act-1/go"))
            await("the row") { it.recent.size == 1 }
            assertFailsWith<SocketTimeoutException> { client.receiveMessage() }
        }

        service.update(service.settings().let { s ->
            OscSettingsRequest(s.enabled, s.bindAddress, s.port, s.senders.map { OscSenderRequest(it.id, it.name, it.address, replies = true) })
        })
        await("listening") { it.listening }
        udpClient().use { client ->
            client.soTimeout = 500
            // Another desk's reply, which must never be answered, or two desks would answer each other forever.
            client.sendTo(port, OscPacket.encode(reply("/lighting7/stack/act-1/go", "done", "GO Act 1")))
            assertEquals(OscOutcome.BAD_MESSAGE, await("the reply's row") { it.recent.size == 2 }.recent.first().outcome)
            assertFailsWith<SocketTimeoutException> { client.receiveMessage() }
        }
    }

    @Test
    fun `OSC over TCP is SLIP-framed on the same port, and replies come back on the connection`() {
        val port = enable(replies = true)
        assertEquals(port, service.boundTcpPort())
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            val framed = OscSlip.encode(OscPacket.encode("/lighting7/stack/act-1/go"))
            // A frame split across writes is one message.
            out.write(framed, 0, 5); out.flush()
            Thread.sleep(50)
            out.write(framed, 5, framed.size - 5)
            out.write(OscSlip.encode(OscPacket.encode("/lighting7/blackout", OscArg.Str("on"))))
            out.flush()

            val rows = await("two rows") { it.recent.size == 2 }.recent.reversed()
            assertEquals(listOf(OscOutcome.DONE, OscOutcome.BAD_MESSAGE), rows.map { it.outcome })
            assertEquals("QLab", rows[0].sender)
            val replies = readFrames(socket, 2).map { (OscPacket.parse(it).single() as OscPacket.Item.Message).message }
            assertEquals(reply("/lighting7/stack/act-1/go", "done", "GO Act 1"), replies[0])
            assertEquals("bad_message", (replies[1].args[1] as OscArg.Str).value)
        }
    }

    @Test
    fun `a TCP frame longer than a datagram is a bad message, and the connection reads on`() {
        val port = enable(replies = false)
        Socket(loopback, port).use { socket ->
            val out = socket.getOutputStream()
            out.write(OscSlip.encode(ByteArray(OscTcpListener.MAX_FRAME + 1) { 1 }))
            out.write(OscSlip.encode(OscPacket.encode("/lighting7/stack/act-1/go")))
            out.flush()
            val rows = await("two rows") { it.recent.size == 2 }.recent.reversed()
            assertEquals(listOf(OscOutcome.BAD_MESSAGE, OscOutcome.DONE), rows.map { it.outcome })
            assertTrue(rows[0].detail.contains("longer than"), rows[0].detail)
        }
    }

    @Test
    fun `a TCP connection from a stranger is closed unread and listed as refused`() {
        val port = enable(replies = true, address = "10.9.9.9")
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply { write(OscSlip.encode(OscPacket.encode("/lighting7/stack/act-1/go"))); flush() }
            assertClosedByDesk(socket, "the desk closes the connection")
        }
        val refused = await("the refusal") { it.refused == 1L }
        assertEquals(listOf("127.0.0.1"), refused.refusedSenders.map { it.address })
        assertEquals(0, refused.received)
        assertTrue(refused.recent.isEmpty())
    }

    @Test
    fun `a sender taken off the list while connected is disconnected`() {
        val port = enable(replies = false)
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().apply { write(OscSlip.encode(OscPacket.encode("/lighting7/stack/act-1/go"))); flush() }
            await("the row") { it.recent.size == 1 }
            service.update(OscSettingsRequest(enabled = true, bindAddress = "127.0.0.1", port = port, senders = emptyList()))
            assertClosedByDesk(socket, "the desk closes the connection")
        }
    }

    private fun Socket.sendFrame(packet: ByteArray) = getOutputStream().apply { write(OscSlip.encode(packet)); flush() }

    @Test
    fun `a connection past the limit closes the one idle longest, never the newcomer`() {
        val port = enable(replies = false)
        val open = List(OscTcpListener.MAX_CONNECTIONS) { Socket(loopback, port).apply { soTimeout = 3_000 } }
        try {
            // Each sends in turn, so the first is idle longest once all eight are held.
            for ((i, socket) in open.withIndex()) {
                socket.sendFrame(OscPacket.encode("/lighting7/stack/act-1/go"))
                await("row ${i + 1}") { it.recent.size == i + 1 }
            }
            Socket(loopback, port).use { newcomer ->
                newcomer.sendFrame(OscPacket.encode("/lighting7/stack/act-1/go"))
                await("the newcomer's row") { it.recent.size == OscTcpListener.MAX_CONNECTIONS + 1 }
                assertClosedByDesk(open.first(), "the connection idle longest is closed")
            }
            open[1].sendFrame(OscPacket.encode("/lighting7/stack/act-1/go"))
            await("the second still reads") { it.recent.size == OscTcpListener.MAX_CONNECTIONS + 2 }
        } finally {
            open.forEach { it.close() }
        }
    }

    @Test
    fun `a client that half-closes after sending still gets its reply`() {
        val port = enable(replies = true)
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 3_000
            socket.sendFrame(OscPacket.encode("/lighting7/stack/act-1/go"))
            socket.shutdownOutput()
            val reply = readFrames(socket, 1).map { (OscPacket.parse(it).single() as OscPacket.Item.Message).message }
            assertEquals(listOf(reply("/lighting7/stack/act-1/go", "done", "GO Act 1")), reply)
        }
    }

    @Test
    fun `an address outside the desk's, or an unreadable packet, is logged but not answered`() {
        val port = enable(replies = true)
        udpClient().use { client ->
            client.soTimeout = 500
            // A peer acknowledging in its own namespace must not start an exchange with no end.
            client.sendTo(port, OscPacket.encode("/reply/lighting7/reply"))
            client.sendTo(port, byteArrayOf(1, 2, 3))
            val rows = await("two rows") { it.recent.size == 2 }.recent
            assertEquals(listOf(OscOutcome.BAD_MESSAGE, OscOutcome.BAD_MESSAGE), rows.map { it.outcome })
            assertFailsWith<SocketTimeoutException> { client.receiveMessage() }
        }
    }

    @Test
    fun `a save that moves neither transport keeps a live TCP connection while UDP cannot bind`() {
        val port = freeOscPort()
        DatagramSocket(InetSocketAddress(loopback, port)).use { _ ->
            service.update(
                OscSettingsRequest(
                    enabled = true, bindAddress = "127.0.0.1", port = port,
                    senders = listOf(OscSenderRequest(name = "QLab", address = "127.0.0.1")),
                ),
            )
            val listening = await("TCP alone") { it.tcpListening && it.error != null }
            assertEquals(false, listening.listening)
            assertTrue(listening.error!!.contains("UDP"), listening.error)
            Socket(loopback, port).use { socket ->
                socket.sendFrame(OscPacket.encode("/lighting7/stack/act-1/go"))
                await("the first row") { it.recent.size == 1 }
                service.update(service.settings().let { s ->
                    OscSettingsRequest(s.enabled, s.bindAddress, s.port, s.senders.map { OscSenderRequest(it.id, it.name, it.address, replies = true) })
                })
                socket.sendFrame(OscPacket.encode("/lighting7/stack/act-1/go"))
                await("the second row, on the same connection") { it.recent.size == 2 }
            }
        }
    }
}
