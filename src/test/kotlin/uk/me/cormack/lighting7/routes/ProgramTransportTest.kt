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
        client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun createSeparator(client: io.ktor.client.HttpClient, label: String): Int =
        client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = label, type = "SEPARATOR", label = label))
        }.body<CueStackDetails>().id

    private suspend fun createCue(client: io.ktor.client.HttpClient, name: String, stackId: Int) {
        val resp = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
    }

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

        val activated = client.post("/api/rest/project/$projectId/show/activate")
            .body<ShowActivateResponse>()
        assertEquals(stackA, activated.activeStackId, "activate should land on the first runnable stack")

        val advanced = client.post("/api/rest/project/$projectId/show/advance") {
            contentType(ContentType.Application.Json)
            setBody(AdvanceShowRequest(direction = "FORWARD"))
        }.body<ShowActivateResponse>()
        assertEquals(stackB, advanced.activeStackId, "advance FORWARD should skip the separator")

        val show = client.get("/api/rest/project/$projectId/show").body<ShowDetails>()
        assertEquals(stackB, show.activeStackId)

        val goToSeparator = client.post("/api/rest/project/$projectId/show/go-to") {
            contentType(ContentType.Application.Json)
            setBody(GoToStackRequest(stackId = separator))
        }
        assertEquals(HttpStatusCode.BadRequest, goToSeparator.status, "go-to a separator must be rejected")
    }
}
