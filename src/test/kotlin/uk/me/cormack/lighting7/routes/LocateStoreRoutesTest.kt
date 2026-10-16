package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.locate.storedLocate
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.LocateValueResolver
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /projects/{id}/locate/store` and `…/locate/clear` — a unit's stored locate (venue-visit plan
 * D6, D17). Pinned here: the store reads what the desk sends, coarse and fine; Locate then points the
 * unit there, and a located unit moves at once; a head stores its own, a fixture its own and every
 * head's; Clear takes it away; a unit that does not move is skipped; and on a venue the show's Focus
 * override takes it — never the venue's row — while an operator cannot store into a venue.
 */
class LocateStoreRoutesTest : RouteIntegrationTest() {

    @Test
    fun `set locate from current stores the output and Locate points the unit there`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 1)
        val axes = axesOf("mac-1")
        send(axes.pan, 40); send(axes.panFine!!.second, 17); send(axes.tilt, 210); send(axes.tiltFine!!.second, 3)

        val stored = client.locate(projectId, "store", """[{"type":"fixture","key":"mac-1"}]""")
        assertEquals(HttpStatusCode.OK, stored.status, stored.bodyAsText())
        val expected = UnitLocate(head = null, pan = 40, tilt = 210, panFine = 17, tiltFine = 3)
        assertEquals(listOf(expected), storedLocateOf("mac-1"))
        assertEquals(listOf(expected), stored.obj()["stored"]!!.jsonArray.single().jsonObject.locateOf(), "answered as stored")
        assertEquals(listOf(expected), client.patch(projectId, "mac-1").locateOf(), "the patch DTO carries it")

        // Locate now points the unit at it.
        assertTrue(LocateTestSupport.toggleLocate(client, "fixture", "mac-1").status == HttpStatusCode.OK)
        assertEquals(CueAssignmentResolver.PropertyValue.Position(40u, 210u), entry("mac-1", "position"))
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(17u), entry("mac-1", axes.panFine.first))
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(3u), entry("mac-1", axes.tiltFine.first))

        // Clear: back to the centre of travel — and a located unit moves at once, rather than when
        // Locate is next pressed.
        val cleared = client.locate(projectId, "clear", """[{"type":"fixture","key":"mac-1"}]""")
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertEquals(emptyList(), storedLocateOf("mac-1"))
        assertEquals(CueAssignmentResolver.PropertyValue.Position(128u, 128u), entry("mac-1", "position"))
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), entry("mac-1", axes.panFine.first))
    }

    @Test
    fun `a head stores its own, a fixture every head's, and a unit that does not move is skipped`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedFixture(state, projectId, "slender-beam-bar-quad-27ch", "bar-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 100)
        val head1 = axesOf("bar-1.head-1")
        send(head1.pan, 11); send(head1.tilt, 22)

        client.locate(projectId, "store", """[{"type":"fixture","key":"bar-1.head-1"}]""")
        assertEquals(listOf(1), storedLocateOf("bar-1").map { it.head }, "only the head named")
        assertEquals(11, storedLocateOf("bar-1").single().pan)

        val whole = client.locate(projectId, "store", """[{"type":"fixture","key":"bar-1"},{"type":"fixture","key":"hex-1"}]""").obj()
        assertEquals(listOf(0, 1, 2, 3), storedLocateOf("bar-1").map { it.head })
        val skipped = whole["skipped"]!!.jsonArray.single().jsonObject
        assertEquals("hex-1", skipped["target"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals("does not pan and tilt", skipped["reason"]!!.jsonPrimitive.content)

        client.locate(projectId, "clear", """[{"type":"fixture","key":"bar-1.head-2"}]""")
        assertEquals(listOf(0, 1, 3), storedLocateOf("bar-1").map { it.head }, "clearing one head keeps the rest")

        val empty = client.locate(projectId, "store", "[]")
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals(CODE_LOCATE_NEEDS_SELECTION, empty.obj()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `in a show on a venue the show's Focus takes the locate and the venue keeps its own`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Test")
        }.venueId!!
        runBlocking { state.projectManager.switchProject(showId) }
        val venueLocate = UnitLocate(head = null, pan = 140, tilt = 96, panFine = 12, tiltFine = 200)
        assertEquals(listOf(venueLocate), client.patch(showId, "rev-1").locateOf(), "the venue's, through the show")
        assertEquals(listOf(venueLocate), state.show.fixtures.storedLocateFor("rev-1"), "and in the live rig")

        val axes = axesOf("rev-1")
        send(axes.pan, 30); send(axes.tilt, 60)
        val stored = client.locate(showId, "store", """[{"type":"fixture","key":"rev-1"}]""")
        assertEquals(HttpStatusCode.OK, stored.status, stored.bodyAsText())
        val mine = client.patch(showId, "rev-1")
        assertEquals(30, mine.locateOf().single().pan)
        assertEquals(listOf("FOCUS"), (mine["facets"] as JsonArray).map { it.jsonObject["facet"]!!.jsonPrimitive.content })
        assertEquals(listOf(venueLocate), venueRowLocate(venueId), "the venue's row is never written")
        assertEquals(30, state.show.fixtures.storedLocateFor("rev-1").single().pan)

        // Clear in a show leaves the show with none; the venue keeps its own.
        client.locate(showId, "clear", """[{"type":"fixture","key":"rev-1"}]""")
        assertEquals(emptyList(), client.patch(showId, "rev-1").locateOf())
        assertEquals(listOf(venueLocate), venueRowLocate(venueId))

        // *Take venue's* brings the venue's back, into the live rig too.
        val taken = client.put("/api/rest/projects/$showId/patches/${mine["id"]!!.jsonPrimitive.int}/facets/FOCUS") {
            json("""{"value":null}""")
        }
        assertEquals(HttpStatusCode.OK, taken.status, taken.bodyAsText())
        assertEquals(listOf(venueLocate), state.show.fixtures.storedLocateFor("rev-1"))
    }

    @Test
    fun `an operator cannot store a locate into a venue, and a project not current is refused`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("admin")
        val op = client.loginCookieHeader("op")
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Test")
        }.venueId!!

        val notCurrent = client.locate(venueId, "store", """[{"type":"fixture","key":"rev-1"}]""", admin)
        assertEquals(HttpStatusCode.Conflict, notCurrent.status, notCurrent.bodyAsText())

        runBlocking { state.projectManager.switchProject(venueId) }
        val refused = client.locate(venueId, "store", """[{"type":"fixture","key":"rev-1"}]""", op)
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertEquals(CODE_VENUE_ADMIN_ONLY, refused.obj()["code"]!!.jsonPrimitive.content)

        val axes = axesOf("rev-1")
        send(axes.pan, 77)
        val byAdmin = client.locate(venueId, "store", """[{"type":"fixture","key":"rev-1"}]""", admin)
        assertEquals(HttpStatusCode.OK, byAdmin.status, byAdmin.bodyAsText())
        assertEquals(77, venueRowLocate(venueId).single().pan, "an admin's is the venue's draft")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun entry(key: String, property: String): CueAssignmentResolver.PropertyValue? =
        state.show.programmerStore.entries()
            .firstOrNull { it.fixtureKey == key && it.propertyName == property }
            ?.slots?.firstOrNull()?.value?.resolved

    private fun axesOf(key: String): LocateValueResolver.LocateAxes {
        val target: GroupableFixture = state.show.fixtures.untypedGroupableFixture(key)
        return LocateValueResolver.axesOf(target)!!
    }

    /** Put [value] on [slider]'s channel of the live frame, as the composition would. */
    private fun send(slider: DmxSlider, value: Int) {
        state.show.fixtures.controller(slider.universe).setValue(slider.channelNo, value.toUByte())
    }

    private fun storedLocateOf(key: String): List<UnitLocate> = transaction(state.database) {
        DaoFixturePatch.find { (DaoFixturePatches.project eq projectId) and (DaoFixturePatches.key eq key) }.single().storedLocate
    }

    private fun venueRowLocate(venueId: Int): List<UnitLocate> = transaction(state.database) {
        DaoFixturePatch.find { (DaoFixturePatches.project eq venueId) and (DaoFixturePatches.key eq "rev-1") }.single().storedLocate
    }

    private suspend fun HttpClient.locate(project: Int, verb: String, targets: String, cookie: String? = null): HttpResponse =
        post("/api/rest/projects/$project/locate/$verb") {
            cookie?.let { header(HttpHeaders.Cookie, it) }
            json("""{"targets":$targets}""")
        }

    private suspend fun HttpClient.patch(project: Int, key: String): JsonObject {
        val response = get("/api/rest/projects/$project/patches")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return TestJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.single { it["key"]!!.jsonPrimitive.content == key }
    }

    private fun JsonObject.locateOf(): List<UnitLocate> =
        (this["locate"] as? JsonArray)?.let { TestJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(UnitLocate.serializer()), it) }.orEmpty()

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
