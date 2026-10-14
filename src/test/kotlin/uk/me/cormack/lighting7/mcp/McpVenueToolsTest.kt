package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.MediaFacet
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.venue.ShowOverrides
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
 * record with `VENUE_OWNED` — naming `override_venue_fixtures` as what a show can do instead —
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

    /** A refusal of a venue fixture also names the tool that overrides or hides one; any other record's does not. */
    private fun assertVenueOwned(result: ToolExecutionResult, fixture: Boolean = false) {
        assertFalse(result.success, result.result)
        val json = result.json()
        assertEquals(VenueRigGuard.CODE_VENUE_OWNED, json["code"]?.jsonPrimitive?.content, result.result)
        assertTrue("publish_venue" in result.result, "the refusal names how the venue changes for every show: ${result.result}")
        assertEquals(fixture, "override_venue_fixtures" in result.result, result.result)
    }

    @Test
    fun `the setup tools refuse every write to a venue record`() {
        liveShowOnVenue()
        assertVenueOwned(call("patch_fixtures", """{"fixtures":[{"key":"hex-1","name":"Mine","fixtureTypeKey":"hex","universe":0,"startChannel":10}]}"""), fixture = true)
        assertVenueOwned(call("patch_fixtures", """{"fixtures":[{"key":"spot-9","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":1,"groups":["front-wash"]}]}"""))
        // Placing one needs its Position to be this show's first (McpVenueFixtureOverridesTest).
        assertVenueOwned(call("place_fixtures", """{"placements":[{"key":"hex-1","x":1.0}]}"""), fixture = true)
        assertVenueOwned(call("delete_groups", """{"names":["front-wash"],"force":true}"""))
        assertVenueOwned(call("unpatch_fixtures", """{"keys":["hex-1"]}"""), fixture = true)
        assertVenueOwned(call("unpatch_fixtures", """{"universes":[{"universe":0}]}"""))
        assertVenueOwned(call("rename_fixtures", """{"fixtures":[{"key":"hex-1","newKey":"hex-one"}]}"""), fixture = true)
        assertVenueOwned(call("edit_groups", """{"groups":[{"name":"front-wash","remove":["hex-1"]}]}"""))
        assertVenueOwned(call("set_stage", """{"riggings":[{"name":"FOH Truss","y":-3.0}]}"""))
        assertVenueOwned(call("set_stage", """{"stage":{"widthM":4.0}}"""))
        assertVenueOwned(call("set_scene", """{"removeElements":["House tabs"]}"""))

        // The show's own additions still go through: a hired spot on its own universe, in its own group.
        val added = call("patch_fixtures", """{"fixtures":[{"key":"spot-9","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":1,"groups":["panto"]}]}""")
        assertTrue(added.success, added.result)

        // A show's own group may hold a venue fixture; a show's own fixture may not take a venue key.
        val grouped = call("edit_groups", """{"groups":[{"name":"panto","add":["hex-1"]}]}""")
        assertTrue(grouped.success, grouped.result)
        val renamed = call("rename_fixtures", """{"fixtures":[{"key":"spot-9","newKey":"hex-2"}]}""")
        assertFalse(renamed.success, renamed.result)
        assertEquals(VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE, renamed.json()["code"]?.jsonPrimitive?.content, renamed.result)
        val unpatched = call("unpatch_fixtures", """{"keys":["spot-9"],"dryRun":true}""")
        assertTrue(unpatched.success, unpatched.result)

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

    /**
     * Session 5: `describe_rig` and `get_patch` name a venue fixture's overridden facets — a drifted or
     * dormant one marked — and `set_stage`'s rigging removal detaches a Position override hung on the
     * show's own rigging, offsets kept, as the REST delete does.
     */
    @Test
    fun `the tools name overridden facets, and set_stage detaches an override from a removed rigging`() {
        liveShowOnVenue()
        val showId = state.projectManager.currentProject.id.value
        assertTrue(call("set_stage", """{"riggings":[{"name":"Panto boom","kind":"BOOM","x":-4.0}]}""").success)
        transaction(state.database) {
            val show = DaoProject[showId]
            val rig = RigScope.of(show)
            val boom = rig.own.riggings().single { it.name == "Panto boom" }
            val hex1 = ShowOverrides.target(show, rig.patches().single { it.key == "hex-1" }.id.value)
            ShowOverrides.put(show, hex1, PatchFacet.POSITION, PositionFacet(riggingUuid = boom.uuid.toString(), stageX = 0.5, stageZ = 1.75))
            ShowOverrides.put(show, hex1, PatchFacet.MEDIA, MediaFacet(gelCode = "L106"))
            // Dormant: a Position on hex-2 naming a rigging no rig holds.
            val hex2 = ShowOverrides.target(show, rig.patches().single { it.key == "hex-2" }.id.value)
            ShowOverrides.put(show, hex2, PatchFacet.POSITION, PositionFacet(riggingUuid = java.util.UUID.randomUUID().toString()))
        }
        val briefing = RigBriefing(state).describeRig()
        assertTrue(briefing.lines().any { "key=`hex-1`" in it && "overrides=position,media" in it }, briefing)
        assertTrue(briefing.lines().any { "key=`hex-2`" in it && "position (dormant" in it }, briefing)
        val fixtures = call("get_patch", "{}").json()["fixtures"]!!.jsonArray.associateBy { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals(listOf("POSITION", "MEDIA"), fixtures.getValue("hex-1").jsonObject["overrides"]!!.jsonArray.map { it.jsonObject["facet"]!!.jsonPrimitive.content })
        assertEquals("true", fixtures.getValue("hex-2").jsonObject["overrides"]!!.jsonArray.single().jsonObject["dormant"]!!.jsonPrimitive.content)

        assertTrue(call("set_stage", """{"removeRiggings":["Panto boom"]}""").success)
        transaction(state.database) {
            val rig = RigScope.of(showId)
            val hex1 = rig.effective(rig.patches().single { it.key == "hex-1" })
            assertEquals(null, hex1.rigging, "detached")
            assertEquals(1.75, hex1.stageZ, "offsets kept")
            assertTrue(PatchFacet.POSITION in hex1.overridden, "the override stays live, not dormant")
        }
    }
}
