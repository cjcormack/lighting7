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
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP show-setup tools (`ai/SetupTools.kt`), driven the way the MCP server drives them: a tool
 * name and a JSON argument object in, a [ToolExecutionResult] out. The through-the-wire half —
 * that `tools/list` offers them and marks the readers read-only — is asserted here too, against
 * [McpProtocol] directly; `McpServerTest` already covers the transport.
 */
class McpSetupToolsTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ToolExecutionResult.problems(): List<String> =
        json()["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun patchKeys(): List<String> = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.project eq projectId }.map { it.key }.sorted()
    }

    private fun patchTwoDimmers(extra: String = ""): ToolExecutionResult = call(
        "patch_fixtures",
        """{"fixtures":[
            {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1,"groups":["FOH","Front wash"]},
            {"key":"foh-2","name":"FOH 2","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":2,"groups":["FOH"]$extra}
        ]}""",
    )

    @Test
    fun `the MCP server offers the setup tools and marks the readers read-only`() {
        val protocol = McpProtocol(state)
        val names = protocol.toolDefs.map { it.name }
        for (tool in listOf(
            "list_projects", "create_project", "switch_project", "list_fixture_types", "get_patch",
            "patch_fixtures", "set_stage", "place_fixtures", "get_prompt_book", "build_cue_stack",
            "mark_up_prompt_book",
        )) {
            assertTrue(tool in names, "$tool is offered")
        }
        assertEquals("describe_rig", names.first())
        assertEquals(names.size, names.toSet().size, "no tool is listed twice")
        assertFalse("run_lighting_script" in names)

        val listed = runBlocking {
            protocol.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        fun readOnly(name: String) =
            listed.getValue(name).jsonObject["annotations"]?.jsonObject?.get("readOnlyHint")?.jsonPrimitive?.boolean == true
        assertTrue(readOnly("get_patch") && readOnly("list_fixture_types") && readOnly("get_prompt_book") && readOnly("list_projects"))
        assertFalse(readOnly("patch_fixtures") || readOnly("switch_project") || readOnly("build_cue_stack"))
    }

    @Test
    fun `a setup tool call through the MCP server reaches the desk`() {
        val response = runBlocking {
            McpProtocol(state).handle(
                Json.parseToJsonElement(
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"patch_fixtures","arguments":
                        {"fixtures":[{"name":"Cyc 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":40}]}}}""",
                ),
                user,
            )
        }!!["result"]!!.jsonObject
        assertFalse(response["isError"]!!.jsonPrimitive.boolean, response.toString())
        assertEquals(listOf("cyc-1"), patchKeys(), "the key is derived from the name")
    }

    @Test
    fun `switch_project moves every tool onto the new project`() {
        val created = call("create_project", """{"name":"Tour","switchTo":true}""")
        assertTrue(created.success, created.result)
        val newId = created.json()["projectId"]!!.jsonPrimitive.int
        assertEquals(newId, state.projectManager.currentProject.id.value)

        assertTrue(patchTwoDimmers().success)
        assertEquals("Tour", call("get_patch", "{}").json()["project"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), patchKeys(), "nothing landed on the original project")

        val back = call("switch_project", """{"projectId":$projectId}""")
        assertTrue(back.success, back.result)
        assertEquals(projectId, state.projectManager.currentProject.id.value)
        assertFalse(call("switch_project", """{"projectId":99999}""").success)
    }

    @Test
    fun `list_fixture_types filters and includes the generic dimmer`() {
        val all = call("list_fixture_types", "{}").json()["fixtureTypes"]!!.jsonArray
        assertTrue(all.size > 10)
        val generic = call("list_fixture_types", """{"query":"generic"}""").json()["fixtureTypes"]!!.jsonArray
        val keys = generic.map { it.jsonObject["typeKey"]!!.jsonPrimitive.content }
        assertTrue("generic-dimmer" in keys)
        assertTrue(generic.size < all.size)
    }

    @Test
    fun `patch_fixtures patches a list, groups it and loads the rig`() {
        val result = patchTwoDimmers()
        assertTrue(result.success, result.result)
        assertEquals(2, result.json()["created"]!!.jsonPrimitive.int)
        assertEquals(listOf("foh-1", "foh-2"), patchKeys())

        // Loaded into the running show, so every other tool can address them at once.
        assertNotNull(state.show.fixtures.untypedFixture("foh-1"))

        val patch = call("get_patch", "{}").json()
        val groups = patch["groups"]!!.jsonObject
        assertEquals(listOf("foh-1", "foh-2"), groups["FOH"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("foh-1"), groups["Front wash"]!!.jsonArray.map { it.jsonPrimitive.content })
        val foh2 = patch["fixtures"]!!.jsonArray.map { it.jsonObject }.single { it["key"]!!.jsonPrimitive.content == "foh-2" }
        assertEquals(2, foh2["startChannel"]!!.jsonPrimitive.int)
    }

    @Test
    fun `patch_fixtures writes nothing when any row is wrong, and lists every problem`() {
        val hexChannels = FixtureTypeRegistry.channelCountForTypeKey("hex")!!
        val result = call(
            "patch_fixtures",
            """{"fixtures":[
                {"key":"ok-1","name":"OK","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1},
                {"key":"bad-type","name":"Bad","fixtureTypeKey":"no-such-thing","universe":0,"startChannel":10},
                {"key":"hex-1","name":"Hex 1","fixtureTypeKey":"hex","universe":0,"startChannel":100},
                {"key":"hex-2","name":"Hex 2","fixtureTypeKey":"hex","universe":0,"startChannel":${100 + hexChannels - 1}},
                {"key":"too-high","name":"High","fixtureTypeKey":"hex","universe":0,"startChannel":510},
                {"key":"ok-1","name":"Dup","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":20}
            ]}""",
        )
        assertFalse(result.success)
        val problems = result.problems()
        assertTrue(problems.any { "no-such-thing" in it }, problems.toString())
        assertTrue(problems.any { "runs to channel" in it }, problems.toString())
        assertTrue(problems.any { "also used by fixtures[0]" in it }, problems.toString())
        assertEquals(emptyList(), patchKeys(), "nothing is written")

        // Fix the per-row problems and the overlap is what remains — checked against the whole list.
        val overlap = call(
            "patch_fixtures",
            """{"fixtures":[
                {"key":"hex-1","name":"Hex 1","fixtureTypeKey":"hex","universe":0,"startChannel":100},
                {"key":"hex-2","name":"Hex 2","fixtureTypeKey":"hex","universe":0,"startChannel":${100 + hexChannels - 1}}
            ]}""",
        )
        assertFalse(overlap.success)
        assertTrue(overlap.problems().single().contains("overlaps"), overlap.result)
        assertEquals(emptyList(), patchKeys())
    }

    @Test
    fun `patch_fixtures updates a key it already patched, and dryRun writes nothing`() {
        val dry = call(
            "patch_fixtures",
            """{"dryRun":true,"fixtures":[{"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1}]}""",
        )
        assertTrue(dry.success, dry.result)
        assertEquals(1, dry.json()["created"]!!.jsonPrimitive.int)
        assertEquals(emptyList(), patchKeys())

        assertTrue(patchTwoDimmers().success)
        assertTrue(patchTwoDimmers().success, "re-sending the same list is an update")
        val fohMembers = call("get_patch", "{}").json()["groups"]!!.jsonObject["FOH"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("foh-1", "foh-2"), fohMembers, "group membership is not duplicated")
        // Move foh-2 onto foh-1's old address and foh-1 elsewhere, in one call: the overlap check
        // sees the patch as it will stand, not as it stood.
        val moved = call(
            "patch_fixtures",
            """{"fixtures":[
                {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":50},
                {"key":"foh-2","name":"FOH 2 (moved)","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1}
            ]}""",
        )
        assertTrue(moved.success, moved.result)
        assertEquals(2, moved.json()["updated"]!!.jsonPrimitive.int)
        assertEquals(listOf("foh-1", "foh-2"), patchKeys())
        transaction(state.database) {
            val foh2 = DaoFixturePatch.find { DaoFixturePatches.key eq "foh-2" }.single()
            assertEquals(1, foh2.startChannel)
            assertEquals("FOH 2 (moved)", foh2.displayName)
        }
    }

    /**
     * `infrastructure` rides patch_fixtures: set on a row, reported by get_patch, carried into the
     * running show (what `GET /fixtures` and the rig order read), kept by a later place_fixtures and
     * by a re-sent row that omits it — and a non-boolean is a problem, not a silent false.
     */
    @Test
    fun `patch_fixtures marks a fixture as infrastructure`() {
        val patched = patchTwoDimmers(""","infrastructure":true""")
        assertTrue(patched.success, patched.result)
        val rows = call("get_patch", "{}").json()["fixtures"]!!.jsonArray.associateBy { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals("true", rows.getValue("foh-2").jsonObject["infrastructure"]?.jsonPrimitive?.content)
        assertEquals(null, rows.getValue("foh-1").jsonObject["infrastructure"], "a lighting fixture omits the flag")
        assertTrue(state.show.fixtures.isInfrastructure("foh-2"))
        assertTrue(!state.show.fixtures.isInfrastructure("foh-1"))

        assertTrue(call("place_fixtures", """{"placements":[{"key":"foh-2","gelCode":"L201"}]}""").success)
        assertTrue(state.show.fixtures.isInfrastructure("foh-2"), "a placement refreshes the cache without clearing the flag")

        assertTrue(patchTwoDimmers().success, "a row that omits the flag leaves it as it was")
        assertTrue(state.show.fixtures.isInfrastructure("foh-2"))

        // The chat's own reads of the rig set it apart rather than listing it as lighting.
        val current = runBlocking {
            AiTools(state).executeTool("get_current_state", Json.parseToJsonElement("""{"include":["fixtures"]}""").jsonObject)
        }.json()["fixtures"]!!.jsonArray.associateBy { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals("true", current.getValue("foh-2").jsonObject["infrastructure"]?.jsonPrimitive?.content)
        assertEquals(null, current.getValue("foh-1").jsonObject["infrastructure"])
        val briefing = RigBriefing(state).describeRig()
        val lightingSection = briefing.substringAfter("## Available Fixtures").substringBefore("## Infrastructure")
        assertTrue("`foh-1`" in lightingSection && "`foh-2`" !in lightingSection, briefing)
        assertTrue("`foh-2`" in briefing.substringAfter("## Infrastructure").substringBefore("## Available Groups"), briefing)

        val bad = patchTwoDimmers(""","infrastructure":"yes"""")
        assertTrue(!bad.success)
        assertTrue(bad.problems().any { "infrastructure must be a boolean" in it }, bad.result)
    }

    @Test
    fun `set_stage upserts riggings by name and place_fixtures hangs fixtures on them`() {
        assertTrue(patchTwoDimmers().success)

        val unknownRigging = call("place_fixtures", """{"placements":[{"key":"foh-1","rigging":"FOH bar"}]}""")
        assertFalse(unknownRigging.success)
        assertTrue(unknownRigging.problems().single().contains("no rigging named 'FOH bar'"))

        val stage = call(
            "set_stage",
            """{"stage":{"widthM":10,"depthM":8,"heightM":6},
                "regions":[{"name":"Main stage","centerX":0,"centerY":4,"widthM":10,"depthM":8}],
                "riggings":[{"name":"FOH bar","kind":"bar","x":0,"y":-6,"z":7,"lengthM":8},
                            {"name":"LX1","kind":"TRUSS","x":0,"y":2,"z":6.5,"lengthM":10}]}""",
        )
        assertTrue(stage.success, stage.result)

        val placed = call(
            "place_fixtures",
            """{"placements":[
                {"key":"foh-1","rigging":"FOH bar","x":-1.5,"pitchDeg":40,"gelCode":"L201","kind":"PROFILE","beamAngleDeg":26},
                {"key":"foh-2","rigging":"FOH bar","x":1.5}
            ]}""",
        )
        assertTrue(placed.success, placed.result)

        // A second set_stage changes only what it names.
        assertTrue(call("set_stage", """{"riggings":[{"name":"FOH bar","z":7.5}]}""").success)

        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            assertEquals(10.0, project.stageWidthM)
            val foh = DaoRigging.find { DaoRiggings.name eq "FOH bar" }.single()
            assertEquals("BAR", foh.kind)
            assertEquals(7.5, foh.positionZ)
            assertEquals(-6.0, foh.positionY, "fields not sent are kept")
            val foh1 = DaoFixturePatch.find { DaoFixturePatches.key eq "foh-1" }.single()
            assertEquals(foh.id, foh1.rigging?.id)
            assertEquals(-1.5, foh1.stageX)
            assertEquals("L201", foh1.gelCode)
            assertEquals("PROFILE", foh1.kindOverride)
            assertEquals(26, foh1.beamAngleDeg)
        }
        // The running show's gel cache (what GET /fixtures serves) follows, as after the REST PUT.
        assertEquals("L201", state.show.fixtures.patchMetadataFor("foh-1")?.gelCode)

        // Removing a rigging keeps its fixtures, detached.
        val removed = call("set_stage", """{"removeRiggings":["FOH bar"]}""")
        assertTrue(removed.success, removed.result)
        assertEquals(2, removed.json()["fixturesDetached"]!!.jsonPrimitive.int)
        transaction(state.database) {
            assertNull(DaoFixturePatch.find { DaoFixturePatches.key eq "foh-1" }.single().rigging)
        }
    }

    @Test
    fun `a malformed number is refused rather than read as a clear`() {
        assertTrue(call("set_stage", """{"regions":[{"name":"Main","centerX":1.5}],"riggings":[{"name":"LX1","z":6}]}""").success)
        val result = call(
            "set_stage",
            """{"regions":[{"name":"Main","centerX":"DSC"}],"riggings":[{"name":"LX1","z":true}],"stage":{"widthM":"wide"}}""",
        )
        assertFalse(result.success)
        assertEquals(3, result.problems().size, result.result)
        assertTrue(result.problems().all { "must be a number" in it }, result.result)
        transaction(state.database) {
            assertEquals(1.5, DaoStageRegion.find { DaoStageRegions.name eq "Main" }.single().centerX)
            assertEquals(6.0, DaoRigging.find { DaoRiggings.name eq "LX1" }.single().positionZ)
        }
        // An explicit null is still a clear.
        assertTrue(call("set_stage", """{"regions":[{"name":"Main","centerX":null}]}""").success)
        transaction(state.database) { assertNull(DaoStageRegion.find { DaoStageRegions.name eq "Main" }.single().centerX) }

        assertFalse(call("set_stage", """{"stage":"big"}""").success)
        assertFalse(call("create_project", """{"name":"Odd","stageWidthM":"wide"}""").success)
    }

    @Test
    fun `set_stage validates everything before writing`() {
        val result = call(
            "set_stage",
            """{"stage":{"widthM":-3},"riggings":[{"name":"LX1","kind":"SPACESHIP","lengthM":0}],"removeRegions":["Nope"]}""",
        )
        assertFalse(result.success)
        assertEquals(4, result.problems().size, result.result)
        transaction(state.database) {
            assertTrue(DaoRigging.find { DaoRiggings.project eq projectId }.empty())
            assertNull(DaoProject.findById(projectId)!!.stageWidthM)
        }
    }

    @Test
    fun `build_cue_stack creates a numbered stack in running order`() {
        val result = call(
            "build_cue_stack",
            """{"stackName":"Act 1","cues":[
                {"name":"Act 1","marker":true},
                {"number":"1","name":"Preset","notes":"Warm preset on the house curtain"},
                {"number":"2","name":"House to half","fadeSeconds":5},
                {"name":"Blackout","fadeSeconds":0,"followSeconds":1.5},
                {"number":"3","name":"Dawn","fadeSeconds":12.5,"fadeCurve":"sine_in_out"}
            ]}""",
        )
        assertTrue(result.success, result.result)
        val stackId = result.json()["stackId"]!!.jsonPrimitive.int

        transaction(state.database) {
            val cues = DaoCue.find { DaoCues.cueStack eq stackId }.sortedBy { it.sortOrder }
            assertEquals(listOf("Act 1", "Preset", "House to half", "Blackout", "Dawn"), cues.map { it.name })
            assertEquals(CueType.MARKER.name, cues[0].cueType)
            assertNull(cues[0].cueNumber)
            assertEquals("Warm preset on the house curtain", cues[1].notes)
            assertEquals(Duration.ofSeconds(5), cues[2].fadeDuration)
            // The unnumbered cue is numbered from its neighbours, as the desk does.
            assertEquals("2.1", cues[3].cueNumber)
            assertTrue(cues[3].autoAdvance)
            assertEquals(Duration.ofMillis(1500), cues[3].autoAdvanceDelay)
            assertEquals("SINE_IN_OUT", cues[4].fadeCurve)
        }

        // Retrying the same call is refused rather than duplicating the show.
        val again = call("build_cue_stack", """{"stackName":"Act 1","cues":[{"name":"x"}]}""")
        assertFalse(again.success)
        val append = call("build_cue_stack", """{"stackId":$stackId,"cues":[{"number":"3","name":"Dup"},{"number":"4","name":"Dusk"}]}""")
        assertFalse(append.success)
        assertTrue(append.problems().single().contains("already has a cue numbered '3'"), append.result)
        assertTrue(call("build_cue_stack", """{"stackId":$stackId,"cues":[{"number":"4","name":"Dusk"}]}""").success)
    }

    @Test
    fun `build_cue_stack refuses what it would otherwise drop`() {
        val result = call(
            "build_cue_stack",
            """{"stackName":"Act 2","cues":[
                {"name":"Interval","marker":true,"number":"12A"},
                {"number":"13","name":"Storm","layers":[{"lookId":99999,"targets":[{"type":"group","key":"FOH"}]}]}
            ]}""",
        )
        assertFalse(result.success)
        val problems = result.problems()
        assertTrue(problems.any { "a marker cannot carry a number" in it }, problems.toString())
        assertTrue(problems.any { "no look 99999" in it }, problems.toString())
        transaction(state.database) {
            assertTrue(DaoCueStack.find { DaoCueStacks.name eq "Act 2" }.empty(), "nothing is written")
        }
    }

    @Test
    fun `prompt-book tools anchor cues and add notes once the book exists`() {
        val noBook = call("get_prompt_book", "{}")
        assertTrue(noBook.success)
        assertFalse(noBook.json()["hasPromptBook"]!!.jsonPrimitive.boolean)

        val anchoredWithoutBook = call("build_cue_stack", """{"stackName":"Show","cues":[{"number":"1","name":"Q1","at":{"pdfPage":2,"y":0.3}}]}""")
        assertFalse(anchoredWithoutBook.success)
        assertTrue(anchoredWithoutBook.problems().single().contains("needs a prompt book"))

        // As the desk's Prompt Book import leaves it.
        transaction(state.database) {
            DaoPromptBook.new {
                project = DaoProject.findById(projectId)!!
                scriptHash = "a".repeat(64)
                scriptFileName = "script.pdf"
                pageCount = 10
            }
        }

        val built = call(
            "build_cue_stack",
            """{"stackName":"Show","cues":[
                {"number":"1","name":"Preset","at":{"pdfPage":2,"y":0.1}},
                {"number":"2","name":"Lights up","at":{"pdfPage":3,"y":0.99}},
                {"number":"3","name":"Off the page","at":{"pdfPage":11,"y":0.5}}
            ]}""",
        )
        assertFalse(built.success)
        assertTrue(built.problems().single().contains("pdfPage must be 1–10"), built.result)

        val ok = call(
            "build_cue_stack",
            """{"stackName":"Show","cues":[
                {"number":"1","name":"Preset","at":{"pdfPage":2,"y":0.1}},
                {"number":"2","name":"Lights up","at":{"pdfPage":3,"y":0.99}}
            ]}""",
        )
        assertTrue(ok.success, ok.result)
        assertEquals(2, ok.json()["anchorsCreated"]!!.jsonPrimitive.int)
        val cueIds = ok.json()["cues"]!!.jsonArray.map { it.jsonObject["cueId"]!!.jsonPrimitive.int }

        val markup = call(
            "mark_up_prompt_book",
            """{"coverPages":1,
                "anchors":[{"cueId":${cueIds[0]},"at":{"pdfPage":2,"y":0.4}}],
                "notes":[{"kind":"NOTE","tone":"SAFETY","text":"Pyro","at":{"pdfPage":4,"y":0.2}},
                         {"kind":"STRIKETHROUGH","at":{"pdfPage":5,"y":0.5,"height":0.2}}]}""",
        )
        assertTrue(markup.success, markup.result)

        transaction(state.database) {
            val book = DaoPromptBook.find { DaoPromptBooks.project eq projectId }.single()
            assertEquals(1, book.coverPages)
            val anchors = book.anchors.associateBy { it.cueId }
            val first = anchors.getValue(cueIds[0])
            assertEquals("Q1", first.label)
            assertEquals(1, first.region.single().page, "pdfPage counts from 1")
            assertEquals(0.4, first.region.single().y)
            // A default height that would run off the page is clamped to it, not refused.
            val second = anchors.getValue(cueIds[1]).region.single()
            assertTrue(second.y + second.h <= 1.0)
            val notes = book.annotations.toList()
            assertEquals(2, notes.size)
            assertEquals("SAFETY", notes.single { it.kind == "NOTE" }.tone)
            assertNull(notes.single { it.kind == "STRIKETHROUGH" }.text)
        }

        val read = call("get_prompt_book", "{}").json()
        assertEquals(10, read["pageCount"]!!.jsonPrimitive.int)
        assertEquals(2, read["anchors"]!!.jsonArray.size)
        assertEquals(2, read["anchors"]!!.jsonArray[0].jsonObject["pdfPage"]!!.jsonPrimitive.int)
        assertEquals(0, read["unanchoredCueCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create_project makes a project without switching unless asked`() {
        val created = call("create_project", """{"name":"Hamlet","stageWidthM":12}""")
        assertTrue(created.success, created.result)
        val newId = created.json()["projectId"]!!.jsonPrimitive.int
        assertEquals(projectId, state.projectManager.currentProject.id.value, "still on the original project")
        transaction(state.database) {
            assertEquals(12.0, DaoProject.findById(newId)!!.stageWidthM)
            assertFalse(DaoSpeedMaster.find { DaoSpeedMasters.project eq newId }.empty(), "seeded like a REST-created project")
        }

        assertFalse(call("create_project", """{"name":"Hamlet"}""").success, "names are unique")

        val projects = call("list_projects", "{}").json()["projects"]!!.jsonArray.map { it.jsonObject }
        assertTrue(projects.single { it["name"]!!.jsonPrimitive.content == "Hamlet" }["isCurrent"]!!.jsonPrimitive.boolean.not())
    }
}
