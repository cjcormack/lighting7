package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The armed standby is server-owned: one definition of "next" that GO, the details DTO and the
 * run-state broadcast all read. Before this, "next" was computed per browser session, so a cue
 * armed on a tablet was invisible to the desk.
 */
class CueStandbyRouteTest : RouteIntegrationTest() {

    private suspend fun createStack(client: HttpClient, name: String): Int =
        client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun createCue(
        client: HttpClient,
        name: String,
        stackId: Int,
        cueType: String = "STANDARD",
    ): Int {
        val resp = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, cueType = cueType))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private suspend fun stack(client: HttpClient, stackId: Int): CueStackDetails =
        client.get("/api/rest/project/$projectId/cue-stacks/$stackId").body()

    private suspend fun arm(client: HttpClient, stackId: Int, cueId: Int?) =
        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/standby") {
            contentType(ContentType.Application.Json)
            setBody(SetStandbyRequest(cueId = cueId))
        }

    private suspend fun advance(client: HttpClient, stackId: Int, direction: String = "FORWARD") =
        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/advance") {
            contentType(ContentType.Application.Json)
            setBody(AdvanceCueStackRequest(direction = direction))
        }

    @Test
    fun `next is positional until a cue is armed, and GO fires what is armed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = createCue(client, "a1", stackId)
        val cue2 = createCue(client, "a2", stackId)
        val cue3 = createCue(client, "a3", stackId)

        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = cue1))
        }

        stack(client, stackId).let {
            assertEquals(cue1, it.activeCueId)
            assertNull(it.standbyCueId, "nothing armed yet")
            assertEquals(cue2, it.nextCueId, "next is positional until a cue is armed")
        }

        val armed = arm(client, stackId, cue3)
        assertEquals(HttpStatusCode.OK, armed.status, armed.bodyAsText())
        assertEquals(cue3, armed.body<CueStackRunStateResponse>().nextCueId)

        stack(client, stackId).let {
            assertEquals(cue3, it.standbyCueId)
            assertEquals(cue3, it.nextCueId, "an armed cue overrides the positional next")
        }

        // The client no longer has to choose between `advance` and `go-to`: a plain advance
        // fires whatever is on deck.
        val advanced = advance(client, stackId).body<CueStackActivateResponse>()
        assertEquals(cue3, advanced.cueId)

        stack(client, stackId).let {
            assertEquals(cue3, it.activeCueId)
            assertNull(it.standbyCueId, "the GO consumed the standby")
            assertNull(it.nextCueId, "end of a non-looping stack has nothing on deck")
        }
    }

    @Test
    fun `disarming falls back to the positional next`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = createCue(client, "a1", stackId)
        val cue2 = createCue(client, "a2", stackId)
        val cue3 = createCue(client, "a3", stackId)

        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = cue1))
        }
        arm(client, stackId, cue3)
        val cleared = arm(client, stackId, null).body<CueStackRunStateResponse>()

        assertNull(cleared.standbyCueId)
        assertEquals(cue2, cleared.nextCueId)
    }

    @Test
    fun `a MARKER cannot be armed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = createStack(client, "Act 1")
        createCue(client, "a1", stackId)
        val marker = createCue(client, "note", stackId, cueType = "MARKER")

        // Same guard `go-to` applies: arming is a deferred GO.
        assertEquals(HttpStatusCode.BadRequest, arm(client, stackId, marker).status)
    }

    @Test
    fun `a cue from another stack cannot be armed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackA = createStack(client, "Act 1")
        val stackB = createStack(client, "Act 2")
        createCue(client, "a1", stackA)
        val b1 = createCue(client, "b1", stackB)

        assertEquals(HttpStatusCode.BadRequest, arm(client, stackA, b1).status)
    }

    @Test
    fun `arming before the stack runs survives, and the first GO fires it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = createStack(client, "Act 1")
        createCue(client, "a1", stackId)
        val cue2 = createCue(client, "a2", stackId)

        // Pre-show: nothing live, but the operator has cue 2 on deck.
        arm(client, stackId, cue2)
        stack(client, stackId).let {
            assertNull(it.activeCueId)
            assertEquals(cue2, it.nextCueId)
        }

        // The client calls activate with no cueId — the armed cue is the server's business now.
        val started = client.post("/api/rest/project/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest())
        }.body<CueStackActivateResponse>()

        assertEquals(cue2, started.cueId, "the first GO fires the armed cue, not the first cue")
        assertNull(stack(client, stackId).standbyCueId)
    }

    @Test
    fun `back ignores the standby`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = createCue(client, "a1", stackId)
        val cue2 = createCue(client, "a2", stackId)
        val cue3 = createCue(client, "a3", stackId)

        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = cue2))
        }
        arm(client, stackId, cue3)

        val backwards = advance(client, stackId, "BACKWARD").body<CueStackActivateResponse>()
        assertEquals(cue1, backwards.cueId, "BACKWARD stays positional")
    }

    @Test
    fun `deactivating a stack clears what was on deck`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = createCue(client, "a1", stackId)
        val cue2 = createCue(client, "a2", stackId)

        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = cue1))
        }
        arm(client, stackId, cue2)
        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/deactivate")

        stack(client, stackId).let {
            assertNull(it.activeCueId)
            assertNull(it.standbyCueId, "a stopped stack has nothing on deck")
            assertEquals(cue1, it.nextCueId, "a stopped stack is back to its first cue")
        }
    }
}
