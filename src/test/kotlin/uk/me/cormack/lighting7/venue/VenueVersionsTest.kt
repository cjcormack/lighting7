package uk.me.cormack.lighting7.venue

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoVenueVersion
import uk.me.cormack.lighting7.models.DaoVenueVersions
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.routes.CODE_SYNC_VENUE_PENDING
import uk.me.cormack.lighting7.routes.CODE_VENUE_ADMIN_ONLY
import uk.me.cormack.lighting7.routes.CODE_VENUE_RECORD_OFF
import uk.me.cormack.lighting7.routes.CODE_VENUE_VERSION_READ_ONLY
import uk.me.cormack.lighting7.routes.CreateProjectRequest
import uk.me.cormack.lighting7.routes.ProjectListDto
import uk.me.cormack.lighting7.routes.PublishVenueRequest
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.readExportFiles
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedUser
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Venue plan session 2: a venue's versions. A snapshot is its draft record for record, it refuses
 * every write and is absent from every list, publish and discard behave, and every venue route is an
 * admin's.
 */
class VenueVersionsTest : RouteIntegrationTest() {

    /** The rich fixture as a venue, not current (the integration test's own project is). */
    private fun seedVenue(): Int {
        val id = seedRichProject(state)
        transaction(state.database) {
            DaoProject[id].apply {
                kind = ProjectKind.VENUE
                isCurrent = false
            }
        }
        return id
    }

    private fun publish(venueId: Int, note: String = "v"): VenueVersionDto =
        runBlocking { state.venueVersions.publish(venueId, note, "Test") }

    @Test
    fun `a snapshot is its draft record for record, by uuid, in a fresh project`() {
        val venueId = seedVenue()
        val v1 = publish(venueId, "First")
        assertEquals(1, v1.number)

        val draftDir = Files.createTempDirectory("venue-draft-")
        val snapDir = Files.createTempDirectory("venue-snap-")
        try {
            ProjectExporter(state).export(venueId, draftDir)
            ProjectExporter(state).export(v1.snapshotProjectId, snapDir)
            val draft = readExportFiles(draftDir)
            val snap = readExportFiles(snapDir)
            // Every record file, scene images and installs included, byte for byte — the uuids are
            // the file names and the links inside them.
            assertEquals(draft - "project.json", snap - "project.json")
            assertTrue(draft.keys.count { it.startsWith("fixturePatches/") } >= 4, "the fixture has patches to compare")

            val draftProject = TestJson.parseToJsonElement(draft.getValue("project.json")).jsonObject
            val snapProject = TestJson.parseToJsonElement(snap.getValue("project.json")).jsonObject
            assertNotEquals(draftProject["uuid"], snapProject["uuid"], "the project's own uuid is fresh")
            assertEquals(draftProject - setOf("uuid", "name"), snapProject - setOf("uuid", "name"))
        } finally {
            draftDir.toFile().deleteRecursively()
            snapDir.toFile().deleteRecursively()
        }

        transaction(state.database) {
            val snapshot = DaoProject[v1.snapshotProjectId]
            assertEquals(ProjectKind.VENUE_VERSION, snapshot.kind)
            assertFalse(snapshot.isCurrent)
            assertEquals(VenueVersions.snapshotName(snapshot.uuid), snapshot.name)
        }
    }

