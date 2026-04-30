package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncSession
import uk.me.cormack.lighting7.models.DaoSyncSessionConflict
import uk.me.cormack.lighting7.sync.ConflictSession
import uk.me.cormack.lighting7.sync.SessionState
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5 conflict-session **route**-level coverage. The engine-level integration is in
 * [uk.me.cormack.lighting7.sync.RemoteSyncEngineConflictsTest]; this file checks the
 * REST contracts of the new `/sync/conflicts`, `/sync/resolve`, `/sync/abort` endpoints
 * and the `/sync/snapshot` guard. We seed sessions directly through DAOs to isolate
 * route behaviour from network-bound engine operations.
 */
class CloudSyncConflictRouteTest : RouteIntegrationTest() {

    private fun seedSession(): Pair<Int, UUID> = transaction(state.database) {
        val project = DaoProject.findById(projectId)!!
        val stack = DaoCueStack.new {
            this.project = project
            this.name = "shared"
            this.palette = emptyList()
        }
        val rows = listOf(
            uk.me.cormack.lighting7.sync.ConflictRow(
                tableName = "cueStacks",
                recordUuid = stack.uuid,
                conflictKind = "EDIT_EDIT",
                localJson = """{"name":"local","uuid":"${stack.uuid}"}""",
                remoteJson = """{"name":"remote","uuid":"${stack.uuid}"}""",
                baseJson = """{"name":"shared","uuid":"${stack.uuid}"}""",
            ),
        )
        val session = ConflictSession.open(
            project = project,
            localSha = "0".repeat(40),
            remoteSha = "1".repeat(40),
            baseSha = "2".repeat(40),
            conflicts = rows,
        )
        session.id.value to stack.uuid
    }

    @Test
    fun `GET conflicts returns activeSession=false when no session`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.get("/api/rest/project/$projectId/sync/conflicts")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<uk.me.cormack.lighting7.routes.ConflictsResponse>()
        assertEquals(false, body.activeSession)
        assertTrue(body.conflicts.isEmpty())
    }

    @Test
    fun `GET conflicts returns the active session and its conflicts`() = testApplication {
        mountTestApp(state)
        val (sessionId, stackUuid) = seedSession()
        val client = jsonClient()
        val resp = client.get("/api/rest/project/$projectId/sync/conflicts")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<uk.me.cormack.lighting7.routes.ConflictsResponse>()
        assertEquals(true, body.activeSession)
        assertEquals(sessionId, body.sessionId)
        assertEquals(SessionState.CONFLICTS_PENDING.name, body.state)
        assertEquals(1, body.conflicts.size)
        val c = body.conflicts.first()
        assertEquals("cueStacks", c.tableName)
        assertEquals(stackUuid.toString(), c.recordUuid)
        assertEquals("EDIT_EDIT", c.conflictKind)
        assertNull(c.resolution)
        assertNotNull(c.localJson)
        assertNotNull(c.remoteJson)
    }

    @Test
    fun `POST resolve sets resolution and is idempotent`() = testApplication {
        mountTestApp(state)
        val (sessionId, stackUuid) = seedSession()
        val client = jsonClient()

        val resolve = client.post("/api/rest/project/$projectId/sync/resolve") {
            contentType(ContentType.Application.Json)
            setBody(
                ResolveRequest(
                    listOf(ResolveEntry(tableName = "cueStacks", recordUuid = stackUuid.toString(), resolution = "LOCAL")),
                ),
            )
        }
        assertEquals(HttpStatusCode.NoContent, resolve.status)

        val after = transaction(state.database) {
            val session = DaoSyncSession.findById(sessionId)!!
            DaoSyncSessionConflict.find { uk.me.cormack.lighting7.models.DaoSyncSessionConflicts.session eq session.id }
                .first().resolution
        }
        assertEquals("LOCAL", after)

        // Idempotent: re-applying the same value still 204s.
        val resolveAgain = client.post("/api/rest/project/$projectId/sync/resolve") {
            contentType(ContentType.Application.Json)
            setBody(
                ResolveRequest(
                    listOf(ResolveEntry(tableName = "cueStacks", recordUuid = stackUuid.toString(), resolution = "LOCAL")),
                ),
            )
        }
        assertEquals(HttpStatusCode.NoContent, resolveAgain.status)
    }

    @Test
    fun `POST resolve with invalid choice is 400`() = testApplication {
        mountTestApp(state)
        val (_, stackUuid) = seedSession()
        val client = jsonClient()
        val resp = client.post("/api/rest/project/$projectId/sync/resolve") {
            contentType(ContentType.Application.Json)
            setBody(
                ResolveRequest(
                    listOf(ResolveEntry(tableName = "cueStacks", recordUuid = stackUuid.toString(), resolution = "MAYBE")),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST resolve with no active session is 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.post("/api/rest/project/$projectId/sync/resolve") {
            contentType(ContentType.Application.Json)
            setBody(
                ResolveRequest(
                    listOf(ResolveEntry(tableName = "cueStacks", recordUuid = UUID.randomUUID().toString(), resolution = "LOCAL")),
                ),
            )
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `POST abort marks the session ABORTED and clears conflicts`() = testApplication {
        mountTestApp(state)
        val (sessionId, _) = seedSession()
        val client = jsonClient()
        val resp = client.post("/api/rest/project/$projectId/sync/abort")
        assertEquals(HttpStatusCode.OK, resp.status)
        transaction(state.database) {
            val session = DaoSyncSession.findById(sessionId)!!
            assertEquals(SessionState.ABORTED.name, session.state)
            assertTrue(
                DaoSyncSessionConflict.find { uk.me.cormack.lighting7.models.DaoSyncSessionConflicts.session eq session.id }.empty(),
                "abort must clear conflict rows",
            )
        }
    }

    @Test
    fun `POST snapshot is rejected while a session is open`() = testApplication {
        mountTestApp(state)
        seedSession()
        val client = jsonClient()
        val resp = client.post("/api/rest/project/$projectId/sync/snapshot") {
            // Empty JSON body so ContentNegotiation accepts the request before the
            // route handler's session-pending guard runs.
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        val body = resp.body<SyncErrorResponse>()
        assertEquals("SESSION_PENDING", body.code)
    }
}
