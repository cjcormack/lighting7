package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import uk.me.cormack.lighting7.venue.VenueRigGuard
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Venue plan session 3 over MCP: on a show on a venue the setup tools refuse every write to a venue
 * record with `VENUE_OWNED` — naming hiding as what a show can do, and facets as a later release —
 * while the show's own records stay writable; `describe_rig` and `get_patch` say whose each is.
 */
class McpVenueToolsTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun liveShowOnVenue() {
        val showId = seedVenueReadyShow(state)
        runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = null)
            state.projectManager.switchProject(showId)
        }
    }

    private fun assertVenueOwned(result: ToolExecutionResult) {
        assertFalse(result.success, result.result)
        val json = result.json()
        assertEquals(VenueRigGuard.CODE_VENUE_OWNED, json["code"]?.jsonPrimitive?.content, result.result)
        val text = result.result
        assertTrue("hide" in text, "the refusal names hiding: $text")
        assertTrue("facet overrides" in text, "and facets as a later release: $text")
    }

    @Test
    fun `the setup tools refuse every write to a venue record`() {
        liveShowOnVenue()
        assertVenueOwned(call("patch_fixtures", """{"fixtures":[{"key":"hex-1","name":"Mine","fixtureTypeKey":"hex","universe":0,"startChannel":10}]}"""))
        assertVenueOwned(call("patch_fixtures", """{"fixtures":[{"key":"spot-9","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":1,"groups":["front-wash"]}]}"""))
        assertVenueOwned(call("place_fixtures", """{"placements":[{"key":"hex-1","x":1.0}]}"""))
        assertVenueOwned(call("delete_groups", """{"names":["front-wash"],"force":true}"""))
        assertVenueOwned(call("set_stage", """{"riggings":[{"name":"FOH Truss","y":-3.0}]}"""))
        assertVenueOwned(call("set_stage", """{"stage":{"widthM":4.0}}"""))
        assertVenueOwned(call("set_scene", """{"removeElements":["House tabs"]}"""))

        // The show's own additions still go through: a hired spot on its own universe, in its own group.
        val added = call("patch_fixtures", """{"fixtures":[{"key":"spot-9","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":1,"groups":["panto"]}]}""")
        assertTrue(added.success, added.result)

        // A venue fixture's head number is the venue's, coded as REST codes it.
        val numbered = call("patch_fixtures", """{"fixtures":[{"key":"spot-10","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":2,"headNumber":104}]}""")
        assertFalse(numbered.success, numbered.result)
        assertEquals(VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE, numbered.json()["code"]?.jsonPrimitive?.content, numbered.result)
    }

    @Test
    fun `describe_rig and get_patch say whose each record is`() {
        liveShowOnVenue()
        call("patch_fixtures", """{"fixtures":[{"key":"spot-9","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":1}]}""")
        val briefing = RigBriefing(state).describeRig()
        assertTrue("## Venue" in briefing && "Commemoration Hall" in briefing, briefing)
        assertTrue(briefing.lines().any { "key=`hex-1`" in it && "owner=venue" in it }, briefing)
        assertTrue(briefing.lines().any { "key=`spot-9`" in it && "owner=show" in it }, briefing)
        assertTrue("FOH Truss [venue's]" in briefing, briefing)

        val patch = call("get_patch", "{}").json()
        assertEquals("Commemoration Hall", patch["venue"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val owners = patch["fixtures"]!!.jsonArray.associate {
            it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["owner"]!!.jsonPrimitive.content
        }
        assertEquals("venue", owners["hex-1"])
        assertEquals("show", owners["spot-9"])
    }
}
