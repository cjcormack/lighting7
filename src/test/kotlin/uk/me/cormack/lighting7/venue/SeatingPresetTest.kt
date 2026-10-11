package uk.me.cormack.lighting7.venue

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoLookSceneryRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.SeatingParams
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageElementLayer
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.encodeElementParams
import uk.me.cormack.lighting7.models.storedParamsObject
import uk.me.cormack.lighting7.routes.CODE_STAGE_ELEMENT_IN_USE
import uk.me.cormack.lighting7.routes.CODE_VENUE_ADMIN_ONLY
import uk.me.cormack.lighting7.routes.FIELD_DISPLAY_DETAIL
import uk.me.cormack.lighting7.routes.STAGE_ELEMENTS_OVERRIDE_TABLE
import uk.me.cormack.lighting7.sync.Overrides
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.VenueShowFixture
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A venue's Preset seating as a show takes it (venue plan D6, session 7): *Create*'s stalls preset — a
 * copy that is the show's own row, with no `block` — *Replace from preset*, what happens to the seat
 * views and scenery of the seating it replaces, and who may do either.
 */
class SeatingPresetTest : RouteIntegrationTest() {

    @Test
    fun `a new show copies the preset it chose, and the copy is the show's own`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val theatre = addPreset(f, "Theatre rows", rows = 10, seatsPerRow = 12)

        val offered = client.get("/api/rest/projects/${f.venueId}/seating/presets").obj()
        assertEquals(3, offered["version"]!!.jsonPrimitive.int, "the installed version's")
        assertEquals(listOf("Cabaret tables", "Theatre rows"), offered.names("presets"), "Presets only: the balcony is Fixed")
        assertEquals(120, offered["presets"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "Theatre rows" }.jsonObject["seats"]!!.jsonPrimitive.int)

        val created = client.post("/api/rest/projects") { json("""{"name":"Panto 2027","venueId":${f.venueId},"seatingPresetUuid":"$theatre"}""") }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val pantoId = created.obj()["id"]!!.jsonPrimitive.int

        transaction(state.database) {
            val rig = RigScope.of(pantoId)
            val seating = rig.stageElements(DaoStageElements.kind eq StageElementKind.SEATING.name).toList()
            val copy = seating.single { it.uuid == theatre }
            assertEquals(RigOwner.SHOW, rig.ownerOf(copy.project.id), "the show's own row")
            assertNull(storedParamsObject(copy.params)["block"], "a show's seating carries no block")
            assertFalse(seating.any { it.name == "Cabaret tables" }, "the other preset is not in the show's rig")
            assertTrue(seating.any { it.project.id == rig.venue!!.snapshotId }, "the Fixed balcony comes with the venue")
        }
        val panto = client.get("/api/rest/projects/$pantoId/seating/presets").obj()
        assertEquals(listOf("Theatre rows"), panto.names("current"))
        assertEquals("true", panto["current"]!!.jsonArray.single().jsonObject["fromPreset"]!!.jsonPrimitive.content)

