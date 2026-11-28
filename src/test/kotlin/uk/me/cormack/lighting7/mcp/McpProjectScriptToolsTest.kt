package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.ProjectScriptGuide
import uk.me.cormack.lighting7.ai.ProjectScriptTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueTrigger
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.models.TriggerType
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project-script tools over MCP: saving and running behind *Allow scripts*, a save refused when the
 * script does not compile, and every example the guide gives compiled and run against a patched rig.
 */
class McpProjectScriptToolsTest : RouteIntegrationTest() {

    private val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")

    private fun call(protocol: McpProtocol, name: String, arguments: JsonObject): JsonObject = runBlocking {
        protocol.handle(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", arguments) })
            },
            user,
        )
    }!!["result"]!!.jsonObject

    private fun JsonObject.text(): String = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.isError(): Boolean = this["isError"]!!.jsonPrimitive.boolean
    private fun JsonObject.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    private fun stored(name: String) = transaction(state.database) { DaoScript.find { DaoScripts.name eq name }.singleOrNull()?.script }

    private fun patchRig() {
        val patched = runBlocking {
            SetupTools(state, AiTools(state)).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[
                        {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1,"groups":["FOH"]},
                        {"key":"foh-2","name":"FOH 2","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":2,"groups":["FOH"]}
                    ]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
    }

    @Test
    fun `saving and running are refused until scripts are allowed`() {
        val protocol = McpProtocol(state)
        val save = call(protocol, "save_script", buildJsonObject { put("name", "Chase"); put("script", "setBpm(120.0)") })
        assertTrue(save.isError() && "REMOTE_SCRIPTS_DISABLED" in save.text(), save.text())
        val run = call(protocol, "run_script", buildJsonObject { put("script", "setBpm(120.0)") })
        assertTrue(run.isError() && "REMOTE_SCRIPTS_DISABLED" in run.text(), run.text())
        assertNull(stored("Chase"))

        val guide = call(protocol, "get_script_guide", buildJsonObject {})
        assertFalse(guide.isError(), guide.text())
    }

    @Test
    fun `the guide's examples compile and run on the rig, and a save that does not compile writes nothing`() {
        patchRig()
        state.remotePolicy.update(allowScripts = true)
        try {
            val protocol = McpProtocol(state)
            val guide = call(protocol, "get_script_guide", buildJsonObject {}).text()
            assertTrue("GenericDimmerFixture" in guide && "foh-1" in guide, guide)

            val broken = call(protocol, "save_script", buildJsonObject { put("name", "Broken"); put("script", "fixture<Nope>(\"x\")") })
            assertTrue(broken.isError(), broken.text())
            assertTrue(broken.json()["messages"]!!.jsonArray.any { "at" in it.jsonObject }, broken.text())
            assertNull(stored("Broken"))

            for ((type, script) in listOf(
                "FX_APPLICATION" to ProjectScriptGuide.FX_APPLICATION_EXAMPLE,
                "GENERAL" to ProjectScriptGuide.GENERAL_EXAMPLE,
                "FX_DEFINITION" to ProjectScriptGuide.FX_DEFINITION_EXAMPLE,
            )) {
                val saved = call(protocol, "save_script", buildJsonObject { put("name", type); put("scriptType", type); put("script", script) })
                assertFalse(saved.isError(), "$type: ${saved.text()}")
                assertNotNull(stored(type))
                val ran = call(protocol, "run_script", buildJsonObject { put("name", type) })
                assertFalse(ran.isError(), "$type: ${ran.text()}")
                assertEquals("success", ran.json()["status"]!!.jsonPrimitive.content, ran.text())
            }
            assertTrue(state.show.fxEngine.getActiveFx().isNotEmpty(), "the FX_APPLICATION example started an FX")
            assertNotNull(state.show.fxRegistry.getRegistration("half-on"), "the FX_DEFINITION example registered its FX")

            val listed = call(protocol, "get_script", buildJsonObject {}).json()["scripts"]!!.jsonArray
            assertEquals(3, listed.size, listed.toString())
            val read = call(protocol, "get_script", buildJsonObject { put("name", "GENERAL") }).json()
            assertEquals(ProjectScriptGuide.GENERAL_EXAMPLE, read["script"]!!.jsonPrimitive.content)

            val deleted = call(protocol, "delete_script", buildJsonObject { put("name", "GENERAL") })
            assertFalse(deleted.isError(), deleted.text())
            assertNull(stored("GENERAL"))
        } finally {
            state.remotePolicy.update(allowScripts = false)
        }
    }

    @Test
    fun `a run that does not compile answers compileError, and one past its deadline is cancelled`() {
        state.remotePolicy.update(allowScripts = true)
        try {
            val ran = call(McpProtocol(state), "run_script", buildJsonObject { put("script", "fixture<Nope>(\"x\")") })
            assertTrue(ran.isError(), ran.text())
            assertEquals("compileError", ran.json()["status"]!!.jsonPrimitive.content, ran.text())
            assertTrue(ran.json()["messages"]!!.jsonArray.isNotEmpty(), ran.text())
        } finally {
            state.remotePolicy.update(allowScripts = false)
        }

        val slow = ProjectScriptTools(state, runDeadlineMs = 200).executeTool(
            "run_script",
            buildJsonObject { put("scriptType", "GENERAL"); put("script", "kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(2_000) }") },
        )
        assertFalse(slow.success, slow.result)
        assertEquals("timedOut", Json.parseToJsonElement(slow.result).jsonObject["status"]!!.jsonPrimitive.content, slow.result)
    }

    @Test
    fun `a script a cue trigger runs is not deleted`() {
        val scriptId = transaction(state.database) {
            val script = DaoScript.new { name = "Haze"; script = "1"; project = state.show.project }
            val cue = DaoCue.new { name = "Storm"; project = state.show.project }
            DaoCueTrigger.new { this.cue = cue; triggerType = TriggerType.ACTIVATION; this.script = script }
            script.id.value
        }
        val deleted = call(McpProtocol(state), "delete_script", buildJsonObject { put("name", "Haze") })
        assertTrue(deleted.isError() && "Storm" in deleted.text(), deleted.text())
        assertNotNull(transaction(state.database) { DaoScript.findById(scriptId) })
    }
}
