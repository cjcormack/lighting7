package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Splitting a hall from the show built in it, over MCP and without switching project: extract the
 * venue, start a show on it, copy the set and a stack across, hide a venue piece for the new show's
 * run, and clean the copied stack out of the original — the live project untouched throughout.
 */
class McpVenueSplitToolsTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject, caller = "Tester") }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ok(name: String, arguments: String): JsonObject {
        val result = call(name, arguments)
        assertTrue(result.success, "$name: ${result.result}")
        return result.json()
    }

    private fun stackId(projectId: Int, name: String): Int = transaction(state.database) {
        DaoCueStack.find { (DaoCueStacks.project eq projectId) and (DaoCueStacks.name eq name) }.single().id.value
    }

    private fun currentId() = transaction(state.database) { state.projectManager.currentProject.id.value }

    /** The rich show extracted into "Commemoration Hall" through the tool; answers (show, venue). */
    private fun extracted(): Pair<Int, Int> {
        val showId = seedVenueReadyShow(state)
        val dry = ok("extract_venue", """{"fromProjectId":$showId,"name":"Commemoration Hall","dryRun":true}""")
        assertEquals("true", dry["dryRun"]!!.jsonPrimitive.content)
        assertTrue(dry["moved"]!!.jsonObject["elements"]!!.jsonArray.any { it.jsonPrimitive.content == "House tabs" }, "$dry")
        transaction(state.database) { assertTrue(DaoProject[showId].venueUuid == null, "a dry run keeps nothing") }

        val venueId = ok("extract_venue", """{"fromProjectId":$showId,"name":"Commemoration Hall"}""")["venueId"]!!.jsonPrimitive.content.toInt()
        return showId to venueId
    }

    @Test
    fun `the venue split runs end to end without touching the live project`() {
        val live = currentId()
        val (showId, venueId) = extracted()

        val venue = ok("list_venues", "{}")["venues"]!!.jsonArray.single().jsonObject
        assertEquals(venueId, venue["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, venue["installedVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", venue["hasRig"]!!.jsonPrimitive.content)
        assertEquals(listOf("Cabaret tables"), venue["seatingPresets"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(showId), venue["shows"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content.toInt() })

        // A new show on the venue, its stalls copied from the preset.
        val created = ok("create_project", """{"name":"Sleeping Beauty","venueId":$venueId,"seatingPreset":"Cabaret tables"}""")
        val sb = created["projectId"]!!.jsonPrimitive.content.toInt()
        assertEquals("Cabaret tables", created["seating"]!!.jsonPrimitive.content)
        val listed = ok("list_projects", "{}")["projects"]!!.jsonArray.map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content.toInt() == sb }
        assertEquals("Commemoration Hall", listed["venue"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        // Its scene, read without switching: the venue's pieces, owner=venue, and the venue named.
        val scene = ok("get_scene", """{"projectId":$sb}""")
        assertEquals("Commemoration Hall", scene["venue"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("NONE", scene["venue"]!!.jsonObject["behind"]!!.jsonPrimitive.content)
        val owners = scene["elements"]!!.jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["owner"]!!.jsonPrimitive.content }
        assertEquals("venue", owners["House tabs"])
        assertEquals("show", owners["Cabaret tables"])

        // Hide a venue piece for the run, and bring it back.
        val hid = ok("set_scene", """{"projectId":$sb,"elements":[{"name":"House tabs","hidden":true}]}""")
        assertEquals(listOf("House tabs"), hid["venueElementsHidden"]!!.jsonArray.map { it.jsonPrimitive.content })
        val hiddenScene = ok("get_scene", """{"projectId":$sb}""")
        assertTrue(hiddenScene["elements"]!!.jsonArray.none { it.jsonObject["name"]!!.jsonPrimitive.content == "House tabs" })
        assertEquals(listOf("House tabs"), hiddenScene["venue"]!!.jsonObject["hiddenElements"]!!.jsonArray.map { it.jsonPrimitive.content })
        ok("set_scene", """{"projectId":$sb,"elements":[{"name":"House tabs","hidden":false}]}""")
        // Anything more than a hide is still the venue's.
        val retrim = call("set_scene", """{"projectId":$sb,"elements":[{"name":"House tabs","hidden":true,"y":1.0}]}""")
        assertFalse(retrim.success, retrim.result)
        assertEquals("VENUE_OWNED", retrim.json()["code"]!!.jsonPrimitive.content)

        // Copy the set and a stack across: dry run first, which keeps nothing.
        val stack = stackId(showId, "show-1")
        val copyArgs = """{"fromProjectId":$showId,"toProjectId":$sb,"sceneElements":["Thrust deck","Day/night cloth"],"cueStacks":[$stack]"""
        val dry = ok("copy_to_project", "$copyArgs,\"dryRun\":true}")
        assertEquals(listOf("show-1"), dry["stacks"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        transaction(state.database) { assertTrue(DaoCueStack.find { DaoCueStacks.project eq sb }.empty(), "a dry run keeps nothing") }

        val copied = ok("copy_to_project", "$copyArgs}")
        assertEquals(listOf("Thrust deck", "Day/night cloth"), copied["elements"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, copied["imagesCopied"]!!.jsonPrimitive.content.toInt())
        val unresolved = copied["unresolved"]!!.jsonArray.map { it.jsonObject["problem"]!!.jsonPrimitive.content }
        assertTrue(unresolved.any { "script" in it }, "the cue's script trigger has no script in the new show: $unresolved")

        transaction(state.database) {
            val sbProject = DaoProject[sb]
            val copiedStack = DaoCueStack.find { DaoCueStacks.project eq sb }.single()
            assertEquals("show-1", copiedStack.name)
            val entries = entriesOf(copiedStack).filter { !it.isMarker }
            val cues = entries.map { it.cue!! }
            // In the source's order — two entries sharing an ordinal (the fixture's "left" and
            // "right") play by uuid, and the copies' uuids are new, so the copy writes the order out.
            val sourceOrder = entriesOf(DaoCueStack[stack]).filter { !it.isMarker }.map { it.cue!!.name }
            assertEquals(listOf("open", "build"), sourceOrder.take(2))
            assertEquals(sourceOrder, cues.map { it.name })
            assertEquals(
                entriesOf(DaoCueStack[stack]).map { it.isMarker },
                entriesOf(copiedStack).map { it.isMarker },
                "markers keep their places",
            )
            val open = cues.first()
            assertEquals("1.5", entries.first().cueNumber)
            // Scenery found by name: the venue's tabs (the pinned version's row) and the copied deck.
            val scenery = DaoCueSceneryRow.find { DaoCueScenery.cue eq open.id }.associate { it.element.name to it.element.project.id }
            val snapshot = RigScope.of(sbProject).venue!!.snapshotId
            assertEquals(snapshot, scenery["House tabs"])
            assertEquals(sbProject.id, scenery["Thrust deck"])
            // Events find the cannon in the venue's rig; layers' Looks and templates came along.
            assertEquals(2, DaoCueEvent.find { DaoCueEvents.cue eq open.id }.count())
            assertTrue(open.layers.all { (it.look?.project?.id ?: it.palette?.project?.id) == sbProject.id })
            assertTrue(DaoCueTrigger.find { DaoCueTriggers.cue eq open.id }.empty())
            // The thrust deck still decks the thrust, now the venue's region.
            val deck = RigScope.of(sbProject).own.stageElements().single { it.name == "Thrust deck" }
            val region = (readElementParams(StageElementKind.PLATFORM, deck.params) as PlatformParams).regionUuid
            assertNotNull(RigScope.of(sbProject).stageRegions().singleOrNull { it.uuid.toString() == region })
        }
        val cloth = ok("get_scene", """{"projectId":$sb}""")["elements"]!!.jsonArray.map { it.jsonObject }
            .single { it["name"]!!.jsonPrimitive.content == "Day/night cloth" }
        assertTrue("missing" !in cloth["paintImages"].toString(), "the cloth's images came with it: $cloth")

        // A second copy is refused by name, all at once.
        val again = call("copy_to_project", "$copyArgs}")
        assertFalse(again.success)
        assertTrue("Thrust deck" in again.result && "show-1" in again.result, again.result)

        // A venue piece the copied cues move can no longer be hidden.
        val inUse = call("set_scene", """{"projectId":$sb,"elements":[{"name":"House tabs","hidden":true}]}""")
        assertFalse(inUse.success, inUse.result)

        // Clean the stack out of the original show.
        ok("delete_cue_stacks", """{"projectId":$showId,"stackIds":[$stack]}""")
        transaction(state.database) {
            assertNull(DaoCueStack.findById(stack))
            assertEquals(1, DaoCueStack.find { (DaoCueStacks.project eq sb) and (DaoCueStacks.name eq "show-1") }.count())
        }

        assertEquals(live, currentId(), "nothing switched project")
    }

    /**
     * Cue model plan §3.9: `copy_to_project` copies whole stacks, so a cue two copied stacks share —
     * or one stack holds twice — is copied once and shared in the target, each entry keeping its place.
     */
    @Test
    fun `a cue two copied stacks share is copied once and shared in the target`() {
        val showId = seedVenueReadyShow(state)
        val showOne = stackId(showId, "show-1")
        val showTwo = stackId(showId, "show-2")
        // The rich fixture already places "open" in both stacks; a second place in show-2 makes it
        // one that stack holds twice.
        transaction(state.database) {
            val open = DaoCue.find { (DaoCues.project eq showId) and (DaoCues.name eq "open") }.single()
            DaoCueStackEntry.new { stack = DaoCueStack[showTwo]; cue = open; ordinal = 5.0; cueNumber = "20" }
        }
        val target = ok("create_project", """{"name":"Copy target"}""")["projectId"]!!.jsonPrimitive.content.toInt()

        ok("copy_to_project", """{"fromProjectId":$showId,"toProjectId":$target,"cueStacks":[$showOne,$showTwo]}""")

        transaction(state.database) {
            val opens = DaoCue.find { (DaoCues.project eq target) and (DaoCues.name eq "open") }.toList()
            assertEquals(1, opens.size, "one copy of the shared cue")
            val placed = entriesOfCue(opens.single()).map { it.stack.name to it.cueNumber }
            assertEquals(3, placed.size, "every entry naming it, in both stacks: $placed")
            assertEquals(setOf("show-1", "show-2"), placed.map { it.first }.toSet())
            assertTrue("show-2" to "20" in placed, "an entry keeps its own number: $placed")
            // Every other cue is copied once too.
            val names = DaoCue.find { DaoCues.project eq target }.map { it.name }
            assertEquals(names.distinct(), names)
        }
    }

    @Test
    fun `copy_cues copies cues from another show, a dry run writing nothing`() {
        val live = currentId()
        val showId = seedVenueReadyShow(state)
        val open = transaction(state.database) {
            DaoCue.find { (DaoCues.project eq showId) and (DaoCues.name eq "open") }.single().id.value
        }
        val stack = ok("build_cue_stack", """{"stackName":"Copies","cues":[{"number":"1","name":"Preset"}]}""")["stackId"]!!.jsonPrimitive.content.toInt()
        fun liveCues() = transaction(state.database) { DaoCue.find { DaoCues.project eq live }.count() }
        val before = liveCues()

        val dry = ok("copy_cues", """{"fromProjectId":$showId,"cueIds":[$open],"stackId":$stack,"dryRun":true}""")
        assertEquals("true", dry["dryRun"]!!.jsonPrimitive.content)
        assertEquals(open, dry["copied"]!!.jsonArray.single().jsonObject["fromCueId"]!!.jsonPrimitive.content.toInt())
        assertNotNull(dry["unresolved"], "the answer lists what would not resolve, empty or not")
        assertEquals(before, liveCues(), "a dry run writes nothing")
        transaction(state.database) { assertEquals(1, entriesOf(DaoCueStack[stack]).size) }

        val copied = ok("copy_cues", """{"fromProjectId":$showId,"cueIds":[$open],"stackId":$stack}""")["copied"]!!.jsonArray.single().jsonObject
        val copy = copied["cueId"]!!.jsonPrimitive.content.toInt()
        transaction(state.database) {
            assertEquals(live, DaoCue[copy].project.id.value)
            assertEquals("open", DaoCue[copy].name)
            assertEquals(copied["entryId"]!!.jsonPrimitive.content.toInt(), entriesOf(DaoCueStack[stack]).last().id.value)
        }

        // Into a project that is not current, loose.
        val other = ok("create_project", """{"name":"Copy target"}""")["projectId"]!!.jsonPrimitive.content.toInt()
        val loose = ok("copy_cues", """{"projectId":$other,"fromProjectId":$showId,"cueIds":[$open]}""")["copied"]!!.jsonArray.single().jsonObject
        assertNull(loose["entryId"]?.takeIf { it.toString() != "null" })
        transaction(state.database) { assertEquals(other, DaoCue[loose["cueId"]!!.jsonPrimitive.content.toInt()].project.id.value) }

        val refused = call("copy_cues", """{"fromProjectId":$showId,"cueIds":[999999]}""")
        assertFalse(refused.success)
        assertTrue("no cue 999999" in refused.result, refused.result)
        assertEquals(live, currentId(), "nothing switched project")
    }

    @Test
    fun `the setup tools read and write a project that is not current`() {
        val live = currentId()
        val (showId, _) = extracted()
        val patch = ok("get_patch", """{"projectId":$showId}""")
        assertEquals("Commemoration Hall", patch["venue"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        ok("patch_fixtures", """{"projectId":$showId,"fixtures":[{"key":"spot-9","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":1}]}""")
        ok("set_stage", """{"projectId":$showId,"riggings":[{"name":"Panto boom","kind":"BOOM","x":-4.0}]}""")
        ok("place_fixtures", """{"projectId":$showId,"placements":[{"key":"spot-9","rigging":"Panto boom"}]}""")
        ok("build_cue_stack", """{"projectId":$showId,"stackName":"Act 1","cues":[{"number":"1","name":"Overture"}]}""")
        val anchored = call("build_cue_stack", """{"projectId":$showId,"stackName":"Act 2","cues":[{"name":"Dawn","at":{"pdfPage":1,"y":0.5}}]}""")
        assertFalse(anchored.success, "a prompt book is only marked up when current: ${anchored.result}")
        transaction(state.database) {
            assertEquals("Panto boom", DaoFixturePatch.find { (DaoFixturePatches.project eq showId) and (DaoFixturePatches.key eq "spot-9") }.single().rigging?.name)
        }
        assertTrue(state.show.fixtures.fixtures.none { it.key == "spot-9" }, "the live rig did not load another project's patch")
        assertEquals(live, currentId())

        val missing = call("get_scene", """{"projectId":999999}""")
        assertFalse(missing.success)
        assertTrue("not found" in missing.result, missing.result)
    }

    @Test
    fun `deleting another project's stack leaves the live desk armed, and a copy names both projects`() {
        val (showId, _) = extracted()
        state.effectsService.arm(60)
        assertTrue(state.effectsService.armed.value.armed, "the live desk is armed")
        ok("delete_cue_stacks", """{"projectId":$showId,"stackIds":[${stackId(showId, "show-1")}]}""")
        assertTrue(state.effectsService.armed.value.armed, "a delete in another project does not touch the live desk")

        for (args in listOf("""{"fromProjectId":$showId,"toProjectId":null,"looks":[1]}""", """{"fromProjectId":null,"toProjectId":$showId,"looks":[1]}""")) {
            val refused = call("copy_to_project", args)
            assertFalse(refused.success, refused.result)
            assertTrue("is required" in refused.result, refused.result)
        }
    }

    @Test
    fun `extract_venue on the live project needs reloadLiveShow, and create_project checks its venue fields`() {
        val showId = seedVenueReadyShow(state)
        runBlocking { state.projectManager.switchProject(showId) }
        val refused = call("extract_venue", """{"fromProjectId":$showId,"name":"Hall"}""")
        assertFalse(refused.success)
        assertTrue("reloadLiveShow" in refused.result, refused.result)
        val venueId = ok("extract_venue", """{"fromProjectId":$showId,"name":"Hall","reloadLiveShow":true}""")["venueId"]!!.jsonPrimitive.content.toInt()

        val sized = call("create_project", """{"name":"Panto","venueId":$venueId,"stageWidthM":8.0}""")
        assertFalse(sized.success)
        val unknown = call("create_project", """{"name":"Panto","venueId":$venueId,"seatingPreset":"Balcony"}""")
        assertFalse(unknown.success)
        assertTrue("Cabaret tables" in unknown.result, "the refusal names what the venue offers: ${unknown.result}")
    }

    @Test
    fun `an operator can neither make a venue nor write to one through projectId`() {
        val (_, venueId) = extracted()
        val protocol = McpProtocol(state)
        val operator = AuthenticatedUser(2, UUID.randomUUID(), "op", "Op", UserRole.OPERATOR, "test")
        fun callAs(name: String, arguments: String): JsonObject = runBlocking {
            protocol.handle(
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""),
                operator,
            )
        }!!["result"]!!.jsonObject

        val extract = callAs("extract_venue", """{"fromProjectId":1,"name":"Other","dryRun":true}""")
        assertEquals("true", extract["isError"]!!.jsonPrimitive.content)
        val write = callAs("set_scene", """{"projectId":$venueId,"elements":[{"name":"Pillar","kind":"OBJECT","widthM":0.5,"depthM":0.5,"heightM":3.0}]}""")
        assertEquals("true", write["isError"]!!.jsonPrimitive.content)
        assertTrue("admin" in write.toString(), "$write")
        // Reading it is anyone's.
        val read = callAs("get_scene", """{"projectId":$venueId}""")
        assertEquals("false", read["isError"]!!.jsonPrimitive.content, "$read")
    }

    @Test
    fun `publish_venue publishes the draft an admin edited through projectId`() {
        val (showId, venueId) = extracted()
        ok("set_scene", """{"projectId":$venueId,"elements":[{"name":"Pillar","kind":"OBJECT","widthM":0.5,"depthM":0.5,"heightM":3.0}]}""")
        val draft = ok("list_venues", "{}")["venues"]!!.jsonArray.single().jsonObject
        assertTrue(draft["unpublishedChanges"]!!.jsonPrimitive.content.toInt() > 0, "$draft")
        val published = ok("publish_venue", """{"venueId":$venueId,"note":"A pillar"}""")
        assertEquals(2, published["version"]!!.jsonPrimitive.content.toInt())
        val scene = ok("get_scene", """{"projectId":$showId}""")["venue"]!!.jsonObject
        assertEquals(2, scene["installed"]!!.jsonPrimitive.content.toInt())
        assertEquals("DRAWING", scene["behind"]!!.jsonPrimitive.content)
    }
}
