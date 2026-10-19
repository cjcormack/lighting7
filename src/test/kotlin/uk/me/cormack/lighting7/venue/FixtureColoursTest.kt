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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoShowColour
import uk.me.cormack.lighting7.models.DaoShowColours
import uk.me.cormack.lighting7.models.FixtureColours
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.ShowColours
import uk.me.cormack.lighting7.routes.deleteOwnPatch
import uk.me.cormack.lighting7.sync.ProjectCloner
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.ShowColourJson
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture colours (tables-colours plan session 3; D7, D9, D10): the ten keys at the write boundary and a
 * stored unknown key reading as none; the patch PUT, its bulk path and the group PUT; a show's recolour
 * of its venue's patches and groups through `show_colours`; and the colour through sync, clone, Extract,
 * Detach and Attach.
 */
class FixtureColoursTest : RouteIntegrationTest() {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── The keys ───────────────────────────────────────────────────────

    @Test
    fun `a write takes the ten keys and refuses anything else by name`() {
        assertEquals(10, FixtureColours.KEYS.size)
        assertEquals("red", FixtureColours.normalise(" Red ").getOrThrow())
        assertNull(FixtureColours.normalise("").getOrThrow(), "blank is a clear")
        assertNull(FixtureColours.normalise(null).getOrThrow())
        for (reserved in FixtureColours.RESERVED.keys) {
            val message = FixtureColours.normalise(reserved).exceptionOrNull()?.message.orEmpty()
            assertTrue("reserved" in message, message)
        }
        assertTrue(FixtureColours.normalise("magenta").isFailure)
        assertTrue(FixtureColours.parse(JsonPrimitive(3)).isFailure, "a number is not a colour")
        assertNull(FixtureColours.read("magenta"), "a stored key this desk does not know reads as none")
        assertEquals("lime", FixtureColours.read("lime"))
    }

    @Test
    fun `the patch PUT sets, clears and refuses a colour, and a stored unknown key reads as none`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedVenueReadyShow(state)
        val hex2 = client.patches(showId).getValue("hex-2")

        val set = client.putPatch(showId, hex2.id(), """{"colour":"Orange"}""")
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals("orange", set.obj().str("colour"))
        assertNull(set.obj().opt("colourOwner"), "a show's own patch has no colour owner")

        for (bad in listOf(""""blue"""", """"magenta"""", "7")) {
            val refused = client.putPatch(showId, hex2.id(), """{"colour":$bad}""")
            assertCode(HttpStatusCode.BadRequest, FixtureColours.CODE_UNKNOWN, refused)
        }
        assertEquals("orange", client.patches(showId).getValue("hex-2").str("colour"), "a refusal writes nothing")

        val cleared = client.putPatch(showId, hex2.id(), """{"colour":null}""")
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertNull(cleared.obj().opt("colour"))

        // Another desk's key, stored as an import would store it: none here, and kept on export.
        transaction(state.database) { DaoFixturePatch[hex2.id()].colour = "magenta" }
        assertNull(client.patches(showId).getValue("hex-2").opt("colour"))
        val dir = tmp.newFolder("unknown").toPath()
        ProjectExporter(state).export(showId, dir)
        val exported = Files.list(dir.resolve("fixturePatches")).toList()
            .map { TestJson.decodeFromString(FixturePatchJson.serializer(), Files.readString(it)) }
            .single { it.key == "hex-2" }
        assertEquals("magenta", exported.colour, "sync carries a key it does not know as stored")
    }

