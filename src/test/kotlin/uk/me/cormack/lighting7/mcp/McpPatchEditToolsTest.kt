package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.BindingTargetJson
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `unpatch_fixtures`, `rename_fixtures` and `edit_groups` (`ai/PatchEditTools.kt`): the patch edits
 * MCP gained beyond `patch_fixtures`' add-or-update. Each refuses a call whole, answers a dry run
 * without writing, and leaves — or, for a rename, rewrites — what the project's records name.
 */
class McpPatchEditToolsTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ToolExecutionResult.problems(): List<String> =
        json()["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun patchKeys(): List<String> = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.project eq projectId }.map { it.key }.sorted()
    }

    private fun liveKeys(): List<String> = state.show.fixtures.fixtures.map { it.key }.sorted()

    private fun groupMembers(): Map<String, List<String>> = transaction(state.database) {
        DaoFixtureGroup.find { DaoFixtureGroups.project eq projectId }.associate { g ->
            g.name to g.members.sortedBy { it.sortOrder }.map { it.fixturePatch.key }
        }
    }

    /** foh-1 and foh-2 on universe 0, foh-10 alone on universe 1; FOH holds all three, Front wash foh-1. */
    private fun patchRig() {
        val patched = call(
            "patch_fixtures",
            """{"fixtures":[
                {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1,"groups":["FOH","Front wash"]},
                {"key":"foh-2","name":"FOH 2","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":2,"groups":["FOH"]},
                {"key":"foh-10","name":"FOH 10","fixtureTypeKey":"generic-dimmer","universe":1,"startChannel":1,"groups":["FOH"]}
            ]}""",
        )
        assertTrue(patched.success, patched.result)
    }

    private class Seeded(val lookId: Int, val cueId: Int, val bindingId: Int)

    /** A Look, a cue and a surface binding naming the rig, and a script mentioning foh-1. */
    private fun seedReferences(): Seeded = transaction(state.database) {
        val project = DaoProject[projectId]
        val look = DaoLook.new { this.project = project; name = "Warm" }
        DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "foh-1"; propertyName = "dimmer"; value = "200"; sortOrder = 0 }
        DaoLookRow.new {
            this.look = look; targetType = "fixture"; targetKey = "foh-1"; elementKey = "foh-1.cell-2"
            propertyName = "dimmer"; value = "100"; sortOrder = 1
        }
        DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "foh-10"; propertyName = "dimmer"; value = "50"; sortOrder = 2 }
        DaoLookRow.new { this.look = look; targetType = "group"; targetKey = "FOH"; propertyName = "dimmer"; value = "10"; sortOrder = 3 }
        val stack = DaoCueStack.new { this.project = project; name = "Main" }
        val cue = DaoCue.new { this.project = project; name = "Opening"; cueStack = stack; cueNumber = "1" }
        DaoCuePropertyAssignment.new { this.cue = cue; targetType = "fixture"; targetKey = "foh-2"; propertyName = "dimmer"; value = "255" }
        DaoCueLayer.new { this.cue = cue; this.look = look; targets = listOf(CueTargetDto("fixture", "foh-1"), CueTargetDto("group", "FOH")) }
        val binding = DaoControlSurfaceBinding.new {
            this.project = project
            deviceTypeKey = "xtouch-mini"; controlId = "fader-1"
            targetType = "fixtureProperty"
            targetPayload = BindingTargetJson.encodeToString<BindingTarget>(BindingTarget.FixtureProperty("foh-1", "dimmer"))
        }
        DaoScript.new { this.project = project; name = "Chase"; script = """fixture<Fixture>("foh-1")""" }
        Seeded(look.id.value, cue.id.value, binding.id.value)
    }

    private fun lookRows(lookId: Int): List<Triple<String, String, String?>> = transaction(state.database) {
        DaoLookRow.find { DaoLookRows.look eq lookId }.sortedBy { it.sortOrder }.map { Triple(it.targetType, it.targetKey, it.elementKey) }
    }

    @Test
    fun `the MCP server offers the patch edits as writes`() {
        val names = McpProtocol(state).toolDefs.map { it.name }
        for (tool in listOf("unpatch_fixtures", "rename_fixtures", "edit_groups")) assertTrue(tool in names, "$tool is offered")
    }

    @Test
    fun `unpatch_fixtures refuses a call whole and lists every problem`() {
        patchRig()
        val refused = call("unpatch_fixtures", """{"keys":["foh-1","nope","foh-1"],"universes":[{"universe":7}],"colour":"red"}""")
        assertFalse(refused.success, refused.result)
        val problems = refused.problems()
        assertTrue(problems.any { "unknown field 'colour'" in it }, refused.result)
        assertTrue(problems.any { "'foh-1' is listed twice" in it }, refused.result)
        assertFalse(call("unpatch_fixtures", "{}").success, "a call that names nothing is refused")
        assertEquals(listOf("foh-1", "foh-10", "foh-2"), patchKeys(), "a refused call removes nothing")

        val unknown = call("unpatch_fixtures", """{"keys":["nope"],"universes":[{"universe":7}]}""")
        assertTrue(unknown.problems().any { "no fixture is patched as 'nope'" in it }, unknown.result)
        assertTrue(unknown.problems().any { "no universe 7 (subnet 0)" in it }, unknown.result)
    }

    @Test
    fun `unpatch_fixtures removes fixtures and universes, and reports who still names them`() {
        patchRig()
        val seeded = seedReferences()

        val dry = call("unpatch_fixtures", """{"keys":["foh-1"],"universes":[{"universe":1}],"dryRun":true}""")
        assertTrue(dry.success, dry.result)
        val answer = dry.json()
        assertEquals(listOf("foh-1", "foh-10"), answer["removed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(3, answer["groupMembershipsRemoved"]!!.jsonPrimitive.int, "foh-1 in two groups, foh-10 in one")
        val namedBy = answer["namedBy"]!!.jsonArray.map { it.jsonObject }.associateBy { it["key"]!!.jsonPrimitive.content }
        val foh1Users = namedBy.getValue("foh-1")["usedBy"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("LOOK", "CUE", "BINDING", "SCRIPT"), foh1Users, dry.result)
        assertEquals(listOf("LOOK"), namedBy.getValue("foh-10")["usedBy"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content })
        assertEquals(listOf("foh-1", "foh-10", "foh-2"), patchKeys(), "a dry run removes nothing")

        val done = call("unpatch_fixtures", """{"keys":["foh-1"],"universes":[{"universe":1}]}""")
        assertTrue(done.success, done.result)
        assertEquals(listOf("foh-2"), patchKeys())
        assertEquals(mapOf("FOH" to listOf("foh-2"), "Front wash" to emptyList()), groupMembers(), "the groups stay")
        transaction(state.database) {
            assertTrue(DaoUniverseConfig.find { DaoUniverseConfigs.project eq projectId }.none { it.universe == 1 })
        }
        assertEquals(listOf("foh-2"), liveKeys(), "the running rig loses them")
        assertEquals(4, lookRows(seeded.lookId).size, "the Look's rows stay, marked missing")
    }

    @Test
    fun `rename_fixtures rewrites what names the key, cells included, and not a key that only shares its prefix`() {
        patchRig()
        val seeded = seedReferences()

        val dry = call("rename_fixtures", """{"fixtures":[{"key":"foh-1","newKey":"wash-1"},{"key":"foh-2","newKey":"foh-1"}],"dryRun":true}""")
        assertTrue(dry.success, dry.result)
        assertEquals(listOf("foh-1", "foh-10", "foh-2"), patchKeys(), "a dry run renames nothing")
        assertEquals("foh-1", lookRows(seeded.lookId).first().second)

        val done = call("rename_fixtures", """{"fixtures":[{"key":"foh-1","newKey":"wash-1"},{"key":"foh-2","newKey":"foh-1"}]}""")
        assertTrue(done.success, done.result)
        assertEquals(listOf("foh-1", "foh-10", "wash-1"), patchKeys())
        assertEquals(
            listOf(
                Triple("fixture", "wash-1", null),
                Triple("fixture", "wash-1", "wash-1.cell-2"),
                Triple("fixture", "foh-10", null),
                Triple("group", "FOH", null),
            ),
            lookRows(seeded.lookId),
        )
        transaction(state.database) {
            assertEquals("foh-1", DaoCuePropertyAssignment.find { DaoCuePropertyAssignments.cue eq seeded.cueId }.single().targetKey, "foh-2 is foh-1 now")
            assertEquals(
                listOf(CueTargetDto("fixture", "wash-1"), CueTargetDto("group", "FOH")),
                DaoCueLayer.find { DaoCueLayers.cue eq seeded.cueId }.single().targets,
            )
            val binding = BindingTargetJson.decodeFromString<BindingTarget>(DaoControlSurfaceBinding[seeded.bindingId].targetPayload)
            assertEquals(BindingTarget.FixtureProperty("wash-1", "dimmer"), binding)
        }
        val rewritten = done.json()["rewritten"]!!.jsonObject
        assertEquals(1, rewritten["looks"]!!.jsonPrimitive.int)
        assertEquals(1, rewritten["cues"]!!.jsonPrimitive.int)
        assertEquals(1, rewritten["bindings"]!!.jsonPrimitive.int)
        val scripts = done.json()["scriptsNotRewritten"]!!.jsonArray.single().jsonObject
        assertEquals("foh-1", scripts["key"]!!.jsonPrimitive.content)
        assertEquals(listOf("foh-1", "foh-10", "wash-1"), liveKeys(), "the running rig takes the new keys")
    }

    @Test
    fun `rename_fixtures refuses a key that is taken, malformed or unknown`() {
        patchRig()
        val refused = call(
            "rename_fixtures",
            """{"fixtures":[{"key":"foh-1","newKey":"foh-2"},{"key":"foh-10","newKey":"a.b"},{"key":"nope","newKey":"x"},{"key":"foh-2","newKey":"foh-2"}]}""",
        )
        assertFalse(refused.success, refused.result)
        val problems = refused.problems()
        assertTrue(problems.any { "may not contain '.'" in it }, refused.result)
        assertTrue(problems.any { "same as key" in it }, refused.result)
        assertEquals(listOf("foh-1", "foh-10", "foh-2"), patchKeys())

        val taken = call("rename_fixtures", """{"fixtures":[{"key":"foh-1","newKey":"foh-2"},{"key":"nope","newKey":"x"}]}""")
        assertTrue(taken.problems().any { "'foh-2' is already patched" in it }, taken.result)
        assertTrue(taken.problems().any { "no fixture is patched as 'nope'" in it }, taken.result)

        val swap = call("rename_fixtures", """{"fixtures":[{"key":"foh-1","newKey":"foh-2"},{"key":"foh-2","newKey":"foh-1"}]}""")
        assertTrue(swap.success, "a swap is two renames in one call: ${swap.result}")
        assertEquals(listOf("foh-2"), groupMembers().getValue("Front wash"), "a group holds the fixture, whatever its key")
        assertEquals(listOf("foh-2", "foh-1", "foh-10"), groupMembers().getValue("FOH"))
    }

    @Test
    fun `edit_groups takes fixtures out, sets the order, renames and creates`() {
        patchRig()
        val seeded = seedReferences()

        val dry = call(
            "edit_groups",
            """{"groups":[{"name":"FOH","newName":"Front of house","remove":["foh-2"]},{"name":"Specials","add":["foh-2"]}],"dryRun":true}""",
        )
        assertTrue(dry.success, dry.result)
        assertEquals(setOf("FOH", "Front wash"), groupMembers().keys, "a dry run creates and renames nothing")

        val done = call(
            "edit_groups",
            """{"groups":[
                {"name":"FOH","newName":"Front of house","remove":["foh-2"]},
                {"name":"Front wash","members":["foh-2","foh-1"]},
                {"name":"Specials","add":["foh-10","foh-1"]}
            ]}""",
        )
        assertTrue(done.success, done.result)
        assertEquals(
            mapOf(
                "Front of house" to listOf("foh-1", "foh-10"),
                "Front wash" to listOf("foh-2", "foh-1"),
                "Specials" to listOf("foh-10", "foh-1"),
            ),
            groupMembers(),
        )
        assertEquals(listOf("foh-1", "foh-10", "foh-2"), patchKeys(), "the fixtures stay patched")
        assertEquals(Triple("group", "Front of house", null), lookRows(seeded.lookId).last(), "the Look names the new name")
        transaction(state.database) {
            assertEquals(CueTargetDto("group", "Front of house"), DaoCueLayer.find { DaoCueLayers.cue eq seeded.cueId }.single().targets.last())
        }
        val answered = done.json()["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals("FOH", answered[0]["renamedFrom"]!!.jsonPrimitive.content)
        assertTrue(answered[2]["created"]!!.jsonPrimitive.boolean)
        assertTrue("Specials" in state.show.fixtures.groups.map { it.name }, "the running rig has the new group")

        val swap = call("edit_groups", """{"groups":[{"name":"Front of house","newName":"Front wash"},{"name":"Front wash","newName":"Front of house"}]}""")
        assertTrue(swap.success, swap.result)
        assertEquals(listOf("foh-2", "foh-1"), groupMembers().getValue("Front of house"))
    }

    @Test
    fun `a fixture whose own key holds a dot is never taken for another's cell`() {
        val patched = call(
            "patch_fixtures",
            """{"fixtures":[
                {"key":"pod","name":"Pod","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1},
                {"key":"pod.b","name":"Pod B","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":2}
            ]}""",
        )
        assertTrue(patched.success, patched.result)
        val lookId = transaction(state.database) {
            val look = DaoLook.new { project = DaoProject[projectId]; name = "Pods" }
            DaoLookRow.new { this.look = look; targetType = "fixture"; targetKey = "pod.b"; propertyName = "dimmer"; value = "1"; sortOrder = 0 }
            DaoLookRow.new {
                this.look = look; targetType = "fixture"; targetKey = "pod"; elementKey = "pod.cell-1"
                propertyName = "dimmer"; value = "2"; sortOrder = 1
            }
            look.id.value
        }

        val dry = call("unpatch_fixtures", """{"keys":["pod"],"dryRun":true}""")
        val named = dry.json()["namedBy"]!!.jsonArray.single().jsonObject["usedBy"]!!.jsonArray
        assertEquals(1, named.size, "only the cell row names pod: ${dry.result}")

        assertTrue(call("rename_fixtures", """{"fixtures":[{"key":"pod","newKey":"wash"}]}""").success)
        assertEquals(
            listOf(Triple("fixture", "pod.b", null), Triple("fixture", "wash", "wash.cell-1")),
            lookRows(lookId),
        )
    }

    @Test
    fun `edit_groups names the scripts a group rename leaves alone`() {
        patchRig()
        transaction(state.database) {
            DaoScript.new { project = DaoProject[projectId]; name = "Wash chase"; script = """group<Fixture>("FOH")""" }
        }
        val done = call("edit_groups", """{"groups":[{"name":"FOH","newName":"Front of house"}]}""")
        assertTrue(done.success, done.result)
        val scripts = done.json()["scriptsNotRewritten"]!!.jsonArray.single().jsonObject
        assertEquals("FOH", scripts["group"]!!.jsonPrimitive.content)
        assertEquals(listOf("Wash chase"), scripts["scripts"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `edit_groups refuses a call whole and lists every problem`() {
        patchRig()
        val refused = call(
            "edit_groups",
            """{"groups":[
                {"name":"FOH","members":["foh-1"],"add":["foh-2"]},
                {"name":"Front wash","remove":["foh-2","ghost"]},
                {"name":"FOH","newName":"Front wash"},
                {"name":"Nowhere","remove":["foh-1"]},
                {"name":"Empty"}
            ]}""",
        )
        assertFalse(refused.success, refused.result)
        val problems = refused.problems()
        assertTrue(problems.any { "members (the whole list) or add/remove, not both" in it }, refused.result)
        assertTrue(problems.any { "nothing to change" in it }, refused.result)
        assertTrue(problems.any { "group 'FOH' is listed twice" in it }, refused.result)

        val second = call(
            "edit_groups",
            """{"groups":[{"name":"Front wash","remove":["foh-2","ghost"]},{"name":"FOH","newName":"Front wash"},{"name":"Nowhere","remove":["foh-1"]}]}""",
        )
        val more = second.problems()
        assertTrue(more.any { "'foh-2' is not in this group" in it }, second.result)
        assertTrue(more.any { "no fixture is patched as 'ghost'" in it }, second.result)
        assertTrue(more.any { "a group named 'Front wash' already exists" in it }, second.result)
        assertTrue(more.any { "'Nowhere'" in it && "no group by that name" in it }, second.result)
        assertEquals(setOf("FOH", "Front wash"), groupMembers().keys)
        assertEquals(listOf("foh-1", "foh-2", "foh-10"), groupMembers().getValue("FOH"))
    }
}
