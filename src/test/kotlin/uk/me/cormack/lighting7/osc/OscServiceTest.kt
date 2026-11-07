package uk.me.cormack.lighting7.osc

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.midi.ActiveBankState
import uk.me.cormack.lighting7.midi.EncoderBankState
import uk.me.cormack.lighting7.midi.FlashStateTracker
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.perf.DeskMetrics
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RecordedCall
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OscService] end to end over loopback, with a [RecordingSurfaceActions] in place of the show
 * (OSC plan D5, D8): an allowlisted sender's message is dispatched under its own history source and
 * logged, a stranger's is refused unread and listed, a port in use is an error rather than a failed
 * start, and the channel counts what it drops.
 */
class OscServiceTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var state: State
    private var projectId = 0
    private val actions = RecordingSurfaceActions()
    private lateinit var service: OscService
    private val metrics = DeskMetrics()

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        projectId = seedMinimalProject(state)
        transaction(state.database) {
            DaoCueStack.new {
                name = "Act 1"
                project = DaoProject[projectId]
            }
        }
        val handler = OscHandler(
            database = state.database,
            currentProjectId = { projectId },
            dispatch = SurfaceDispatch(actions, ActiveBankState(), EncoderBankState(), FlashStateTracker()),
        )
        service = OscService(state.database, handler, metrics)
        service.start()
    }

    @After
    fun tearDown() {
        runCatching { service.close() }
        runCatching { state.shutdown() }
    }

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    private fun enable(senders: List<OscSenderRequest>, port: Int = freePort()): Int {
        service.update(OscSettingsRequest(enabled = true, bindAddress = "127.0.0.1", port = port, senders = senders))
        return port
    }

    private fun send(port: Int, packet: ByteArray) = DatagramSocket(null).use { socket ->
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"), port))
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

    @Test
    fun `off by default, and listening once an admin enables it`() {
        val initial = service.state.value
        assertEquals(false, initial.enabled)
        assertEquals(false, initial.listening)
        assertNull(service.boundPort())
        val port = enable(emptyList())
        await("listening") { it.listening && it.port == port }
        assertEquals(port, service.boundPort())
        service.update(OscSettingsRequest(enabled = false, bindAddress = "127.0.0.1", port = port))
        await("stopped") { !it.listening }
    }

    @Test
    fun `the stored settings are read before the service starts, and applied when it does`() {
        service.update(OscSettingsRequest(enabled = false, port = 9100, senders = listOf(OscSenderRequest(name = "QLab", address = "10.0.0.5"))))
        val handler = OscHandler(state.database, { projectId }, SurfaceDispatch(actions, ActiveBankState(), EncoderBankState(), FlashStateTracker()))
        val notStarted = OscService(state.database, handler, DeskMetrics())
        try {
            assertEquals(listOf("QLab"), notStarted.settings().senders.map { it.name })
            assertEquals(9100, notStarted.settings().port)
        } finally {
            notStarted.close()
        }
    }

    @Test
    fun `an allowlisted sender's message is dispatched as that sender and logged`() {
        val port = enable(listOf(OscSenderRequest(name = "QLab", address = "127.0.0.1")))
        await("listening") { it.listening }
        send(port, OscPacket.encode("/lighting7/stack/ACT-1/go"))

        val logged = await("a log row") { it.recent.isNotEmpty() }
        val row = logged.recent.single()
        assertEquals("QLab", row.sender)
        assertEquals("/lighting7/stack/ACT-1/go", row.address)
        assertEquals(OscOutcome.DONE, row.outcome, row.detail)
        assertEquals("GO Act 1", row.detail)
        assertEquals(1, logged.received)

        val stack = transaction(state.database) { DaoCueStack.all().single().let { it.id.value to it.uuid.toString() } }
        assertEquals(listOf<RecordedCall>(RecordedCall.CueStackGo(stack.first, stack.second)), actions.calls.toList())
        val source = actions.answerOrigins.single()?.source
        assertNotNull(source)
        assertEquals(HistorySource.Kind.OSC, source.kind)
        assertEquals("QLab", source.name)
        assertEquals(setOf("/lighting7/stack/act-1/go"), service.resolvedAddresses())
        assertEquals(mapOf(DeskMetrics.OscResult.RECEIVED to 1L), metrics.drainOsc())
    }

    @Test
    fun `a bundle's messages run in order, and a bad one is its own row`() {
        val port = enable(listOf(OscSenderRequest(name = "QLab", address = "127.0.0.1")))
        await("listening") { it.listening }
        val broken = "/x".toByteArray() + byteArrayOf(0, 0) + ",q".toByteArray() + byteArrayOf(0, 0)
        send(port, OscPacket.bundle(OscPacket.encode("/lighting7/stack/act-1/go"), broken, OscPacket.encode("/lighting7/stack/act-2/go")))

        val rows = await("three rows") { it.recent.size == 3 }.recent.reversed()
        assertEquals(listOf(OscOutcome.DONE, OscOutcome.BAD_MESSAGE, OscOutcome.NOT_FOUND), rows.map { it.outcome })
        assertEquals("No stack `act-2` in this show", rows[2].detail)
    }

    @Test
    fun `a stranger's packet is refused unread, listed once by address, and allowing it clears the row`() {
        val port = enable(listOf(OscSenderRequest(name = "Sound desk", address = "10.9.9.9")))
        await("listening") { it.listening }
        repeat(3) { send(port, OscPacket.encode("/lighting7/stack/act-1/go")) }

        val refused = await("three refusals") { it.refused == 3L }
        assertEquals(listOf("127.0.0.1"), refused.refusedSenders.map { it.address })
        assertEquals(3, refused.refusedSenders.single().count)
        assertTrue(refused.recent.isEmpty(), "a refused packet is never a log row")
        assertEquals(0, refused.received)
        assertTrue(actions.calls.isEmpty())
        assertEquals(mapOf(DeskMetrics.OscResult.REFUSED to 3L), metrics.drainOsc())

        val settings = service.settings()
        service.update(
            OscSettingsRequest(
                enabled = true, bindAddress = "127.0.0.1", port = port,
                senders = settings.senders.map { OscSenderRequest(it.id, it.name, it.address) } + OscSenderRequest(name = "QLab", address = "127.0.0.1"),
            ),
        )
        assertTrue(await("the row cleared") { it.refusedSenders.isEmpty() }.listening)
        assertEquals(listOf("Sound desk", "QLab"), service.settings().senders.map { it.name })
    }

    @Test
    fun `a port already in use is an error in the state, never a failed start`() {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use { taken ->
            enable(emptyList(), port = taken.localPort)
            val state = await("the error") { it.error != null }
            assertEquals(false, state.listening)
            assertTrue(state.error!!.contains("${taken.localPort}"), state.error)
        }
    }

    @Test
    fun `the settings are refused naming every problem at once`() {
        val problems = service.problems(
            OscSettingsRequest(
                enabled = true, bindAddress = "desk.local", port = 80,
                senders = listOf(
                    OscSenderRequest(name = "", address = "192.168.1.10"),
                    OscSenderRequest(name = "B", address = "192.168.1.10"),
                    OscSenderRequest(name = "C", address = "999.1.1.1"),
                ),
            ),
        )
        assertEquals(5, problems.size, problems.toString())
        assertTrue(service.problems(OscSettingsRequest(enabled = true, bindAddress = "", port = 8415)).isEmpty())
        assertNotNull(OscService.ipLiteral("::1"))
        assertNull(OscService.ipLiteral("localhost"))
    }

    /** D8's drop count rests on this: a `DROP_OLDEST` channel hands every element it drops to `onUndeliveredElement`. */
    @Test
    fun `a full DROP_OLDEST channel reports each element it drops`() = runBlocking {
        val dropped = AtomicInteger()
        val dropOldest = Channel<Int>(4, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }
        repeat(10) { assertTrue(dropOldest.trySend(it).isSuccess) }
        assertEquals(6, dropped.get())
        assertEquals(listOf(6, 7, 8, 9), List(4) { dropOldest.receive() })
    }
}