    @Test
    fun `a patch create takes a colour and refuses an unknown one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val created = client.postJson(
            "/api/rest/projects/$projectId/patches",
            """{"universe":0,"fixtureTypeKey":"hex","key":"spot","name":"Spot","startChannel":300,"colour":"cyan"}""",
        )
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals("cyan", created.obj().str("colour"))
        val refused = client.postJson(
            "/api/rest/projects/$projectId/patches",
            """{"universe":0,"fixtureTypeKey":"hex","key":"spot-2","name":"Spot 2","startChannel":320,"colour":"teal"}""",
        )
        assertCode(HttpStatusCode.BadRequest, FixtureColours.CODE_UNKNOWN, refused)
    }

    @Test
    fun `the bulk path colours a marquee of patches in one request and names every bad colour at once`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedVenueReadyShow(state)
        val patches = client.patches(showId)
        val ids = listOf("hex-1", "hex-2", "hex-3").map { patches.getValue(it).id() }

        val ok = client.bulk(showId, """{"updates":[${ids.joinToString { """{"patchId":$it,"colour":"yellow"}""" }}]}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(3, ok.obj()["updated"]!!.jsonArray.size)
        val after = client.patches(showId)
        assertEquals(listOf("yellow", "yellow", "yellow"), listOf("hex-1", "hex-2", "hex-3").map { after.getValue(it).str("colour") })

        val refused = client.bulk(
            showId,
            """{"updates":[{"patchId":${ids[0]},"colour":"blue"},{"patchId":${ids[1]},"colour":"lime"},{"patchId":${ids[2]},"colour":"sky"}]}""",
        )
        assertCode(HttpStatusCode.BadRequest, FixtureColours.CODE_UNKNOWN, refused)
        val message = refused.obj().str("error")
        assertTrue("patch ${ids[0]}" in message && "patch ${ids[2]}" in message, message)
        assertEquals("yellow", client.patches(showId).getValue("hex-2").str("colour"), "an atomic refusal writes nothing")

        val partial = client.bulk(
            showId,
            """{"atomic":false,"updates":[{"patchId":${ids[0]},"colour":"blue"},{"patchId":${ids[1]},"colour":"lime"}]}""",
        )
        assertEquals(HttpStatusCode.OK, partial.status, partial.bodyAsText())
        assertEquals(listOf(ids[0]), partial.obj()["failed"]!!.jsonArray.map { it.jsonObject["patchId"]!!.jsonPrimitive.int })
        assertEquals("lime", client.patches(showId).getValue("hex-2").str("colour"))
    }

    @Test
    fun `the group PUT sets and clears a colour, which never reaches the members`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedVenueReadyShow(state)
        val front = client.groups(showId).getValue("front-wash")
        assertEquals("green", front.str("colour"), "the rich fixture colours its front wash")

        val set = client.putGroup(showId, front.id(), """{"colour":"brown"}""")
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals("brown", set.obj().str("colour"))
        assertNull(client.patches(showId).getValue("hex-2").opt("colour"), "no inheritance: a member keeps its own")
        assertCode(HttpStatusCode.BadRequest, FixtureColours.CODE_UNKNOWN, client.putGroup(showId, front.id(), """{"colour":"amber"}"""))
        assertCode(HttpStatusCode.BadRequest, FixtureColours.CODE_UNKNOWN, client.putGroup(showId, front.id(), """{"colour":{"key":"red"}}"""))
        assertEquals(1, buskRigAnnouncements, "a busk rig tile embeds its group's colour")

        val renamed = client.putGroup(showId, front.id(), """{"name":"Front","colour":null}""")
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals("Front", renamed.obj().str("name"))
        assertNull(renamed.obj().opt("colour"))
    }

    @Test
    fun `the live group summary carries the group's colour`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedVenueReadyShow(state)
        runBlocking { state.projectManager.switchProject(showId) }
        val response = client.get("/api/rest/groups")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val groups = TestJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
        assertEquals("green", groups.getValue("front-wash").str("colour"))
        assertNull(groups.getValue("back-wash").opt("colour"))
    }

    // ─── Shows on a venue (D10) ─────────────────────────────────────────

    @Test
    fun `a show recolours a venue patch through show_colours, and clearing takes the venue's back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, venueId) = showOnVenue()
        val hex1 = client.patches(showId).getValue("hex-1")
        assertEquals("red", hex1.str("colour"), "the venue's colour, extracted with the rig")
        assertEquals("VENUE", hex1.str("colourOwner"))

        val set = client.putPatch(showId, hex1.id(), """{"colour":"pink"}""")
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals("pink", set.obj().str("colour"))
        assertEquals("SHOW", set.obj().str("colourOwner"))
        transaction(state.database) {
            assertEquals("red", DaoFixturePatch[hex1.id()].colour, "the version's row is never written")
            val rig = RigScope.of(showId)
            assertEquals("pink", rig.colourOf(rig.patches(DaoFixturePatches.id eq hex1.id()).single()))
            assertEquals(1, DaoShowColour.find { DaoShowColours.project eq showId }.count())
            assertTrue(rig.own.patches().none { it.uuid == hex1.uuidOf() }, "RigScope.own is the show's own rows")
            assertEquals("red", venuePatch(venueId, "hex-1").colour, "nor the venue's draft")
        }

        // A colour beside any other key is still the venue's row, refused as before.
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, client.putPatch(showId, hex1.id(), """{"colour":"lime","gelCode":"L106"}"""))

        val cleared = client.putPatch(showId, hex1.id(), """{"colour":null}""")
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertEquals("red", cleared.obj().str("colour"))
        assertEquals("VENUE", cleared.obj().str("colourOwner"))
        transaction(state.database) { assertEquals(0, DaoShowColour.find { DaoShowColours.project eq showId }.count()) }
    }

    @Test
    fun `a show recolours venue patches on the bulk path, and a venue patch with other keys is the venue's`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        val patches = client.patches(showId)
        val (a, b) = listOf("hex-1", "hex-2").map { patches.getValue(it).id() }

        val ok = client.bulk(showId, """{"updates":[{"patchId":$a,"colour":"white"},{"patchId":$b,"colour":"grey"}]}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val updated = ok.obj()["updated"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
        assertEquals("white", updated.getValue("hex-1").str("colour"))
        assertEquals("SHOW", updated.getValue("hex-2").str("colourOwner"))

        val refused = client.bulk(showId, """{"updates":[{"patchId":$a,"colour":"lime","stageX":1.0}]}""")
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, refused)
        // An entry naming no colour is no recolour: it is the venue's row, and the recolour stands.
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, client.bulk(showId, """{"updates":[{"patchId":$a}]}"""))
        assertEquals("white", client.patches(showId).getValue("hex-1").str("colour"))
    }

    @Test
    fun `a show recolours a venue group, and a hidden record's recolour is dormant`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        val front = client.groups(showId).getValue("front-wash")
        assertEquals("green", front.str("colour"))
        assertEquals("VENUE", front.str("colourOwner"))
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, client.putGroup(showId, front.id(), """{"name":"Front","colour":"lime"}"""))

        val back = client.groups(showId).getValue("back-wash")
        assertNull(back.opt("colour"))
        val set = client.putGroup(showId, back.id(), """{"colour":"orange"}""")
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals("orange", set.obj().str("colour"))
        assertEquals("SHOW", client.groups(showId).getValue("back-wash").str("colourOwner"))

        // Hidden, the group leaves the rig and its recolour stays, dormant; it is back with the group.
        transaction(state.database) {
            ShowHides.setHiddenByUuid(DaoProject[showId], HideKind.GROUP, back.uuidOf(), hidden = true)
        }
        assertTrue("back-wash" !in client.groups(showId))
        transaction(state.database) { assertEquals(1, DaoShowColour.find { DaoShowColours.project eq showId }.count()) }
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, client.putGroup(showId, back.id(), """{"colour":"lime"}"""))
        transaction(state.database) {
            ShowHides.setHiddenByUuid(DaoProject[showId], HideKind.GROUP, back.uuidOf(), hidden = false)
        }
        assertEquals("orange", client.groups(showId).getValue("back-wash").str("colour"))

        val cleared = client.putGroup(showId, back.id(), """{"colour":null}""")
        assertNull(cleared.obj().opt("colour"), "the venue's none")
        assertEquals("VENUE", cleared.obj().str("colourOwner"))
    }

    @Test
    fun `a venue colour change is drawing only`() {
        val (showId, venueId) = showOnVenue()
        transaction(state.database) {
            venuePatch(venueId, "hex-1").colour = "lime"
            DaoFixtureGroup.find { (DaoFixtureGroups.project eq venueId) and (DaoFixtureGroups.name eq "front-wash") }.single().colour = "white"
        }
        val draft = state.venueVersions.history(venueId).draft
        assertEquals(2, draft.changes, "the patch and the group")
        assertEquals(0, draft.outputChanges, "a colour never changes output")
        runBlocking { state.venueVersions.publish(venueId, "Recoloured the house rig", "Test") }
        assertEquals(VenueBehind.DRAWING, ShowBehind.judge(state, showId).behind)
    }

    // ─── Sync, clone, Extract, Detach, Attach ───────────────────────────

    @Test
    fun `a show's recolours round-trip through its archive and a clone keeps them`() {
        val f = seedShowOnVenue(state)
        val dir = tmp.newFolder("show").toPath()
        ProjectExporter(state).export(f.showId, dir)
        val exported = Files.list(dir.resolve("showColours")).toList()
            .map { TestJson.decodeFromString(ShowColourJson.serializer(), Files.readString(it)) }
        assertEquals(setOf("pink", "cyan"), exported.map { it.colour }.toSet())

        val clone = ProjectCloner(state).clone(f.showId, "cloned-colours", description = null)
        transaction(state.database) {
            val source = DaoShowColour.find { DaoShowColours.project eq f.showId }.associateBy { it.recordUuid }
            val cloned = DaoShowColour.find { DaoShowColours.project eq clone.projectId }.associateBy { it.recordUuid }
            assertEquals(source.mapValues { it.value.colour }, cloned.mapValues { it.value.colour }, "the same venue records, the same colours")
            cloned.forEach { (record, row) -> assertNotEquals(source.getValue(record).uuid, row.uuid, "a fresh identity") }
            assertEquals("pink", RigScope.of(clone.projectId).let { rig -> rig.colourOf(rig.patches().single { it.uuid == f.overriddenUuid }) })
        }

        // A re-import over the show (a pull) sweeps and re-reads them.
        transaction(state.database) { ShowColours.put(DaoProject[f.showId].id, f.overriddenUuid, "lime") }
        ProjectImporter(state).replaceFromWorkingTree(f.showId, dir)
        transaction(state.database) {
            assertEquals(setOf("pink", "cyan"), DaoShowColour.find { DaoShowColours.project eq f.showId }.map { it.colour }.toSet())
        }
    }

    @Test
    fun `Extract moves colours into the venue, Detach writes the show's into its own rows, Attach makes them recolours again`() {
        val f = seedShowOnVenue(state)
        // A dormant recolour, of the record the show hides: never copied, so never counted.
        transaction(state.database) { ShowColours.put(DaoProject[f.showId].id, f.hiddenUuid, "brown") }
        transaction(state.database) {
            val rig = RigScope.of(f.showId)
            assertEquals("red", venuePatch(f.venueId, "hex-1").colour, "Extract moved the patch's colour into the venue")
            assertEquals("pink", rig.colourOf(rig.patches().single { it.uuid == f.overriddenUuid }))
        }

        val detached = runBlocking { state.venueDetach.detach(f.showId, dryRun = false) }
        assertEquals(2, detached.coloursApplied)
        val (patchColours, groupColours) = transaction(state.database) {
            assertEquals(0, DaoShowColour.find { DaoShowColours.project eq f.showId }.count())
            val own = RigScope.of(f.showId).own
            own.patches().associate { it.uuid to it.colour } to own.groups().associate { it.name to it.colour }
        }
        assertEquals("pink", patchColours[f.overriddenUuid], "the show's recolour, written into its own row")
        assertEquals("cyan", groupColours["back-wash"])
        assertEquals("green", groupColours["front-wash"], "the venue's colour where the show had none of its own")

        val review = runBlocking { state.venueAttach.attach(f.showId, f.venueId, 1, emptyList(), dryRun = false) }.review
        assertTrue(review.drawing.none { "colour" in it.detail }, "a colour the show keeps changes nothing drawn: ${review.drawing}")
        transaction(state.database) {
            val recolours = DaoShowColour.find { DaoShowColours.project eq f.showId }.associate { it.recordUuid to it.colour }
            assertEquals("pink", recolours[f.overriddenUuid], "a colour the venue does not have becomes a recolour")
            assertEquals(2, recolours.size, "and one equal to the venue's does not")
        }
    }

    @Test
    fun `an Update to a version without the record drops its recolour, and says so`() {
        val (showId, venueId) = showOnVenue()
        val hex2Uuid = transaction(state.database) { venuePatch(venueId, "hex-2").uuid }
        transaction(state.database) { ShowColours.put(DaoProject[showId].id, hex2Uuid, "grey") }
        transaction(state.database) { deleteOwnPatch(venuePatch(venueId, "hex-2")) }
        runBlocking { state.venueVersions.publish(venueId, "Struck hex 2", "Test") }

        val updated = runBlocking { state.venueUpdate.update(showId, null, emptyList(), dryRun = false) }
        assertTrue(updated.dropped.any { it.kind == "COLOUR" && it.recordUuid == hex2Uuid.toString() }, updated.dropped.toString())
        transaction(state.database) { assertEquals(0, DaoShowColour.find { DaoShowColours.project eq showId }.count()) }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private var buskRigAnnouncements = 0

    @org.junit.Before
    fun countBuskRigAnnouncements() {
        state.show.fixtures.registerListener(object : uk.me.cormack.lighting7.show.FixturesChangeListener {
            override fun buskRigChanged() { buskRigAnnouncements++ }
        })
    }

    private fun showOnVenue(): Pair<Int, Int> {
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Test")
        }.venueId!!
        return showId to venueId
    }

    private fun venuePatch(venueId: Int, key: String): DaoFixturePatch =
        DaoFixturePatch.find { (DaoFixturePatches.project eq venueId) and (DaoFixturePatches.key eq key) }.single()

    private suspend fun HttpClient.patches(projectId: Int): Map<String, JsonObject> = list("/api/rest/projects/$projectId/patches", "key")

    private suspend fun HttpClient.groups(projectId: Int): Map<String, JsonObject> = list("/api/rest/projects/$projectId/patch-groups", "name")

    private suspend fun HttpClient.list(path: String, by: String): Map<String, JsonObject> {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return TestJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.associateBy { it.str(by) }
    }

    private suspend fun HttpClient.putPatch(projectId: Int, patchId: Int, body: String): HttpResponse =
        put("/api/rest/projects/$projectId/patches/$patchId") { json(body) }

    private suspend fun HttpClient.putGroup(projectId: Int, groupId: Int, body: String): HttpResponse =
        put("/api/rest/projects/$projectId/patch-groups/$groupId") { json(body) }

    private suspend fun HttpClient.bulk(projectId: Int, body: String): HttpResponse =
        put("/api/rest/projects/$projectId/patches/placements") { json(body) }

    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse =
        post(path) { json(body) }

    /** A patch's uuid from its DTO; a group's, whose DTO carries none, from its row. */
    private fun JsonObject.uuidOf(): UUID =
        this["uuid"]?.jsonPrimitive?.content?.let(UUID::fromString) ?: transaction(state.database) { DaoFixtureGroup[id()].uuid }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.id(): Int = this["id"]!!.jsonPrimitive.int
    private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content
    /** A field the REST JSON may write as an explicit null, or leave out. */
    private fun JsonObject.opt(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

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
