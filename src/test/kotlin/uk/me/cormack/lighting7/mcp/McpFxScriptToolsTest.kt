package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.FxScriptGuide
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.fx.FxSource
import uk.me.cormack.lighting7.models.DaoFxDefinition
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The custom-FX tools over MCP: the guide, compiling and saving behind *Allow scripts*, the save's trial
 * run refusing a script that would fail on stage, preview on notional heads, and `apply_fx` running the
 * saved FX in the programmer as a Local FX.
 */
class McpFxScriptToolsTest : RouteIntegrationTest() {

    private val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")

    private val pulseParameters = """[
        {"name":"low","type":"ubyte","defaultValue":"0","unit":"%","pair":"high"},
        {"name":"high","type":"ubyte","defaultValue":"255","unit":"%"},
        {"name":"width","type":"double","defaultValue":"0.3","min":0.01,"max":1,"unit":"ratio"}
    ]"""

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

    private fun call(protocol: McpProtocol, name: String, arguments: String) = call(protocol, name, Json.parseToJsonElement(arguments).jsonObject)

    private fun JsonObject.text(): String = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.isError(): Boolean = this["isError"]!!.jsonPrimitive.boolean
    private fun JsonObject.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    private fun saveArguments(fxId: String, script: String, outputType: String = "SLIDER", extra: String = ""): JsonObject =
        Json.parseToJsonElement(
            """{"fxId":"$fxId","name":"Half pulse","family":"intensity","outputType":"$outputType",
                "compatibleAttributes":["dimmer"],"parameters":$pulseParameters,"script":${JsonPrimitive(script)}$extra}""",
        ).jsonObject

    private fun storedDefinition(fxId: String) = transaction(state.database) {
        DaoFxDefinition.find { DaoFxDefinitions.fxId eq fxId }.singleOrNull()?.script
    }

    private inline fun withScripts(block: () -> Unit) {
        state.remotePolicy.update(allowScripts = true)
        try {
            block()
        } finally {
            state.remotePolicy.update(allowScripts = false)
        }
    }

    @Test
    fun `compiling and saving are refused until scripts are allowed, while the guide and the library read`() {
        val protocol = McpProtocol(state)
        val guide = call(protocol, "get_fx_script_guide", "{}")
        assertFalse(guide.isError(), guide.text())
        assertTrue("FxOutput.Slider" in guide.text())

        val wave = call(protocol, "get_fx_script", """{"fxType":"Wave"}""")
        assertFalse(wave.isError(), wave.text())
        assertEquals("BUILT_IN", wave.json()["source"]!!.jsonPrimitive.content)
        assertTrue(wave.json()["script"]!!.jsonPrimitive.content.isNotBlank())

        for (name in listOf("check_fx_script", "save_fx_script")) {
            val refused = call(protocol, name, saveArguments("half-pulse", FxScriptGuide.STANDARD_EXAMPLE).let {
                if (name == "check_fx_script") Json.parseToJsonElement("""{"script":"FxOutput.Slider(0u)"}""").jsonObject else it
            })
            assertTrue(refused.isError(), refused.text())
            assertTrue("REMOTE_SCRIPTS_DISABLED" in refused.text(), refused.text())
        }
        assertNull(storedDefinition("half-pulse"))
    }

    @Test
    fun `the guide's examples compile`() = withScripts {
        val protocol = McpProtocol(state)
        for ((script, mode) in listOf(FxScriptGuide.STANDARD_EXAMPLE to "STANDARD", FxScriptGuide.STATEFUL_EXAMPLE to "STATEFUL")) {
            val checked = call(protocol, "check_fx_script", buildJsonObject { put("script", script); put("fxMode", mode) })
            assertFalse(checked.isError(), checked.text())
        }
    }

    @Test
    fun `a save refuses a script that would fail on stage, writing nothing`() = withScripts {
        val protocol = McpProtocol(state)

        val broken = call(protocol, "save_fx_script", saveArguments("broken", "val x = \nFxOutput.Slider(nope)"))
        assertTrue(broken.isError(), broken.text())
        assertTrue(broken.json()["messages"]!!.jsonArray.any { "at" in it.jsonObject }, broken.text())

        val wrongOutput = call(protocol, "save_fx_script", saveArguments("wrong", "FxOutput.Colour(Color.RED)"))
        assertTrue(wrongOutput.isError(), wrongOutput.text())
        assertTrue("outputType SLIDER" in wrongOutput.text(), wrongOutput.text())

        val throws = call(protocol, "save_fx_script", saveArguments("throws", "check(phase < 0.5) { \"late\" }\nFxOutput.Slider(0u)"))
        assertTrue(throws.isError(), throws.text())
        assertTrue("late" in throws.text(), throws.text())

        val builtIn = call(protocol, "save_fx_script", saveArguments("Wave", FxScriptGuide.STANDARD_EXAMPLE))
        assertTrue(builtIn.isError(), builtIn.text())
        assertTrue("built-in" in builtIn.text(), builtIn.text())

        val badType = call(
            protocol, "save_fx_script",
            Json.parseToJsonElement(
                """{"fxId":"typed","name":"T","family":"intensity","outputType":"SLIDER","compatibleAttributes":["dimmer"],
                    "parameters":[{"name":"steps","type":"paletteList","defaultValue":""}],"script":"FxOutput.Slider(0u)"}""",
            ).jsonObject,
        )
        assertTrue(badType.isError(), badType.text())
        assertTrue("paletteList" in badType.text(), badType.text())

        for (fxId in listOf("broken", "wrong", "throws", "typed")) assertNull(storedDefinition(fxId), fxId)
        assertEquals(FxSource.BUILT_IN, state.show.fxRegistry.getRegistration("Wave")?.source)
    }

