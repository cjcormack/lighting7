package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Busk stacks through the MCP tools (busk-stack plan session 1, §3 "Wire, history, MCP"): the two stack
 * tools take the run settings, GO and advance word a release, and `get_current_state` reports them.
 */
class McpBuskStackTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }
    private val setup by lazy { SetupTools(state, tools) }

    private fun call(name: String, arguments: String): ToolExecutionResult = runBlocking {
        val input = Json.parseToJsonElement(arguments).jsonObject
        if (setup.toolDefs.any { it.name == name }) setup.executeTool(name, input) else tools.executeTool(name, input)
    }

    private fun ok(name: String, arguments: String): JsonObject {
        val result = call(name, arguments)
        assertTrue(result.success, "$name: ${result.result}")
        return Json.parseToJsonElement(result.result).jsonObject
    }

    @Test
    fun `create_cue_stack makes a busk stack that releases at its end, and refuses a bad release time`() {
        val song = ok("create_cue_stack", """{"name":"Song","inRunningOrder":false,"releaseTimeMs":3000}""")
        assertFalse(song["inRunningOrder"]!!.jsonPrimitive.boolean)
        assertTrue(song["releaseAtEnd"]!!.jsonPrimitive.boolean, "Release, since it does not loop")
        assertEquals(3000, song["releaseTimeMs"]!!.jsonPrimitive.long)

        val fractional = call("create_cue_stack", """{"name":"X","releaseTimeMs":1500.5}""")
        assertFalse(fractional.success, "a bad release time is named, never the default")
        assertTrue("releaseTimeMs" in fractional.result || "releaseTimeMs" in fractional.description)
        assertFalse(call("create_cue_stack", """{"name":"Y","loop":true,"releaseAtEnd":true}""").success)
    }

    @Test
    fun `build_cue_stack takes the run settings, and go_cue_stack words a release that get_current_state reports`() {
        val built = ok(
            "build_cue_stack",
            """{"stackName":"Carabossy","inRunningOrder":false,"releaseTimeMs":0,"cues":[{"name":"Entrance"}]}""",
        )
        val stackId = built["stackId"]!!.jsonPrimitive.int

        val start = ok("go_cue_stack", """{"stackId":$stackId}""")
        assertFalse(start["released"]!!.jsonPrimitive.boolean)
        val release = call("go_cue_stack", """{"stackId":$stackId}""")
        assertTrue(release.success, release.result)
        assertTrue("released stack $stackId" in release.description, release.description)

        val current = ok("get_current_state", """{"include":["cue_stacks","cue_run"]}""")
        val stack = current["cueStacks"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.int == stackId }
        assertFalse(stack["inRunningOrder"]!!.jsonPrimitive.boolean)
        assertTrue(stack["releaseAtEnd"]!!.jsonPrimitive.boolean)
        assertEquals(0, stack["releaseTimeMs"]!!.jsonPrimitive.long)
        assertTrue(stack["releasedAtEnd"]!!.jsonPrimitive.boolean)
        val run = current["cueRun"]!!.jsonArray.map { it.jsonObject }.single { it["stackId"]!!.jsonPrimitive.int == stackId }
        assertTrue(run["releasedAtEnd"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `create_cue_stack takes Over the show on a busk stack only`() {
        val cara = ok("create_cue_stack", """{"name":"Carabossy","inRunningOrder":false,"overShow":"REPLACES"}""")
        assertEquals("REPLACES", cara["overShow"]!!.jsonPrimitive.content)
        val refused = call("create_cue_stack", """{"name":"Act 1","overShow":"REPLACES"}""")
        assertFalse(refused.success)
        assertTrue("overShow" in refused.result || "overShow" in refused.description)
        assertFalse(call("build_cue_stack", """{"stackName":"Act 2","overShow":"REPLACES","cues":[{"name":"Q1"}]}""").success)
    }

    @Test
    fun `get_current_state names what a replacing stack holds and the FX it holds back`() {
        uk.me.cormack.lighting7.testsupport.LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val act = ok("create_cue_stack", """{"name":"Act 1"}""")["stackId"]!!.jsonPrimitive.int
        val cara = ok("create_cue_stack", """{"name":"Carabossy","inRunningOrder":false,"overShow":"REPLACES"}""")["stackId"]!!.jsonPrimitive.int
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(state.database) {
            for ((stackId, level) in listOf(act to "204", cara to "0")) {
                val cue = uk.me.cormack.lighting7.models.DaoCue.new {
                    project = uk.me.cormack.lighting7.models.DaoProject[projectId]
                    name = "Q$stackId"
                }
                uk.me.cormack.lighting7.models.DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = "hex-1"; attribute = "dimmer"; value = level; sortOrder = 0
                }
                uk.me.cormack.lighting7.models.DaoCueStackEntry.new {
                    stack = uk.me.cormack.lighting7.models.DaoCueStack[stackId]; this.cue = cue; ordinal = 1.0; cueNumber = "1"
                }
            }
        }
        ok("go_cue_stack", """{"stackId":$act}""")
        val chase = state.show.fxEngine.addFx(
            uk.me.cormack.lighting7.fx.FxInstance(
                effect = uk.me.cormack.lighting7.testsupport.WindowedSlider(60u),
                target = uk.me.cormack.lighting7.fx.SliderTarget("hex-1", "dimmer"),
                timing = uk.me.cormack.lighting7.fx.FxTiming(cycle = uk.me.cormack.lighting7.fx.CycleLength.ONE_BEAT),
                blendMode = uk.me.cormack.lighting7.fx.BlendMode.OVERRIDE,
            ),
        )
        ok("go_cue_stack", """{"stackId":$cara}""")

        val current = ok("get_current_state", """{"include":["cue_stacks"]}""")
        val stack = current["cueStacks"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.int == cara }
        assertEquals("REPLACES", stack["overShow"]!!.jsonPrimitive.content)
        val replacing = current["replacing"]!!.jsonArray.single().jsonObject
        assertEquals(cara, replacing["stackId"]!!.jsonPrimitive.int)
        val held = replacing["holds"]!!.jsonArray.single().jsonObject
        assertEquals("hex-1", held["target"]!!.jsonPrimitive.content)
        assertEquals("0", held["value"]!!.jsonPrimitive.content)
        val back = replacing["holdsBackFx"]!!.jsonArray.single().jsonObject
        assertEquals(chase, back["fxId"]!!.jsonPrimitive.long)
        assertEquals(0.0, back["paintsAt"]!!.jsonPrimitive.content.toDouble())
    }
}
