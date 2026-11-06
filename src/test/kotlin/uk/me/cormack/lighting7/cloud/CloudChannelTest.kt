package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.cloud.protocol.AuthFailure
import uk.me.cormack.lighting7.cloud.protocol.Capability
import uk.me.cormack.lighting7.cloud.protocol.CloseCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.MAX_FRAME_BYTES
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.PROTOCOL_VERSION
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RecordingTransport
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.update.BuildInfo
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The control channel against [FakeCloud] (cloud-p0 plan session 5). */
class CloudChannelTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var state: State
    private lateinit var fake: FakeCloud
    private val others = mutableListOf<FakeCloud>()
    private val store = InMemoryCredentialStore()
    private val services = mutableListOf<CloudService>()
    private var adminId = 0

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        fake = FakeCloud().apply { heartbeatS = 1 }
        adminId = seedUser(state, "admin").userId
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.close() } }
        (others + fake).forEach { runCatching { it.close() } }
        runCatching { state.shutdown() }
    }

    private fun service(enabled: Boolean = true): CloudService = CloudService(
        database = state.database,
        config = CloudConfig(fake.baseUrl, enabled),
        credentialStore = { store },
        userChanges = state.authService.userChanges,
        apiFactory = { CloudApi(it, Duration.ofSeconds(5)) },
    ).also {
        it.pollDelay = { 20.milliseconds }
        it.handshakeTimeout = 2.seconds
        services += it
        it.start()
    }

    private fun CloudService.await(what: String, predicate: (CloudStateDto) -> Boolean): CloudStateDto = runBlocking {
        try {
            withTimeout(15.seconds) { state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state is ${state.value}", e)
        }
    }

    /** An adopted service, online. */
    private fun online(): Pair<CloudService, String> {
        val service = service()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { service.startAdoption(adminId) }
        val installId = service.await("adopted") { it.adopted != null }.adopted!!.installId
        service.await("online") { it.connection == CloudConnectionStatus.ONLINE }
        return service to installId
    }

    private fun retryingWith(service: CloudService, code: String): CloudStateDto =
        service.await("retrying with $code") { it.connection == CloudConnectionStatus.RETRYING && it.lastError?.code == code }

    private fun eventually(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(15.seconds) { while (!check()) kotlinx.coroutines.delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun totalChanges(): Long = transaction(state.database) {
        exec("SELECT total_changes()") { rs -> rs.next(); rs.getLong(1) }!!
    }

    @Test
    fun `an adopted desk connects, says hello and sends a heartbeat`() {
        val (service, installId) = online()
        eventually("two heartbeats") { fake.received.count { it.second.body is Message.Health } >= 2 }

        val sent = fake.received.filter { it.first == installId }.map { it.second.body }
        val hello = sent.first() as Message.Hello
        val install = transaction(state.database) { DaoInstall.all().first() }
        assertEquals(PROTOCOL_VERSION, hello.protocol)
        assertEquals(install.uuid.toString(), hello.deskInstallUuid)
        assertEquals(install.friendlyName, hello.friendlyName)
        assertEquals(DeskPlatform.current, hello.platform)
        assertEquals(BuildInfo.current.version, hello.deskVersion)
        assertEquals(
            listOf(Capability.REMOTE_V1, Capability.MCP_V1, Capability.REMOTE_OFF, Capability.MCP_OFF, Capability.REMOTE_NO_SIDECAR),
            hello.capabilities,
            "a desk with both switches off and no sidecar says so",
        )
        val health = sent.filterIsInstance<Message.Health>().first()
        assertEquals(BuildInfo.current.version, health.deskVersion)

        val now = service.state.value
        assertNotNull(now.lastOnlineAt)
        assertNull(now.retryAt)
        assertNull(now.lastError)
        assertEquals(setOf(installId), fake.online)
    }

    @Test
    fun `heartbeats and reconnects write nothing to the database`() {
        val (service, _) = online()
        val before = totalChanges()
        eventually("two heartbeats") { fake.received.count { it.second.body is Message.Health } >= 2 }
        fake.dropChannel()
        retryingWith(service, CloudErrorCodes.DISCONNECTED)
        service.await("online again") { it.connection == CloudConnectionStatus.ONLINE }
        eventually("a heartbeat after the reconnect") { fake.received.count { it.second.body is Message.Health } >= 3 }
        assertEquals(before, totalChanges(), "the channel wrote to the database outside a transition")
    }

    /** A forced `auth` refusal: reported with its reason, the adoption kept, and retried until the cloud relents. */
    private fun refusedThenRetried(reason: AuthFailure) {
        val (service, installId) = online()
        fake.authFailure = reason
        fake.dropChannel()
        val refused = retryingWith(service, CloudErrorCodes.AUTH_FAILED)
        val error = assertNotNull(refused.lastError)
        assertTrue(error.message.contains(reason.name), error.message)
        assertNotNull(refused.adopted, "an auth refusal must not forget the adoption")
        assertNotNull(refused.retryAt)
        fake.authFailure = null
        service.await("online after $reason") { it.connection == CloudConnectionStatus.ONLINE }
        assertEquals(setOf(installId), fake.online)
    }

    @Test
    fun `a MALFORMED refusal is reported and retried`() = refusedThenRetried(AuthFailure.MALFORMED)

    @Test
    fun `a TIMEOUT refusal is reported and retried`() = refusedThenRetried(AuthFailure.TIMEOUT)

    @Test
    fun `a forced BAD_SIGNATURE refusal is reported and retried`() = refusedThenRetried(AuthFailure.BAD_SIGNATURE)

    @Test
    fun `a signature by another key is refused BAD_SIGNATURE`() {
        val (service, installId) = online()
        fake.installs[installId] = fake.installs.getValue(installId).copy(key = InstallKeys.generate().public)
        fake.dropChannel()
        val refused = retryingWith(service, CloudErrorCodes.AUTH_FAILED)
        assertTrue(refused.lastError!!.message.contains(AuthFailure.BAD_SIGNATURE.name))
    }

    @Test
    fun `clock skew is reported as this machine's clock`() {
        val (service, _) = online()
        fake.clockOffset = Duration.ofMinutes(10)
        fake.dropChannel()
        val skewed = retryingWith(service, CloudErrorCodes.CLOCK_SKEW)
        val message = skewed.lastError!!.message
        assertTrue(message.contains("check this machine's clock"), message)
        assertTrue(message.contains("behind the cloud"), message)
    }

    @Test
    fun `a newer connection from the same install replaces this one, which reconnects`() {
        val (service, installId) = online()
        val key = assertNotNull(service.keys.load())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }
        try {
            scope.launch {
                ChannelConnection(
                    client = client,
                    target = ChannelTarget(fake.baseUrl, installId) { key },
                    identity = DeskIdentity("other", "other"),
                    clock = java.time.Instant::now,
                    handshakeTimeout = 2.seconds,
                    unexpected = OnceLog(),
                    noteThread = {},
                ).connect {}
            }
            retryingWith(service, CloudErrorCodes.REPLACED)
            service.await("online again") { it.connection == CloudConnectionStatus.ONLINE }
            assertTrue(fake.closes.any { it.code == CloseCode.REPLACED.code })
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun `an install removed in the portal is forgotten at its next connect`() {
        val (service, installId) = online()
        fake.removeInPortal(installId, tell = false)
        fake.dropChannel()
        val removed = service.await("forgotten") { it.adopted == null }
        assertEquals("This desk was removed from Test Org in the cloud.", removed.notice)
        assertEquals(CloudConnectionStatus.OFF, removed.connection)
        assertTrue(!service.keys.exists(), "the install key must go")
        assertEquals(0, transaction(state.database) { DaoCloudConnections.selectAll().count() })
    }

    @Test
    fun `an unadopted message forgets the adoption`() {
        val (service, installId) = online()
        val attempts = fake.channelAttempts.get()
        fake.removeInPortal(installId, tell = true)
        val removed = service.await("forgotten") { it.adopted == null }
        assertEquals("This desk was removed from Test Org in the cloud.", removed.notice)
        assertEquals(CloudConnectionStatus.OFF, removed.connection)
        Thread.sleep(1_500)
        assertEquals(attempts, fake.channelAttempts.get(), "a forgotten desk must not reconnect")
    }

    @Test
    fun `an unknown message type is ignored and logged once`() {
        val (service, installId) = online()
        val (_, logs) = captureLogs(CloudChannel::class.java.name) {
            repeat(3) { fake.send(installId, Message.Unknown("future.thing", JsonObject(mapOf("x" to JsonPrimitive(1))))) }
            fake.send(installId, Message.Welcome("again", 1), v = PROTOCOL_VERSION + 1)
            eventually("a heartbeat after the unknown frames") {
                fake.received.count { it.second.body is Message.Health } >= 2
            }
        }
        assertEquals(1, logs.count { it.contains("future.thing") }, logs.toString())
        assertEquals(CloudConnectionStatus.ONLINE, service.state.value.connection)
    }

    @Test
    fun `a frame over the limit is closed 4000 and the desk reconnects`() {
        val (service, installId) = online()
        val attempts = fake.channelAttempts.get()
        fake.sendText(installId, "\"" + "x".repeat(MAX_FRAME_BYTES) + "\"")
        eventually("the desk's 4000") { fake.closes.any { it.code == CloseCode.PROTOCOL_ERROR.code } }
        service.await("online again") { it.connection == CloudConnectionStatus.ONLINE && fake.channelAttempts.get() > attempts }
    }

    @Test
    fun `a redirect to another host is not followed`() {
        val other = FakeCloud().also { others += it }
        val (service, _) = online()
        fake.redirectChannelTo = other.baseUrl
        fake.dropChannel()
        retryingWith(service, CloudErrorCodes.CONNECT_FAILED)
        assertEquals(0, other.channelAttempts.get())
    }

    @Test
    fun `the channel URL keeps the base URL's host and scheme`() {
        assertEquals("wss://cloud.example.com/v1/desk", ChannelConnection.channelUrl("https://cloud.example.com"))
        assertEquals("wss://cloud.example.com/base/v1/desk", ChannelConnection.channelUrl("https://cloud.example.com/base"))
        assertEquals("ws://127.0.0.1:8080/v1/desk", ChannelConnection.channelUrl("http://127.0.0.1:8080"))
        assertEquals("wss://Cloud.Example.com/v1/desk", ChannelConnection.channelUrl("HTTPS://Cloud.Example.com"))
        assertTrue(CloudConfig.checkBaseUrl("http://cloud.example.com") is BaseUrlCheck.Refused)
        assertTrue(CloudConfig.checkBaseUrl("http://localhost:9000") is BaseUrlCheck.Ok)
    }

    @Test
    fun `text the cloud chose is cut short before the desk repeats it`() {
        val long = "\u00e9".repeat(200)
        assertEquals(65, ChannelConnection.clip(long).length)
        assertEquals("ab", ChannelConnection.clip("a\u0000\nb"))
        val reason = ChannelConnection.utf8Prefix("x" + "\u20ac".repeat(100), 123)
        assertTrue(reason.toByteArray().size <= 123)
        assertEquals(1 + 40, reason.length)
    }

    @Test
    fun `an over-long unknown type is ignored and logged short`() {
        val (service, installId) = online()
        val type = "t".repeat(10_000)
        val (_, logs) = captureLogs(CloudChannel::class.java.name) {
            fake.send(installId, Message.Unknown(type, JsonObject(emptyMap())))
            eventually("a heartbeat after it") { fake.received.count { it.second.body is Message.Health } >= 2 }
        }
        assertTrue(logs.isNotEmpty() && logs.all { it.length < 200 }, logs.toString())
        assertEquals(CloudConnectionStatus.ONLINE, service.state.value.connection)
    }

    @Test
    fun `cloud enabled false makes no connection`() {
        val (first, _) = online()
        first.close()
        val attempts = fake.channelAttempts.get()
        val off = service(enabled = false)
        Thread.sleep(1_500)
        assertEquals(attempts, fake.channelAttempts.get())
        assertEquals(CloudConnectionStatus.OFF, off.state.value.connection)
        assertNotNull(off.state.value.adopted)
    }

    @Test
    fun `an install key the store cannot read is reported and retried`() {
        val (service, _) = online()
        val blob = assertNotNull(store.getBlob(service.keys.blobName))
        store.setBlob(service.keys.blobName, "not a key")
        service.close()
        val restarted = service()
        retryingWith(restarted, CloudErrorCodes.KEY_UNREADABLE)
        store.setBlob(restarted.keys.blobName, blob)
        restarted.await("online once the key reads") { it.connection == CloudConnectionStatus.ONLINE }
    }

    @Test
    fun `a cloud that closes between challenge and auth is retried`() {
        val (service, installId) = online()
        fake.closeAfterChallenge = true
        fake.dropChannel()
        service.await("a retry after the early close") { it.connection == CloudConnectionStatus.RETRYING }
        fake.closeAfterChallenge = false
        service.await("online again") { it.connection == CloudConnectionStatus.ONLINE }
        assertEquals(setOf(installId), fake.online)
    }

    @Test
    fun `the channel's work never runs on a thread the show uses`() {
        val (service, _) = online()
        val channelThreads = ConcurrentHashMap.newKeySet<String>()
        service.onChannelWork = { channelThreads += Thread.currentThread().name }
        service.handshakeTimeout = 300.milliseconds
        fake.channelStall = true
        fake.dropChannel()

        val showThreads = ConcurrentHashMap.newKeySet<String>()
        val transport = RecordingTransport()
        val controller = ArtNetController(Universe(0, 0), transport = transport)
        val show = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            show.launch { while (true) { showThreads += Thread.currentThread().name; kotlinx.coroutines.delay(5) } }
            show.launch { while (true) { showThreads += transport.frames.receive().threadName } }
            val attempts = fake.channelAttempts.get()
            eventually("three stalled attempts") { fake.channelAttempts.get() >= attempts + 3 }
        } finally {
            show.cancel()
            controller.close()
            fake.releaseChannel()
        }

        val channel = channelThreads.toSet()
        assertTrue(channel.isNotEmpty())
        assertTrue(channel.all { it.startsWith("cloud-") }, "the channel ran on $channel")
        assertTrue(showThreads.isNotEmpty())
        assertEquals(emptySet(), channel intersect showThreads)
    }
}
