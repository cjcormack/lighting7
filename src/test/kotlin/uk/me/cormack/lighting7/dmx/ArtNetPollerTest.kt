package uk.me.cormack.lighting7.dmx

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import uk.me.cormack.lighting7.testsupport.FakeArtNetNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Controller reachability by ArtPoll (cloud-p3 plan D4), over loopback: a fake node answering on
 * 127.0.0.1, the universes' transports sending through the same socket the poller reads, and the
 * poller's decisions on a test clock while its receive loop runs in real time.
 */
class ArtNetPollerTest {
    private val opened = mutableListOf<AutoCloseable>()
    private val clock = AtomicLong(1_000_000)

    @AfterTest
    fun close() = opened.asReversed().forEach { runCatching { it.close() } }

    private fun reply(): ByteArray = FakeArtNetNode.REPLY.copyOf()

    private fun node() = FakeArtNetNode().also { opened += it }

    private fun socket(port: Int = 0) = ArtNetSocket(port).also { opened += it }

    /** Collects the frames the universes send, so their sockets are really sending throughout. */
    private fun sink(): DatagramSocket =
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { opened += it }

    private fun universes(socket: ArtNetSocket, sink: DatagramSocket, count: Int = 2) = (0 until count).map { u ->
        ArtNetController(
            Universe(0, u),
            "127.0.0.1",
            transport = DefaultArtNetTransport(socket, sink.localPort, InetAddress.getLoopbackAddress()),
        ).also { opened += AutoCloseable { it.close() } }
    }

    private fun poller(socket: ArtNetSocket, node: FakeArtNetNode?, addresses: () -> List<String?>) =
        ArtNetPoller(
            socket = socket,
            addresses = addresses,
            destinationPort = node?.port ?: 9,
            nowMs = clock::get,
            wallMs = clock::get,
            receiveTimeoutMs = 20,
        ).also { opened += it }

