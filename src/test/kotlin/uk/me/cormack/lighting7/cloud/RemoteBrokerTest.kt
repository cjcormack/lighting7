package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.cloud.protocol.Capability
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.IceCandidate
import uk.me.cormack.lighting7.cloud.protocol.IceServer
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.link.LinkIn
import uk.me.cormack.lighting7.link.LinkSidecar
import uk.me.cormack.lighting7.link.LinkStatus
import uk.me.cormack.lighting7.models.DaoCloudRemoteSessions
import uk.me.cormack.lighting7.process.PidFile
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.FakeSidecar
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedUser
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The broker (cloud-p1 plan session 3, D5, D7, D17): offers from [FakeCloud] opened in the registry and
 * in a sidecar, here [FakeSidecar] run as a process of its own, and every way one misbehaves.
 */
class RemoteBrokerTest : RouteIntegrationTest() {

    private val fake = FakeCloud().apply { heartbeatS = 1 }

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    private lateinit var installId: String
    private val record: File = File.createTempFile("fake-sidecar", ".log").apply { deleteOnExit() }
    private val sessions get() = state.cloud.remoteSessions

    @After
    fun closeFake() {
        runCatching { state.cloud.close() }
        runCatching { state.credentialStore.deleteBlob(state.cloud.keys.blobName) }
        fake.close()
    }

    /** Adopt and connect, with a sidecar in [mode] unless null, and the public listener bound when [bound]. */
    private fun adopt(mode: String? = "normal", bound: Boolean = true, remoteAccess: Boolean = true) {
        val admin = seedUser(state, "admin").userId
        state.cloud.pollDelay = { 20.milliseconds }
        state.cloud.handshakeTimeout = 2.seconds
        state.cloud.linkCommand = mode?.let { m -> { _, _ -> FakeSidecar.command(m, record) } }
        if (bound) state.cloud.publicListenerBound("127.0.0.1", 8414)
        state.cloud.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { state.cloud.startAdoption(admin) }
        eventually("adopted") { state.cloud.state.value.adopted != null }
        installId = state.cloud.state.value.adopted!!.installId
        eventually("online") { state.cloud.state.value.connection == CloudConnectionStatus.ONLINE }
        eventually("the hello") { fake.sent<Message.Hello>(installId).isNotEmpty() }
        if (remoteAccess) state.cloud.setSwitches(remoteAccess = true)
        if (mode != null && bound && remoteAccess) eventually("the sidecar serves", 30.seconds) { state.cloud.sidecarRunning }
    }

