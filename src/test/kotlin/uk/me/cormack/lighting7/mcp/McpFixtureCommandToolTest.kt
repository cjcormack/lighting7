package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `run_fixture_command` over MCP (fixture optics plan session 7, D13): offered, refused unless an admin
 * has allowed fixture commands for remote access — MCP is always remote — and, once allowed, the same
 * hold the REST route runs. `describe_rig` lists each type's commands.
 */
class McpFixtureCommandToolTest : RouteIntegrationTest() {

    private val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")

    private fun patchVarytec() {
        val patched = runBlocking {
            SetupTools(state, AiTools(state)).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[{"key":"vary","name":"Vary","fixtureTypeKey":"varytec-easymove-xl-60-spot-11ch","universe":0,"startChannel":1}]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
    }

    private fun callTool(protocol: McpProtocol, arguments: String): JsonObject = runBlocking {
        protocol.handle(
            Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run_fixture_command","arguments":$arguments}}"""),
            user,
        )
    }!!["result"]!!.jsonObject

    private fun JsonObject.text(): String = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun `the tool is offered, refused until an admin allows commands, then runs the hold`() {
        patchVarytec()
        val protocol = McpProtocol(state)
        val listed = runBlocking {
            protocol.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("run_fixture_command" in listed, "$listed")

        val refused = callTool(protocol, """{"fixtureKey":"vary","command":"reset"}""")
        assertTrue(refused["isError"]!!.jsonPrimitive.boolean)
        assertTrue("turned off for remote access" in refused.text(), refused.text())
        assertEquals(0u.toUByte(), state.show.outputSource.getParkedValue(0, 11), "nothing was held")

        state.remoteAccess.update(allowCommands = true, hasAnyUser = true)
        try {
            val unknown = callTool(protocol, """{"fixtureKey":"vary","command":"lampOn"}""")
            assertTrue(unknown["isError"]!!.jsonPrimitive.boolean)
            assertTrue("COMMAND_UNKNOWN" in unknown.text(), unknown.text())

            val ran = callTool(protocol, """{"fixtureKey":"vary","command":"reset"}""")
            assertTrue(!ran["isError"]!!.jsonPrimitive.boolean, ran.text())
            assertTrue("\"completed\":true" in ran.text(), ran.text())
            assertEquals(0u.toUByte(), state.show.outputSource.getParkedValue(0, 11), "back to idle after the hold")
        } finally {
            state.remoteAccess.update(allowCommands = false, hasAnyUser = true)
        }
    }

    @Test
    fun `describe_rig lists each fixture's commands, with their hold`() {
        patchVarytec()
        val rig = RigBriefing(state).describeRig()
        assertTrue("commands=reset (Reset, 5.0 s)" in rig, rig)
    }
}
