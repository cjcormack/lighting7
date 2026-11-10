package uk.me.cormack.lighting7.venue

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.NameFacet
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.cueEventPatchesOf
import uk.me.cormack.lighting7.models.cueEventsOf
import uk.me.cormack.lighting7.models.toDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Venue-rig-fixes plan D24–D25: a show's name for a venue fixture, the Name facet. The route sets it
 * (blank refused), drift answers a venue rename under it, a change rebuilds the live rig, and every
 * reader of a patch's name in the show reads the show's — the patch list, the busk rig, a group's
 * members, a cue event and the trigger guard — while its key stays the venue's.
 */
class NameFacetTest : RouteIntegrationTest() {

    private fun showOnVenue(): Pair<Int, Int> {
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Test")
        }.venueId!!
        return showId to venueId
    }

    @Test
    fun `a show renames a venue fixture, a blank name is refused, and a revert takes the venue's back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, venueId) = showOnVenue()
        val hex1 = client.patches(showId).getValue("hex-1")
        val venueName = hex1.str("displayName")

        val renamed = client.facet(showId, hex1.id(), "NAME", """{"displayName":"  Stage left wash  "}""")
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        val patch = renamed.obj()["patch"]!!.jsonObject
        assertEquals("Stage left wash", patch.str("displayName"), "trimmed")
        assertEquals("hex-1", patch.str("key"), "the key stays the venue's")
        assertEquals(listOf("NAME"), patch.facets().map { it.str("facet") })
        assertEquals("Stage left wash", client.patches(showId).getValue("hex-1").str("displayName"))
        transaction(state.database) {
            val venueRow = DaoFixturePatch.find { (DaoFixturePatches.project eq venueId) and (DaoFixturePatches.key eq "hex-1") }.single()
            assertEquals(venueName, venueRow.displayName, "the venue's draft is untouched")
            val rig = RigScope.of(showId)
            val row = rig.patches().single { it.key == "hex-1" }
            assertEquals("Stage left wash", rig.nameOf(row))
            assertEquals("Stage left wash", rig.effective(row).displayName)
            assertEquals(venueName, row.displayName, "the version's row keeps the venue's name")
        }
        assertEquals(0, state.venueVersions.draftChanges(venueId))

        for (blank in listOf("""{"displayName":"   "}""", """{}""", """{"displayName":"x","key":"y"}""")) {
            assertCode(HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_INVALID, client.facet(showId, hex1.id(), "NAME", blank))
        }

        // The patch PUT still refuses a venue row's name: a rename goes through the facet.
        val put = client.put("/api/rest/projects/$showId/patches/${hex1.id()}") { json("""{"displayName":"Nope"}""") }
        assertEquals(HttpStatusCode.Conflict, put.status, put.bodyAsText())

        val reverted = client.facet(showId, hex1.id(), "NAME", "null")
        assertEquals(HttpStatusCode.OK, reverted.status, reverted.bodyAsText())
        assertEquals(venueName, client.patches(showId).getValue("hex-1").str("displayName"))
    }

    @Test
    fun `a venue rename under the show's name is drift, answered by keep mine or take venue's`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, venueId) = showOnVenue()
        val hex1 = client.patches(showId).getValue("hex-1")
        client.facet(showId, hex1.id(), "NAME", """{"displayName":"Stage left wash"}""")
        assertFalse(client.nameState(showId).bool("drifted"))

        renameAndRepin(venueId, showId, "House left wash", version = 2)
        val drifted = client.nameState(showId)
        assertTrue(drifted.bool("drifted"))
        assertEquals("House left wash", drifted["venue"]!!.jsonObject.str("displayName"))
        assertEquals("Stage left wash", client.patches(showId).getValue("hex-1").str("displayName"), "the show's is kept")
        transaction(state.database) {
            val drift = ShowOverrides.drift(DaoProject[showId]).single()
            assertEquals(PatchFacet.NAME, drift.facet)
            assertTrue(drift.drifted)
        }

        val hex1v2 = client.patches(showId).getValue("hex-1")
        val keep = client.post("/api/rest/projects/$showId/patches/${hex1v2.id()}/facets/name/keep-mine")
        assertEquals(HttpStatusCode.OK, keep.status, keep.bodyAsText())
        assertFalse(client.nameState(showId).bool("drifted"))

        renameAndRepin(venueId, showId, "Wash L", version = 3)
        val hex1v3 = client.patches(showId).getValue("hex-1")
        client.facet(showId, hex1v3.id(), "NAME", "null")
        assertEquals("Wash L", client.patches(showId).getValue("hex-1").str("displayName"), "take venue's")
    }

    @Test
    fun `a rename rebuilds the live rig, and the busk rig and a group's members name it the show's way`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        runBlocking { state.projectManager.switchProject(showId) }
        val hex1 = client.patches(showId).getValue("hex-1")

        client.facet(showId, hex1.id(), "NAME", """{"displayName":"Stage left wash"}""")
        assertEquals("Stage left wash", state.show.fixtures.untypedFixture("hex-1").fixtureName, "the loader names it from the effective patch")

        val rig = client.put("/api/rest/projects/$showId/busk/rig") {
            json("""{"rows":[{"name":"Front","tiles":[{"patchId":${hex1.id()},"cellMode":"PIPS"}]}]}""")
        }
        assertEquals(HttpStatusCode.OK, rig.status, rig.bodyAsText())
        val tile = rig.obj()["rows"]!!.jsonArray.single().jsonObject["tiles"]!!.jsonArray.single().jsonObject
        assertEquals("Stage left wash", tile["patch"]!!.jsonObject.str("name"))

        val groupId = transaction(state.database) {
            val rigScope = RigScope.of(showId)
            rigScope.groups().first { g -> rigScope.members(g).any { it.fixturePatch.key == "hex-1" } }.id.value
        }
        val group = client.get("/api/rest/projects/$showId/patch-groups/$groupId")
        assertEquals(HttpStatusCode.OK, group.status, group.bodyAsText())
        val member = group.obj()["members"]!!.jsonArray.map { it.jsonObject }.single { it.str("fixtureKey") == "hex-1" }
        assertEquals("Stage left wash", member.str("fixtureName"))
    }

    @Test
    fun `a cue event and the trigger guard name a renamed cannon the show's way`() {
        val (showId, _) = showOnVenue()
        transaction(state.database) {
            val show = DaoProject[showId]
            val target = ShowOverrides.target(show, RigScope.of(show).patches().single { it.key == "cannon-1" }.id.value)
            ShowOverrides.put(show, target, PatchFacet.NAME, NameFacet("Finale cannon"))
        }
        transaction(state.database) {
            val show = DaoProject[showId]
            val rig = RigScope.of(show)
            val cannonId = rig.patches().single { it.key == "cannon-1" }.id.value
            assertEquals("Finale cannon", cueEventPatchesOf(show).getValue(cannonId).name)
            val events = DaoCue.find { DaoCues.project eq showId }.flatMap { cueEventsOf(it.id) }.filter { it.patch.id.value == cannonId }
            assertTrue(events.isNotEmpty(), "the rich fixture's cue fires the cannon")
            assertTrue(events.all { it.toDto(rig).fixtureName == "Finale cannon" })
            val refusal = TriggerIndex.of(show).refusal("fixture", "cannon-1", "output1", "row")
            assertTrue(refusal != null && "'Finale cannon'" in refusal, refusal)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun renameAndRepin(venueId: Int, showId: Int, name: String, version: Int) {
        transaction(state.database) {
            DaoFixturePatch.find { (DaoFixturePatches.project eq venueId) and (DaoFixturePatches.key eq "hex-1") }.single().displayName = name
        }
        val published = runBlocking { state.venueVersions.publish(venueId, "Rename hex-1", "Test") }
        assertEquals(version, published.number)
        transaction(state.database) { DaoProject[showId].venueVersion = version }
    }

    private suspend fun HttpClient.patches(projectId: Int): Map<String, JsonObject> {
        val response = get("/api/rest/projects/$projectId/patches")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return TestJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
    }

    private suspend fun HttpClient.facet(projectId: Int, patchId: Int, facet: String, value: String): HttpResponse =
        put("/api/rest/projects/$projectId/patches/$patchId/facets/$facet") { json("""{"value":$value}""") }

    private suspend fun HttpClient.nameState(showId: Int): JsonObject =
        patches(showId).getValue("hex-1").facets().single { it.str("facet") == "NAME" }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.id(): Int = this["id"]!!.jsonPrimitive.int
    private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content
    private fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.boolean ?: false
    private fun JsonObject.facets(): List<JsonObject> = (this["facets"] as? JsonArray).orEmpty().map { it.jsonObject }

    private suspend fun assertCode(status: HttpStatusCode, code: String, response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals(code, TestJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content, body)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