    private fun eventually(what: String, within: Duration = 15.seconds, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(within) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun reply(id: String): Message {
        var reply: Message? = null
        eventually("a reply to $id") { fake.replyTo(installId, id)?.body?.also { reply = it } != null }
        return reply!!
    }

    private fun stdin(): List<String> = synchronized(record) { record.readLines() }

    private fun auditRows(): Int = transaction(state.database) { DaoCloudRemoteSessions.selectAll().count().toInt() }

    private fun lastCapabilities(): List<String> = fake.sent<Message.Capabilities>(installId).lastOrNull()?.list
        ?: fake.sent<Message.Hello>(installId).last().capabilities

    @Test
    fun `an offer is answered through the sidecar, candidates go both ways, and the cloud's close ends it`() {
        adopt()
        assertTrue(Capability.REMOTE_V1 in lastCapabilities())
        eventually("remote.no-sidecar cleared") { Capability.REMOTE_NO_SIDECAR !in lastCapabilities() }

        val id = fake.offer(installId, "sid-1")
        assertEquals(Message.RtcAnswer("sid-1", "answer for sid-1"), reply(id))
        val session = assertNotNull(sessions.find("sid-1"))
        assertEquals(fake.account.id, session.accountId)
        assertEquals(fake.org.id, session.orgId)
        assertEquals(1, auditRows())

        eventually("the sidecar's candidates") { fake.sent<Message.RtcIce>(installId).size == 2 }
        assertEquals(listOf("sid-1", "sid-1"), fake.sent<Message.RtcIce>(installId).map { it.sid })
        assertNull(fake.sent<Message.RtcIce>(installId).last().candidate)
        eventually("the path") { session.path == "RELAY" }

        fake.send(installId, Message.RtcIce("sid-1", IceCandidate("candidate:2 1 udp 1 192.0.2.1 9 typ host", "0", 0)))
        eventually("the cloud's candidate reached the sidecar") { stdin().any { "192.0.2.1" in it } }
        val open = stdin().first { "session.open" in it }
        assertTrue("stun:stun.example:3478" in open && "v=0 offer for sid-1" in open, open)
        assertTrue(stdin().first().isNotBlank() && !stdin().first().startsWith("{"), "the secret comes first")

        fake.send(installId, Message.RtcClose("sid-1", ErrorCode.SESSION_ENDED))
        eventually("the session ended") { sessions.find("sid-1") == null }
        eventually("the sidecar was told") { stdin().any { "session.close" in it && "sid-1" in it } }
        assertTrue(fake.sent<Message.RtcClose>(installId).none { it.sid == "sid-1" }, "the cloud closed it, so is not told")
        val row = transaction(state.database) { DaoCloudRemoteSessions.selectAll().where { DaoCloudRemoteSessions.sid eq "sid-1" }.single() }
        assertEquals("RELAY", row[DaoCloudRemoteSessions.path])
        assertEquals(RemoteSessionEnd.CLOSED_BY_CLOUD.name, row[DaoCloudRemoteSessions.endReason])
    }

    @Test
    fun `a session the sidecar closes is closed to the cloud with its reason`() {
        adopt()
        val id = fake.offer(installId, "sid-1", sdp = "then-close")
        assertTrue(reply(id) is Message.RtcAnswer)
        eventually("rtc.close") { fake.sent<Message.RtcClose>(installId).any { it.sid == "sid-1" } }
        assertEquals("TOKEN_EXPIRED", fake.sent<Message.RtcClose>(installId).single().reason)
        assertNull(sessions.find("sid-1"))
        assertTrue(stdin().none { "session.close" in it }, "the sidecar closed it, so is not told")
    }

    @Test
    fun `a session the desk ends is closed to both`() {
        adopt()
        reply(fake.offer(installId, "sid-1"))
        sessions.end("sid-1", RemoteSessionEnd.REVOKED)
        eventually("rtc.close") { fake.sent<Message.RtcClose>(installId).any { it.sid == "sid-1" } }
        assertEquals(ErrorCode.TOKEN_REVOKED, fake.sent<Message.RtcClose>(installId).single().reason)
        eventually("the sidecar was told") { stdin().any { "session.close" in it && "sid-1" in it } }
    }

    @Test
    fun `an offer with the switch off is refused before anything is written`() {
        adopt(remoteAccess = false)
        assertTrue(Capability.REMOTE_OFF in lastCapabilities())
        assertEquals(Message.RtcClose("sid-1", ErrorCode.REMOTE_ACCESS_OFF), reply(fake.offer(installId, "sid-1")))
        assertEquals(0, auditRows())
        assertNull(state.cloud.link, "no sidecar runs while Remote sessions is off")
        assertEquals(0, record.length())
    }

    @Test
    fun `an offer too large for the sidecar to read is refused before anything is written`() {
        adopt()
        val servers = (1..2000).map { IceServer(listOf("turn:turn-$it.example:443"), "user-$it", "x".repeat(100)) }
        val id = fake.send(installId, Message.RtcOffer("sid-1", fake.account.id, "a@example.com", "A", "v=0", servers))
        assertEquals(Message.RtcClose("sid-1", ErrorCode.MALFORMED), reply(id))
        assertEquals(0, auditRows())
        assertTrue(stdin().none { "session.open" in it })
    }

    @Test
    fun `with no sidecar configured an offer is refused and the desk says why`() {
        adopt(mode = null)
        assertEquals(Message.RtcClose("sid-1", ErrorCode.SIDECAR_UNAVAILABLE), reply(fake.offer(installId, "sid-1")))
        assertEquals(0, auditRows())
        assertTrue(Capability.REMOTE_V1 in lastCapabilities())
        assertTrue(Capability.REMOTE_NO_SIDECAR in lastCapabilities())
    }

    @Test
    fun `no sidecar starts while the public listener has not bound`() {
        adopt(bound = false)
        Thread.sleep(500)
        assertNull(state.cloud.link)
        assertEquals(0, record.length())
        assertEquals(listOf(Capability.REMOTE_V1, Capability.MCP_OFF, Capability.REMOTE_NO_SIDECAR), lastCapabilities())
        assertEquals(Message.RtcClose("sid-1", ErrorCode.SIDECAR_UNAVAILABLE), reply(fake.offer(installId, "sid-1")))
    }

    @Test
    fun `beyond eight open sessions an offer is refused before the sidecar or the database`() {
        adopt()
        (1..8).forEach { assertTrue(reply(fake.offer(installId, "sid-$it")) is Message.RtcAnswer) }
        assertEquals(Message.RtcClose("sid-9", ErrorCode.REMOTE_SESSION_LIMIT), reply(fake.offer(installId, "sid-9")))
        assertEquals(8, auditRows())
        assertTrue(stdin().none { "sid-9" in it })
    }

    @Test
    fun `an offer flood is refused past twenty a minute`() {
        adopt()
        (1..20).forEach { n ->
            assertTrue(reply(fake.offer(installId, "sid-$n", sdp = "then-close")) is Message.RtcAnswer)
            eventually("sid-$n closed") { sessions.find("sid-$n") == null }
        }
        assertEquals(Message.RtcClose("sid-21", ErrorCode.REMOTE_SESSION_LIMIT), reply(fake.offer(installId, "sid-21")))
        assertEquals(20, auditRows())
        assertTrue(stdin().none { "\"sid-21\"" in it })
    }

    @Test
    fun `a sidecar that dies ends its sessions and the desk says so`() {
        adopt(mode = "die")
        val id = fake.offer(installId, "sid-1")
        assertEquals(Message.RtcClose("sid-1", ErrorCode.SIDECAR_UNAVAILABLE), reply(id))
        eventually("not running") { !state.cloud.sidecarRunning }
        assertNull(sessions.find("sid-1"))
        eventually("remote.no-sidecar") { Capability.REMOTE_NO_SIDECAR in lastCapabilities() }
        val row = transaction(state.database) { DaoCloudRemoteSessions.selectAll().single() }
        assertEquals(RemoteSessionEnd.SIDECAR_STOPPED.name, row[DaoCloudRemoteSessions.endReason])

        eventually("restarted", 30.seconds) { state.cloud.sidecarRunning }
        assertEquals(2, assertNotNull(state.cloud.link).sidecar.launches.get())
        eventually("remote.no-sidecar cleared") { Capability.REMOTE_NO_SIDECAR !in lastCapabilities() }
        eventually("cloud.state says it runs") { state.cloud.state.value.link == LinkStatus.RUNNING }
    }

    @Test
    fun `turning Remote sessions off stops the sidecar, and on starts it again`() {
        adopt()
        eventually("cloud.state says it runs") { state.cloud.state.value.link == LinkStatus.RUNNING }
        state.cloud.setSwitches(remoteAccess = false)
        eventually("stopped") { state.cloud.link == null && !state.cloud.sidecarRunning }
        assertEquals(LinkStatus.OFF, state.cloud.state.value.link)
        eventually("remote.no-sidecar") { Capability.REMOTE_NO_SIDECAR in lastCapabilities() }

        state.cloud.setSwitches(remoteAccess = true)
        eventually("serving again", 30.seconds) { state.cloud.sidecarRunning }
        eventually("cloud.state says it runs") { state.cloud.state.value.link == LinkStatus.RUNNING }
        assertEquals(2, stdin().count { !it.startsWith("{") }, "a second launch, with its own secret")
    }

    @Test
    fun `a sidecar that stops reading is restarted when its stdin queue fills, rather than a line dropped`() {
        adopt(mode = "hang")
        val id = fake.offer(installId, "sid-1")
        eventually("opened") { sessions.find("sid-1") != null }
        val link = assertNotNull(state.cloud.link).sidecar
        assertEquals(1, link.launches.get())
        val candidate = IceCandidate("candidate:" + "z".repeat(1000))
        var refused = 0
        repeat(LinkSidecar.STDIN_LINES * 4) { if (!link.send(LinkIn.Ice("sid-1", candidate))) refused++ }
        assertTrue(refused > 0, "the queue never filled")
        eventually("a second launch") { link.launches.get() == 2 }
        eventually("serving again", 30.seconds) { state.cloud.sidecarRunning }
        assertNull(sessions.find("sid-1"))
        assertEquals(Message.RtcClose("sid-1", ErrorCode.SIDECAR_UNAVAILABLE), reply(id))
        assertEquals(2, stdin().count { !it.startsWith("{") }, "each launch had its own secret")
    }

    @Test
    fun `a sidecar flooding its stdout still has its offers answered`() {
        adopt(mode = "flood-stdout")
        val healthBefore = fake.sent<Message.Health>(installId).size
        repeat(3) { assertTrue(reply(fake.offer(installId, "sid-$it")) is Message.RtcAnswer) }
        eventually("heartbeats go on") { fake.sent<Message.Health>(installId).size >= healthBefore + 2 }
        assertTrue(state.cloud.sidecarRunning)
    }

    @Test
    fun `a sidecar flooding its stderr still has its offers answered`() {
        adopt(mode = "flood-stderr")
        val healthBefore = fake.sent<Message.Health>(installId).size
        repeat(3) { assertTrue(reply(fake.offer(installId, "sid-$it")) is Message.RtcAnswer) }
        eventually("heartbeats go on") { fake.sent<Message.Health>(installId).size >= healthBefore + 2 }
        assertTrue(state.cloud.sidecarRunning)
    }

    @Test
    fun `a sidecar a killed desk left running is stopped at the next start`() {
        val pidFile = PidFile(File.createTempFile("link", ".pid").toPath(), "a test sidecar")
        val command = FakeSidecar.command("hang", File.createTempFile("orphan", ".log"))
        val orphan = ProcessBuilder(command).start()
        try {
            pidFile.write(orphan, Path.of(command.first()))
            state.cloud.linkPidFile = { pidFile }
            adopt()
            assertTrue(orphan.waitFor(5, TimeUnit.SECONDS), "the orphan was stopped")
            eventually("this run's own recorded") {
                runCatching { pidFile.path.toFile().readLines().first().toLong() }.getOrNull() == state.cloud.link?.pid
            }
        } finally {
            orphan.destroyForcibly()
        }
    }

    @Test
    fun `unadopting stops the sidecar`() {
        adopt()
        reply(fake.offer(installId, "sid-1"))
        runBlocking { state.cloud.unadopt(force = true) }
        eventually("stopped") { state.cloud.link == null && !state.cloud.sidecarRunning }
        assertNull(sessions.find("sid-1"))
        assertFalse(record.readText().isEmpty())
    }
}
