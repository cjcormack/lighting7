package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.ai.FIXTURE_COMMANDS_REMOTE_REFUSAL
import uk.me.cormack.lighting7.cloud.protocol.Capability
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.TokenScope
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.models.CloudLinkVia
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedUser
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * MCP through the cloud (cloud-p1 plan session 8, D15): calls relayed by [FakeCloud] and run by the
 * desk's own `McpProtocol` as the desk user the cloud account links to.
 */
class McpRelayTest : RouteIntegrationTest() {

    private val fake = FakeCloud().apply { heartbeatS = 1 }

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    private lateinit var installId: String
    private var adminId = 0

    @Before
    fun adopt() {
        adminId = seedUser(state, "admin").userId
        state.cloud.pollDelay = { 20.milliseconds }
        state.cloud.handshakeTimeout = 2.seconds
        state.cloud.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { state.cloud.startAdoption(adminId) }
        eventually("adopted") { state.cloud.state.value.adopted != null }
        installId = state.cloud.state.value.adopted!!.installId
        eventually("online with the cloud's keys") {
            state.cloud.state.value.connection == CloudConnectionStatus.ONLINE && state.cloud.jwks.keys.isNotEmpty()
        }
        // Adoption links the adopter's account to them.
        state.cloud.setSwitches(mcpAccess = true)
    }

    @After
    fun closeFake() {
        runCatching { state.credentialStore.deleteBlob(state.cloud.keys.blobName) }
        fake.close()
    }

    private fun eventually(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(15.seconds) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun link(userId: Int, accountId: String) = transaction(state.database) {
        DaoCloudIdentityLinks.insert {
            it[user] = userId
            it[cloudAccountId] = accountId
            it[email] = "$accountId@example.com"
            it[linkedAt] = Instant.now()
            it[linkedVia] = CloudLinkVia.PROVED
        }
    }

    private fun reply(id: String): Message {
        eventually("the reply") { fake.replyTo(installId, id) != null }
        return fake.replyTo(installId, id)!!.body
    }

    private fun result(id: String): JsonObject? = (reply(id) as Message.McpResult).response

    private fun refusal(id: String): String = (reply(id) as Message.Error).code

    private fun toolCall(tool: String, arguments: String = "{}", accountId: String = fake.account.id, grantId: String = "grant-1"): String =
        fake.mcpCall(
            installId,
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
            accountId = accountId,
            grantId = grantId,
            clientName = "Cursor",
        )

    /** A tool call's answer: whether it is an error, and its text. */
    private fun toolResult(id: String): Pair<Boolean, String> {
        val answer = result(id)!!["result"]!!.jsonObject
        val text = answer["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        return (answer["isError"]!!.jsonPrimitive.content == "true") to text
    }

    @Test
    fun `tools are listed and called as the linked user`() {
        val listed = result(fake.mcpCall(installId, """{"jsonrpc":"2.0","id":"a","method":"tools/list"}"""))!!
        assertEquals("a", listed["id"]!!.jsonPrimitive.content)
        val tools = listed["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("describe_rig" in tools && "list_history" in tools, "$tools")

        val (error, text) = toolResult(toolCall("create_cue_stack", """{"name":"Act 1"}"""))
        assertFalse(error, text)
        val entry = state.history.entries(projectId).single { it.source.kind == HistorySource.Kind.MCP }
        assertEquals("Claude · Cursor", entry.source.name)
        assertEquals("mcp:cloud:grant-1", entry.source.key)
    }

    @Test
    fun `an operator's call is refused where a venue tool is an admin's`() {
        val operatorId = seedUser(state, "op", UserRole.OPERATOR).userId
        link(operatorId, "acct-op")
        val (error, text) = toolResult(toolCall("extract_venue", """{"name":"Hall"}""", accountId = "acct-op"))
        assertTrue(error, text)
        assertEquals("Only an admin can make or publish a venue.", text)
    }

    @Test
    fun `a fixture command through the cloud is held to its switch`() {
        assertFalse(state.remoteAccess.allowRemoteCommands)
        val (error, text) = toolResult(toolCall("run_fixture_command", """{"fixtureKey":"x","command":"reset"}"""))
        assertTrue(error, text)
        assertEquals(FIXTURE_COMMANDS_REMOTE_REFUSAL, text)
    }

    @Test
    fun `an unlinked account's call is refused`() {
        assertEquals(ErrorCode.CLOUD_ACCOUNT_NOT_LINKED, refusal(toolCall("get_current_state", accountId = "acct-stranger")))
    }

    @Test
    fun `a remote session's token is refused at the relay`() {
        val token = fake.mint(installId, scope = TokenScope.REMOTE)
        val id = fake.mcpCall(installId, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = token)
        assertEquals(ErrorCode.TOKEN_WRONG_SCOPE, refusal(id))
    }

    @Test
    fun `a call whose grant is not its token's is refused`() {
        val token = fake.mint(installId, gid = "grant-2", scope = TokenScope.MCP)
        val id = fake.mcpCall(installId, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", grantId = "grant-1", token = token)
        assertEquals(ErrorCode.MCP_GRANT_MISMATCH, refusal(id))
    }

    @Test
    fun `a revoked account's calls are refused`() {
        val token = fake.mint(installId, scope = TokenScope.MCP, iat = Instant.now().epochSecond - 5)
        // The channel reads in order, so the revoke is heard before the call that follows it.
        fake.revoke(installId, accountId = fake.account.id)
        val id = fake.mcpCall(installId, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", token = token)
        assertEquals(ErrorCode.TOKEN_REVOKED, refusal(id))
    }

    @Test
    fun `list_history keeps two grants apart`() {
        assertFalse(toolResult(toolCall("create_cue_stack", """{"name":"Act 1"}""", grantId = "grant-1")).first)
        assertFalse(toolResult(toolCall("create_cue_stack", """{"name":"Act 2"}""", grantId = "grant-2")).first)

        fun listed(grantId: String): List<String> {
            val (error, text) = toolResult(toolCall("list_history", grantId = grantId))
            assertFalse(error, text)
            return Json.parseToJsonElement(text).jsonObject["changes"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        }
        assertTrue(listed("grant-1").single().contains("Act 1"))
        assertTrue(listed("grant-2").single().contains("Act 2"))
    }

    @Test
    fun `a notification is answered with no response`() {
        val id = fake.mcpCall(installId, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNull(result(id))
    }

    @Test
    fun `Claude (MCP) off refuses every call and is announced`() {
        state.cloud.setSwitches(mcpAccess = false)
        eventually("capabilities with mcp.off") { fake.sent<Message.Capabilities>(installId).lastOrNull()?.list?.contains(Capability.MCP_OFF) == true }
        val announced = fake.sent<Message.Capabilities>(installId).last().list
        assertTrue(Capability.MCP_V1 in announced && Capability.MCP_OFF in announced, "$announced")
        assertEquals(ErrorCode.MCP_ACCESS_OFF, refusal(fake.mcpCall(installId, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")))

        state.cloud.setSwitches(mcpAccess = true)
        eventually("capabilities without mcp.off") { Capability.MCP_OFF !in fake.sent<Message.Capabilities>(installId).last().list }
        assertTrue(Capability.MCP_V1 in fake.sent<Message.Capabilities>(installId).last().list)
    }
}
