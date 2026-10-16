package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.cueDerivedPriority
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.entryIdOf
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Numbering on entries (cue model plan §3.3, session 1): venue-visit S3's three D11 cases carried
 * across onto `renumberAutoEntries` unchanged, with a **marker entry** in the list — it neither
 * takes a number nor counts as an auto cue's neighbour, as a MARKER cue never did — and the
 * entry-only rules session 1 adds: a cue moved into another stack renumbers the stack it left as
 * well as the one it joined, and reorder and the marker routes address entries.
 */
class EntryNumberingTest : RouteIntegrationTest() {

    private suspend fun createStack(client: HttpClient, name: String = "Act 1"): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun createCue(client: HttpClient, stackId: Int, name: String): Int {
        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private suspend fun addMarker(client: HttpClient, stackId: Int, label: String, at: Int? = null): CueStackCueEntry {
        val resp = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/markers") {
            contentType(ContentType.Application.Json)
            setBody(MarkerEntryRequest(label = label, sortOrder = at))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun rows(client: HttpClient, stackId: Int): List<CueStackCueEntry> =
        client.get("/api/rest/projects/$projectId/cue-stacks/$stackId").body<CueStackDetails>().cues

    /** The list as an operator reads it: a cue by its number, a marker by `|label|`. */
    private suspend fun list(client: HttpClient, stackId: Int): List<String?> =
        rows(client, stackId).map { if (it.cueType == "MARKER") "|${it.name}|" else it.cueNumber }

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

    /** Five auto cues with a marker after the second: `1, 2, |Interval|, 3, 4, 5`. */
    private suspend fun fiveCuesAndAMarker(client: HttpClient): Pair<Int, List<Int>> {
        val stackId = createStack(client)
        val ids = (1..5).map { createCue(client, stackId, "Q$it") }
        addMarker(client, stackId, "Interval", at = 2)
        assertEquals(listOf("1", "2", "|Interval|", "3", "4", "5"), list(client, stackId))
        return stackId to ids
    }

    @Test
    fun `a marker takes no number and the auto numbers count past it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        fiveCuesAndAMarker(client)
    }

    @Test
    fun `S3 case 1 on entries — cue 2 set to 4·5 leaves its neighbours, and Fix Order moves it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveCuesAndAMarker(client)

        renumber(client, ids[1], "4.5")
        assertEquals(listOf("1", "4.5", "|Interval|", "3", "4", "5"), list(client, stackId))
        assertTrue(detectCueNumbersOutOfOrder(list(client, stackId).filter { it?.startsWith("|") != true }))

        // The marker keeps its slot; the cues sort through the slots the cue entries held.
        fixOrder(client, stackId)
        assertEquals(listOf("Q1", "Q3", "Interval", "Q4", "Q2", "Q5"), rows(client, stackId).map { it.name })
        assertEquals(listOf("1", "2", "|Interval|", "3", "4.5", "5"), list(client, stackId))
    }

    @Test
    fun `S3 case 2 on entries — cue 3 set to 10 reads out of order and moves nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveCuesAndAMarker(client)

        renumber(client, ids[2], "10")
        assertEquals(listOf("1", "2", "|Interval|", "10", "4", "5"), list(client, stackId))
        fixOrder(client, stackId)
        assertEquals(listOf("1", "2", "|Interval|", "3", "4", "10"), list(client, stackId))
    }

    @Test
    fun `S3 case 3 on entries — cue 3 set to 1 takes cue 1's number and leaves the rest`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, ids) = fiveCuesAndAMarker(client)

        renumber(client, ids[2], "1")
        assertEquals(listOf("0", "2", "|Interval|", "1", "4", "5"), list(client, stackId))
    }

    @Test
    fun `reorder names entries, markers included, and the moved entries take their new numbers`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveCuesAndAMarker(client)