    @Test
    fun `a saved FX is in the library, previews, and runs in the programmer as a Local FX`() = withScripts {
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
        val protocol = McpProtocol(state)

        val saved = call(protocol, "save_fx_script", saveArguments("half-pulse", FxScriptGuide.STANDARD_EXAMPLE))
        assertFalse(saved.isError(), saved.text())
        assertTrue(saved.json()["created"]!!.jsonPrimitive.boolean)
        assertEquals("255", saved.json()["trialRun"]!!.jsonArray.first().jsonObject["output"]!!.jsonPrimitive.content)
        assertEquals(FxSource.USER, state.show.fxRegistry.getRegistration("half-pulse")?.source)
        assertNotNull(storedDefinition("half-pulse"))

        val listed = call(protocol, "get_fx_script", "{}").json()["fx"]!!.jsonArray
        assertTrue(listed.any { it.jsonObject["fxType"]!!.jsonPrimitive.content == "half-pulse" && it.jsonObject["source"]!!.jsonPrimitive.content == "CUSTOM" })

        val preview = call(protocol, "preview_fx", """{"fxType":"half-pulse","attribute":"dimmer","order":"NORMAL","heads":4,"samples":8}""")
        assertFalse(preview.isError(), preview.text())
        val heads = preview.json()["heads"]!!.jsonArray
        assertEquals(4, heads.size, preview.text())
        assertTrue(heads.all { it.jsonObject["values"]!!.jsonArray.size == 8 }, preview.text())
        assertTrue(heads.first().jsonObject["values"]!!.jsonArray.any { it.jsonPrimitive.content == "0" }, preview.text())

        val applied = call(
            protocol, "apply_fx",
            """{"targets":[{"type":"group","key":"FOH"}],"fxType":"half-pulse","attribute":"dimmer","order":"NORMAL","parameters":{"high":200}}""",
        )
        assertFalse(applied.isError(), applied.text())
        val running = state.show.fxEngine.getFx(applied.json()["fxId"]!!.jsonPrimitive.long)
        assertNotNull(running)
        assertTrue(running.isLocalFx)
        assertEquals("half-pulse", running.registrationId)
        assertEquals("200", running.effect.parameters["high"])

        val replaced = call(protocol, "save_fx_script", saveArguments("half-pulse", FxScriptGuide.STANDARD_EXAMPLE.replace("cos(", "sin(")))
        assertFalse(replaced.isError(), replaced.text())
        assertFalse(replaced.json()["created"]!!.jsonPrimitive.boolean)

        val deleted = call(protocol, "delete_fx_script", """{"fxId":"half-pulse"}""")
        assertFalse(deleted.isError(), deleted.text())
        assertNull(state.show.fxRegistry.getRegistration("half-pulse"))
        assertNull(storedDefinition("half-pulse"))
    }

    @Test
    fun `apply_fx refuses an FX the library lacks and an undeclared argument`() {
        val protocol = McpProtocol(state)
        val unknown = call(protocol, "apply_fx", """{"targets":[{"type":"group","key":"FOH"}],"fxType":"nope","attribute":"dimmer"}""")
        assertTrue(unknown.isError(), unknown.text())
        val legacy = call(protocol, "apply_fx", """{"targets":[{"type":"group","key":"FOH"}],"fxType":"Wave","attribute":"dimmer","seconds":true}""")
        assertTrue(legacy.isError(), legacy.text())
        assertTrue("seconds" in legacy.text(), legacy.text())
    }

    @Test
    fun `preview_fx runs an old FX id as today's, and draws a running FX on its own heads only`() {
        val protocol = McpProtocol(state)
        val legacy = call(protocol, "preview_fx", """{"fxType":"SineWave","attribute":"dimmer","heads":2,"samples":4}""")
        assertFalse(legacy.isError(), legacy.text())
        assertEquals(2, legacy.json()["heads"]!!.jsonArray.size, legacy.text())

        val withHeads = call(protocol, "preview_fx", """{"fxId":1,"heads":4}""")
        assertTrue(withHeads.isError() && "heads" in withHeads.text(), withHeads.text())
    }
}
