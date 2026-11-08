package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.sync.RestoreRequest
import uk.me.cormack.lighting7.sync.RestoreResultDto
import uk.me.cormack.lighting7.sync.RestoreReviewDto
import uk.me.cormack.lighting7.sync.SnapshotResponse
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.venue.VenueBehind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * `GET …/sync/restore/{sha}` and `POST …/sync/restore` (sync-restore plan session 1): the answers,
 * the refusals' codes, the admin gate, and the live show — reloaded, and never held by its restore (P4).
 */
class CloudSyncRestoreRouteTest : RouteIntegrationTest() {

    private suspend fun HttpClient.snapshot(projectId: Int, cookie: String? = null): SnapshotResponse =
        post("/api/rest/projects/$projectId/sync/snapshot") {
            contentType(ContentType.Application.Json)
            setBody("{}")
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }.body()

    private suspend fun HttpClient.restore(projectId: Int, body: RestoreRequest, cookie: String? = null): HttpResponse =
        post("/api/rest/projects/$projectId/sync/restore") {
            contentType(ContentType.Application.Json)
            setBody(body)
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }

    private fun code(response: HttpResponse): String = runBlocking {
        TestJson.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
    }

    private fun addStack(projectId: Int, name: String) = transaction(state.database) {
        DaoCueStack.new { project = DaoProject[projectId]; this.name = name }.uuid
    }

    @Test
    fun `the review and the restore answer over REST, and the live show reloads`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val first = client.snapshot(projectId).commit!!
        val added = addStack(projectId, "Added since")
        client.snapshot(projectId)

        val reviewed = client.get("/api/rest/projects/$projectId/sync/restore/${first.sha}")
        assertEquals(HttpStatusCode.OK, reviewed.status, reviewed.bodyAsText())
        val review = reviewed.body<RestoreReviewDto>()
        assertTrue(review.live)
        assertEquals(1, review.counts.gone)

        // The refusals, each with its code and status.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/rest/projects/$projectId/sync/restore/${"0".repeat(40)}").status)
        val badNote = client.restore(projectId, RestoreRequest(first.sha, review.token, "a\nb"))
        assertEquals(HttpStatusCode.BadRequest, badNote.status)
        assertEquals("RESTORE_INVALID", code(badNote))
        val stale = client.restore(projectId, RestoreRequest(first.sha, "stale"))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val staleBody = TestJson.parseToJsonElement(stale.bodyAsText()).jsonObject
        assertEquals("RESTORE_REVIEW_STALE", staleBody["code"]!!.jsonPrimitive.content)
        assertEquals(review.token, staleBody["review"]!!.jsonObject["token"]!!.jsonPrimitive.content, "it carries the review as it is")

        val showBefore = state.show
        val restored = client.restore(projectId, RestoreRequest(first.sha, review.token, "before the stack"))
        assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
        val result = restored.body<RestoreResultDto>()
        assertEquals(first.sha, result.commit.restoredFrom)
        assertTrue(transaction(state.database) { DaoCueStack.find { DaoCueStacks.uuid eq added }.empty() })
        assertNotSame(showBefore, state.show, "the live show reloaded")

        val again = client.restore(projectId, RestoreRequest(first.sha, review.token))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("RESTORE_NO_CHANGES", code(again))
    }

    @Test
    fun `only an admin restores`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("admin")
        val first = client.snapshot(projectId, admin).commit!!
        addStack(projectId, "Added since")
        client.snapshot(projectId, admin)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rest/projects/$projectId/sync/restore/${first.sha}").status)
        val op = client.loginCookieHeader("op")
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/rest/projects/$projectId/sync/restore/${first.sha}") { header(HttpHeaders.Cookie, op) }.status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.restore(projectId, RestoreRequest(first.sha, "t"), op).status)
        val ok = client.get("/api/rest/projects/$projectId/sync/restore/${first.sha}") { header(HttpHeaders.Cookie, admin) }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
    }

    @Test
    fun `a venue's draft has no snapshots to restore`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val refused = client.get("/api/rest/projects/${f.venueId}/sync/restore/${"a".repeat(40)}")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("VENUE_DRAFT_NOT_SYNCED", code(refused))
    }

    @Test
    fun `a running show restored behind its venue on output runs against its restored pin`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val first = client.snapshot(f.showId).commit!!

        // v3 re-addresses a unit the show drives as the venue patched it: output.
        transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq f.venueId }
                .filter { it.fixtureTypeKey == "hex" && it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid }
                .minBy { it.key }.startChannel = 260
        }
        runBlocking { state.venueVersions.publish(f.venueId, "a hex re-addressed", "Chris") }
        runBlocking { state.venueUpdate.update(f.showId, null, emptyList(), dryRun = false) }
        client.snapshot(f.showId)
        runBlocking { state.projectManager.switchProject(f.showId) }
        assertFalse(state.venueHold.status.value.held, "on v3, the installed version")
        val showBefore = state.show

        val review = client.get("/api/rest/projects/${f.showId}/sync/restore/${first.sha}").body<RestoreReviewDto>()
        val venue = assertNotNull(review.venue)
        assertEquals(3, venue.now?.version)
        assertEquals(1, venue.then?.version)
        assertEquals(VenueBehind.OUTPUT, venue.behind)
        assertTrue(venue.runAgainst)

        val restored = client.restore(f.showId, RestoreRequest(first.sha, review.token))
        assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
        assertNotSame(showBefore, state.show, "the live show reloaded")
        val hold = state.venueHold.status.value
        assertEquals(f.showId, hold.projectId)
        assertEquals(1, hold.pinned)
        assertFalse(hold.held, "not blacked out mid-run")
        assertTrue(hold.runAgainst, "run against v1")
    }
}
