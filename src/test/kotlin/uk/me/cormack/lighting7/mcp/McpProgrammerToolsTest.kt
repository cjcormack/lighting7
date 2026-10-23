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
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tools that write the programmer: plain values, Clear and Release, Blind, a template's click and
 * ⌥click, and Record into a Look — each through the code the desk's own gesture goes through.
 */
class McpProgrammerToolsTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ok(name: String, arguments: String): JsonObject {
        val result = call(name, arguments)
        assertTrue(result.success, "$name: ${result.result}")
        return Json.parseToJsonElement(result.result).jsonObject
    }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private val controller get() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    private fun level(channel: Int) = controller.getEffectiveValue(channel).toInt()

    /** Two dimmers in group FOH on 1 and 2, a third on 3 in no group, and an RGB par. */
    private fun patchRig() {
        val patched = runBlocking {
            SetupTools(state, tools).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[
                        {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1,"groups":["FOH"]},
                        {"key":"foh-2","name":"FOH 2","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":2,"groups":["FOH"]},
                        {"key":"foh-3","name":"FOH 3","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":3},
                        {"key":"hex-1","name":"Hex 1","fixtureTypeKey":"hex","universe":0,"startChannel":10}
                    ]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
    }

    private fun programmerEntries(): List<Triple<String, String, String>> =
        ok("get_current_state", """{"include":["programmer"]}""")["programmer"]!!.jsonObject["entries"]!!.jsonArray.map {
            val entry = it.jsonObject
            Triple(entry["targetKey"]!!.jsonPrimitive.content, entry["propertyName"]!!.jsonPrimitive.content, entry["value"]!!.jsonPrimitive.content)
        }

    @Test
    fun `set_programmer writes values onto the rig and clear_programmer takes them off, by target or whole`() {
        patchRig()
        val set = ok(
            "set_programmer",
            """{"targets":[{"type":"group","key":"FOH"},{"type":"fixture","key":"foh-3"}],"values":[{"propertyName":"dimmer","value":"pct:50"}]}""",
        )
        assertEquals(3, set["written"]!!.jsonArray.size, "$set")
        assertEquals(listOf(128, 128, 128), listOf(level(1), level(2), level(3)))
        assertEquals(
            "FOH",
            set["written"]!!.jsonArray.single { it.jsonObject["targetKey"]!!.jsonPrimitive.content == "foh-1" }.jsonObject["group"]!!.jsonPrimitive.content,
        )

        val released = ok("clear_programmer", """{"targets":[{"type":"fixture","key":"foh-1"}]}""")
        assertEquals(1, released["cleared"]!!.jsonArray.single().jsonObject["values"]!!.jsonPrimitive.int)
        assertEquals(listOf(0, 128, 128), listOf(level(1), level(2), level(3)), "only foh-1 is released")

        val cleared = ok("clear_programmer", "{}")
        assertEquals(2, cleared["valuesCleared"]!!.jsonPrimitive.int)
        assertEquals(listOf(0, 0, 0), listOf(level(1), level(2), level(3)))
        assertTrue(programmerEntries().isEmpty())
    }

    @Test
    fun `a colour on a fixture whose colour is per zone is held on every zone`() {
        val patched = runBlocking {
            SetupTools(state, tools).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[{"key":"shehds-1","name":"Shehds 1","fixtureTypeKey":"shehds-led19-rgbw-24ch","universe":0,"startChannel":80}]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
        val set = ok(
            "set_programmer",
            """{"targets":[{"type":"fixture","key":"shehds-1"}],"values":[{"propertyName":"rgbColour","value":"#0000ff"}]}""",
        )
        assertEquals(
            listOf("shehds-1.zone-1", "shehds-1.zone-2", "shehds-1.zone-3"),
            set["written"]!!.jsonArray.map { it.jsonObject["targetKey"]!!.jsonPrimitive.content },
        )
        assertTrue(set["skipped"]?.jsonArray.isNullOrEmpty(), "$set")
        // Zone n's blue is at 90 + 4(n − 1).
        assertEquals(listOf(255, 255, 255), listOf(level(90), level(94), level(98)))
    }

    @Test
    fun `a head that cannot take a value is skipped by name, and a value nothing can take writes nothing`() {
        patchRig()
        val set = ok(
            "set_programmer",
            """{"targets":[{"type":"fixture","key":"foh-1"},{"type":"fixture","key":"hex-1"}],"values":[{"propertyName":"rgbColour","value":"#ff0000"}]}""",
        )
        assertEquals(listOf("hex-1"), set["written"]!!.jsonArray.map { it.jsonObject["targetKey"]!!.jsonPrimitive.content })
        val skip = set["skipped"]!!.jsonArray.single().jsonObject
        assertEquals("foh-1", skip["targetKey"]!!.jsonPrimitive.content)
        assertEquals("rgbColour", skip["propertyName"]!!.jsonPrimitive.content)

        ok("clear_programmer", "{}")
        for ((arguments, expected) in listOf(
            """{"targets":[{"type":"fixture","key":"foh-1"}],"values":[{"propertyName":"gobo","value":"cone"}]}""" to "no selected head can take gobo",
            """{"targets":[{"type":"fixture","key":"foh-1"}],"values":[{"propertyName":"dimmer","value":"#ff0000"}]}""" to "is not a value dimmer takes",
            """{"targets":[{"type":"fixture","key":"nope"}],"values":[{"propertyName":"dimmer","value":"pct:50"}]}""" to "no fixture 'nope'",
            """{"targets":[{"type":"fixture","key":"foh-1"}],"values":[{"propertyName":"dimmer","value":"pct:50"},{"propertyName":"dimmer","value":"pct:10"}]}""" to "already set by values[0]",
            """{"targets":[{"type":"fixture","key":"foh-1"}],"values":[{"propertyName":"dimmer","value":"pct:50"}],"fadeSeconds":-1}""" to "fadeSeconds must be between",
            """{"values":[{"propertyName":"dimmer","value":"pct:50"}]}""" to "Missing 'targets'",
        )) {
            val refused = call("set_programmer", arguments)
            assertFalse(refused.success, arguments)
            assertTrue(expected in refused.description, "$arguments → ${refused.description}")
            assertTrue(refused.json()["error"] != null)
        }
        assertTrue(programmerEntries().isEmpty(), "nothing was written")

        val unknown = call("clear_programmer", """{"targets":[{"type":"group","key":"Nope"}]}""")
        assertFalse(unknown.success)
        assertTrue("no group 'Nope'" in unknown.description, unknown.description)
    }

    @Test
    fun `set_programmer sets a colour with its emitters, pan and tilt in DMX, a wheel slot and a gobo`() {
        val patched = runBlocking {
            SetupTools(state, tools).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[
                        {"key":"hex-1","name":"Hex 1","fixtureTypeKey":"hex","universe":0,"startChannel":10},
                        {"key":"mac-1","name":"MAC 1","fixtureTypeKey":"martin-mac-250-mode-4","universe":0,"startChannel":30}
                    ]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
        // Hex: R G B on 11-13, amber 14, white 15, UV 16. MAC: colour wheel 32, gobo 33, pan 37, tilt 39.
        fun hex() = (11..16).map(::level)

        val colour = ok("set_programmer", """{"targets":[{"type":"fixture","key":"hex-1"}],"values":[{"propertyName":"rgbColour","value":"#ff8000;w120;uv40"}]}""")
        assertEquals("#ff8000;w120;uv40", colour["written"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(listOf(255, 128, 0, 0, 120, 40), hex())

        // A colour carries its emitters, so a later one resets them; an emitter beside it in the same call wins.
        ok("set_programmer", """{"targets":[{"type":"fixture","key":"hex-1"}],"values":[{"propertyName":"amber","value":"dmx:90"},{"propertyName":"rgbColour","value":"#00ff00"}]}""")
        assertEquals(listOf(0, 255, 0, 90, 0, 0), hex())

        ok(
            "set_programmer",
            """{"targets":[{"type":"fixture","key":"mac-1"}],"values":[
                {"propertyName":"pan","value":"dmx:10"},{"propertyName":"tilt","value":"pct:25"},
                {"propertyName":"rgbColour","value":"#ff0000;w128"},{"propertyName":"gobo","value":"cone"}
            ]}""",
        )
        assertEquals(listOf(10, 64), listOf(level(37), level(39)))
        assertEquals(176, level(32), "a colour lands on the wheel's nearest slot, its white left out")
        assertEquals(10, level(33))
        assertEquals(
            setOf(Triple("mac-1", "pan", "10"), Triple("mac-1", "tilt", "64"), Triple("mac-1", "colourWheel", "176"), Triple("mac-1", "gobo", "10")),
            programmerEntries().filter { it.first == "mac-1" }.toSet(),
        )

        for (value in listOf("#ff0000;w300", "#ff0000;w1;w2", "#ff0000;sparkle", "#ff0000;policy=loud", "red;w10")) {
            val refused = call("set_programmer", """{"targets":[{"type":"fixture","key":"hex-1"}],"values":[{"propertyName":"rgbColour","value":"$value"}]}""")
            assertFalse(refused.success, value)
            assertTrue("is not a value rgbColour takes" in refused.description, "$value → ${refused.description}")
        }
        assertEquals(listOf(0, 255, 0, 90, 0, 0), hex(), "a refused colour writes nothing")
    }

    @Test
    fun `a malformed or empty targets never widens to the whole programmer, and a null fade is no fade`() {
        patchRig()
        ok("set_programmer", """{"targets":[{"type":"group","key":"FOH"}],"values":[{"propertyName":"dimmer","value":"pct:50"}],"fadeSeconds":null}""")
        assertEquals(listOf(128, 128), listOf(level(1), level(2)))

        for ((name, arguments, expected) in listOf(
            Triple("clear_programmer", """{"targets":"FOH"}""", "'targets' must be an array"),
            Triple("clear_programmer", """{"targets":{"type":"group","key":"FOH"}}""", "'targets' must be an array"),
            Triple("clear_programmer", """{"targets":[]}""", "omit it to clear everything"),
            Triple("record_look", """{"name":"Wide","targets":"foh-1"}""", "'targets' must be an array"),
            Triple("record_look", """{"name":{"text":"Wide"}}""", "'name' must be a string"),
            Triple("record_look", """{"name":"Wide","mask":"COLOUR"}""", "'mask' must be an array"),
            Triple("apply_palette", """{"paletteName":["Half"]}""", "'paletteName' must be a string"),
            Triple("set_programmer", """{"targets":[{"type":"group","key":"FOH"}],"values":[{"propertyName":{},"value":"pct:10"}]}""", "'propertyName' must be a string"),
            Triple("set_blind", """{"blind":"yes"}""", "'blind' must be true or false"),
        )) {
            val refused = call(name, arguments)
            assertFalse(refused.success, "$name $arguments")
            assertTrue(expected in refused.description, "$name $arguments → ${refused.description}")
            assertTrue(refused.json()["error"] != null)
        }
        assertEquals(listOf(128, 128), listOf(level(1), level(2)), "nothing was cleared")
        assertEquals(0L, transaction(state.database) { DaoLook.all().count() }, "nothing was recorded")

        ok("clear_programmer", """{"fadeSeconds":null}""")
        assertEquals(listOf(0, 0), listOf(level(1), level(2)))
    }

    @Test
    fun `a group write records back through record_cue as one group row`() {
        patchRig()
        ok("set_programmer", """{"targets":[{"type":"group","key":"FOH"}],"values":[{"propertyName":"dimmer","value":"pct:50"}]}""")
        val stackId = ok("create_cue_stack", """{"name":"Main"}""")["stackId"]!!.jsonPrimitive.int
        val recorded = ok("record_cue", """{"cueStackId":$stackId,"name":"Q1"}""")
        val cueId = recorded["cueId"]!!.jsonPrimitive.int

        val rows = transaction(state.database) {
            DaoCue[cueId].propertyAssignments.map { listOf(it.targetType, it.targetKey, it.attribute, it.value) }
        }
        assertEquals(listOf(listOf("group", "FOH", "dimmer", "128")), rows)
    }

    @Test
    fun `Blind keeps the stage as it was while the programmer holds and takes values`() {
        patchRig()
        ok("set_programmer", """{"targets":[{"type":"fixture","key":"foh-1"}],"values":[{"propertyName":"dimmer","value":"dmx:200"}]}""")
        assertEquals(200, level(1))

        assertEquals("true", ok("set_blind", """{"blind":true}""")["blind"]!!.jsonPrimitive.content)
        assertEquals(0, level(1), "the programmer is off stage")
        ok("set_programmer", """{"targets":[{"type":"fixture","key":"foh-2"}],"values":[{"propertyName":"dimmer","value":"dmx:100"}]}""")
        assertEquals(0, level(2), "a value set blind is held, not shown")
        assertEquals(
            setOf(Triple("foh-1", "dimmer", "200"), Triple("foh-2", "dimmer", "100")),
            programmerEntries().toSet(),
        )
        assertEquals("true", ok("get_current_state", """{"include":["programmer"]}""")["programmer"]!!.jsonObject["blind"]!!.jsonPrimitive.content)

        assertEquals("false", ok("set_blind", """{"blind":false}""")["blind"]!!.jsonPrimitive.content)
        assertEquals(listOf(200, 100), listOf(level(1), level(2)))

    }

    @Test
    fun `the palette tools are offered under their palette names, and the template names are gone`() {
        val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")
        val listed = runBlocking {
            McpProtocol(state).handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("apply_palette" in listed && "create_palette" in listed, listed.keys.toString())
        assertTrue(listed.keys.none { "template" in it }, listed.keys.toString())
        val apply = listed.getValue("apply_palette").jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(setOf("paletteId", "paletteName"), apply.keys.filter { it.startsWith("palette") || it.startsWith("template") }.toSet())
        val include = listed.getValue("get_current_state").jsonObject["inputSchema"]!!.jsonObject["properties"]!!
            .jsonObject["include"]!!.jsonObject["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("palettes" in include && "templates" !in include, include.toString())
    }

    @Test
    fun `apply_palette puts on a layer that toggles, or literals with asValues`() {
        patchRig()
        ok("create_palette", """{"name":"Half","rows":[{"propertyName":"dimmer","value":"pct:50"}]}""")

        val bare = call("apply_palette", """{"paletteName":"Half"}""")
        assertFalse(bare.success)
        assertEquals("PALETTE_NEEDS_SELECTION", bare.json()["code"]!!.jsonPrimitive.content)
        val masked = call("apply_palette", """{"paletteName":"Half","targets":[{"type":"group","key":"FOH"}],"mask":["POSITION"]}""")
        assertFalse(masked.success)
        assertEquals("PALETTE_OUTSIDE_MASK", masked.json()["code"]!!.jsonPrimitive.content)
        val maskedValues = call("apply_palette", """{"paletteName":"Half","targets":[{"type":"group","key":"FOH"}],"asValues":true,"mask":["POSITION"]}""")
        assertEquals("PALETTE_OUTSIDE_MASK", maskedValues.json()["code"]!!.jsonPrimitive.content)
        assertFalse(call("apply_palette", """{"paletteName":"Nope","targets":[{"type":"group","key":"FOH"}]}""").success)

        val literals = ok("apply_palette", """{"paletteName":"Half","targets":[{"type":"group","key":"FOH"}],"asValues":true}""")
        assertEquals(2, literals["written"]!!.jsonPrimitive.int)
        assertEquals(listOf(128, 128), listOf(level(1), level(2)))
        assertEquals(setOf("foh-1", "foh-2"), programmerEntries().map { it.first }.toSet(), "literals sit in the programmer")
        ok("clear_programmer", "{}")
        assertEquals(0, level(1))

        val on = ok("apply_palette", """{"paletteName":"Half","targets":[{"type":"group","key":"FOH"}]}""")
        assertEquals("applied", on["action"]!!.jsonPrimitive.content)
        assertEquals(listOf(128, 128), listOf(level(1), level(2)))
        val layers = ok("get_current_state", """{"include":["programmer"]}""")["programmer"]!!.jsonObject["layers"]!!.jsonArray
        assertEquals("Half", layers.single().jsonObject["sourceName"]!!.jsonPrimitive.content)
        assertEquals("PALETTE", layers.single().jsonObject["sourceKind"]!!.jsonPrimitive.content, "Claude reads palette, not the stored kind")
        val off = ok("apply_palette", """{"paletteName":"Half","targets":[{"type":"group","key":"FOH"}]}""")
        assertEquals("removed", off["action"]!!.jsonPrimitive.content)
        assertEquals(listOf(0, 0), listOf(level(1), level(2)))
    }

    @Test
    fun `a per-fixture template with no targets lands on its own heads`() {
        patchRig()
        val templateId = ok(
            "create_palette",
            """{"name":"Special","rows":[{"targetKey":"foh-3","propertyName":"dimmer","value":"pct:100"}]}""",
        )["paletteId"]!!.jsonPrimitive.int
        ok("apply_palette", """{"paletteId":$templateId}""")
        assertEquals(listOf(0, 0, 255), listOf(level(1), level(2), level(3)))
        ok("clear_programmer", "{}")
        ok("apply_palette", """{"paletteId":$templateId,"asValues":true}""")
        assertEquals(listOf(0, 0, 255), listOf(level(1), level(2), level(3)), "as values too")
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `apply_palette refuses the retired mode rather than guessing`() {
        patchRig()
        ok("create_palette", """{"name":"Half","rows":[{"propertyName":"dimmer","value":"pct:50"}]}""")

        val old = call("apply_palette", """{"paletteName":"Half","targets":[{"type":"group","key":"FOH"}],"mode":"LITERALS"}""")

        assertFalse(old.success)
        assertTrue(old.result.contains("asValues"), old.result)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `record_look makes a new Look from the programmer and merges into it`() {
        patchRig()
        ok(
            "set_programmer",
            """{"targets":[{"type":"group","key":"FOH"}],"values":[{"propertyName":"dimmer","value":"pct:50"}]}""",
        )
        ok("set_programmer", """{"targets":[{"type":"fixture","key":"foh-3"}],"values":[{"propertyName":"dimmer","value":"dmx:200"}]}""")

        val created = ok(
            "record_look",
            """{"name":"Warm","targets":[{"type":"group","key":"FOH"},{"type":"fixture","key":"foh-3"}]}""",
        )
        assertEquals("true", created["created"]!!.jsonPrimitive.content)
        val expected = setOf(
            LookRowDto("group", "FOH", "dimmer", "128"),
            LookRowDto("fixture", "foh-3", "dimmer", "200"),
        )
        assertEquals(expected, storedRows("Warm"))
        assertEquals(
            expected,
            created["rows"]!!.jsonArray.map {
                val row = it.jsonObject
                LookRowDto(row["targetType"]!!.jsonPrimitive.content, row["targetKey"]!!.jsonPrimitive.content, row["propertyName"]!!.jsonPrimitive.content, row["value"]!!.jsonPrimitive.content)
            }.toSet(),
            "the answer carries the Look's rows",
        )

        val lookId = created["lookId"]!!.jsonPrimitive.int
        ok("set_programmer", """{"targets":[{"type":"fixture","key":"foh-3"}],"values":[{"propertyName":"dimmer","value":"dmx:100"}]}""")
        val merged = ok("record_look", """{"mode":"MERGE","lookId":$lookId,"targets":[{"type":"fixture","key":"foh-3"}]}""")
        assertEquals("false", merged["created"]!!.jsonPrimitive.content)
        assertEquals(
            setOf(LookRowDto("group", "FOH", "dimmer", "128"), LookRowDto("fixture", "foh-3", "dimmer", "100")),
            storedRows("Warm"),
        )

        for ((arguments, expected) in listOf(
            """{}""" to "CREATE requires a name",
            """{"mode":"MERGE"}""" to "MERGE requires lookId",
            """{"name":"Warm"}""" to "already exists",
            """{"mode":"SIDEWAYS","name":"X"}""" to "Unknown record mode",
        )) {
            val refused = call("record_look", arguments)
            assertFalse(refused.success, arguments)
            assertTrue(expected in refused.description, "$arguments → ${refused.description}")
        }
    }

    @Test
    fun `on a venue record_look is refused and the programmer tools still work`() {
        patchRig()
        transaction(state.database) { state.projectManager.currentProject.kind = ProjectKind.VENUE }
        val protocol = McpProtocol(state)
        val admin = AuthenticatedUser(1, UUID.randomUUID(), "admin", "Admin", UserRole.ADMIN, "test")
        fun callAs(name: String, arguments: String): JsonObject = runBlocking {
            protocol.handle(
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""),
                admin,
            )
        }!!["result"]!!.jsonObject

        val set = callAs("set_programmer", """{"targets":[{"type":"fixture","key":"foh-1"}],"values":[{"propertyName":"dimmer","value":"pct:100"}]}""")
        assertEquals("false", set["isError"]!!.jsonPrimitive.content, "$set")
        assertEquals(255, level(1))
        val record = callAs("record_look", """{"name":"Test"}""")
        assertEquals("true", record["isError"]!!.jsonPrimitive.content)
        assertTrue("Record is off in a venue" in record.toString(), "$record")
        assertEquals("false", callAs("clear_programmer", "{}")["isError"]!!.jsonPrimitive.content)
    }

    private fun storedRows(name: String): Set<LookRowDto> = transaction(state.database) {
        DaoLook.find { DaoLooks.name eq name }.single().rows.map {
            LookRowDto(it.targetType, it.targetKey, it.attribute, it.value, elementKey = it.elementKey)
        }.toSet()
    }
}
