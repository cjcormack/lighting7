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
 * Auto numbers from position (venue-rig-fixes plan D6–D7) through the routes an operator's gestures
 * reach: a number typed into an entry, *Fix Order* (`sort-by-cue-number`), Duplicate (`POST /cues`
 * with no `sortOrder`), the list changes that relabel what they shift, and the placements.
 * `CueNumberingTest` pins the rule; this pins that each handler renumbers after its change.
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
        // A number is the entry's, written on the entry route (cue model plan D2).
        val entry = client.get("/api/rest/projects/$projectId/cues/$cueId").body<CueDetails>().entries.single()
        val resp = client.patch("/api/rest/projects/$projectId/cue-stacks/${entry.stackId}/entries/${entry.entryId}") {
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
    fun `cue 2 set to 4·5 moves the cues after it, and reads in order`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)

        renumber(client, ids[1], "4.5")
        assertEquals(listOf("1", "4.5", "5", "6", "7"), numbers(client, stackId))
        assertFalse(outOfOrder(numbers(client, stackId)))

        fixOrder(client, stackId)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "Q5"), names(client, stackId), "nothing to fix")
        assertEquals(listOf("1", "4.5", "5", "6", "7"), numbers(client, stackId))
    }

    @Test
    fun `cue 3 set to 10 moves the cues after it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)

        renumber(client, ids[2], "10")
        assertEquals(listOf("1", "2", "10", "11", "12"), numbers(client, stackId))
        assertFalse(outOfOrder(numbers(client, stackId)))
    }

    @Test
    fun `cue 3 set to 1 reads out of order, and Fix Order moves it to the front`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)

        renumber(client, ids[2], "1")
        assertEquals(listOf("2", "3", "1", "5", "6"), numbers(client, stackId))
        assertTrue(outOfOrder(numbers(client, stackId)))

        fixOrder(client, stackId)
        assertEquals(listOf("Q3", "Q1", "Q2", "Q4", "Q5"), names(client, stackId))
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId))
        assertFalse(outOfOrder(numbers(client, stackId)))
    }

    @Test
    fun `a typed number reads as the auto cue after it follows`() = testApplication {
        // The rig session's stack: a typed Q30, and the auto cue after it.
        mountTestApp(state)
        val client = jsonClient()
        val stackId = createStack(client)
        val ids = (1..2).map { createCue(client, stackId, "Q$it") }

        renumber(client, ids[0], "30")
        assertEquals(listOf("30", "31"), numbers(client, stackId))
    }

    @Test
    fun `a cue appended to a renumbered stack takes the next number`() = testApplication {
        // Duplicate's gesture: a create with no sortOrder lands at the end of the stack.
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveAutoCues(client)
        renumber(client, ids[2], "10")

        createCue(client, stackId, "Q3 (copy)")
        assertEquals(listOf("1", "2", "10", "11", "12", "13"), numbers(client, stackId))
    }

    @Test
    fun `an insert, a removal and a move still relabel the cues they shift`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)

        client.delete("/api/rest/projects/$projectId/cue-stacks/$stackId/entries/${cues(client, stackId)[1].entryId}")
        assertEquals(listOf("1", "2", "3", "4"), numbers(client, stackId))

        // Into the gap Q2 left (sortOrder 1): Q1 stays, everything after it shifts.
        createCue(client, stackId, "Q0", sortOrder = 1)
        assertEquals(listOf("Q1", "Q0", "Q3", "Q4", "Q5"), names(client, stackId))
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId))

        val reversed = cues(client, stackId).map { it.entryId }.reversed()
        client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/reorder") {
            contentType(ContentType.Application.Json)
            setBody(ReorderCuesRequest(entryIds = reversed))
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

    // ─── Numbers are numbers ────────────────────────────────────────────

    private suspend fun patchNumber(client: HttpClient, stackId: Int, entryId: Int, body: String) =
        client.patch("/api/rest/projects/$projectId/cue-stacks/$stackId/entries/$entryId") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `a number that is not one is refused, and one number has one spelling`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)
        val entry = cues(client, stackId)[4].entryId

        for (bad in listOf("14A", "S1-3", "1.2.3", "1.23456", "10000")) {
            val resp = patchNumber(client, stackId, entry, """{"cueNumber":"$bad"}""")
            assertEquals(HttpStatusCode.BadRequest, resp.status, bad)
            assertEquals(CUE_NUMBER_INVALID, resp.body<ErrorResponse>().code, bad)
        }
        assertEquals(HttpStatusCode.OK, patchNumber(client, stackId, entry, """{"cueNumber":"5.10"}""").status)
        assertEquals("5.1", numbers(client, stackId)[4])

        // Another spelling of a number another entry holds explicitly is that number.
        val other = cues(client, stackId)[3].entryId
        val taken = patchNumber(client, stackId, other, """{"cueNumber":"5.100"}""")
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals(CUE_NUMBER_TAKEN, taken.body<ErrorResponse>().code)
    }

    @Test
    fun `ASK refuses an out-of-order number and names its neighbours, writing nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)
        val second = cues(client, stackId)[1].entryId
        val fourth = cues(client, stackId)[3].entryId

        // Three cues before it fit neither 1 nor 0 below 1.5.
        val resp = patchNumber(client, stackId, fourth, """{"cueNumber":"1.5","placement":"ASK"}""")
        assertEquals(HttpStatusCode.Conflict, resp.status)
        val body = resp.body<CueNumberOutOfOrderResponse>()
        assertEquals(CUE_NUMBER_OUT_OF_ORDER, body.code)
        assertEquals("1.5", body.number)
        assertEquals("3", body.previous)
        assertEquals(null, body.next)
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId), "nothing written")

        // In order, ASK writes as HERE does, and the cues after it follow it.
        assertEquals(HttpStatusCode.OK, patchNumber(client, stackId, second, """{"cueNumber":"4.5","placement":"ASK"}""").status)
        assertEquals(listOf("1", "4.5", "5", "6", "7"), numbers(client, stackId))
    }

    @Test
    fun `BY_NUMBER moves the cue to its number's place`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)
        val second = cues(client, stackId)[1].entryId

        assertEquals(HttpStatusCode.OK, patchNumber(client, stackId, second, """{"cueNumber":"4.5","placement":"BY_NUMBER"}""").status)
        assertEquals(listOf("Q1", "Q3", "Q4", "Q2", "Q5"), names(client, stackId))
        // The cues it passed take numbers from where they land, as Fix Order's do.
        assertEquals(listOf("1", "2", "3", "4.5", "5"), numbers(client, stackId))
        assertFalse(outOfOrder(numbers(client, stackId)))
    }

    @Test
    fun `HERE keeps an out-of-order number where it is`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)
        val second = cues(client, stackId)[1].entryId

        val fourth = cues(client, stackId)[3].entryId
        assertEquals(HttpStatusCode.OK, patchNumber(client, stackId, fourth, """{"cueNumber":"1.5","placement":"HERE"}""").status)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "Q5"), names(client, stackId))
        assertEquals(listOf("1", "2", "3", "1.5", "5"), numbers(client, stackId))
        assertTrue(outOfOrder(numbers(client, stackId)))
        assertEquals(second, cues(client, stackId)[1].entryId)
    }

    @Test
    fun `a new cue numbered out of order asks, or goes to its place`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)

        val asked = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q2.5", cueStackId = stackId, cueNumber = "2.5", placement = CueNumberPlacement.ASK))
        }
        assertEquals(HttpStatusCode.Conflict, asked.status)
        assertEquals("5", asked.body<CueNumberOutOfOrderResponse>().previous)
        assertEquals(5, cues(client, stackId).size, "no cue made")

        val placed = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q2.5", cueStackId = stackId, cueNumber = "2.5", placement = CueNumberPlacement.BY_NUMBER))
        }
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        assertEquals(listOf("Q1", "Q2", "Q2.5", "Q3", "Q4", "Q5"), names(client, stackId))
        assertEquals(listOf("1", "2", "2.5", "3", "4", "5"), numbers(client, stackId))
    }

    @Test
    fun `renumber numbers the selected cues in stack order from a whole number`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)
        val rows = cues(client, stackId)

        val resp = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/renumber") {
            contentType(ContentType.Application.Json)
            // Named out of order: they are numbered in the stack's.
            setBody(RenumberEntriesRequest(entryIds = listOf(rows[3].entryId, rows[1].entryId, rows[2].entryId), start = 10, step = 10))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val after = cues(client, stackId)
        assertEquals(listOf("1", "10", "20", "30", "31"), after.map { it.cueNumber })
        assertEquals(listOf(true, false, false, false, true), after.map { it.cueNumberAuto })
        assertEquals(rows.map { it.entryId }, after.map { it.entryId }, "nothing moves")
    }

    @Test
    fun `renumber refuses a number an unselected cue holds explicitly, and a bad start or step`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)
        val rows = cues(client, stackId)
        assertEquals(HttpStatusCode.OK, patchNumber(client, stackId, rows[4].entryId, """{"cueNumber":"8"}""").status)

        suspend fun post(body: RenumberEntriesRequest) = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/renumber") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val taken = post(RenumberEntriesRequest(entryIds = listOf(rows[0].entryId, rows[1].entryId), start = 7))
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertTrue(taken.body<ErrorResponse>().error.contains("Q8"))
        assertEquals(HttpStatusCode.BadRequest, post(RenumberEntriesRequest(entryIds = listOf(rows[0].entryId), start = 1, step = 0)).status)
        assertEquals(HttpStatusCode.BadRequest, post(RenumberEntriesRequest(entryIds = listOf(rows[0].entryId, rows[1].entryId), start = 9999)).status)
        assertEquals(HttpStatusCode.BadRequest, post(RenumberEntriesRequest(entryIds = emptyList(), start = 1)).status)

        // An auto number in the way gives way, as a typed number's does.
        val ok = post(RenumberEntriesRequest(entryIds = listOf(rows[0].entryId), start = 2))
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals("2", numbers(client, stackId)[0])
    }

    @Test
    fun `ASK on a cue inserted mid-stack reads the numbers the stack will show`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)

        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q2.5", cueStackId = stackId, sortOrder = 2, cueNumber = "2.5", placement = CueNumberPlacement.ASK))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        assertEquals(listOf("1", "2", "2.5", "3", "4", "5"), numbers(client, stackId))

        // A number the typed one after it would read out of order against is asked about.
        val asked = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q3", cueStackId = stackId, sortOrder = 2, cueNumber = "3", placement = CueNumberPlacement.ASK))
        }
        assertEquals(HttpStatusCode.Conflict, asked.status, asked.bodyAsText())
        assertEquals("2.5", asked.body<CueNumberOutOfOrderResponse>().next)
    }

    @Test
    fun `a refused field in an entry PATCH writes nothing, and a null placement is HERE`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveAutoCues(client)
        val second = cues(client, stackId)[1].entryId

        val refused = patchNumber(client, stackId, second, """{"cueNumber":"4.5","placement":"BY_NUMBER","autoAdvance":"yes"}""")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4", "Q5"), names(client, stackId), "nothing moved")
        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(client, stackId))

        assertEquals(HttpStatusCode.OK, patchNumber(client, stackId, second, """{"cueNumber":"4.5","placement":null}""").status)
        assertEquals(listOf("1", "4.5", "5", "6", "7"), numbers(client, stackId))
    }
}
