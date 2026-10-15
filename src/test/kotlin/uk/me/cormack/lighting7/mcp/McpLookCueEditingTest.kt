package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignments
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Editing what `create_look` and `create_cue` made: `update_look` and `delete_look`, a cue's own
 * values, and cue layers applying a template rather than a Look.
 */
class McpLookCueEditingTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }
    private val setup by lazy { SetupTools(state, tools) }

    private fun call(name: String, arguments: String): ToolExecutionResult = runBlocking {
        val input = Json.parseToJsonElement(arguments).jsonObject
        if (setup.toolDefs.any { it.name == name }) setup.executeTool(name, input) else tools.executeTool(name, input)
    }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ok(name: String, arguments: String): JsonObject {
        val result = call(name, arguments)
        assertTrue(result.success, "$name: ${result.result}")
        return result.json()
    }

    private fun refused(name: String, arguments: String, expected: String): ToolExecutionResult {
        val result = call(name, arguments)
        assertFalse(result.success, "$name should be refused: ${result.result}")
        // A setup tool lists its problems in the result; a show-running tool's description is the one.
        assertTrue(expected in result.description || expected in result.result, "$name → ${result.result}")
        assertNotNull(result.json()["error"], "the answer stays valid JSON")
        return result
    }

    /** Two RGB pars in a group and conventionals on channels 70 and 71. */
    private fun patchRig() {
        ok(
            "patch_fixtures",
            """{"fixtures":[
                {"key":"hex-1","name":"Hex 1","fixtureTypeKey":"hex","universe":0,"startChannel":1,"groups":["Wash"]},
                {"key":"hex-2","name":"Hex 2","fixtureTypeKey":"hex","universe":0,"startChannel":20,"groups":["Wash"]},
                {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":70,"groups":["FOH"]},
                {"key":"foh-2","name":"FOH 2","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":71,"groups":["FOH"]}
            ]}""",
        )
    }

    private val controller get() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    private fun level(channel: Int) = controller.getEffectiveValue(channel).toInt()

    private fun foh1(value: String) = """{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"$value"}"""

    private fun look(name: String, vararg values: String): Int =
        ok("create_look", """{"name":"$name","values":[${values.joinToString()}]}""")["lookId"]!!.jsonPrimitive.int

    private fun cue(arguments: String): Int = ok("create_cue", arguments)["cueId"]!!.jsonPrimitive.int

    private val pulse = """{"effectType":"Pulse","category":"dimmer","beatDivision":1.0,"blendMode":"OVERRIDE","parameters":{}}"""

    @Test
    fun `update_look replacing values moves a live cue without a re-fire`() {
        patchRig()
        val lookId = look("FOH", foh1("dmx:200"))
        val cueId = cue("""{"name":"Q1","layers":[{"lookId":$lookId}]}""")
        ok("apply_cue", """{"cueId":$cueId}""")
        assertEquals(200, level(70))

        val updated = ok("update_look", """{"lookId":$lookId,"values":[${foh1("dmx:90")}]}""")
        assertEquals(listOf(cueId), updated["cuesRepublished"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(1, updated["valueCount"]!!.jsonPrimitive.int)
        assertEquals(90, level(70), "the live cue moved with the look")
    }

    @Test
    fun `update_look keeps the uuids of the values and effects it keeps`() {
        patchRig()
        val foh2 = """{"target":{"type":"fixture","key":"foh-2"},"propertyName":"dimmer","value":"dmx:40"}"""
        val hex1 = """{"target":{"type":"fixture","key":"hex-1"},"propertyName":"rgbColour","value":"#ff0000"}"""
        val lookId = ok(
            "create_look",
            """{"name":"Keep","values":[${foh1("dmx:200")},$foh2],"effects":[$pulse]}""",
        )["lookId"]!!.jsonPrimitive.int
        fun uuids() = transaction(state.database) {
            DaoLook[lookId].rows.associate { it.targetKey to it.uuid } to DaoLook[lookId].effects.map { it.uuid }
        }
        val (rowsBefore, effectsBefore) = uuids()

        val slower = pulse.replace("\"beatDivision\":1.0", "\"beatDivision\":2.0")
        ok("update_look", """{"lookId":$lookId,"values":[${foh1("dmx:90")},$hex1],"effects":[$slower]}""")

        val (rowsAfter, effectsAfter) = uuids()
        assertEquals(rowsBefore.getValue("foh-1"), rowsAfter.getValue("foh-1"), "changed in place")
        assertFalse("foh-2" in rowsAfter, "dropped")
        assertFalse(rowsAfter.getValue("hex-1") in rowsBefore.values, "added with a fresh uuid")
        assertEquals(effectsBefore, effectsAfter)
        transaction(state.database) {
            assertEquals("90", DaoLook[lookId].rows.single { it.targetKey == "foh-1" }.value)
            assertEquals(2.0, DaoLook[lookId].effects.single().beatDivision)
        }
    }

    @Test
    fun `update_look replaces only the parts it is given`() {
        patchRig()
        val lookId = ok(
            "create_look",
            """{"name":"Both","description":"old","values":[${foh1("dmx:200")}],"effects":[$pulse]}""",
        )["lookId"]!!.jsonPrimitive.int

        ok("update_look", """{"lookId":$lookId,"name":"Renamed","description":null}""")
        transaction(state.database) {
            val look = DaoLook[lookId]
            assertEquals("Renamed", look.name)
            assertNull(look.notes)
            assertEquals(listOf("200"), look.rows.map { it.value }, "values kept")
            assertEquals(1, look.effects.count().toInt(), "effects kept")
        }

        ok("update_look", """{"lookId":$lookId,"effects":[]}""")
        assertNull(ok("update_look", """{"lookId":$lookId,"effects":[$pulse]}""")["warnings"], "no cue layers it")
        cue("""{"name":"Bare","values":[${foh1("dmx:1")}],"layers":[{"lookId":$lookId,"targets":[{"type":"fixture","key":"foh-1"}]}]}""")
        ok("update_look", """{"lookId":$lookId,"effects":[]}""")
        cue("""{"name":"Untargeted","layers":[{"lookId":$lookId}]}""")
        val warned = ok("update_look", """{"lookId":$lookId,"effects":[$pulse]}""")["warnings"]!!.jsonArray
        assertEquals(1, warned.size, "$warned")
        assertTrue("cue 'Untargeted'" in warned.single().jsonPrimitive.content, "$warned")
        ok("update_look", """{"lookId":$lookId,"effects":[]}""")
        transaction(state.database) {
            assertEquals(0, DaoLook[lookId].effects.count().toInt(), "[] empties the effects")
            assertEquals(1, DaoLook[lookId].rows.count().toInt(), "values still kept")
        }
    }

    @Test
    fun `update_look refuses a taken or blank name, a bad value, an unknown look and an empty edit, writing nothing`() {
        patchRig()
        look("Taken", foh1("dmx:10"))
        val lookId = look("Mine", foh1("dmx:20"))

        refused("update_look", """{"lookId":$lookId,"name":"Taken"}""", "already exists")
        refused("update_look", """{"lookId":$lookId,"name":"  "}""", "must not be blank")
        refused("update_look", """{"lookId":$lookId,"name":"New","values":[${foh1("#ff0000")}]}""", "is not a value dimmer takes")
        refused("update_look", """{"lookId":9999,"name":"New"}""", "Look not found")
        refused("update_look", """{"lookId":$lookId}""", "Give 'name'")
        transaction(state.database) {
            assertEquals("Mine", DaoLook[lookId].name)
            assertEquals(listOf("20"), DaoLook[lookId].rows.map { it.value })
        }
    }

    @Test
    fun `delete_look is refused while a cue layers it, and force deletes the layers with it`() {
        patchRig()
        val lookId = look("FOH", foh1("dmx:200"))
        val cueId = cue("""{"name":"Q1","layers":[{"lookId":$lookId}]}""")

        val inUse = refused("delete_look", """{"lookId":$lookId}""", "still used by 1 cue layer(s) in Q1")
        assertEquals("LOOK_IN_USE", inUse.json()["code"]!!.jsonPrimitive.content)
        assertEquals(listOf(cueId), inUse.json()["cueIds"]!!.jsonArray.map { it.jsonPrimitive.int })
        transaction(state.database) { assertNotNull(DaoLook.findById(lookId)) }

        ok("delete_look", """{"lookId":$lookId,"force":true}""")
        transaction(state.database) {
            assertNull(DaoLook.findById(lookId))
            assertTrue(DaoCueLayer.find { DaoCueLayers.cue eq cueId }.empty(), "the cue's layer went with it")
        }
        refused("delete_look", """{"lookId":$lookId}""", "Look not found")
    }

    @Test
    fun `a forced delete_look takes the look off a live cue and announces the cues it changed`() {
        patchRig()
        val lookId = look("FOH 1", foh1("dmx:200"))
        val otherId = look("FOH 2", """{"target":{"type":"fixture","key":"foh-2"},"propertyName":"dimmer","value":"dmx:120"}""")
        val live = cue("""{"name":"Q1","layers":[{"lookId":$lookId},{"lookId":$otherId}]}""")
        val dark = cue("""{"name":"Q2","layers":[{"lookId":$lookId}]}""")
        val unrelated = cue("""{"name":"Q3","layers":[{"lookId":$otherId}]}""")
        ok("apply_cue", """{"cueId":$live}""")
        assertEquals(200, level(70))
        assertEquals(120, level(71))

        val recomposed = mutableListOf<List<Int>>()
        var cueLists = 0
        var stackLists = 0
        val listener = object : FixturesChangeListener {
            override fun cuesRecomposed(cueIds: List<Int>) { recomposed += cueIds }
            override fun cueListChanged() { cueLists++ }
            override fun cueStackListChanged() { stackLists++ }
        }
        state.show.fixtures.registerListener(listener)
        try {
            ok("delete_look", """{"lookId":$lookId,"force":true}""")
            assertEquals(0, level(70), "the live cue no longer transmits the deleted look, without a re-fire")
            assertEquals(120, level(71), "the live cue's other layer stays on stage")
            assertEquals(setOf(live, dark), recomposed.single().toSet(), "live and dark cues that layered it")
            assertTrue(unrelated !in recomposed.single())
            assertTrue(cueLists > 0, "a cue lost a layer, so the cue list is announced")
            assertTrue(stackLists > 0, "and so is the stack list, which carries each cue's layer count")

            recomposed.clear()
            cueLists = 0
            stackLists = 0
            ok("delete_look", """{"lookId":${look("Unused", foh1("dmx:10"))},"force":true}""")
            assertTrue(recomposed.isEmpty(), "a look no cue layers recomposes nothing")
            assertEquals(0, cueLists, "and changes no cue")
            assertEquals(0, stackLists)
        } finally {
            state.show.fixtures.unregisterListener(listener)
        }
    }

    @Test
    fun `a forced delete_look empties a live cue it was the only layer of, and the cue still runs`() {
        patchRig()
        val lookId = look("FOH 1", foh1("dmx:200"))
        val cueId = cue("""{"name":"Q1","layers":[{"lookId":$lookId}]}""")
        ok("apply_cue", """{"cueId":$cueId}""")
        assertEquals(200, level(70))

        ok("delete_look", """{"lookId":$lookId,"force":true}""")
        assertEquals(0, level(70), "the cue's only layer went, so it asserts nothing")

        ok("apply_cue", """{"cueId":$cueId}""")
        assertEquals(0, level(70), "re-firing the emptied cue lands nothing")
        ok("stop_cue", """{"cueId":$cueId}""")
    }

    @Test
    fun `delete_look takes a pressed look out of the programmer`() {
        patchRig()
        val lookId = ok("create_look", """{"name":"FOH","values":[${foh1("dmx:150")}],"apply":true}""")["lookId"]!!.jsonPrimitive.int
        assertEquals(150, level(70))
        ok("delete_look", """{"lookId":$lookId}""")
        assertEquals(0, level(70), "its programmer layer was released")
    }

    @Test
    fun `a cue's own values beat every layer`() {
        patchRig()
        val lookId = look("FOH", foh1("dmx:200"), """{"target":{"type":"fixture","key":"foh-2"},"propertyName":"dimmer","value":"dmx:120"}""")
        val created = ok(
            "create_cue",
            """{"name":"Q1","values":[${foh1("dmx:50")}],"layers":[{"lookId":$lookId}]}""",
        )
        assertEquals(1, created["valueCount"]!!.jsonPrimitive.int)
        ok("apply_cue", """{"cueId":${created["cueId"]!!.jsonPrimitive.int}}""")
        assertEquals(50, level(70), "the cue's own value, over the layer")
        assertEquals(120, level(71), "the layer still lands where the cue says nothing")
    }

    @Test
    fun `build_cue_stack takes values in the current project and refuses them in another`() {
        patchRig()
        val built = ok(
            "build_cue_stack",
            """{"stackName":"Act 1","cues":[{"number":"1","name":"Up","values":[{"target":{"type":"group","key":"FOH"},"propertyName":"dimmer","value":"pct:100"}]}]}""",
        )
        val cueId = built["cues"]!!.jsonArray.single().jsonObject["cueId"]!!.jsonPrimitive.int
        transaction(state.database) {
            val rows = DaoCuePropertyAssignment.find { DaoCuePropertyAssignments.cue eq cueId }.map { Triple(it.targetType, it.targetKey, it.value) }
            assertEquals(listOf(Triple("group", "FOH", "255")), rows)
        }
        ok("go_cue_stack", """{"stackId":${built["stackId"]!!.jsonPrimitive.int}}""")
        assertEquals(255, level(70))
        assertEquals(255, level(71))

        val other = ok("create_project", """{"name":"Other show"}""")["projectId"]!!.jsonPrimitive.int
        refused(
            "build_cue_stack",
            """{"projectId":$other,"stackName":"Act 1","cues":[{"name":"Up","values":[${foh1("pct:100")}]}]}""",
            "only the current project has",
        )
        ok("build_cue_stack", """{"projectId":$other,"stackName":"Act 1","cues":[{"name":"Up"}]}""")
    }

    @Test
    fun `a template layer lands its values on the layer's targets`() {
        patchRig()
        val templateId = ok(
            "create_palette",
            """{"name":"Low","rows":[{"propertyName":"dimmer","value":"pct:40"}]}""",
        )["paletteId"]!!.jsonPrimitive.int
        val cueId = cue("""{"name":"Q1","layers":[{"paletteId":$templateId,"targets":[{"type":"fixture","key":"foh-1"}]}]}""")
        transaction(state.database) {
            assertEquals(templateId, DaoCueLayer.find { DaoCueLayers.cue eq cueId }.single().template?.id?.value)
        }
        ok("apply_cue", """{"cueId":$cueId}""")
        assertEquals(102, level(70))
        assertEquals(0, level(71), "only the layer's targets")
    }

    @Test
    fun `a template layer must say what it applies, and a generic one needs targets`() {
        patchRig()
        val lookId = look("FOH", foh1("dmx:200"))
        val values = ok("create_palette", """{"name":"Low","rows":[{"propertyName":"dimmer","value":"pct:40"}]}""")["paletteId"]!!.jsonPrimitive.int
        val effect = ok("create_palette", """{"name":"Throb","effect":$pulse}""")["paletteId"]!!.jsonPrimitive.int
        val before = transaction(state.database) { DaoCue.all().count() }

        refused("create_cue", """{"name":"Q","layers":[{"paletteId":$effect}]}""", "palette $effect is generic")
        refused("create_cue", """{"name":"Q","layers":[{"paletteId":$values}]}""", "palette $values is generic")
        refused("create_cue", """{"name":"Q","layers":[{"paletteId":9999,"targets":[{"type":"fixture","key":"foh-1"}]}]}""", "no palette 9999")
        refused("create_cue", """{"name":"Q","layers":[{"lookId":9999}]}""", "no look 9999")
        refused("create_cue", """{"name":"Q","layers":[{"lookId":$lookId,"paletteId":$values}]}""", "exactly one of lookId or paletteId")
        refused("create_cue", """{"name":"Q","layers":[{"targets":[]}]}""", "exactly one of lookId or paletteId")
        refused("build_cue_stack", """{"stackName":"S","cues":[{"name":"Q","layers":[{"paletteId":$effect}]}]}""", "palette $effect is generic")
        refused("build_cue_stack", """{"stackName":"S","cues":[{"name":"Q","layers":[{"paletteId":9999,"targets":[{"type":"fixture","key":"foh-1"}]}]}]}""", "no palette 9999")
        assertEquals(before, transaction(state.database) { DaoCue.all().count() }, "nothing was written")

        ok("create_cue", """{"name":"Q","layers":[{"paletteId":$effect,"targets":[{"type":"group","key":"FOH"}]}]}""")
    }

    @Test
    fun `an effect missing a required field is refused by the field's name`() {
        patchRig()
        val lookCount = transaction(state.database) { DaoLook.all().count() }
        refused(
            "create_look",
            """{"name":"A","effects":[{"effectType":"Pulse","category":"dimmer","blendMode":"OVERRIDE"}]}""",
            "effects[0] is missing 'beatDivision'",
        )
        refused(
            "create_look",
            """{"name":"A","effects":[$pulse,{"effectType":"Pulse","category":"dimmer","beatDivision":1.0}]}""",
            "effects[1] is missing 'blendMode'",
        )
        refused(
            "create_look",
            """{"name":"A","effects":[{"effectType":"Pulse","category":"dimmer","beatDivision":"fast","blendMode":"OVERRIDE"}]}""",
            "effects[0]: 'beatDivision' must be a number",
        )
        refused("create_palette", """{"name":"T","effect":{"category":"dimmer","beatDivision":1.0,"blendMode":"OVERRIDE"}}""", "effect is missing 'effectType'")
        refused(
            "create_cue",
            """{"name":"Q","adHocEffects":[{"targetType":"fixture","targetKey":"foh-1","effectType":"Pulse","category":"dimmer","beatDivision":1.0}]}""",
            "adHocEffects[0] is missing 'blendMode'",
        )
        val lookId = look("FOH", foh1("dmx:200"))
        refused("update_look", """{"lookId":$lookId,"effects":[{"category":"dimmer","beatDivision":1.0,"blendMode":"OVERRIDE"}]}""", "effects[0] is missing 'effectType'")
        assertEquals(lookCount + 1, transaction(state.database) { DaoLook.all().count() })
        transaction(state.database) { assertTrue(DaoLookEffect.all().empty()) }
    }

    @Test
    fun `an operator cannot update or delete a look in a venue`() {
        val showId = seedVenueReadyShow(state)
        val venueId = ok("extract_venue", """{"fromProjectId":$showId,"name":"Hall"}""")["venueId"]!!.jsonPrimitive.content.toInt()
        runBlocking { state.projectManager.switchProject(venueId) }

        val protocol = McpProtocol(state)
        val operator = AuthenticatedUser(2, UUID.randomUUID(), "op", "Op", UserRole.OPERATOR, "test")
        for ((name, arguments) in listOf("update_look" to """{"lookId":1,"name":"X"}""", "delete_look" to """{"lookId":1}""")) {
            val result = runBlocking {
                protocol.handle(
                    Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""),
                    operator,
                )
            }!!["result"]!!.jsonObject
            assertEquals("true", result["isError"]!!.jsonPrimitive.content, "$name: $result")
            assertTrue("Only an admin can change a venue" in result.toString(), "$name: $result")
        }
    }
}
