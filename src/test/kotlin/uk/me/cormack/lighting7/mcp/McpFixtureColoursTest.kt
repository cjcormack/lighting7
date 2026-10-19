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
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoShowColour
import uk.me.cormack.lighting7.models.DaoShowColours
import uk.me.cormack.lighting7.models.FixtureColours
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import uk.me.cormack.lighting7.venue.VenueRigGuard
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fixture colours over MCP (tables-colours plan session 3): `patch_fixtures`, `place_fixtures`,
 * `override_venue_fixtures` and `edit_groups` take `colour` with REST's validation and its
 * `COLOUR_UNKNOWN`, a show's colour on a venue record is its recolour, and `describe_rig` and
 * `get_patch` list it.
 */
class McpFixtureColoursTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ok(name: String, arguments: String): JsonObject =
        call(name, arguments).also { assertTrue(it.success, it.result) }.json()

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun assertRefused(code: String, result: ToolExecutionResult, where: String) {
        assertFalse(result.success, result.result)
        assertEquals(code, result.json()["code"]?.jsonPrimitive?.content, result.result)
        assertTrue(where in result.result, result.result)
    }

    private fun patchColour(projectId: Int, key: String): String? = transaction(state.database) {
        DaoFixturePatch.find { (DaoFixturePatches.project eq projectId) and (DaoFixturePatches.key eq key) }.single().colour
    }

    private fun liveShowOnVenue(): Int {
        val showId = seedVenueReadyShow(state)
        runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = null)
            state.projectManager.switchProject(showId)
        }
        return showId
    }

    @Test
    fun `patch_fixtures and place_fixtures take a colour and refuse an unknown one as REST does`() {
        ok(
            "patch_fixtures",
            """{"fixtures":[{"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1,"colour":"Red"}]}""",
        )
        assertEquals("red", patchColour(projectId, "foh-1"))

        val refused = call(
            "patch_fixtures",
            """{"fixtures":[{"key":"foh-2","name":"FOH 2","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":2,"colour":"blue"}]}""",
        )
        assertRefused(FixtureColours.CODE_UNKNOWN, refused, "fixtures[0]")
        assertTrue("reserved" in refused.result, refused.result)

        ok("place_fixtures", """{"placements":[{"key":"foh-1","colour":"lime"}]}""")
        assertEquals("lime", patchColour(projectId, "foh-1"))
        assertRefused(FixtureColours.CODE_UNKNOWN, call("place_fixtures", """{"placements":[{"key":"foh-1","colour":"magenta"}]}"""), "placements[0]")
        ok("place_fixtures", """{"placements":[{"key":"foh-1","colour":null}]}""")
        assertNull(patchColour(projectId, "foh-1"))
    }

    @Test
    fun `edit_groups colours a group and refuses an unknown colour`() {
        ok(
            "patch_fixtures",
            """{"fixtures":[{"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1,"groups":["FOH"]}]}""",
        )
        val live = state.show.fixtures.untypedFixture("foh-1")
        val edited = ok("edit_groups", """{"groups":[{"name":"FOH","colour":"yellow"}]}""")
        assertSame(live, state.show.fixtures.untypedFixture("foh-1"), "a colour-only edit does not rebuild the live rig")
        assertEquals("yellow", edited["groups"]!!.jsonArray.single().jsonObject["colour"]?.jsonPrimitive?.content)
        transaction(state.database) {
            assertEquals("yellow", DaoFixtureGroup.find { (DaoFixtureGroups.project eq projectId) and (DaoFixtureGroups.name eq "FOH") }.single().colour)
        }
        assertNull(patchColour(projectId, "foh-1"), "a group's colour never reaches its members")
        assertRefused(FixtureColours.CODE_UNKNOWN, call("edit_groups", """{"groups":[{"name":"FOH","colour":"violet"}]}"""), "groups[0]")
    }

    @Test
    fun `on a show on a venue a colour is the show's recolour, from every tool`() {
        val showId = liveShowOnVenue()
        fun recolours(): Map<String, String> = transaction(state.database) {
            val rig = RigScope.of(showId)
            val names = rig.patches().associate { it.uuid to it.key } + rig.groups().associate { it.uuid to it.name }
            DaoShowColour.find { DaoShowColours.project eq showId }.associate { names.getValue(it.recordUuid) to it.colour }
        }

        // override_venue_fixtures: a recolour, and null takes the venue's back.
        ok("override_venue_fixtures", """{"fixtures":[{"key":"hex-1","colour":"pink"}]}""")
        assertEquals(mapOf("hex-1" to "pink"), recolours())
        assertEquals("red", patchColour(RigScope.of(showId).let { transaction(state.database) { it.venue!!.snapshotId.value } }, "hex-1"), "the version is never written")
        ok("override_venue_fixtures", """{"fixtures":[{"key":"hex-1","colour":null}]}""")
        assertEquals(emptyMap(), recolours())
        val hiddenAndColoured = call("override_venue_fixtures", """{"fixtures":[{"key":"hex-1","hidden":true,"colour":"pink"}]}""")
        assertFalse(hiddenAndColoured.success, hiddenAndColoured.result)
        ok("override_venue_fixtures", """{"fixtures":[{"key":"hex-4","hidden":true}]}""")
        val onHidden = call("override_venue_fixtures", """{"fixtures":[{"key":"hex-4","colour":"pink"}]}""")
        assertFalse(onHidden.success, onHidden.result)
        assertTrue(VenueRigGuard.CODE_VENUE_OWNED in onHidden.result, "the REST patch PUT's code: ${onHidden.result}")
        ok("override_venue_fixtures", """{"fixtures":[{"key":"hex-4","hidden":false}]}""")

        // place_fixtures: the colour needs no facet.
        ok("place_fixtures", """{"placements":[{"key":"hex-2","colour":"white"}]}""")
        assertEquals(mapOf("hex-2" to "white"), recolours())

        // edit_groups: a colour-only row recolours the venue's group; anything more is the venue's.
        ok("edit_groups", """{"groups":[{"name":"front-wash","colour":"orange"}]}""")
        assertEquals("orange", recolours()["front-wash"])
        val refused = call("edit_groups", """{"groups":[{"name":"front-wash","colour":"lime","newName":"Front"}]}""")
        assertRefused(VenueRigGuard.CODE_VENUE_OWNED, refused, "front-wash")

        // get_patch and describe_rig say so.
        val patch = ok("get_patch", "{}")
        val hex2 = patch["fixtures"]!!.jsonArray.map { it.jsonObject }.single { it["key"]!!.jsonPrimitive.content == "hex-2" }
        assertEquals("white", hex2["colour"]!!.jsonPrimitive.content)
        assertEquals("show", hex2["colourOwner"]!!.jsonPrimitive.content)
        val rig = RigBriefing(state).describeRig()
        assertTrue("key=`hex-2`" in rig && "colour=white" in rig, rig)
        assertTrue(rig.lines().any { "**front-wash**" in it && "colour=orange" in it }, rig)
    }
}