        // A preset the version lacks, or one without its venue, is refused and makes no show.
        assertCode(
            HttpStatusCode.BadRequest, SeatingPresets.CODE_PRESET_UNKNOWN,
            client.post("/api/rest/projects") { json("""{"name":"Nope","venueId":${f.venueId},"seatingPresetUuid":"${UUID.randomUUID()}"}""") },
        )
        assertCode(
            HttpStatusCode.BadRequest, SeatingPresets.CODE_PRESET_UNKNOWN,
            client.post("/api/rest/projects") { json("""{"name":"Nope","seatingPresetUuid":"$theatre"}""") },
        )
        transaction(state.database) { assertFalse(DaoProject.find { DaoProjects.name eq "Nope" }.any()) }
    }

    @Test
    fun `replace from preset swaps the stalls, moves the seat views that keep their seat, and asks about the rest`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val theatre = addPreset(f, "Theatre rows", rows = 10, seatsPerRow = 12)
        val cabaret = presetUuid(f, "Cabaret tables")
        val pantoId = client.post("/api/rest/projects") {
            json("""{"name":"Panto 2027","venueId":${f.venueId},"seatingPresetUuid":"$theatre"}""")
        }.obj()["id"]!!.jsonPrimitive.int
        for ((name, seat) in listOf("Front" to "A1", "Back row" to "J12")) {
            val view = client.post("/api/rest/projects/$pantoId/stage-viewpoints") {
                json("""{"name":"$name","kind":"SEAT","seatElementUuid":"$theatre","seatId":"$seat"}""")
            }
            assertEquals(HttpStatusCode.Created, view.status, view.bodyAsText())
        }

        // The cabaret has no J12: the replace names the view it would strand, and changes nothing.
        val refused = client.post("/api/rest/projects/$pantoId/seating/from-preset") { json("""{"elementUuid":"$cabaret"}""") }
        assertCode(HttpStatusCode.Conflict, CODE_STAGE_ELEMENT_IN_USE, refused)
        assertTrue("Back row" in refused.bodyAsText())
        assertEquals(listOf(theatre), ownSeating(pantoId).map { it.first })

        val forced = client.post("/api/rest/projects/$pantoId/seating/from-preset") { json("""{"elementUuid":"$cabaret","force":true}""") }
        assertEquals(HttpStatusCode.OK, forced.status, forced.bodyAsText())
        val result = forced.obj()
        assertEquals(listOf("Theatre rows"), result["removed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, result["viewsReseated"]!!.jsonPrimitive.int)
        assertEquals(listOf("Back row"), result["viewsWithoutSeat"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(cabaret to "Cabaret tables"), ownSeating(pantoId))
        transaction(state.database) {
            val views = DaoStageViewpoint.find { DaoStageViewpoints.project eq pantoId }.associate { it.name to it.seatElementUuid }
            assertEquals(mapOf("Front" to cabaret, "Back row" to cabaret), views, "both sit in the new stalls; one has no seat there")
        }
        // The venue's presets are untouched.
        assertEquals(setOf(theatre, cabaret), transaction(state.database) {
            DaoStageElement.find { (DaoStageElements.project eq f.venueId) and (DaoStageElements.kind eq StageElementKind.SEATING.name) }
                .map { it.uuid }.filter { it != fixedUuid(f) }.toSet()
        })
    }

    @Test
    fun `a re-copy of the same preset keeps the stalls' scenery`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val cabaret = presetUuid(f, "Cabaret tables")
        // The show's own Cabaret tables (Extract's copy) is shown by one of its Looks.
        transaction(state.database) {
            val stalls = DaoStageElement.find { (DaoStageElements.project eq f.showId) and (DaoStageElements.uuid eq cabaret) }.single()
            stalls.positionX = 2.5
            DaoLookSceneryRow.new {
                look = DaoLook.find { DaoLooks.project eq f.showId }.first()
                element = stalls
                stateJson = """{"visible":false}"""
                sortOrder = 0
            }
        }
        // This machine shows the stalls in Full detail: a machine override keyed by the copy's uuid.
        val stallsId = transaction(state.database) {
            DaoStageElement.find { (DaoStageElements.project eq f.showId) and (DaoStageElements.uuid eq cabaret) }.single().id.value
        }
        val detail = client.put("/api/rest/projects/${f.showId}/stage-elements/$stallsId/display-detail") { json("""{"full":true}""") }
        assertEquals(HttpStatusCode.OK, detail.status, detail.bodyAsText())
        val replaced = client.post("/api/rest/projects/${f.showId}/seating/from-preset") { json("""{"elementUuid":"$cabaret"}""") }
        assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
        assertEquals(1, replaced.obj()["sceneryMoved"]!!.jsonPrimitive.int)
        transaction(state.database) {
            assertEquals(
                "4096",
                Overrides.getString(f.showId, STAGE_ELEMENTS_OVERRIDE_TABLE, cabaret, FIELD_DISPLAY_DETAIL),
                "the same preset again keeps this machine's Full detail: the override is the copy's",
            )
        }
        transaction(state.database) {
            val stalls = DaoStageElement.find { (DaoStageElements.project eq f.showId) and (DaoStageElements.uuid eq cabaret) }.single()
            assertEquals(-6.0, stalls.positionY, "a fresh copy of the venue's preset")
            assertEquals(0.0, stalls.positionX, "the show's own move is gone")
            assertEquals(1, DaoLookSceneryRow.find { DaoLookScenery.element eq stalls.id }.count())
        }
    }

    @Test
    fun `replace is a show's, either role's, and refused for a venue or a standalone show`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val admin = client.loginCookieHeader("admin")
        val f = seedShowOnVenue(state)
        val cabaret = presetUuid(f, "Cabaret tables")
        val standalone = transaction(state.database) { DaoProject.new { name = "Studio test"; kind = ProjectKind.SHOW }.id.value }
        val body = """{"elementUuid":"$cabaret"}"""

        assertEquals(HttpStatusCode.OK, client.post("/api/rest/projects/${f.showId}/seating/from-preset") { cookie(op); json(body) }.status)
        assertCode(HttpStatusCode.Forbidden, CODE_VENUE_ADMIN_ONLY, client.post("/api/rest/projects/${f.venueId}/seating/from-preset") { cookie(op); json(body) })
        assertCode(HttpStatusCode.Conflict, SeatingPresets.CODE_NOT_A_VENUE, client.post("/api/rest/projects/${f.venueId}/seating/from-preset") { cookie(admin); json(body) })
        assertCode(HttpStatusCode.Conflict, SeatingPresets.CODE_NOT_A_VENUE, client.post("/api/rest/projects/$standalone/seating/from-preset") { cookie(admin); json(body) })
        assertCode(HttpStatusCode.Conflict, SeatingPresets.CODE_NOT_A_VENUE, client.get("/api/rest/projects/$standalone/seating/presets") { cookie(op) })
        assertCode(
            HttpStatusCode.BadRequest, SeatingPresets.CODE_PRESET_UNKNOWN,
            client.post("/api/rest/projects/${f.showId}/seating/from-preset") { cookie(op); json("""{"elementUuid":"${fixedUuid(f)}"}""") },
        )
        // A preset whose name another of the show's own elements holds is refused by name, not by the index.
        transaction(state.database) {
            DaoStageElement.find { (DaoStageElements.project eq f.showId) and (DaoStageElements.uuid eq cabaret) }.single().name = "Stalls"
            DaoStageElement.new {
                project = DaoProject[f.showId]
                name = "Cabaret tables"
                kind = StageElementKind.PLATFORM.name
                layer = StageElementLayer.SET.name
                widthM = 2.0
                depthM = 1.0
                heightM = 0.4
                params = "{}"
            }
        }
        assertCode(
            HttpStatusCode.Conflict, SeatingPresets.CODE_NAME_TAKEN,
            client.post("/api/rest/projects/${f.showId}/seating/from-preset") { cookie(op); json(body) },
        )
        assertEquals(listOf("Stalls"), ownSeating(f.showId).map { it.second }, "nothing changed")
        // Both roles read what a venue offers: an operator creates shows too.
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/projects/${f.venueId}/seating/presets") { cookie(op) }.status)
    }

    @Test
    fun `the project list carries each venue's card`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val list = client.get("/api/rest/projects").let { TestJson.parseToJsonElement(it.bodyAsText()).jsonArray }.map { it.jsonObject }
        val venue = list.single { it["id"]!!.jsonPrimitive.int == f.venueId }["venueSummary"]!!.jsonObject
        assertEquals(2, venue["version"]!!.jsonPrimitive.int)
        assertEquals(1, venue["shows"]!!.jsonPrimitive.int)
        assertEquals(1, venue["behind"]!!.jsonPrimitive.int, "the show is on v1")
        assertTrue(venue["patches"]!!.jsonPrimitive.int > 0)
        assertEquals(1, venue["fixedSeating"]!!.jsonArray.size)
        assertTrue(list.single { it["id"]!!.jsonPrimitive.int == f.showId }["venueSummary"].let { it == null || it is JsonNull }, "a show has no venue card")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** A Preset seating block added to the venue's draft and published: the venue's installed version then has it. */
    private fun addPreset(f: VenueShowFixture, name: String, rows: Int, seatsPerRow: Int): UUID {
        val uuid = transaction(state.database) {
            DaoStageElement.new {
                project = DaoProject[f.venueId]
                this.name = name
                kind = StageElementKind.SEATING.name
                layer = StageElementLayer.VENUE.name
                positionY = -7.0
                params = encodeElementParams(
                    StageElementKind.SEATING,
                    SeatingParams(rows = rows, seatsPerRow = seatsPerRow, rowPitchM = 0.9, seatPitchM = 0.5),
                )
                sortOrder = 20
            }.uuid
        }
        runBlocking { state.venueVersions.publish(f.venueId, "Add $name", "Test") }
        return uuid
    }

    private fun presetUuid(f: VenueShowFixture, name: String): UUID = transaction(state.database) {
        DaoStageElement.find { (DaoStageElements.project eq f.venueId) and (DaoStageElements.name eq name) }.single().uuid
    }

    private fun fixedUuid(f: VenueShowFixture): UUID = transaction(state.database) {
        DaoStageElement.find { (DaoStageElements.project eq f.venueId) and (DaoStageElements.kind eq StageElementKind.SEATING.name) }
            .single { uk.me.cormack.lighting7.models.VenueLayer.isFixedSeating(it.params) }.uuid
    }

    private fun ownSeating(projectId: Int): List<Pair<UUID, String>> = transaction(state.database) {
        RigScope.of(projectId).own.stageElements(DaoStageElements.kind eq StageElementKind.SEATING.name).map { it.uuid to it.name }
    }

    private fun JsonObject.names(key: String): List<String> =
        this[key]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun assertCode(status: HttpStatusCode, code: String, response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals(code, TestJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content, body)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.cookie(value: String) = header(HttpHeaders.Cookie, value)
}