    private fun ArtNetPoller.await(what: String, condition: (ArtNetReachability) -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition(state.value)) return
            Thread.sleep(5)
        }
        fail("$what; last ${state.value}")
    }

    private fun ArtNetPoller.stateOf(address: String) = state.value.of(address).state

    /** Real time for the loop to take a few turns, for a test asserting that nothing changed. */
    private fun settle() = Thread.sleep(200)

    @Test
    fun `a node answering is reachable, silent for 30 s unreachable, and reachable again once it answers`() {
        val node = node()
        val socket = socket()
        val sink = sink()
        universes(socket, sink)
        val poller = poller(socket, node) { listOf("127.0.0.1", "127.0.0.1") }
        poller.start()

        poller.await("reachable after its first reply") { it.of("127.0.0.1").state == Reachability.REACHABLE }
        assertTrue(poller.state.value.polling)
        assertEquals(clock.get(), poller.state.value.of("127.0.0.1").lastReplyAtMs)
        val repliedAt = clock.get()

        node.answering = false
        clock.addAndGet(10_001)
        poller.await("polled again") { node.polls.get() >= 2 }
        settle()
        assertEquals(Reachability.REACHABLE, poller.stateOf("127.0.0.1"), "within 30 s of its reply")

        clock.addAndGet(20_000)
        poller.await("unreachable 30 s after its last reply") { it.of("127.0.0.1").state == Reachability.UNREACHABLE }
        assertEquals(repliedAt, poller.state.value.of("127.0.0.1").lastReplyAtMs)

        node.answering = true
        clock.addAndGet(10_001)
        poller.await("reachable once it answers again") { it.of("127.0.0.1").state == Reachability.REACHABLE }
        assertEquals(0x50, sink.receive()[9].toInt(), "the universes still send ArtDMX through the shared socket")
    }

    @Test
    fun `a broadcast address, or none, is unknown whatever answers`() {
        val node = node()
        val socket = socket()
        val poller = poller(socket, node) { listOf("127.0.0.1", "255.255.255.255", null) }
        poller.start()
        poller.await("the node reachable") { it.of("127.0.0.1").state == Reachability.REACHABLE }
        clock.addAndGet(40_000)
        poller.await("the node polled again") { node.polls.get() >= 2 }
        settle()
        assertEquals(Reachability.UNKNOWN, poller.stateOf("255.255.255.255"))
        assertEquals(Reachability.UNKNOWN, poller.state.value.of(null).state)
    }

    @Test
    fun `every controller is unknown until a reply arrives, and only then can one be unreachable`() {
        val node = node()
        node.answering = false
        val socket = socket()
        val addresses = CopyOnWriteArrayList(listOf<String?>("127.0.0.2"))
        val poller = poller(socket, node) { addresses }
        poller.start()
        poller.await("polled once") { "127.0.0.2" in it.controllers }
        clock.addAndGet(31_000)
        settle()
        assertEquals(Reachability.UNKNOWN, poller.stateOf("127.0.0.2"), "no reply has ever reached the poller")

        node.answering = true
        addresses += "127.0.0.1"
        poller.await("the new controller polled at once and reachable") { it.of("127.0.0.1").state == Reachability.REACHABLE }
        assertEquals(Reachability.UNREACHABLE, poller.stateOf("127.0.0.2"), "polled 31 s ago, and replies now reach the poller")

        addresses.remove("127.0.0.2")
        poller.await("dropped with its universe") { "127.0.0.2" !in it.controllers }
        addresses += "127.0.0.2"
        poller.await("back, and polled afresh") { "127.0.0.2" in it.controllers }
        settle()
        assertEquals(Reachability.UNKNOWN, poller.stateOf("127.0.0.2"), "inside its first 30 s again")
    }

    @Test
    fun `6454 held without reuse leaves every controller unknown until it is freed, then the poller reads it`() {
        val holder = DatagramSocket(InetSocketAddress(0)).also { opened += it }
        val port = holder.localPort
        val socket = socket(port)
        val node = node()
        val poller = poller(socket, node) { listOf("127.0.0.1") }
        poller.start()
        settle()
        assertFalse(socket.listening)
        assertFalse(poller.state.value.polling)
        assertEquals(Reachability.UNKNOWN, poller.stateOf("127.0.0.1"))
        assertEquals(0, node.polls.get())
        val fallbackPort = socket.localPort
        assertTrue(fallbackPort != null && fallbackPort != port, "it still sends, from an ephemeral port")

        clock.addAndGet(ArtNetPoller.POLL_INTERVAL_MS)
        settle()
        assertFalse(socket.listening, "still held")

        holder.close()
        clock.addAndGet(ArtNetPoller.POLL_INTERVAL_MS)
        poller.await("reachable once 6454 is free") { it.polling && it.of("127.0.0.1").state == Reachability.REACHABLE }
        assertTrue(socket.listening)
        assertEquals(port, socket.localPort)
    }

    @Test
    fun `a closed socket hands out no socket and a poller restarted forgets what it knew`() {
        val node = node()
        val socket = socket()
        val poller = poller(socket, node) { listOf("127.0.0.1") }
        poller.start()
        poller.await("reachable") { it.of("127.0.0.1").state == Reachability.REACHABLE }
        poller.close()
        node.answering = false
        poller.start()
        poller.await("polled afresh") { "127.0.0.1" in it.controllers }
        clock.addAndGet(31_000)
        settle()
        assertEquals(Reachability.UNKNOWN, poller.stateOf("127.0.0.1"), "no reply since this start")
        poller.close()

        socket.close()
        assertNull(socket.open())
        assertNull(socket.localPort)
    }

    @Test
    fun `close stops the thread and leaves the shared socket open`() {
        val node = node()
        val socket = socket()
        val poller = poller(socket, node) { listOf("127.0.0.1") }
        poller.start()
        poller.await("reachable") { it.of("127.0.0.1").state == Reachability.REACHABLE }
        poller.close()
        assertFalse(poller.state.value.polling)
        assertFalse(Thread.getAllStackTraces().keys.any { it.name == ArtNetPoller.THREAD_NAME && it.isAlive })
        assertTrue(socket.open()?.isClosed == false)
    }

    @Test
    fun `the tracker's rule on a test clock`() {
        val tracker = ReachabilityTracker(30_000)
        val a = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val b = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        tracker.polled("a", 0)
        tracker.polled("b", 0)
        assertEquals(Reachability.UNKNOWN, tracker.of("a", a, 40_000).state, "no reply from anything yet")
        tracker.replied(listOf(a), 40_000)
        assertEquals(ControllerReachability(Reachability.REACHABLE, 40_000), tracker.of("a", a, 70_000))
        assertEquals(ControllerReachability(Reachability.UNREACHABLE, 40_000), tracker.of("a", a, 70_001))
        assertEquals(Reachability.UNREACHABLE, tracker.of("b", b, 40_000).state)
        tracker.polled("c", 50_000)
        assertEquals(Reachability.UNKNOWN, tracker.of("c", b, 79_999).state, "inside its own first window")
        assertEquals(Reachability.UNREACHABLE, tracker.of("c", b, 80_000).state)
        assertEquals(Reachability.UNKNOWN, tracker.of("d", null, 80_000).state, "a broadcast or unresolved address")
    }

    @Test
    fun `ArtPoll is the 14 bytes every node accepts, and only an ArtPollReply counts as one`() {
        assertContentEquals(
            "Art-Net\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x20, 0x00, 0x0e, 0x00, 0x00),
            ArtPoll.POLL,
        )
        val from = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))
        val reply = reply()
        val parsed = ArtPoll.replyFrom(DatagramPacket(reply, reply.size, from, 6454))
        assertEquals(setOf(from, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))), parsed)

        val dmx = reply.copyOf().also { it[8] = 0x00; it[9] = 0x50 }
        assertNull(ArtPoll.replyFrom(DatagramPacket(dmx, dmx.size, from, 6454)), "the desk's own ArtDMX")
        assertNull(ArtPoll.replyFrom(DatagramPacket(ArtPoll.POLL, ArtPoll.POLL.size, from, 6454)), "another desk's ArtPoll")
        val garbage = ByteArray(20)
        assertNull(ArtPoll.replyFrom(DatagramPacket(garbage, garbage.size, from, 6454)))

        val broadcasts = setOf(InetAddress.getByAddress(byteArrayOf(2, (-1).toByte(), (-1).toByte(), (-1).toByte())))
        assertTrue(ArtPoll.isBroadcast(InetAddress.getByName("255.255.255.255"), broadcasts))
        assertTrue(ArtPoll.isBroadcast(InetAddress.getByName("2.255.255.255"), broadcasts))
        assertFalse(ArtPoll.isBroadcast(from, broadcasts))
    }

    private fun DatagramSocket.receive(): ByteArray {
        soTimeout = 2000
        val p = DatagramPacket(ByteArray(600), 600)
        receive(p)
        return p.data.copyOf(p.length)
    }
}
