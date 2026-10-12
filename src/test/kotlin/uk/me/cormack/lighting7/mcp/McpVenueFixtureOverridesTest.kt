package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.fixture.DmxFixture
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.EffectivePatch
import uk.me.cormack.lighting7.models.FocusFacet
import uk.me.cormack.lighting7.models.encodeFacet
import uk.me.cormack.lighting7.models.EffectivePlacement
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.ShowOverrides
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import uk.me.cormack.lighting7.venue.VenueRigGuard
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A show's venue fixtures over MCP: `override_venue_fixtures` is the fixture sheet's *Venue | This show*
 * switch, *Keep mine*, *Hide in this show* and an Address override, and `place_fixtures` edits a venue
 * fixture through the facets the show owns — `adv2-1`, the seeded zoom profile with an SR lantern.
 */
class McpVenueFixtureOverridesTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ok(name: String, arguments: String): JsonObject =
        call(name, arguments).also { assertTrue(it.success, it.result) }.json()

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ToolExecutionResult.problems(): String = json()["problems"]?.jsonArray?.joinToString("\n") ?: result

    private fun liveShowOnVenue(): Int {
        val showId = seedVenueReadyShow(state)
        runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = null)
            state.projectManager.switchProject(showId)
        }
        return showId
    }

    private fun <T> profile(showId: Int, read: (EffectivePatch, List<EffectivePlacement>) -> T): T = transaction(state.database) {
        val rig = RigScope.of(showId)
        val row = rig.patches().single { it.key == "adv2-1" }
        read(rig.effective(row), rig.effectivePlacements(row, extraPlacementsOf(row)))
    }

    @Test
    fun `focus is placed once the show has made it its own`() {
        val showId = liveShowOnVenue()

        val refused = call("place_fixtures", """{"placements":[{"key":"adv2-1","zoomDeg":40.0}]}""")
        assertFalse(refused.success, refused.result)
        assertEquals(VenueRigGuard.CODE_VENUE_OWNED, refused.json()["code"]?.jsonPrimitive?.content, refused.result)
        assertTrue("override_venue_fixtures" in refused.result && "FOCUS" in refused.result, refused.result)

        // The switch: the venue's value becomes the show's, so nothing drawn changes.
        val flipped = ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"FOCUS":"SHOW"}}]}""")
        val row = flipped["fixtures"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("FOCUS"), row["overrides"]!!.jsonArray.map { it.jsonObject["facet"]!!.jsonPrimitive.content })
        assertEquals(31.5, profile(showId) { p, _ -> p.focus.zoomDeg })
        // Again is a no-op: the show's value stays.
        val again = ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"FOCUS":"SHOW"}}]}""")
        assertTrue(again["fixtures"]!!.jsonArray.single().jsonObject["changed"]!!.jsonArray.isEmpty(), again.toString())

        val placed = ok(
            "place_fixtures",
            """{"placements":[{"key":"adv2-1","zoomDeg":40.0,"iris":0.5,"alsoAt":[{"label":"SR","focusSoftness":0.1}]}]}""",
        )
        assertEquals(1, placed["placed"]!!.jsonPrimitive.content.toInt())
        profile(showId) { p, placements ->
            assertEquals(40.0, p.focus.zoomDeg)
            assertEquals(0.5, p.focus.iris)
            assertEquals(7.5, p.focus.gateRotationDeg, "a field the row did not send is kept")
            assertEquals(0.1, placements.single().focus.focusSoftness)
            assertEquals(45.0, placements.single().focus.lampRotationDeg, "and the lantern's own unsent fields")
            assertTrue(PatchFacet.POSITION !in p.overridden, "only the touched facet")
        }
        transaction(state.database) {
            val venueRow = RigScope.of(showId).patches().single { it.key == "adv2-1" }
            assertEquals(31.5, venueRow.zoomDeg, "the venue's own row is untouched")
        }

        // A lantern change without a zoom drops the zoom the new lantern cannot take, as a patch write does.
        ok("place_fixtures", """{"placements":[{"key":"adv2-1","lanternType":"s4-26"}]}""")
        profile(showId) { p, _ ->
            assertEquals("s4-26", p.focus.lanternType)
            assertNull(p.focus.zoomDeg)
        }

        // A value the lantern refuses is refused whole, nothing written.
        val bad = call("place_fixtures", """{"placements":[{"key":"adv2-1","zoomDeg":30.0}]}""")
        assertFalse(bad.success, bad.result)
        assertTrue("focus" in bad.problems(), bad.result)

        // Take the venue's: the override goes and the venue's focus stands again.
        ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"FOCUS":"VENUE"}}]}""")
        profile(showId) { p, _ ->
            assertEquals("s4-zoom-25-50", p.focus.lanternType)
            assertEquals(31.5, p.focus.zoomDeg)
            assertTrue(p.overridden.isEmpty())
        }
    }

    @Test
    fun `position and media go through their facets, and the venue's identity stays the venue's`() {
        val showId = liveShowOnVenue()
        ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"POSITION":"SHOW","MEDIA":"SHOW"}}]}""")
        ok("place_fixtures", """{"placements":[{"key":"adv2-1","x":2.25,"gelCode":"L106","alsoAt":[{"x":-2.25}]}]}""")
        profile(showId) { p, placements ->
            assertEquals(2.25, p.stageX)
            assertEquals(-3.0, p.stageY, "unsent position fields kept")
            assertEquals("L106", p.gelCode)
            assertEquals(-2.25, placements.single().stageX)
        }

        assertEquals("L106", state.show.fixtures.patchMetadataFor("adv2-1")?.gelCode, "a Media edit reloads the live rig")

        // A rigging by name — the venue's — and back off it; a name the rig lacks is refused.
        ok("place_fixtures", """{"placements":[{"key":"adv2-1","rigging":"FOH Truss","x":0.5,"alsoAt":[{"rigging":"FOH Truss"}]}]}""")
        profile(showId) { p, placements ->
            assertEquals("FOH Truss", p.rigging?.name)
            assertEquals(0.5, p.stageX)
            assertEquals("FOH Truss", placements.single().rigging?.name)
        }
        ok("place_fixtures", """{"placements":[{"key":"adv2-1","rigging":null}]}""")
        assertNull(profile(showId) { p, _ -> p.rigging })
        val unknown = call("place_fixtures", """{"placements":[{"key":"adv2-1","rigging":"Nowhere"}]}""")
        assertFalse(unknown.success)
        assertTrue("no rigging named 'Nowhere'" in unknown.problems(), unknown.result)

        val refusals = call(
            "place_fixtures",
            """{"placements":[
                {"key":"adv2-1","kind":"FRESNEL"},
                {"key":"adv2-1","x":1.0}
            ]}""",
        )
        assertFalse(refusals.success)
        assertTrue("kind" in refusals.problems() && "listed twice" in refusals.problems(), refusals.result)

        val lanterns = call("place_fixtures", """{"placements":[{"key":"adv2-1","alsoAt":[{"x":0.0},{"x":1.0}]}]}""")
        assertFalse(lanterns.success)
        assertTrue("cannot add or remove" in lanterns.problems(), lanterns.result)
        val relabel = call("place_fixtures", """{"placements":[{"key":"adv2-1","alsoAt":[{"label":"SL"}]}]}""")
        assertFalse(relabel.success)
        assertTrue("label is the venue's" in relabel.problems(), relabel.result)
    }

    @Test
    fun `an address is set through the tool, and a dry run writes nothing`() {
        val showId = liveShowOnVenue()
        val dry = ok("override_venue_fixtures", """{"dryRun":true,"fixtures":[{"key":"adv2-1","address":{"universe":0,"startChannel":470}}]}""")
        assertEquals("ADDRESS", dry["fixtures"]!!.jsonArray.single().jsonObject["overrides"]!!.jsonArray.single().jsonObject["facet"]!!.jsonPrimitive.content)
        assertEquals(300, profile(showId) { p, _ -> p.startChannel }, "the dry run rolled back")

        ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","address":{"universe":0,"startChannel":470}}]}""")
        assertEquals(470, profile(showId) { p, _ -> p.startChannel })
        assertEquals(470, (state.show.fixtures.untypedFixture("adv2-1") as DmxFixture).firstChannel, "the live rig reloaded")

        val outOfRange = call("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","address":{"universe":0,"startChannel":513}}]}""")
        assertFalse(outOfRange.success, outOfRange.result)
        val conflicting = call("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"ADDRESS":"VENUE"},"address":{"universe":0,"startChannel":2}}]}""")
        assertFalse(conflicting.success, conflicting.result)
    }

    @Test
    fun `a venue fixture is hidden and shown, and the refusals name what to do`() {
        val showId = liveShowOnVenue()
        val notMine = call("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"FOCUS":"KEEP_MINE"}}]}""")
        assertFalse(notMine.success)
        assertTrue(ShowOverrides.CODE_NOT_OVERRIDDEN in notMine.problems(), notMine.result)

        ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","hidden":true}]}""")
        val venue = ok("get_patch", "{}")["venue"]!!.jsonObject
        assertEquals("adv2-1", venue["hiddenFixtures"]!!.jsonArray.single().jsonObject["key"]!!.jsonPrimitive.content)
        val placeHidden = call("place_fixtures", """{"placements":[{"key":"adv2-1","x":1.0}]}""")
        assertFalse(placeHidden.success)
        assertTrue("hidden: false" in placeHidden.problems(), placeHidden.result)
        val overrideHidden = call("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"FOCUS":"SHOW"}}]}""")
        assertFalse(overrideHidden.success)
        assertTrue(ShowOverrides.CODE_PATCH_HIDDEN in overrideHidden.problems(), overrideHidden.result)

        // Shown again and overridden in one row: the show comes first.
        ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","hidden":false,"facets":{"FOCUS":"SHOW"}}]}""")
        assertTrue(PatchFacet.FOCUS in profile(showId) { p, _ -> p.overridden })

        ok("patch_fixtures", """{"fixtures":[{"key":"spot-9","name":"Spot","fixtureTypeKey":"uv","universe":2,"startChannel":1}]}""")
        val own = call("override_venue_fixtures", """{"fixtures":[{"key":"spot-9","facets":{"FOCUS":"SHOW"}},{"key":"nope","hidden":true}]}""")
        assertFalse(own.success)
        assertTrue("this show's own" in own.problems() && "no fixture with this key" in own.problems(), own.result)
        assertTrue("override_venue_fixtures" in call("patch_fixtures", """{"fixtures":[{"key":"hex-1","name":"Mine","fixtureTypeKey":"hex","universe":0,"startChannel":10}]}""").result)
    }

    @Test
    fun `keep mine answers a drifted facet and keeps the show's value`() {
        val showId = liveShowOnVenue()
        ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"FOCUS":"SHOW"}}]}""")
        ok("place_fixtures", """{"placements":[{"key":"adv2-1","iris":0.5}]}""")
        // The venue moved under the override: its base no longer matches the pinned version's value.
        transaction(state.database) {
            DaoShowOverride.find { (DaoShowOverrides.project eq showId) and (DaoShowOverrides.facet eq PatchFacet.FOCUS) }
                .single().base = encodeFacet(FocusFacet(lanternType = "s4-26"))
        }
        fun drifted() = ok("get_patch", "{}")["fixtures"]!!.jsonArray.map { it.jsonObject }
            .single { it["key"]!!.jsonPrimitive.content == "adv2-1" }["overrides"]!!.jsonArray.single().jsonObject["drifted"]
        assertEquals("true", drifted()?.jsonPrimitive?.content)

        val kept = ok("override_venue_fixtures", """{"fixtures":[{"key":"adv2-1","facets":{"FOCUS":"KEEP_MINE"}}]}""")
        assertEquals(listOf("focus: kept mine"), kept["fixtures"]!!.jsonArray.single().jsonObject["changed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(drifted(), "the drift is answered")
        assertEquals(0.5, profile(showId) { p, _ -> p.focus.iris }, "and the show's value stays")
    }

    @Test
    fun `a standalone show has no venue fixtures to override`() {
        val refused = call("override_venue_fixtures", """{"fixtures":[{"key":"hex-1","hidden":true}]}""")
        assertFalse(refused.success)
        assertTrue("not a show on a venue" in refused.problems(), refused.result)
    }
}
