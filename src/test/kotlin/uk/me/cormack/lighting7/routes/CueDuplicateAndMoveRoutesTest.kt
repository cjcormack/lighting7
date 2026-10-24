package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The show-cue-editing plan's session 3 routes: *Duplicate* (`POST /cues/{id}/duplicate`, D12)
 * appends a full copy to the end of a stack or leaves it loose, and *Move to stack…*
 * (`POST /cue-stacks/{id}/entries/{entryId}/move`) moves an entry whole.
 */
class CueDuplicateAndMoveRoutesTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"
    private val manager get() = state.show.cueStackManager

    private fun stack(name: String, sort: Int): Int = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject[projectId]
            this.name = name; loop = false
            type = CueStackType.STACK.name; sortOrder = sort
        }.id.value
    }

    private fun cue(name: String, level: Int = 100): Int = transaction(state.database) {
        DaoCue.new { project = DaoProject[projectId]; this.name = name }.also { cue ->
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                attribute = "dimmer"; value = "$level"; sortOrder = 0
            }
        }.id.value
    }

    private suspend fun HttpClient.addCue(stackId: Int, cueId: Int, number: String? = null) {
        val status = post("$base/cue-stacks/$stackId/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(AddCueToStackRequest(cueId = cueId, cueNumber = number))
        }.status
        assertEquals(HttpStatusCode.OK, status)
    }

    private suspend fun HttpClient.stackRows(stackId: Int): List<CueStackCueEntry> =
        get("$base/cue-stacks/$stackId").body<CueStackDetails>().cues

    private suspend fun HttpClient.duplicate(cueId: Int, stackId: Int?): HttpResponse =
        post("$base/cues/$cueId/duplicate") {
            contentType(ContentType.Application.Json)
            setBody(DuplicateCueRequest(stackId))
        }

    private suspend fun HttpClient.move(stackId: Int, entryId: Int, to: Int): HttpResponse =
        post("$base/cue-stacks/$stackId/entries/$entryId/move") {
            contentType(ContentType.Application.Json)
            setBody(MoveEntryRequest(to))
        }

    private fun onStage(cueId: Int) = cueId in state.show.fxEngine.cueLayer.activeCueIds()

    @Test
    fun `duplicate appends a copy to the end of the stack, unnumbered, and nothing else moves`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val (one, two, three) = listOf(cue("One"), cue("Two"), cue("Three"))
        client.addCue(a, one); client.addCue(a, two, number = "5"); client.addCue(a, three)
        val before = client.stackRows(a).map { it.entryId to it.cueNumber }

        val response = client.duplicate(two, a)
        assertEquals(HttpStatusCode.Created, response.status)
        val copy = response.body<DuplicateCueResponse>()
        assertEquals("Two (copy)", copy.name)
        assertEquals(a, copy.stackId)
        assertNotEquals(two, copy.cueId)

        val rows = client.stackRows(a)
        assertEquals(before, rows.dropLast(1).map { it.entryId to it.cueNumber }, "every other entry keeps its place and number")
        val last = rows.last()
        assertEquals(copy.cueId, last.id)
        assertEquals(copy.entryId, last.entryId)
        assertTrue(last.cueNumberAuto, "the copy takes an auto number, never the source's")

        val details = client.get("$base/cues/${copy.cueId}").body<CueDetails>()
        assertEquals("dimmer", details.propertyAssignments.single().propertyName)
        assertEquals(listOf(a), details.entries.map { it.stackId }, "one place: the end of A")
    }

    /** A copy's effect runs are its own (stack-timeline plan C4): it joins no run of its source's. */
    @Test
    fun `duplicate gives the copy's effects fresh run keys, used nowhere else`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val (one, two) = listOf(cue("One"), cue("Two"))
        client.addCue(a, one); client.addCue(a, two)
        val run = UUID.randomUUID()
        transaction(state.database) {
            for ((cueId, property) in listOf(one to "dimmer", two to "dimmer", one to "uv")) {
                DaoCueAdHocEffect.new {
                    cue = DaoCue[cueId]; targetType = "fixture"; targetKey = "hex-1"
                    fxType = "Pulse"; family = "intensity"; attribute = property; cycle = 1.0
                    blendMode = "OVERRIDE"; order = "NORMAL"; parameters = emptyMap()
                    runUuid = if (property == "dimmer") run else null
                }
            }
        }

        val first = client.duplicate(one, a).body<DuplicateCueResponse>()
        val second = client.duplicate(one, a).body<DuplicateCueResponse>()

        transaction(state.database) {
            fun keys(cueId: Int) = DaoCue[cueId].adHocEffects.associate { it.attribute to it.runUuid }
            val copied = keys(first.cueId)
            assertNull(copied["uv"], "an effect in no run stays in none")
            val fresh = assertNotNull(copied["dimmer"])
            val everyOther = DaoCueAdHocEffect.all().filter { it.cue.id.value != first.cueId }.mapNotNull { it.runUuid }
            assertTrue(fresh !in everyOther, "the copy's key is used nowhere else, the next copy's included")
            assertNotNull(keys(second.cueId)["dimmer"])
            assertEquals(mapOf<String?, UUID?>("dimmer" to run, "uv" to null), keys(one), "the source keeps its own")
            assertEquals(run, keys(two)["dimmer"])
        }
    }

    @Test
    fun `duplicate with no stack is loose, and refuses another project's or a separator`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val one = cue("One")
        client.addCue(a, one)

        val loose = client.duplicate(one, null).body<DuplicateCueResponse>()
        assertNull(loose.stackId)
        assertNull(loose.entryId)
        assertTrue(client.get("$base/cues/${loose.cueId}").body<CueDetails>().entries.isEmpty())
        assertEquals(listOf(one), client.stackRows(a).map { it.id }, "the source's stack is untouched")

        val separator = transaction(state.database) {
            DaoCueStack.new {
                project = DaoProject[projectId]; name = "—"; loop = false
                type = CueStackType.SEPARATOR.name; sortOrder = 1
            }.id.value
        }
        assertEquals(HttpStatusCode.BadRequest, client.duplicate(one, separator).status)
        assertEquals(HttpStatusCode.BadRequest, client.duplicate(one, 9_999).status)
        assertEquals(HttpStatusCode.NotFound, client.duplicate(9_999, a).status)
    }

    @Test
    fun `duplicate carries the cue's scenery, events, notes and timing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val a = stack("A", 0)
        val source = cue("Storm")
        val elementId = transaction(state.database) {
            val project = DaoProject[projectId]
            val element = DaoStageElement.new {
                this.project = project; name = "House tabs"
                kind = StageElementKind.DRAPE.name; layer = StageElementLayer.VENUE.name
            }
            val cueRow = DaoCue[source]
            cueRow.notes = "Thunder on the GO"
            cueRow.fadeDuration = Duration.ofMillis(3_500L)
            DaoCueSceneryRow.new {
                cue = cueRow; this.element = element
                stateJson = encodeSceneryState(ElementStates(open = 0.25))
                transition = Duration.ofMillis(4_000L)
            }
            val patch = DaoFixturePatch.find { DaoFixturePatches.key eq "hex-1" }.single()
            DaoCueEvent.new { cue = cueRow; this.patch = patch; trigger = "output1"; offset = Duration.ofMillis(600L) }
            element.id.value
        }
        client.addCue(a, source)

        val copy = client.duplicate(source, a).body<DuplicateCueResponse>()
        transaction(state.database) {
            val copied = DaoCue[copy.cueId]
            assertEquals("Thunder on the GO", copied.notes)
            assertEquals(Duration.ofMillis(3_500L), copied.fadeDuration)
            val scenery = cueSceneryOf(copied.id).single()
            assertEquals(elementId, scenery.element.id.value)
            assertEquals(Duration.ofMillis(4_000L), scenery.transition)
            val event = cueEventsOf(copied.id).single()
            assertEquals("output1", event.trigger)
            assertEquals(Duration.ofMillis(600L), event.offset)
            // The source keeps its own.
            assertEquals(1, cueSceneryOf(DaoCue[source].id).size)
            assertEquals(1, cueEventsOf(DaoCue[source].id).size)
        }
    }

    @Test
    fun `move keeps the entry's uuid, explicit number, follow and anchor, and renumbers both stacks`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val b = stack("B", 1)
        val (one, two, three) = listOf(cue("One"), cue("Two"), cue("Three"))
        client.addCue(a, one); client.addCue(a, two, number = "7A"); client.addCue(a, three)
        client.addCue(b, cue("Other"))
        val moving = client.stackRows(a)[1].entryId
        client.patch("$base/cue-stacks/$a/entries/$moving") {
            contentType(ContentType.Application.Json)
            setBody("""{"autoAdvance":true,"autoAdvanceDelayMs":1500}""")
        }
        val uuidBefore = transaction(state.database) {
            val book = DaoPromptBook.new { project = DaoProject[projectId]; scriptHash = "x".repeat(64); pageCount = 4 }
            DaoPromptBookAnchor.new {
                promptBook = book; entry = DaoCueStackEntry[moving]
                region = listOf(PromptBookRectDto(page = 0, x = 0.1, y = 0.2, w = 0.8, h = 0.05))
            }
            DaoCueStackEntry[moving].uuid
        }

        val response = client.move(a, moving, to = b)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(MoveEntryResponse(stackId = b, entryId = moving), response.body<MoveEntryResponse>())

        assertEquals(listOf(one, three), client.stackRows(a).map { it.id })
        val bRows = client.stackRows(b)
        val moved = bRows.last()
        assertEquals(moving, moved.entryId, "the same entry")
        assertEquals("7A", moved.cueNumber)
        assertTrue(moved.autoAdvance)
        assertEquals(1_500L, moved.autoAdvanceDelayMs)
        transaction(state.database) {
            assertEquals(uuidBefore, DaoCueStackEntry[moving].uuid)
            val anchor = DaoPromptBookAnchor.find { DaoPromptBookAnchors.entry eq DaoCueStackEntry[moving].id }.singleOrNull()
            assertNotNull(anchor, "the anchor went with it")
        }
        assertEquals(listOf("1", "2"), client.stackRows(a).map { it.cueNumber }, "A's auto numbers close the gap")
    }

    @Test
    fun `an auto-numbered entry is labelled where it lands, and an explicit clash is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val b = stack("B", 1)
        client.addCue(a, cue("One")); client.addCue(a, cue("Two"))
        client.addCue(b, cue("B1")); client.addCue(b, cue("B2"), number = "9")
        val (first, second) = client.stackRows(a).map { it.entryId }

        assertEquals(HttpStatusCode.OK, client.move(a, second, to = b).status)
        val landed = client.stackRows(b).last()
        assertEquals(second, landed.entryId)
        assertTrue(landed.cueNumberAuto)
        assertNotNull(landed.cueNumber, "labelled from B's numbers")
        assertEquals(3, client.stackRows(b).size)

        client.patch("$base/cue-stacks/$a/entries/$first") {
            contentType(ContentType.Application.Json)
            setBody("""{"cueNumber":"9"}""")
        }
        assertEquals(HttpStatusCode.Conflict, client.move(a, first, to = b).status, "B already holds 9 explicitly")
        assertEquals(listOf(first), client.stackRows(a).map { it.entryId }, "a refused move moves nothing")

        assertEquals(HttpStatusCode.BadRequest, client.move(a, first, to = a).status)
        assertEquals(HttpStatusCode.NotFound, client.move(b, first, to = a).status, "not B's entry")
    }

    @Test
    fun `moving the live entry leaves the stage alone, and the next GO fires its follower`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val a = stack("A", 0)
        val b = stack("B", 1)
        val (one, two, three) = listOf(cue("One", 50), cue("Two", 100), cue("Three", 150))
        listOf(one, two, three).forEach { client.addCue(a, it) }
        val (_, e2, e3) = client.stackRows(a).map { it.entryId }

        manager.activateEntry(state, a, e2)
        assertEquals(HttpStatusCode.OK, client.move(a, e2, to = b).status)

        assertEquals(e2, manager.getActiveEntryId(a), "A still holds the moved entry's cue")
        assertTrue(onStage(two), "nothing on stage moved")
        val heldAt = manager.holds.priorityOf(two)
        // An add to B restamps B's entries — the moved one among them — and must leave A's hold alone.
        val four = cue("Four", 10)
        client.addCue(b, four)
        assertEquals(heldAt, manager.holds.priorityOf(two), "A's hold stays in A's priority band")
        assertEquals(e3, client.get("$base/cue-stacks/$a").body<CueStackDetails>().nextEntryId)

        manager.advanceStack(state, a, CueStackManager.AdvanceDirection.FORWARD)
        assertEquals(e3, manager.getActiveEntryId(a))
        assertTrue(onStage(three))
        assertTrue(!onStage(two), "A's hold let go on its GO")
        assertEquals(listOf(two, four), client.stackRows(b).map { it.id })
    }
}
