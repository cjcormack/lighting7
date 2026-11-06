package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FadeTiming
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The MCP relay's limits (cloud-p1 plan D15, P1): four calls at once and four waiting, `DESK_BUSY`
 * beyond; a result over the frame limit answered as a JSON-RPC error; and the work done off the
 * channel's threads, so a flood of calls leaves a fade as it is with none.
 */
class McpRelayLimitsTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var state: State
    private lateinit var fake: FakeCloud
    private lateinit var service: CloudService
    private lateinit var installId: String

    @Volatile private var handler: suspend (JsonObject, AuthenticatedUser) -> JsonObject? = { request, _ -> answer(request) }
    private val threads = ConcurrentHashMap.newKeySet<String>()

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        fake = FakeCloud().apply { heartbeatS = 1 }
        val adminId = seedUser(state, "admin").userId
        val store = InMemoryCredentialStore()
        service = CloudService(
            database = state.database,
            config = CloudConfig(fake.baseUrl, true),
            credentialStore = { store },
            apiFactory = { CloudApi(it, Duration.ofSeconds(5)) },
            authService = state.authService,
            mcpHandler = { request, user ->
                threads += Thread.currentThread().name
                handler(request, user)
            },
        )
        service.pollDelay = { 20.milliseconds }
        service.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking {
            service.startAdoption(adminId)
            withTimeout(15.seconds) {
                service.state.first { it.connection == CloudConnectionStatus.ONLINE }
                while (service.jwks.keys.isEmpty()) delay(20)
            }
        }
        installId = service.state.value.adopted!!.installId
        service.setSwitches(mcpAccess = true)
    }

    @After
    fun tearDown() {
        runCatching { service.close() }
        runCatching { fake.close() }
        runCatching { state.shutdown() }
    }

    private fun answer(request: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request["id"]!!)
        put("result", buildJsonObject { put("ok", true) })
    }

    private fun call(n: Int): String = fake.mcpCall(installId, """{"jsonrpc":"2.0","id":$n,"method":"ping"}""")

    private fun reply(id: String): Message = runBlocking {
        withTimeout(15.seconds) { while (fake.replyTo(installId, id) == null) delay(10) }
        fake.replyTo(installId, id)!!.body
    }

    @Test
    fun `four calls run and four wait, and a ninth is refused busy`() {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        handler = { request, _ ->
            started.incrementAndGet()
            gate.await()
            answer(request)
        }
        val ids = (1..8).map(::call)
        runBlocking { withTimeout(10.seconds) { while (started.get() < McpRelay.RUNNING) delay(10) } }
        val ninth = call(9)
        assertEquals(ErrorCode.DESK_BUSY, (reply(ninth) as Message.Error).code)
        assertEquals(McpRelay.RUNNING, started.get(), "four at once, the rest waiting")
        assertTrue(ids.none { fake.replyTo(installId, it) != null }, "nothing answered before the gate opens")

        gate.complete(Unit)
        ids.forEachIndexed { n, id ->
            val response = (reply(id) as Message.McpResult).response!!
            assertEquals("${n + 1}", response["id"]!!.jsonPrimitive.content)
        }
        assertEquals(8, started.get())
        assertTrue(reply(call(10)) is Message.McpResult, "room again once they are done")
    }

    @Test
    fun `a result over the frame limit is answered as a JSON-RPC error`() {
        handler = { request, _ ->
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", request["id"]!!)
                put("result", buildJsonObject { put("text", "x".repeat(9 * 1024 * 1024)) })
            }
        }
        val response = (reply(call(1)) as Message.McpResult).response!!
        assertEquals("1", response["id"]!!.jsonPrimitive.content)
        val error = response["error"]!!.jsonObject
        assertEquals(-32603, error["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("too large"), "$error")
    }

    @Test
    fun `a flood of calls leaves a fade as it is, and the channel's threads run none of them`() {
        val baseline = FadeTiming.fade()
        handler = { request, _ ->
            Thread.sleep(5)
            answer(request)
        }
        val ids = mutableListOf<String>()
        val run = FadeTiming.fade {
            repeat(200) { ids += call(it) }
        }
        FadeTiming.assertLike(baseline, run, "a flood of MCP calls")
        val replies = ids.map(::reply)
        assertTrue(replies.any { it is Message.McpResult }, "some calls ran")
        assertTrue(replies.all { it is Message.McpResult || (it as Message.Error).code == ErrorCode.DESK_BUSY }, "the rest refused busy")
        assertTrue(threads.isNotEmpty() && threads.none { it.startsWith("cloud-") }, "ran on $threads")
    }
}
