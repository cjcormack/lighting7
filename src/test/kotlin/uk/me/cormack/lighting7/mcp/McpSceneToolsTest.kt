package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `set_scene` and `get_scene` (stage-view plan session 2): the template, upsert by name, the
 * all-or-nothing check, seat views, and the stage summary `describe_rig` gains.
 */
class McpSceneToolsTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ToolExecutionResult.problems(): List<String> =
        json()["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun elementNames(): List<String> = transaction(state.database) {
        DaoStageElement.find { DaoStageElements.project eq projectId }.map { it.name }.sorted()
    }

    /** The Commemoration Hall's numbers, as the prototype estimates them from the video. */
    private val hall = """"template":"proscenium-hall","templateParams":{
        "hallWidthM":8.6,"hallDepthM":18.4,"hallHeightM":4.55,"stageWidthM":6.3,"stageDepthM":11,
        "prosWidthM":5.1,"prosHeightM":2.9,"deckHeightM":0.95,"apronM":0.35,
        "rows":12,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52,"firstRowM":2.4,
        "balconyDepthM":2.2,"balconyHeightM":1.85}"""

    @Test
    fun `the MCP server offers set_scene and get_scene, the reader read-only`() {
        val protocol = McpProtocol(state)
        val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")
        val listed = runBlocking {
            protocol.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        fun readOnly(name: String) =
            listed.getValue(name).jsonObject["annotations"]?.jsonObject?.get("readOnlyHint")?.jsonPrimitive?.boolean == true
        assertTrue(readOnly("get_scene"))
        assertFalse(readOnly("set_scene"))
    }

    @Test
    fun `the proscenium-hall template builds the hall, and get_scene reads it back`() {
        val result = call("set_scene", "{$hall}")
        assertTrue(result.success, result.result)
        assertEquals(
            listOf("Balcony", "Hall", "Main stage", "Proscenium", "Stage house", "Stalls"),
            elementNames(),
        )

        val scene = call("get_scene", "{}").json()
        val elements = scene["elements"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val hallRow = elements.getValue("Hall").jsonObject
        assertEquals(-9.2, hallRow["y"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(-0.95, hallRow["z"]!!.jsonPrimitive.double, 1e-9, "the hall floor is below the deck")
        val deck = elements.getValue("Main stage").jsonObject
        assertEquals(0.0, deck["z"]!!.jsonPrimitive.double, "a platform's z is its top surface")
        assertEquals(0.95, deck["heightM"]!!.jsonPrimitive.double)
        val pros = elements.getValue("Proscenium").jsonObject["params"]!!.jsonObject
        assertEquals(0.95, pros["openingSillM"]!!.jsonPrimitive.double)
        assertTrue(elements.getValue("Stalls").jsonObject["seats"]!!.jsonPrimitive.content.startsWith("144"))
        val balcony = elements.getValue("Balcony").jsonObject
        assertEquals("-17.3", balcony["y"]!!.jsonPrimitive.content, "the template's numbers are rounded, not float dust")
        assertEquals("0.9", balcony["z"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the template's deck links the stage's own Main stage region when there is one`() {
        assertTrue(call("set_stage", """{"regions":[{"name":"Main stage","centerY":5.5,"widthM":6.3,"depthM":11}]}""").success)
        assertTrue(call("set_scene", "{$hall}").success)
        val deck = call("get_scene", "{}").json()["elements"]!!.jsonArray.single { it.jsonObject["name"]!!.jsonPrimitive.content == "Main stage" }
        assertEquals("Main stage", deck.jsonObject["params"]!!.jsonObject["region"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an explicit element lays its fields over the template's of the same name`() {
        val result = call(
            "set_scene",
            """{$hall,"elements":[{"name":"Hall","finish":{"pattern":"panels"}},
                {"name":"Sofa","kind":"object","layer":"set","x":-0.85,"y":1.75,"widthM":1.9,"depthM":0.85,"heightM":0.85}]}""",
        )
        assertTrue(result.success, result.result)
        transaction(state.database) {
            val hallRow = DaoStageElement.find { (DaoStageElements.project eq projectId) }.single { it.name == "Hall" }
            assertEquals("PANELS", hallRow.finishPattern, "the explicit field wins")
            assertEquals("#4a4540", hallRow.finishColour, "the template's other fields stand")
            assertEquals(18.4, hallRow.depthM)
        }
        assertTrue("Sofa" in elementNames())
    }

    @Test
    fun `nothing is written when any row is wrong, and every problem is listed`() {
        val result = call(
            "set_scene",
            """{"elements":[{"name":"Hall","kind":"room","widthM":8.6,"depthM":18.4,"heightM":4.55,"params":{"omit":["sideways"]}},
                {"name":"Tabs","kind":"drape","widthM":5,"depthM":0.1,"heightM":3,"params":{"role":"tabs","states":{"open":1}}},
                {"name":"Ghost","widthM":1}],
               "viewpoints":[{"name":"Row Z","kind":"seat","seat":"Z1"}]}""",
        )
        assertFalse(result.success)
        val problems = result.problems()
        assertTrue(problems.any { "omit[0] must be one of" in it }, problems.toString())
        assertTrue(problems.any { "drawn drape" in it }, problems.toString())
        assertTrue(problems.any { "'Ghost'" in it && "kind is required" in it }, problems.toString())
        assertTrue(problems.any { "'Row Z'" in it && "seating element and a seat" in it }, problems.toString())
        assertEquals(emptyList(), elementNames(), "nothing landed")
    }

    @Test
    fun `a seat view sits in the one seating by default, and a change that would unseat it is refused`() {
        assertTrue(call("set_scene", "{$hall}").success)
        val seated = call(
            "set_scene",
            """{"viewpoints":[{"name":"Row F centre","kind":"seat","seat":"F6"},
                {"name":"Balcony · desk","kind":"eye","eye":{"x":1.25,"y":-17,"z":2.6},"target":{"x":0,"y":2.6,"z":0.8},"fovDeg":50}]}""",
        )
        assertTrue(seated.success, seated.result)

        val views = call("get_scene", "{}").json()["viewpoints"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val rowF = views.getValue("Row F centre").jsonObject
        assertEquals("Stalls", rowF["seating"]!!.jsonPrimitive.content)
        val eye = rowF["seatedEye"]!!.jsonObject
        assertEquals(-0.26, eye["x"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(-2.4 - 5 * 0.95 - 0.05, eye["y"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(-0.95 + SEATED_EYE_HEIGHT_M, eye["z"]!!.jsonPrimitive.double, 1e-9)

        val shrink = call(
            "set_scene",
            """{"elements":[{"name":"Stalls","params":{"rows":4,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52}}]}""",
        )
        assertFalse(shrink.success)
        assertTrue(shrink.problems().any { "'Row F centre' would lose its seat F6" in it }, shrink.problems().toString())
        assertFalse(call("set_scene", """{"removeElements":["Stalls"]}""").success)

        val both = call("set_scene", """{"removeElements":["Stalls"],"removeViewpoints":["Row F centre"]}""")
        assertTrue(both.success, both.result)
        assertFalse("Stalls" in elementNames())
    }

    @Test
    fun `a seat view left dangling by a forced delete still takes a new lens, as REST's PUT does`() {
        assertTrue(call("set_scene", "{$hall}").success)
        assertTrue(call("set_scene", """{"viewpoints":[{"name":"Row F centre","kind":"seat","seat":"F6"}]}""").success)
        // What `DELETE stage-elements/{id}?force=true` leaves: the seating gone, the view naming it.
        transaction(state.database) {
            DaoStageElement.find { DaoStageElements.project eq projectId }.single { it.name == "Stalls" }.delete()
        }

        val lens = call("set_scene", """{"viewpoints":[{"name":"Row F centre","fovDeg":40}]}""")
        assertTrue(lens.success, lens.result)
        // Naming another seat of the gone seating is a new reference, and is refused.
        val moved = call("set_scene", """{"viewpoints":[{"name":"Row F centre","seat":"G6"}]}""")
        assertFalse(moved.success)
    }

    @Test
    fun `a call that removes a seating cannot keep a view in it by restating the view`() {
        assertTrue(call("set_scene", "{$hall}").success)
        assertTrue(call("set_scene", """{"viewpoints":[{"name":"Row F centre","kind":"seat","seat":"F6"}]}""").success)
        val both = call("set_scene", """{"removeElements":["Stalls"],"viewpoints":[{"name":"Row F centre","fovDeg":40}]}""")
        assertFalse(both.success, both.result)
        assertTrue("Stalls" in elementNames())
    }

    @Test
    fun `a platform names its region by name, and a dry run writes nothing`() {
        assertTrue(call("set_stage", """{"regions":[{"name":"Main stage","centerY":5.5,"widthM":6.3,"depthM":11}]}""").success)
        val dry = call(
            "set_scene",
            """{"dryRun":true,"elements":[{"name":"Main stage","kind":"platform","y":5.5,"widthM":6.3,"depthM":11,"heightM":0.95,"params":{"region":"Main stage"}}]}""",
        )
        assertTrue(dry.success, dry.result)
        assertEquals(emptyList(), elementNames())

        val deck = """{"elements":[{"name":"Main stage","kind":"platform","y":5.5,"widthM":6.3,"depthM":11,"heightM":0.95,"params":{"region":"Main stage"}}]}"""
        assertTrue(call("set_scene", deck).success)
        val row = call("get_scene", "{}").json()["elements"]!!.jsonArray.single().jsonObject
        assertEquals("Main stage", row["params"]!!.jsonObject["region"]!!.jsonPrimitive.content, "get_scene answers the region by name")

        val missing = call(
            "set_scene",
            """{"elements":[{"name":"Rostrum","kind":"platform","widthM":2,"depthM":1,"heightM":0.2,"params":{"region":"Nowhere"}}]}""",
        )
        assertTrue(missing.problems().any { "no stage region named 'Nowhere'" in it }, missing.problems().toString())
    }

    @Test
    fun `a row read back with get_scene can be sent back as it stands`() {
        assertTrue(call("set_scene", "{$hall}").success)
        assertTrue(call("set_scene", """{"viewpoints":[{"name":"Row F centre","kind":"seat","seat":"F6"}]}""").success)
        val scene = call("get_scene", "{}").json()
        val stalls = scene["elements"]!!.jsonArray.single { it.jsonObject["name"]!!.jsonPrimitive.content == "Stalls" }
        val rowF = scene["viewpoints"]!!.jsonArray.single()
        val resent = call("set_scene", """{"elements":[$stalls],"viewpoints":[$rowF]}""")
        assertTrue(resent.success, resent.result)
    }

    @Test
    fun `a field of the wrong type is refused, never read as false or absent`() {
        assertTrue(call("set_scene", """{"elements":[{"name":"Sofa","kind":"object","widthM":1,"depthM":1,"heightM":1}]}""").success)
        val result = call(
            "set_scene",
            """{"elements":[{"name":"Sofa","hidden":"yes","kind":7,"finish":{"emissive":1}}],
               "viewpoints":[{"name":"V","kind":"eye","seat":6}]}""",
        )
        assertFalse(result.success)
        val problems = result.problems()
        assertTrue(problems.any { "hidden must be true or false" in it }, problems.toString())
        assertTrue(problems.any { "emissive must be true or false" in it }, problems.toString())
        assertTrue(problems.any { "kind must be a string" in it }, problems.toString())
        assertTrue(problems.any { "seat must be a string" in it }, problems.toString())
    }

    @Test
    fun `describe_rig gains a stage summary, and LEDGE is a rigging kind`() {
        assertFalse("## Stage" in RigBriefing(state).describeRig(), "no summary for a project with no stage")
        assertTrue(
            call("set_stage", """{"riggings":[{"name":"FOH Balcony","kind":"ledge","y":-16.24,"z":1.9,"lengthM":8.6},{"name":"LX1","kind":"bar","y":3.72,"z":4}]}""").success,
        )
        assertTrue(call("set_scene", "{$hall}").success)
        val briefing = RigBriefing(state).describeRig()
        assertTrue("## Stage" in briefing, briefing)
        assertTrue("LX1 (bar, y 3.7, z 4.0), FOH Balcony (ledge, y −16.2, z 1.9, units stand on it)" in briefing, briefing)
        assertTrue("Scene: 6 venue and 0 set element(s)" in briefing, briefing)
        assertFalse("Mounts:" in briefing, "nothing on the rig disagrees with its mount yet")
    }

    @Test
    fun `describe_rig flags a moving head whose base orientation disagrees with its mount`() {
        assertTrue(
            call("set_stage", """{"riggings":[{"name":"FOH Balcony","kind":"LEDGE","y":-16.24,"z":1.9,"lengthM":8.6},{"name":"LX2","kind":"BAR","y":6.7,"z":4}]}""").success,
        )
        assertTrue(
            call(
                "patch_fixtures",
                """{"fixtures":[
                    {"key":"rev-sr","name":"Balcony Rev SR","fixtureTypeKey":"gear4music-orbit-70-13ch","universe":0,"startChannel":1},
                    {"key":"rev-sl","name":"Balcony Rev SL","fixtureTypeKey":"gear4music-orbit-70-13ch","universe":0,"startChannel":20},
                    {"key":"lx2-1","name":"LX2 mover","fixtureTypeKey":"gear4music-orbit-70-13ch","universe":0,"startChannel":40},
                    {"key":"lx2-2","name":"LX2 standing mover","fixtureTypeKey":"gear4music-orbit-70-13ch","universe":0,"startChannel":60},
                    {"key":"profile","name":"Balcony profile","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":80},
                    {"key":"side","name":"LX2 side mover","fixtureTypeKey":"gear4music-orbit-70-13ch","universe":0,"startChannel":100},
                    {"key":"rolled","name":"Balcony rolled mover","fixtureTypeKey":"gear4music-orbit-70-13ch","universe":0,"startChannel":120},
                    {"key":"override","name":"Balcony override mover","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":140}
                ]}""",
            ).success,
        )
        assertTrue(
            call(
                "place_fixtures",
                """{"placements":[
                    {"key":"rev-sr","rigging":"FOH Balcony","x":-0.9,"pitchDeg":180},
                    {"key":"rev-sl","rigging":"FOH Balcony","x":0.9,"pitchDeg":0},
                    {"key":"lx2-1","rigging":"LX2","x":0,"pitchDeg":180},
                    {"key":"lx2-2","rigging":"LX2","x":1,"pitchDeg":0},
                    {"key":"profile","rigging":"FOH Balcony","x":0,"pitchDeg":180},
                    {"key":"side","rigging":"LX2","x":2,"pitchDeg":90},
                    {"key":"rolled","rigging":"FOH Balcony","x":2,"pitchDeg":0,"rollDeg":180},
                    {"key":"override","rigging":"FOH Balcony","x":3,"pitchDeg":180,"kind":"MOVING_HEAD"}
                ]}""",
            ).success,
        )
        val briefing = RigBriefing(state).describeRig()
        // Hung at 180 on a ledge: drawn and aimed upside down, which is what P5's data fix is for.
        assertTrue("Balcony Rev SR is on FOH Balcony, which it stands on, but its basePitchDeg 180 hangs it" in briefing, briefing)
        // Standing at 0 on a bar: drawn on top of it.
        assertTrue("LX2 standing mover hangs from LX2 but its basePitchDeg 0 stands it on top" in briefing, briefing)
        // Agreeing with their mounts — and a static lantern, whose pitch is its focus — say nothing.
        assertFalse("Balcony Rev SL" in briefing.substringAfter("Mounts:", ""), briefing)
        assertFalse("LX2 mover " in briefing.substringAfter("Mounts:", ""), briefing)
        assertFalse("Balcony profile" in briefing.substringAfter("Mounts:", ""), briefing)
        // A head on its side is neither standing nor hung, so it is not advice-worthy either way.
        assertFalse("LX2 side mover" in briefing.substringAfter("Mounts:", ""), briefing)
        // Read through roll as the view reads it: rolled over, a head at pitch 0 hangs.
        assertTrue("Balcony rolled mover is on FOH Balcony, which it stands on" in briefing, briefing)
        // A mover by the patch's kind override, as the view draws it, though its type is a dimmer.
        assertTrue("Balcony override mover is on FOH Balcony, which it stands on" in briefing, briefing)
    }
}
