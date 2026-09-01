package uk.me.cormack.lighting7.routes

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

/**
 * Project-level show transport over the ordered stacks: activate lands on the first runnable
 * stack, advance skips separators, and go-to rejects a separator.
 */
class ProgramTransportTest : RouteIntegrationTest() {

    private suspend fun createStack(client: io.ktor.client.HttpClient, name: String): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun createSeparator(client: io.ktor.client.HttpClient, label: String): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = label, type = "SEPARATOR", label = label))
        }.body<CueStackDetails>().id

    private suspend fun createCue(
        client: io.ktor.client.HttpClient,
        name: String,
        stackId: Int,
        cueType: String = "STANDARD",
    ): Int {
        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, cueType = cueType))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private suspend fun stack(client: io.ktor.client.HttpClient, stackId: Int): CueStackDetails =
        client.get("/api/rest/projects/$projectId/cue-stacks/$stackId").body()

    @Test
    fun `activate lands on first stack, advance skips separator, go-to separator rejected`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Order (by creation → sortOrder): stackA(0), separator(1), stackB(2).
        val stackA = createStack(client, "Act 1")
        val separator = createSeparator(client, "Interval")
        val stackB = createStack(client, "Act 2")
        createCue(client, "a1", stackA)
        createCue(client, "b1", stackB)

        val activated = client.post("/api/rest/projects/$projectId/show/activate")
            .body<ShowActivateResponse>()
        assertEquals(stackA, activated.activeStackId, "activate should land on the first runnable stack")

        val advanced = client.post("/api/rest/projects/$projectId/show/advance") {
            contentType(ContentType.Application.Json)
            setBody(AdvanceShowRequest(direction = "FORWARD"))
        }.body<ShowActivateResponse>()
        assertEquals(stackB, advanced.activeStackId, "advance FORWARD should skip the separator")

        val show = client.get("/api/rest/projects/$projectId/show").body<ShowDetails>()
        assertEquals(stackB, show.activeStackId)

        val goToSeparator = client.post("/api/rest/projects/$projectId/show/go-to") {
            contentType(ContentType.Application.Json)
            setBody(GoToStackRequest(stackId = separator))
        }
        assertEquals(HttpStatusCode.BadRequest, goToSeparator.status, "go-to a separator must be rejected")
    }

    /**
     * `/show/go-to` with a `cueId` — the busk view's pinned-cue pad.
     *
     * The rejection halves are the point rather than the happy path: every one of them has to be
     * refused **before** the playhead moves, because the route deactivates the outgoing stack the
     * moment the transaction commits. A 400 raised after that is a dark rig plus a playhead sitting
     * on a stack with nothing on stage — strictly worse than a rejected press.
     */
    @Test
    fun `go-to with a cueId fires that cue, and a bad cue leaves the playhead alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackA = createStack(client, "Act 1")
        val stackB = createStack(client, "Act 2")
        createCue(client, "a1", stackA)
        createCue(client, "b1", stackB)
        val b2 = createCue(client, "b2", stackB)
        val marker = createCue(client, "interval", stackB, cueType = "MARKER")

        client.post("/api/rest/projects/$projectId/show/activate").body<ShowActivateResponse>()

        // The whole reason the parameter exists: land on b2 without firing b1 on the way past.
        val goTo = client.post("/api/rest/projects/$projectId/show/go-to") {
            contentType(ContentType.Application.Json)
            setBody(GoToStackRequest(stackId = stackB, cueId = b2))
        }
        assertEquals(HttpStatusCode.OK, goTo.status, goTo.bodyAsText())
        assertEquals(stackB, goTo.body<ShowActivateResponse>().activeStackId)
        assertEquals(b2, stack(client, stackB).activeCueId, "go-to must land on the named cue, not the first")

        // A MARKER and a cue that no longer exists are both refused with the show exactly where it
        // was — same stack, same live cue.
        for ((label, badCueId) in listOf("a marker" to marker, "a deleted cue" to 1_000_000)) {
            val rejected = client.post("/api/rest/projects/$projectId/show/go-to") {
                contentType(ContentType.Application.Json)
                setBody(GoToStackRequest(stackId = stackB, cueId = badCueId))
            }
            assertEquals(HttpStatusCode.BadRequest, rejected.status, "go-to $label must be rejected")
            assertEquals(stackB, client.get("/api/rest/projects/$projectId/show").body<ShowDetails>().activeStackId)
            assertEquals(b2, stack(client, stackB).activeCueId, "a rejected go-to ($label) must not move the playhead")
        }

        val wrongStack = client.post("/api/rest/projects/$projectId/show/go-to") {
            contentType(ContentType.Application.Json)
            setBody(GoToStackRequest(stackId = stackA, cueId = b2))
        }
        assertEquals(HttpStatusCode.BadRequest, wrongStack.status, "a cue from another stack must be rejected")
        assertEquals(stackB, client.get("/api/rest/projects/$projectId/show").body<ShowDetails>().activeStackId)
    }
}