        val reversed = rows(client, stackId).map { it.entryId }.reversed()
        val resp = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/reorder") {
            contentType(ContentType.Application.Json)
            setBody(ReorderCuesRequest(entryIds = reversed))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals(listOf("Q5", "Q4", "Q3", "Interval", "Q2", "Q1"), rows(client, stackId).map { it.name })
        assertEquals(listOf("1", "2", "3", "|Interval|", "4", "5"), list(client, stackId))
        // Positions are the entries' places in the list, markers counted.
        assertEquals((0..5).toList(), rows(client, stackId).map { it.sortOrder })
    }

    @Test
    fun `moving a cue into another stack renumbers the stack it left as well as the one it joined`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val act1 = createStack(client, "Act 1")
        val act2 = createStack(client, "Act 2")
        val a = (1..3).map { createCue(client, act1, "A$it") }
        (1..2).forEach { createCue(client, act2, "B$it") }

        val resp = client.post("/api/rest/projects/$projectId/cue-stacks/$act2/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(AddCueToStackRequest(cueId = a[0]))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals(listOf("B1", "B2", "A1"), rows(client, act2).map { it.name })
        assertEquals(listOf("1", "2", "3"), list(client, act2))
        // Act 1 lost its first entry, so the two that followed shift up the derived sequence.
        assertEquals(listOf("A2", "A3"), rows(client, act1).map { it.name })
        assertEquals(listOf("1", "2"), list(client, act1))
        // One entry per cue (P4): the move took the entry, it did not copy it.
        transaction(state.database) {
            assertEquals(2, entriesOf(DaoCueStack[act1]).size)
            assertEquals(3, entriesOf(DaoCueStack[act2]).size)
        }
    }

    @Test
    fun `an explicit number the target already holds refuses the move, as the unique index would`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val act1 = createStack(client, "Act 1")
        val act2 = createStack(client, "Act 2")
        val a1 = createCue(client, act1, "A1")
        val b1 = createCue(client, act2, "B1")
        renumber(client, a1, "7")
        renumber(client, b1, "7")

        val resp = client.post("/api/rest/projects/$projectId/cue-stacks/$act2/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(AddCueToStackRequest(cueId = a1))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(listOf("A1"), rows(client, act1).map { it.name }, "nothing moved")
    }

    @Test
    fun `a marker is renamed and removed on its stack, and is no cue`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, _) = fiveCuesAndAMarker(client)
        val marker = rows(client, stackId).single { it.cueType == "MARKER" }
        assertNull(marker.id, "a marker has no cue")

        val renamed = client.put("/api/rest/projects/$projectId/cue-stacks/$stackId/markers/${marker.entryId}") {
            contentType(ContentType.Application.Json)
            setBody(MarkerEntryRequest(label = "Act break"))
        }
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals("|Act break|", list(client, stackId)[2])

        // A cue's entry is not a marker: the marker routes refuse it.
        val cueEntry = rows(client, stackId).first { it.cueType != "MARKER" }.entryId
        val wrong = client.delete("/api/rest/projects/$projectId/cue-stacks/$stackId/markers/$cueEntry")
        assertEquals(HttpStatusCode.NotFound, wrong.status)

        val removed = client.delete("/api/rest/projects/$projectId/cue-stacks/$stackId/markers/${marker.entryId}")
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertEquals(listOf("1", "2", "3", "4", "5"), list(client, stackId), "no auto number moved")
        assertFalse(rows(client, stackId).any { it.cueType == "MARKER" })
    }

    @Test
    fun `a marker cannot be made through the cue routes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val stackId = createStack(client)
        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Interval", cueStackId = stackId, cueType = "MARKER"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(rows(client, stackId).isEmpty())
    }

    /**
     * [cueId] live in [stackId] — its entry's hold taken by a GO — with a Layer 4 row standing in for
     * its contribution, stamped with a deliberately stale priority. A restamp reaches what a hold holds.
     */
    private fun putOnStage(stackId: Int, cueId: Int) {
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cueId))
        state.show.fxEngine.cueLayer.setAssignments(
            cueId,
            listOf(
                CueAssignmentResolver.Assignment(
                    cueId = cueId, priority = 1, fadeWeight = 1.0,
                    targetKey = "fx-1", targetIsGroup = false, propertyName = "dimmer",
                    category = PropertyCategory.DIMMER, compositionOverride = CompositionRule.LTP,
                    value = CueAssignmentResolver.PropertyValue.Slider(200u),
                ),
            ),
        )
    }

    private fun livePriority(cueId: Int): Int =
        state.show.fxEngine.cueLayer.assignmentsExcludingCues(emptySet()).single { it.cueId == cueId }.priority

    @Test
    fun `a delete, a marker and an insert restamp the priority of a cue already on stage`() = testApplication {
        // A stack hold's priority is its entry's position, markers counted (cues-engineering.md
        // §"Priority"), so every write that shifts positions restamps the holds on stage.
        mountTestApp(state)
        val client = jsonClient()
        val stackId = createStack(client)
        val ids = (1..3).map { createCue(client, stackId, "Q$it") }
        putOnStage(stackId, ids[2])

        client.delete("/api/rest/projects/$projectId/cues/${ids[0]}")
        assertEquals(cueDerivedPriority(stackId, 1), livePriority(ids[2]), "Q1 deleted: Q3 is second")

        val marker = addMarker(client, stackId, "Interval", at = 0)
        assertEquals(cueDerivedPriority(stackId, 2), livePriority(ids[2]), "a marker before it pushes it down")

        client.delete("/api/rest/projects/$projectId/cue-stacks/$stackId/markers/${marker.entryId}")
        assertEquals(cueDerivedPriority(stackId, 1), livePriority(ids[2]), "and its removal brings it back")

        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q0", cueStackId = stackId, sortOrder = 0))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        assertEquals(cueDerivedPriority(stackId, 2), livePriority(ids[2]), "an insert before it pushes it down")
    }
}
