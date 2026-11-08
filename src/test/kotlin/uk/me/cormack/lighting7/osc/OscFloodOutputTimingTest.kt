package uk.me.cormack.lighting7.osc

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.midi.ActiveBankState
import uk.me.cormack.lighting7.midi.EncoderBankState
import uk.me.cormack.lighting7.midi.FlashStateTracker
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.perf.DeskMetrics
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.FadeTiming.assertLike
import uk.me.cormack.lighting7.testsupport.FadeTiming.fade
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * OSC is never on the show's path (OSC plan D8, P3): a fade's output is the same while an allowed
 * sender and a refused one each flood the listener, over UDP and over TCP, as with no OSC at all. Wall clock, through
 * [uk.me.cormack.lighting7.testsupport.FadeTiming], as `CloudOutageOutputTimingTest` does for the cloud.
 */
class OscFloodOutputTimingTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var state: State
    private lateinit var service: OscService

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        val projectId = seedMinimalProject(state)
        transaction(state.database) { DaoCueStack.new { name = "Act 1"; project = DaoProject[projectId] } }
        service = OscService(
            state.database,
            OscHandler(state.database, { projectId }, SurfaceDispatch(RecordingSurfaceActions(), ActiveBankState(), EncoderBankState(), FlashStateTracker())),
            DeskMetrics(),
        )
        service.start()
    }

    @After
    fun tearDown() {
        runCatching { service.close() }
        runCatching { state.shutdown() }
    }

    private val floods = ArrayList<Thread>()

    /** Run [fade] with [flood] started inside it, and the flood finished before anything reads what it did. */
    private fun fadeUnder(flood: () -> Thread) = fade { floods += flood() }.also {
        floods.forEach { it.join() }
        floods.clear()
    }

    /** Send [packet] to [port] as fast as the socket takes it for the length of one fade. */
    private fun flood(port: Int, packet: ByteArray): () -> Thread = {
        thread(name = "osc-flood") {
            DatagramSocket().use { socket ->
                val target = InetAddress.getByName("127.0.0.1")
                val until = System.nanoTime() + 2_000_000_000L
                while (System.nanoTime() < until) socket.send(DatagramPacket(packet, packet.size, target, port))
            }
        }
    }

    @Test
    fun `a fade runs as it does with no OSC while allowed and refused senders flood the listener`() {
        val baseline = fade()
        val port = freeOscPort()
        val go = OscPacket.bundle(*Array(OscPacket.MAX_MESSAGES) { OscPacket.encode("/lighting7/stack/act-1/go") })

        service.update(OscSettingsRequest(enabled = true, bindAddress = "127.0.0.1", port = port, senders = listOf(OscSenderRequest(name = "QLab", address = "127.0.0.1"))))
        assertLike(baseline, fadeUnder(flood(port, go)), "an allowed sender flooding")
        assertTrue(service.state.value.received > 0, "the flood reached the listener: ${service.state.value}")

        service.update(OscSettingsRequest(enabled = true, bindAddress = "127.0.0.1", port = port, senders = listOf(OscSenderRequest(name = "Other", address = "10.1.1.1"))))
        assertLike(baseline, fadeUnder(flood(port, go)), "a refused sender flooding")
        assertTrue(service.state.value.refused > 0, "the flood was refused: ${service.state.value}")
    }

    /** Write SLIP frames of [packet] down one connection after another for the length of one fade. */
    private fun tcpFlood(port: Int, packet: ByteArray): () -> Thread = {
        thread(name = "osc-tcp-flood") {
            val frame = OscSlip.encode(packet)
            val chunk = ByteArray(frame.size * 16).also { out -> repeat(16) { frame.copyInto(out, it * frame.size) } }
            val until = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < until) {
                // A refused connection is closed by the desk, so open the next one.
                runCatching {
                    Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                        val out = socket.getOutputStream()
                        while (System.nanoTime() < until) out.write(chunk)
                    }
                }
                Thread.sleep(1)
            }
        }
    }

    @Test
    fun `a fade runs as it does with no OSC while allowed and refused senders flood TCP`() {
        val baseline = fade()
        val port = freeOscPort()
        val go = OscPacket.bundle(*Array(OscPacket.MAX_MESSAGES) { OscPacket.encode("/lighting7/stack/act-1/go") })

        service.update(OscSettingsRequest(enabled = true, bindAddress = "127.0.0.1", port = port, senders = listOf(OscSenderRequest(name = "QLab", address = "127.0.0.1", replies = true))))
        assertLike(baseline, fadeUnder(tcpFlood(port, go)), "an allowed sender flooding TCP, with replies")
        assertTrue(service.state.value.received > 0, "the flood reached the listener: ${service.state.value}")

        service.update(OscSettingsRequest(enabled = true, bindAddress = "127.0.0.1", port = port, senders = listOf(OscSenderRequest(name = "Other", address = "10.1.1.1"))))
        assertLike(baseline, fadeUnder(tcpFlood(port, go)), "a refused sender flooding TCP")
        assertTrue(service.state.value.refused > 0, "the flood was refused: ${service.state.value}")
    }
}
