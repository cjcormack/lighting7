package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.RigBriefing
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
import uk.me.cormack.lighting7.models.*
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

    private val pulse = """{"targetType":"fixture","targetKey":"foh-1","effectType":"Pulse","category":"intensity","beatDivision":1.0,"blendMode":"OVERRIDE","parameters":{}}"""

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
            assertEquals(2.0, DaoLook[lookId].effects.single().cycle)
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
        // Its effects name their own targets, so a cue layering it with none runs them: nothing to warn of.
        cue("""{"name":"Untargeted","layers":[{"lookId":$lookId}]}""")
        assertNull(ok("update_look", """{"lookId":$lookId,"effects":[$pulse]}""")["warnings"])
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
            assertEquals(templateId, DaoCueLayer.find { DaoCueLayers.cue eq cueId }.single().palette?.id?.value)
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
        // `beatDivision` is not required since venue-visit plan S15: an omitted one is one bar (D16),
        // which `McpEffectTimingTest` pins. The category still is.
        refused(
            "create_look",
            """{"name":"A","effects":[{"effectType":"Pulse","blendMode":"OVERRIDE"}]}""",
            "effects[0] is missing 'category'",
        )
        refused(
            "create_look",
            """{"name":"A","effects":[$pulse,{"effectType":"Pulse","category":"intensity","beatDivision":1.0}]}""",
            "effects[1] is missing 'blendMode'",
        )
        refused(
            "create_look",
            """{"name":"A","effects":[{"effectType":"Pulse","category":"intensity","beatDivision":"fast","blendMode":"OVERRIDE"}]}""",
            "effects[0]: 'beatDivision' must be a number",
        )
        refused("create_palette", """{"name":"T","effect":{"category":"intensity","beatDivision":1.0,"blendMode":"OVERRIDE"}}""", "effect is missing 'effectType'")
        refused(
            "create_cue",
            """{"name":"Q","adHocEffects":[{"targetType":"fixture","targetKey":"foh-1","effectType":"Pulse","category":"intensity","beatDivision":1.0}]}""",
            "adHocEffects[0] is missing 'blendMode'",
        )
        val lookId = look("FOH", foh1("dmx:200"))
        refused("update_look", """{"lookId":$lookId,"effects":[{"category":"intensity","beatDivision":1.0,"blendMode":"OVERRIDE"}]}""", "effects[0] is missing 'effectType'")
        assertEquals(lookCount + 1, transaction(state.database) { DaoLook.all().count() })
        transaction(state.database) { assertTrue(DaoLookEffect.all().empty()) }
    }

    @Test
    fun `create_cue takes an ad-hoc effect over a selection as one effect`() {
        patchRig()
        val targets = """[{"type":"fixture","key":"foh-2"},{"type":"group","key":"Wash"}]"""
        val effect = """{"targets":$targets,"effectType":"Pulse","category":"intensity","propertyName":"dimmer","beatDivision":1.0,"blendMode":"OVERRIDE","parameters":{}}"""
        val cueId = cue("""{"name":"Ripple","adHocEffects":[$effect]}""")
        transaction(state.database) {
            val stored = DaoCue[cueId].adHocEffects.single()
            assertEquals("selection", stored.targetType)
            assertEquals("""[{"key":"foh-2","type":"fixture"},{"key":"Wash","type":"group"}]""", stored.targetKey)
        }

        ok("apply_cue", """{"cueId":$cueId}""")
        val running = state.show.fxEngine.getActiveEffects().single()
        assertEquals(listOf("foh-2", "hex-1", "hex-2"), state.show.fxEngine.fixtureKeysCoveredBy(running))

        refused(
            "create_cue",
            """{"name":"Q","adHocEffects":[{"targets":$targets,"targetType":"fixture","targetKey":"foh-1","effectType":"Pulse","category":"intensity","blendMode":"OVERRIDE","parameters":{}}]}""",
            "send targets, or targetType and targetKey",
        )
        refused(
            "create_cue",
            """{"name":"Q","adHocEffects":[{"targets":[{"type":"selection","key":"x"}],"effectType":"Pulse","category":"intensity","blendMode":"OVERRIDE","parameters":{}}]}""",
            "type must be 'fixture' or 'group'",
        )
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

    // ─── A cue's places: entries (cue model plan §3.10) ─────────────────

    private fun stack(name: String): Int = ok("create_cue_stack", """{"name":"$name"}""")["stackId"]!!.jsonPrimitive.int

    private fun JsonObject.int(field: String): Int = this[field]!!.jsonPrimitive.int

    private fun JsonObject.text(field: String): String? = this[field]?.jsonPrimitive?.content

    @Test
    fun `create_cue places the cue in a stack with its number and after, or leaves it loose`() {
        val act1 = stack("Act 1")
        val preset = ok("create_cue", """{"name":"Preset","stackId":$act1,"number":"1"}""")["entry"]!!.jsonObject
        assertEquals("1", preset.text("number"))
        assertEquals(act1, preset.int("stackId"))
        ok("create_cue", """{"name":"Dawn","stackId":$act1,"number":"3"}""")
        ok("create_cue", """{"name":"Morning","stackId":$act1,"number":"2","after":{"number":"1"}}""")
        val loose = ok("create_cue", """{"name":"Spare"}""")
        assertNull(loose["entry"], "no stack, no entry: a loose cue")

        refused("create_cue", """{"name":"Clash","stackId":$act1,"number":"2"}""", "already used")
        refused("create_cue", """{"name":"Nowhere","number":"9"}""", "give stackId")
        refused("create_cue", """{"name":"Lost","stackId":$act1,"after":{"number":"99"}}""", "no cue numbered '99'")
        refused("create_cue", """{"name":"True","stackId":$act1,"number":true}""", "number must be the cue number")
        transaction(state.database) {
            assertEquals(listOf("Preset", "Morning", "Dawn"), entriesOf(DaoCueStack[act1]).map { it.displayName })
            assertEquals(listOf("1", "2", "3"), entriesOf(DaoCueStack[act1]).map { it.cueNumber })
            assertTrue(entriesOfCue(DaoCue[loose.int("cueId")]).isEmpty())
            assertTrue(DaoCue.find { DaoCues.name inList listOf("Clash", "Nowhere", "Lost") }.empty(), "a refused placement writes no cue")
        }
    }

    @Test
    fun `a cue shared into two stacks, and twice into one, is addressed by number and by entryId`() {
        patchRig()
        val up = look("Up", foh1("dmx:255"))
        val act1 = stack("Act 1")
        val act2 = stack("Act 2")
        val created = ok("create_cue", """{"name":"Dawn","layers":[{"lookId":$up}],"stackId":$act1,"number":"14"}""")
        val dawn = created.int("cueId")
        val first = created["entry"]!!.jsonObject.int("entryId")
        val shared = ok("add_cue_to_stack", """{"stackId":$act2,"cueId":$dawn,"number":"3"}""")
        assertEquals(act2, shared.int("stackId"))
        assertEquals("3", shared.text("number"))
        val second = ok("add_cue_to_stack", """{"stackId":$act1,"cueId":$dawn,"number":"20","after":{"entryId":$first}}""").int("entryId")
        refused("add_cue_to_stack", """{"stackId":$act1,"cueId":$dawn,"sortOrder":0}""", "sortOrder is gone")
        refused("add_cue_to_stack", """{"stackId":$act2,"cueId":$dawn,"number":"3"}""", "already used")

        // GO to its second place in Act 1 by number, then arm its first by entryId.
        assertEquals(second, ok("activate_cue_stack", """{"stackId":$act1,"number":"20"}""").int("entryId"))
        val armed = ok("set_standby", """{"stackId":$act1,"entryId":$first}""")
        assertEquals(first, armed.int("standbyEntryId"))
        assertEquals(first, armed.int("nextEntryId"))
        assertEquals(second, armed.int("activeEntryId"))
        refused("activate_cue_stack", """{"stackId":$act1,"cueId":$dawn}""", "not by cueId")
        refused("set_standby", """{"stackId":$act1,"number":"99"}""", "no cue numbered '99'")
        refused("set_standby", """{"stackId":$act1,"entryId":$first,"number":"14"}""", "not both")

        val now = ok("get_current_state", """{"include":["cues","cue_run","cue_holds"]}""")
        val places = now["cues"]!!.jsonArray.map { it.jsonObject }.single { it.int("id") == dawn }["entries"]!!.jsonArray
            .map { it.jsonObject.int("stackId") to it.jsonObject.text("number") }
        assertEquals(setOf(act1 to "14", act1 to "20", act2 to "3"), places.toSet())
        val run = now["cueRun"]!!.jsonArray.map { it.jsonObject }.single { it.int("stackId") == act1 }
        assertEquals(second, run.int("activeEntryId"))
        assertEquals(first, run.int("standbyEntryId"))
        val hold = now["cueHolds"]!!.jsonArray.map { it.jsonObject }.single { it.int("cueId") == dawn }["holds"]!!.jsonArray.single().jsonObject
        assertEquals("STACK", hold.text("kind"))
        assertEquals(second, hold.int("entryId"))

        // Removing a place never deletes the cue; its last place leaves it loose.
        val removed = call("remove_cue_from_stack", """{"stackId":$act2,"number":"3"}""")
        assertTrue(removed.success, removed.result)
        assertFalse(removed.json()["cueLeftLoose"]!!.jsonPrimitive.boolean)
        assertTrue("keeps its 2 other place(s)" in removed.description, removed.description)
        val only = cue("""{"name":"Only","stackId":$act2,"number":"1"}""")
        assertTrue(ok("remove_cue_from_stack", """{"stackId":$act2,"number":"1"}""")["cueLeftLoose"]!!.jsonPrimitive.boolean)
        refused("remove_cue_from_stack", """{"stackId":$act2}""", "name the entry")
        transaction(state.database) {
            assertEquals(2, entriesOfCue(DaoCue[dawn]).size)
            assertNotNull(DaoCue.findById(only), "a removed cue stays, loose")
        }
        ok("deactivate_cue_stack", """{"stackId":$act1}""")
    }

    @Test
    fun `move_cue_to_stack moves an entry whole, its number, follow and anchor with it`() {
        val act1 = stack("Act 1")
        val act2 = stack("Act 2")
        transaction(state.database) {
            DaoPromptBook.new {
                project = DaoProject.findById(projectId)!!
                scriptHash = "b".repeat(64)
                pageCount = 4
            }
        }
        val built = ok(
            "build_cue_stack",
            """{"stackId":$act1,"cues":[{"number":"5","name":"Storm","followSeconds":2,"at":{"pdfPage":2,"y":0.5}},{"number":"6","name":"Calm"}]}""",
        )
        val storm = built["cues"]!!.jsonArray[0].jsonObject.int("entryId")
        cue("""{"name":"Clash","stackId":$act2,"number":"6"}""")
        refused("move_cue_to_stack", """{"stackId":$act1,"number":"6","toStackId":$act2}""", "already used")
        refused("move_cue_to_stack", """{"stackId":$act1,"number":"5","toStackId":$act1}""", "already in that stack")

        val moved = ok("move_cue_to_stack", """{"stackId":$act1,"number":"5","toStackId":$act2}""")
        val entry = moved["entry"]!!.jsonObject
        assertEquals(storm, entry.int("entryId"), "the same entry")
        assertEquals(act2, entry.int("stackId"))
        assertEquals("5", entry.text("number"))
        assertTrue(entry["autoAdvance"]!!.jsonPrimitive.boolean)
        assertEquals(2000L, entry["autoAdvanceDelayMs"]!!.jsonPrimitive.long)
        assertTrue(moved["anchored"]!!.jsonPrimitive.boolean)
        transaction(state.database) {
            assertEquals(storm, DaoPromptBookAnchor.all().single().entryId, "the anchor stays on the entry")
            assertEquals(listOf("Calm"), entriesOf(DaoCueStack[act1]).map { it.displayName })
            assertEquals(listOf("Clash", "Storm"), entriesOf(DaoCueStack[act2]).map { it.displayName })
        }
    }

    @Test
    fun `delete_cues names every use without force, writes nothing on a dry run, and deletes with force`() {
        val act1 = stack("Act 1")
        val act2 = stack("Act 2")
        val used = cue("""{"name":"Used","stackId":$act1,"number":"1"}""")
        ok("add_cue_to_stack", """{"stackId":$act2,"cueId":$used}""")
        val spare = cue("""{"name":"Spare"}""")
        transaction(state.database) {
            val project = DaoProject[projectId]
            val page = DaoBuskPage.new { this.project = project; name = "Page 1"; sortOrder = 0 }
            val column = DaoBuskColumn.new { this.page = page; row = 0; sortOrder = 0; width = 12 }
            val bank = DaoBuskBank.new { this.column = column; sortOrder = 0; name = "Bank"; solo = false; flow = BuskFlow.WRAP.name }
            DaoBuskPad.new { this.bank = bank; sortOrder = 0; cue = DaoCue[used] }
            DaoCueSlot.new { this.project = project; this.page = 0; slotIndex = 2; cue = DaoCue[used] }
        }

        val inUse = refused("delete_cues", """{"cueIds":[$spare,$used]}""", "In use").json()
        assertEquals("CUE_IN_USE", inUse.text("code"))
        val uses = inUse["uses"]!!.jsonArray.single().jsonObject
        assertEquals(used, uses.int("cueId"))
        assertEquals(setOf(act1, act2), uses["entries"]!!.jsonArray.map { it.jsonObject.int("stackId") }.toSet())
        assertEquals(1, uses["pads"]!!.jsonArray.size)
        assertEquals(1, uses["slots"]!!.jsonArray.size)

        val dry = ok("delete_cues", """{"cueIds":[$spare,$used],"dryRun":true}""")
        assertTrue(dry["deleted"]!!.jsonArray.isEmpty())
        assertEquals(1, dry["uses"]!!.jsonArray.size)
        refused("delete_cues", """{"cueIds":[$spare,999999]}""", "no cue 999999")
        transaction(state.database) { assertNotNull(DaoCue.findById(spare), "a refusal and a dry run delete nothing") }

        ok("delete_cues", """{"cueIds":[$spare,$used],"force":true}""")
        transaction(state.database) {
            assertNull(DaoCue.findById(used))
            assertNull(DaoCue.findById(spare))
            assertTrue(DaoCueStackEntry.find { DaoCueStackEntries.stack inList listOf(act1, act2) }.empty())
            assertTrue(DaoBuskPad.find { DaoBuskPads.cue eq used }.empty())
            assertTrue(DaoCueSlot.find { DaoCueSlots.cue eq used }.empty())
        }
    }

    @Test
    fun `build_cue_stack shares a cue a row names, and describe_rig reads every place`() {
        val act1 = stack("Act 1")
        val dawn = cue("""{"name":"Dawn","stackId":$act1,"number":"14"}""")
        cue("""{"name":"Spare"}""")
        refused("build_cue_stack", """{"stackName":"Act 2","cues":[{"cueId":$dawn,"fadeSeconds":3}]}""", "would change the cue in every place")
        refused("build_cue_stack", """{"stackName":"Act 2","cues":[{"cueId":999999}]}""", "no cue 999999")

        // A row built from one template may carry empty fields beside cueId: they change nothing.
        val built = ok(
            "build_cue_stack",
            """{"stackName":"Act 2","cues":[{"number":"1","name":"Prologue"},{"cueId":$dawn,"number":"3","followSeconds":1,"layers":[],"notes":"","marker":false}]}""",
        )
        val row = built["cues"]!!.jsonArray[1].jsonObject
        assertEquals(dawn, row.int("cueId"))
        assertTrue(row["shared"]!!.jsonPrimitive.boolean)
        assertEquals("3", row.text("number"))
        assertNull(row["marker"], "a cue row is no marker")
        assertNull(built["cues"]!!.jsonArray[0].jsonObject["marker"])
        transaction(state.database) {
            assertEquals(2, entriesOfCue(DaoCue[dawn]).size)
            assertEquals(1, DaoCue.find { DaoCues.name eq "Dawn" }.count(), "shared, not copied")
        }

        val rig = RigBriefing(state).describeRig()
        assertTrue("**Dawn** (id=$dawn" in rig && "— in: Act 1 Q14, Act 2 Q3" in rig, rig)
        assertTrue(Regex("""\*\*Spare\*\* \(id=\d+.*\) — loose""").containsMatchIn(rig), rig)
        assertTrue("delete_cues" in RigBriefing(state).keyConcepts())
    }

    @Test
    fun `include_into_programmer names the entry it is included from`() {
        patchRig()
        val up = look("Up", foh1("dmx:200"))
        val act1 = stack("Act 1")
        val act2 = stack("Act 2")
        val dawn = cue("""{"name":"Dawn","layers":[{"lookId":$up}],"stackId":$act1,"number":"14"}""")
        val there = ok("add_cue_to_stack", """{"stackId":$act2,"cueId":$dawn,"number":"3"}""").int("entryId")

        assertEquals(there, ok("include_into_programmer", """{"cueId":$dawn,"stackId":$act2,"number":"3"}""").int("entryId"))
        assertEquals(there, state.show.programmerStore.lastIncludedTarget?.entryId)
        refused("include_into_programmer", """{"lookId":$up,"entryId":$there}""", "give cueId too")
        refused("include_into_programmer", """{"cueId":$dawn,"number":"3"}""", "give stackId too")
        refused("include_into_programmer", """{"cueId":$dawn,"entryId":"$there"}""", "entryId must be an integer")
        refused("include_into_programmer", """{"cueId":$dawn,"entryId":$there,"stackId":$act1}""", "not stack $act1")
        ok("clear_programmer", "{}")
    }
}
