package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Venue-visit plan D11 through the routes an operator's gestures reach: a number typed into a cue
 * (`PATCH /cues/{id}`), *Fix Order* (`sort-by-cue-number`), Duplicate (`POST /cues` with no
 * `sortOrder`), and the list changes that must still relabel what they shift. `CueNumberingTest`
 * pins the rule; this pins that each handler hands it the order from before its change.
 */
class CueNumberingRouteTest : RouteIntegrationTest() {

    private suspend fun createStack(client: HttpClient): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1"))
        }.body<CueStackDetails>().id

    private suspend fun createCue(client: HttpClient, stackId: Int, name: String, sortOrder: Int? = null): Int {
        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, sortOrder = sortOrder))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    /** Five auto cues, numbered 1–5 by position. */
    private suspend fun fiveAutoCues(client: HttpClient): Pair<Int, List<Int>> {
        val stackId = createStack(client)
        val ids = (1..5).map { createCue(client, stackId, "Q$it") }
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId))
        return stackId to ids
    }

    private suspend fun cues(client: HttpClient, stackId: Int): List<CueStackCueEntry> =
        client.get("/api/rest/projects/$projectId/cue-stacks/$stackId").body<CueStackDetails>().cues

    private suspend fun numbers(client: HttpClient, stackId: Int): List<String?> = cues(client, stackId).map { it.cueNumber }

    private suspend fun names(client: HttpClient, stackId: Int): List<String> = cues(client, stackId).map { it.name }

    private suspend fun renumber(client: HttpClient, cueId: Int, number: String) {
        val resp = client.patch("/api/rest/projects/$projectId/cues/$cueId") {
            contentType(ContentType.Application.Json)
            setBody("""{"cueNumber":"$number"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private suspend fun fixOrder(client: HttpClient, stackId: Int) {
        val resp = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/sort-by-cue-number")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private fun outOfOrder(numbers: List<String?>) = detectCueNumbersOutOfOrder(numbers)

    @Test
    fun `cue 2 set to 4·5 leaves its neighbours, and Fix Order moves it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)

        renumber(client, ids[1], "4.5")
        assertEquals(listOf("1", "4.5", "3", "4", "5"), numbers(client, stackId))
        assertTrue(outOfOrder(numbers(client, stackId)))

        fixOrder(client, stackId)
        assertEquals(listOf("Q1", "Q3", "Q4", "Q2", "Q5"), names(client, stackId), "the cue moved")
        assertEquals(listOf("1", "2", "3", "4.5", "5"), numbers(client, stackId), "and kept its number")
        assertFalse(outOfOrder(numbers(client, stackId)))
    }

    @Test
    fun `cue 3 set to 10 leaves its neighbours, and Fix Order moves it to the end`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)

        renumber(client, ids[2], "10")
        assertEquals(listOf("1", "2", "10", "4", "5"), numbers(client, stackId))
        assertTrue(outOfOrder(numbers(client, stackId)))

        fixOrder(client, stackId)
        assertEquals(listOf("Q1", "Q2", "Q4", "Q5", "Q3"), names(client, stackId))
        assertEquals(listOf("1", "2", "3", "4", "10"), numbers(client, stackId))
    }

    @Test
    fun `cue 3 set to 1 takes cue 1's number, and Fix Order moves it to the front`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)

        renumber(client, ids[2], "1")
        assertEquals(listOf("0", "2", "1", "4", "5"), numbers(client, stackId))
        assertTrue(outOfOrder(numbers(client, stackId)))

        fixOrder(client, stackId)
        assertEquals(listOf("Q1", "Q3", "Q2", "Q4", "Q5"), names(client, stackId))
        assertEquals(listOf("0", "1", "2", "4", "5"), numbers(client, stackId))
        assertFalse(outOfOrder(numbers(client, stackId)))
    }

    @Test
    fun `a cue appended to a renumbered stack takes the next number and moves nothing`() = testApplication {
        // Duplicate's gesture: a create with no sortOrder lands at the end of the stack.
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)
        renumber(client, ids[2], "10")

        createCue(client, stackId, "Q3 (copy)")
        assertEquals(listOf("1", "2", "10", "4", "5", "6"), numbers(client, stackId))
    }

    @Test
    fun `an insert, a delete and a move still relabel the cues they shift`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)

        client.delete("/api/rest/projects/$projectId/cues/${ids[1]}")
        assertEquals(listOf("1", "2", "3", "4"), numbers(client, stackId))

        // Into the gap Q2 left (sortOrder 1): Q1 stays, everything after it shifts.
        createCue(client, stackId, "Q0", sortOrder = 1)
        assertEquals(listOf("Q1", "Q0", "Q3", "Q4", "Q5"), names(client, stackId))
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId))

        val reversed = cues(client, stackId).map { it.id }.reversed()
        client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/reorder") {
            contentType(ContentType.Application.Json)
            setBody(ReorderCuesRequest(cueIds = reversed))
        }
        assertEquals(listOf("Q5", "Q4", "Q3", "Q0", "Q1"), names(client, stackId))
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId))
    }

    @Test
    fun `clearing an explicit number hands the cue back to its place`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)
        renumber(client, ids[1], "4.5")

        renumber(client, ids[1], "")
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId))
    }
}