    @Test
    fun `publishing needs a note and a change, and numbers versions densely`() {
        val venueId = seedVenue()
        val refused = runCatching { runBlocking { state.venueVersions.publish(venueId, "  ", null) } }.exceptionOrNull()
        assertEquals(VenueVersions.CODE_NOTE_REQUIRED, (refused as VenueException).code)
        val long = runCatching { runBlocking { state.venueVersions.publish(venueId, "x".repeat(501), null) } }.exceptionOrNull()
        assertEquals(VenueVersions.CODE_NOTE_TOO_LONG, (long as VenueException).code)

        publish(venueId, "One")
        val empty = runCatching { publish(venueId, "Again") }.exceptionOrNull()
        assertEquals(VenueVersions.CODE_DRAFT_EMPTY, (empty as VenueException).code)

        transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq venueId }.first().startChannel += 100
        }
        assertEquals(2, publish(venueId, "Two").number)
        val history = state.venueVersions.history(venueId)
        assertEquals(listOf(2, 1), history.versions.map { it.number })
        assertEquals(2, history.installed)
        assertEquals(0, history.draft.changes)
        assertEquals("Test", history.versions.first().publishedBy)
    }

    @Test
    fun `the draft count and the diff classify output and drawing`() {
        val venueId = seedVenue()
        publish(venueId, "One")
        val (moved, readdressed) = transaction(state.database) {
            val patches = DaoFixturePatch.find { DaoFixturePatches.project eq venueId }.toList()
            patches[0].stageX = (patches[0].stageX ?: 0.0) + 1.5
            patches[1].startChannel += 200
            patches[0].uuid.toString() to patches[1].uuid.toString()
        }
        val history = state.venueVersions.history(venueId)
        assertEquals(2, history.draft.changes)
        assertEquals(1, history.draft.outputChanges)

        val diff = state.venueVersions.diff(venueId, 1, null)
        assertEquals(listOf(readdressed), diff.output.map { it.uuid })
        assertEquals(listOf(VenueFacet.ADDRESS), diff.output.single().facets)
        assertEquals(listOf(moved), diff.drawing.map { it.uuid })
        assertEquals(listOf(VenueFacet.POSITION), diff.drawing.single().facets)

        // From nothing, every record is an addition.
        val fromEmpty = state.venueVersions.diff(venueId, 0, 1)
        assertTrue(fromEmpty.output.isNotEmpty() && fromEmpty.output.all { it.change == VenueChangeType.ADDED })
    }

    @Test
    fun `discarding the draft brings back the installed version, uuids and all`() {
        val venueId = seedVenue()
        publish(venueId, "One")
        val (patch, region) = transaction(state.database) {
            val patch = DaoFixturePatch.find { DaoFixturePatches.project eq venueId }.first()
            patch.startChannel += 300
            patch.displayName = "Draft name"
            val region = DaoStageRegion.find { DaoStageRegions.project eq venueId }.first()
            val gone = region.uuid
            region.delete()
            DaoProject[venueId].name = "Renamed hall"
            patch.uuid to gone
        }
        val before = transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq venueId }.associate { it.uuid to it.startChannel }
        }
        assertTrue(state.venueVersions.history(venueId).draft.changes > 0)

        // Live, so the discard reloads it — and it must stay the current project in the database.
        runBlocking { state.projectManager.switchProject(venueId) }
        val after = runBlocking { state.venueVersions.discard(venueId) }
        assertEquals(0, after.draft.changes)
        transaction(state.database) {
            assertTrue(DaoProject[venueId].isCurrent, "reloading the live venue keeps it current")
            assertEquals(1, DaoProject.all().count { it.isCurrent })
        }
        transaction(state.database) {
            val restored = DaoFixturePatch.find { DaoFixturePatches.project eq venueId }.single { it.uuid == patch }
            assertEquals(before.getValue(patch) - 300, restored.startChannel)
            assertNotEquals("Draft name", restored.displayName)
            assertEquals(1, DaoStageRegion.find { DaoStageRegions.project eq venueId }.count { it.uuid == region })
            assertEquals("Renamed hall", DaoProject[venueId].name, "the name is the venue's, not the version's")
            assertEquals(ProjectKind.VENUE, DaoProject[venueId].kind)
        }
    }

    // ── Over HTTP ───────────────────────────────────────────────────────────────

    @Test
    fun `a snapshot refuses every write and still answers reads`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val venueId = seedVenue()
        val snapshotId = publish(venueId).snapshotProjectId
        val base = "/api/rest/projects/$snapshotId"

        val writes: List<suspend () -> HttpResponse> = listOf(
            { client.put(base) { json("""{"name":"x"}""") } },
            { client.delete(base) },
            { client.post("$base/set-current") },
            { client.post("$base/clone") { json("""{"name":"copy"}""") } },
            { client.post("$base/patches") { json("""{"fixtureTypeKey":"generic-dimmer","key":"x","name":"X","universe":0,"startChannel":400}""") } },
            { client.post("$base/riggings") { json("""{"name":"LX9","kind":"BAR"}""") } },
            { client.post("$base/stage-elements") { json("""{"name":"Box","kind":"OBJECT"}""") } },
            { client.post("$base/scripts") { json("""{"name":"s","script":""}""") } },
        )
        for (write in writes) {
            val response = write()
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue(CODE_VENUE_VERSION_READ_ONLY in response.bodyAsText())
        }
        assertEquals(HttpStatusCode.OK, client.get("$base/patches").status)
        assertEquals(HttpStatusCode.OK, client.get(base).status)
        assertEquals(ProjectKind.VENUE_VERSION, transaction(state.database) { DaoProject[snapshotId].kind })
    }

    @Test
    fun `a snapshot is in no list and cannot be made current`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val venueId = seedVenue()
        val snapshotId = publish(venueId).snapshotProjectId

        val listed = client.get("/api/rest/projects").body<List<ProjectListDto>>()
        assertTrue(listed.none { it.id == snapshotId })
        assertEquals(ProjectKind.VENUE, listed.single { it.id == venueId }.kind)

        val tools = SetupTools(state, uk.me.cormack.lighting7.ai.AiTools(state))
        val list = runBlocking { tools.executeTool("list_projects", JsonObject(emptyMap())) }
        assertTrue(list.success)
        val ids = TestJson.parseToJsonElement(list.result).jsonObject["projects"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content.toInt() }
        assertFalse(snapshotId in ids, list.result)
        val switch = runBlocking {
            tools.executeTool("switch_project", TestJson.parseToJsonElement("""{"projectId":$snapshotId}""").jsonObject)
        }
        assertFalse(switch.success)
        assertTrue(runCatching { runBlocking { state.projectManager.switchProject(snapshotId) } }.isFailure)
        assertNotEquals(snapshotId, state.projectManager.currentProject.id.value)
    }

    @Test
    fun `deleting a venue takes its versions with it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val venueId = seedVenue()
        val snapshotId = publish(venueId).snapshotProjectId

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$venueId").status)
        transaction(state.database) {
            assertNull(DaoProject.findById(venueId))
            assertNull(DaoProject.findById(snapshotId))
            assertEquals(0, DaoVenueVersion.find { DaoVenueVersions.venueProject eq venueId }.count())
        }
    }

    @Test
    fun `the routes publish, list, diff and discard`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val created = client.post("/api/rest/projects") {
            json(TestJson.encodeToString(CreateProjectRequest.serializer(), CreateProjectRequest("Hall", kind = ProjectKind.VENUE)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val venueId = created.body<JsonObject>()["id"]!!.jsonPrimitive.content.toInt()

        // An empty venue has nothing to publish.
        val empty = client.post("/api/rest/venues/$venueId/publish") { json("""{"note":"v1"}""") }
        assertEquals(HttpStatusCode.Conflict, empty.status)
        assertTrue(VenueVersions.CODE_DRAFT_EMPTY in empty.bodyAsText())

        val patched = client.post("/api/rest/projects/$venueId/patches") {
            json("""{"fixtureTypeKey":"generic-dimmer","key":"sr-face","name":"SR FACE","universe":1,"startChannel":5}""")
        }
        assertEquals(HttpStatusCode.Created, patched.status, patched.bodyAsText())
        val v1 = client.post("/api/rest/venues/$venueId/publish") { json("""{"note":"First hang"}""") }
        assertEquals(HttpStatusCode.Created, v1.status, v1.bodyAsText())

        val history = client.get("/api/rest/venues/$venueId/versions").body<VenueHistoryDto>()
        assertEquals(1, history.installed)
        assertEquals("First hang", history.versions.single().note)

        val diff = client.get("/api/rest/venues/$venueId/versions/0/diff/1").body<VenueDiffDto>()
        assertEquals(setOf(VenueChangeKind.UNIVERSE, VenueChangeKind.PATCH), diff.output.map { it.kind }.toSet())
        assertEquals("1-005", diff.output.single { it.kind == VenueChangeKind.PATCH }.address)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/rest/venues/$venueId/versions/x/diff/1").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/rest/venues/$venueId/versions/7/diff/draft").status)

        // A show is not a venue.
        assertEquals(HttpStatusCode.Conflict, client.get("/api/rest/venues/$projectId/versions").status)
        assertEquals(HttpStatusCode.OK, client.delete("/api/rest/venues/$venueId/draft").status)
        // Nor may anyone make a version by hand.
        val forged = client.post("/api/rest/projects") { json("""{"name":"Forged","kind":"VENUE_VERSION"}""") }
        assertEquals(HttpStatusCode.BadRequest, forged.status)
    }

    @Test
    fun `operators are refused every venue route and every stored write to a venue`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("admin")
        val op = client.loginCookieHeader("op")
        val venueId = seedVenue()
        publish(venueId)

        val adminRoutes: List<suspend (String) -> HttpResponse> = listOf(
            { c -> client.get("/api/rest/venues/$venueId/versions") { cookie(c) } },
            { c -> client.get("/api/rest/venues/$venueId/versions/0/diff/draft") { cookie(c) } },
            { c -> client.post("/api/rest/venues/$venueId/publish") { cookie(c); json("""{"note":"x"}""") } },
            { c -> client.delete("/api/rest/venues/$venueId/draft") { cookie(c) } },
        )
        for (route in adminRoutes) {
            assertEquals(HttpStatusCode.Forbidden, route(op).status)
        }
        assertEquals(HttpStatusCode.OK, adminRoutes[0](admin).status)

        // A stored write to the venue is an admin's; a read is anyone's, and so is opening it.
        val write = client.post("/api/rest/projects/$venueId/riggings") { cookie(op); json("""{"name":"LX9","kind":"BAR"}""") }
        assertEquals(HttpStatusCode.Forbidden, write.status)
        assertTrue(CODE_VENUE_ADMIN_ONLY in write.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/rest/projects/$venueId") { cookie(op); json("""{"name":"x"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/projects/$venueId/patches") { cookie(op) }.status)
        val addVenue = client.post("/api/rest/projects") { cookie(op); json("""{"name":"Barn","kind":"VENUE"}""") }
        assertEquals(HttpStatusCode.Forbidden, addVenue.status)
        assertEquals(HttpStatusCode.Created, client.post("/api/rest/projects/$venueId/riggings") { cookie(admin); json("""{"name":"LX9","kind":"BAR"}""") }.status)

        val opened = client.post("/api/rest/projects/$venueId/set-current") { cookie(op) }
        assertEquals(HttpStatusCode.OK, opened.status, opened.bodyAsText())
    }

    @Test
    fun `a copy into a version or, by an operator, into a venue is refused like any other write`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("admin")
        val op = client.loginCookieHeader("op")
        val venueId = seedVenue()
        val snapshotId = publish(venueId).snapshotProjectId
        // The guard judges the path's project, the show; the target is in the body.
        val lookId = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoLook.new {
                project = DaoProject[projectId]
                name = "Wash"
            }.id.value
        }
        val copy = { target: Int, cookie: String ->
            runBlocking {
                client.post("/api/rest/projects/$projectId/looks/$lookId/copy") { cookie(cookie); json("""{"targetProjectId":$target}""") }
            }
        }
        val intoVersion = copy(snapshotId, admin)
        assertEquals(HttpStatusCode.Conflict, intoVersion.status)
        assertTrue(CODE_VENUE_VERSION_READ_ONLY in intoVersion.bodyAsText())
        val opIntoVenue = copy(venueId, op)
        assertEquals(HttpStatusCode.Forbidden, opIntoVenue.status)
        assertTrue(CODE_VENUE_ADMIN_ONLY in opIntoVenue.bodyAsText())
        val adminIntoVenue = copy(venueId, admin)
        assertTrue(adminIntoVenue.status.isSuccess(), adminIntoVenue.bodyAsText())
        val scriptCopy = client.post("/api/rest/projects/$projectId/scripts/1/copy") { cookie(admin); json("""{"targetProjectId":$snapshotId}""") }
        assertEquals(HttpStatusCode.Conflict, scriptCopy.status)

        // An FX definition lands in the live project, whose path names none: an operator's is refused on a venue.
        runBlocking { state.projectManager.switchProject(venueId) }
        val fx = client.post("/api/rest/fx/definitions") { cookie(op); json("""{"name":"x","outputType":"SLIDER","script":""}""") }
        assertEquals(HttpStatusCode.Forbidden, fx.status)
        assertTrue(CODE_VENUE_ADMIN_ONLY in fx.bodyAsText())
    }

    @Test
    fun `a live venue refuses Record, and no venue syncs yet`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val venueId = seedVenue()
        runBlocking { state.projectManager.switchProject(venueId) }

        val record = client.post("/api/rest/programmer/record") { json("""{"mode":"CREATE","cueStackId":1}""") }
        assertEquals(HttpStatusCode.Conflict, record.status)
        assertTrue(CODE_VENUE_RECORD_OFF in record.bodyAsText())

        val sync = client.get("/api/rest/projects/$venueId/sync/config")
        assertEquals(HttpStatusCode.Conflict, sync.status)
        assertTrue(CODE_SYNC_VENUE_PENDING in sync.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/projects/$projectId/sync/config").status)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.cookie(value: String) = header(HttpHeaders.Cookie, value)
}
