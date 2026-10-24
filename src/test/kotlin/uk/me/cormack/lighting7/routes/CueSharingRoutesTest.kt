package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sharing and explicit delete (cue model plan session 4, §3.6): `add-cue` shares a cue rather than
 * moving it, removing an entry never deletes its cue, a stack delete leaves its cues, and deleting a
 * cue in use is refused until forced.
 */
class CueSharingRoutesTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"
    private val manager get() = state.show.cueStackManager

    private fun stack(name: String, sort: Int): Int = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject[projectId]
            this.name = name; loop = false
            type = CueStackType.STACK.name; sortOrder = sort
        }.id.value
    }

    /** A cue setting hex-1's dimmer to [level], so it shows on stage when live. */
    private fun cue(name: String, level: Int = 100): Int = transaction(state.database) {
        DaoCue.new { project = DaoProject[projectId]; this.name = name }.also { cue ->
            DaoCueAttributeAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                attribute = "dimmer"; value = "$level"; sortOrder = 0
            }
        }.id.value
    }

    private suspend fun HttpClient.addCue(stackId: Int, cueId: Int, number: String? = null): HttpStatusCode =
        post("$base/cue-stacks/$stackId/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(AddCueToStackRequest(cueId = cueId, cueNumber = number))
        }.status

    private suspend fun HttpClient.stackRows(stackId: Int): List<CueStackCueEntry> =
        get("$base/cue-stacks/$stackId").body<CueStackDetails>().cues

    private suspend fun HttpClient.cueDetails(cueId: Int): CueDetails = get("$base/cues/$cueId").body()

    private fun onStage(cueId: Int) = cueId in state.show.fxEngine.cueLayer.activeCueIds()

    @Test
    fun `add-cue shares a cue, and one edit reads the same in both stacks`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val b = stack("B", 1)
        val opener = cue("Opener")
        assertEquals(HttpStatusCode.OK, client.addCue(a, opener))
        assertEquals(HttpStatusCode.OK, client.addCue(b, opener, number = "14"))
        // Twice in one stack is allowed too (D7).
        assertEquals(HttpStatusCode.OK, client.addCue(a, opener))

        assertEquals(listOf(opener, opener), client.stackRows(a).map { it.id })
        val bRow = client.stackRows(b).single()
        assertEquals(opener, bRow.id)
        assertEquals("14", bRow.cueNumber)
        assertFalse(bRow.cueNumberAuto)

        client.patch("$base/cues/$opener") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"House to half"}""")
        }
        assertEquals(listOf("House to half", "House to half"), client.stackRows(a).map { it.name })
        assertEquals("House to half", client.stackRows(b).single().name)

        val details = client.cueDetails(opener)
        assertEquals(listOf(a, a, b), details.entries.map { it.stackId }, "every place, in show order")
        assertNull(details.cueStackId, "a shared cue read without an entry has no one place")
        val atB = client.get("$base/cues/$opener?entryId=${bRow.entryId}").body<CueDetails>()
        assertEquals(b, atB.cueStackId)
        assertEquals("14", atB.cueNumber)

        // A number the stack already uses explicitly is refused — and a refused create leaves no cue behind.
        assertEquals(HttpStatusCode.Conflict, client.addCue(b, cue("Other"), number = "14"))
        val cuesBefore = transaction(state.database) { DaoCue.count() }
        val clash = client.post("$base/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Clash", cueStackId = b, cueNumber = "14"))
        }
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertEquals(cuesBefore, transaction(state.database) { DaoCue.count() })
    }

    @Test
    fun `an entry's number and follow are written on the entry, per stack`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val b = stack("B", 1)
        val shared = cue("Shared")
        client.addCue(a, shared)
        client.addCue(b, shared)
        val aEntry = client.stackRows(a).single().entryId

        val row = client.patch("$base/cue-stacks/$a/entries/$aEntry") {
            contentType(ContentType.Application.Json)
            setBody("""{"cueNumber":"7A","autoAdvance":true,"autoAdvanceDelayMs":2500}""")
        }.body<CueStackCueEntry>()
        assertEquals("7A", row.cueNumber)
        assertFalse(row.cueNumberAuto)
        assertTrue(row.autoAdvance)
        assertEquals(2500L, row.autoAdvanceDelayMs)
        val inB = client.stackRows(b).single()
        assertEquals("1", inB.cueNumber, "B's place keeps its own number")
        assertFalse(inB.autoAdvance)

        // The cue's own PATCH no longer reaches an entry.
        client.patch("$base/cues/$shared") {
            contentType(ContentType.Application.Json)
            setBody("""{"cueNumber":"99","autoAdvance":false}""")
        }
        assertEquals("7A", client.stackRows(a).single().cueNumber)
        assertTrue(client.stackRows(a).single().autoAdvance)

        // A follow is true or false, never null.
        assertEquals(
            HttpStatusCode.BadRequest,
            client.patch("$base/cue-stacks/$a/entries/$aEntry") {
                contentType(ContentType.Application.Json)
                setBody("""{"autoAdvance":null}""")
            }.status,
        )

        // An entry of another stack is not this stack's.
        assertEquals(
            HttpStatusCode.NotFound,
            client.patch("$base/cue-stacks/$b/entries/$aEntry") {
                contentType(ContentType.Application.Json)
                setBody("""{"cueNumber":"3"}""")
            }.status,
        )
    }

    @Test
    fun `removing an entry keeps the cue and renumbers the stack it left`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val cues = listOf(cue("One"), cue("Two"), cue("Three"))
        cues.forEach { client.addCue(a, it) }
        assertEquals(listOf("1", "2", "3"), client.stackRows(a).map { it.cueNumber })

        val first = client.stackRows(a).first().entryId
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/cue-stacks/$a/entries/$first").status)

        assertEquals(listOf(cues[1], cues[2]), client.stackRows(a).map { it.id })
        assertEquals(listOf("1", "2"), client.stackRows(a).map { it.cueNumber }, "auto numbers follow the new positions")
        val loose = client.cueDetails(cues[0])
        assertTrue(loose.entries.isEmpty(), "the cue is loose, not gone")
        assertNull(loose.cueStackId)
        assertEquals(HttpStatusCode.NotFound, client.delete("$base/cue-stacks/$a/entries/$first").status)
    }

    @Test
    fun `removing the live entry moves nothing until the next GO, which fires its follower`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val a = stack("A", 0)
        val (one, two, three) = listOf(cue("One", 50), cue("Two", 100), cue("Three", 150))
        listOf(one, two, three).forEach { client.addCue(a, it) }
        val (e1, e2, e3) = client.stackRows(a).map { it.entryId }

        manager.activateEntry(state, a, e2)
        client.delete("$base/cue-stacks/$a/entries/$e2")

        assertEquals(e2, manager.getActiveEntryId(a), "the stack still holds the removed entry's cue")
        assertTrue(onStage(two), "nothing on stage moved")
        val details = client.get("$base/cue-stacks/$a").body<CueStackDetails>()
        assertEquals(e3, details.nextEntryId, "next is the entry that followed it")

        manager.advanceStack(state, a, CueStackManager.AdvanceDirection.FORWARD)
        assertEquals(e3, manager.getActiveEntryId(a))
        assertTrue(onStage(three))
        assertFalse(onStage(two), "the detached hold let go on the GO")

        // And GO BACK from a removed live entry lands on the one before it.
        client.delete("$base/cue-stacks/$a/entries/$e3")
        manager.advanceStack(state, a, CueStackManager.AdvanceDirection.BACKWARD)
        assertEquals(e1, manager.getActiveEntryId(a))
        assertTrue(onStage(one))

        // A cue added into the gap a removed live entry left may take that entry's very ordinal; the
        // next GO still fires the entry that followed the removed one.
        listOf(cue("Five", 60), cue("Six", 70)).forEach { client.addCue(a, it) }
        val (_, e5, e6) = client.stackRows(a).map { it.entryId }
        manager.activateEntry(state, a, e5)
        client.delete("$base/cue-stacks/$a/entries/$e5")
        client.post("$base/cue-stacks/$a/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(AddCueToStackRequest(cueId = cue("Four", 200), sortOrder = 1))
        }
        manager.advanceStack(state, a, CueStackManager.AdvanceDirection.FORWARD)
        assertEquals(e6, manager.getActiveEntryId(a), "Six followed Five")
    }

    @Test
    fun `a stack delete leaves its cues, and deleteLooseCues deletes only those no other stack holds`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val b = stack("B", 1)
        val c = stack("C", 2)
        val shared = cue("Shared")
        val onlyA = cue("Only A")
        val onlyC = cue("Only C")
        client.addCue(a, shared); client.addCue(a, onlyA); client.addCue(a, onlyA)
        client.addCue(b, shared)
        client.addCue(c, onlyC)

        val kept = client.delete("$base/cue-stacks/$a").body<DeleteCueStackResponse>()
        assertEquals(listOf(onlyA), kept.looseCueIds, "twice in A and nowhere else: loose once")
        assertTrue(kept.deletedCueIds.isEmpty())
        assertEquals(HttpStatusCode.OK, client.get("$base/cues/$onlyA").status)
        assertEquals(listOf(shared), client.stackRows(b).map { it.id }, "B's entry is untouched")

        val deleted = client.delete("$base/cue-stacks/$c?deleteLooseCues=true").body<DeleteCueStackResponse>()
        assertEquals(listOf(onlyC), deleted.deletedCueIds)
        assertTrue(deleted.looseCueIds.isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("$base/cues/$onlyC").status)
        assertEquals(HttpStatusCode.OK, client.get("$base/cues/$onlyA").status, "a cue already loose is not the stack's to delete")
    }

    @Test
    fun `a cue in use is named in full, and force leaves nothing pointing at it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val used = cue("Used")
        client.addCue(a, used)
        val entryId = client.stackRows(a).single().entryId
        val unused = cue("Unused")
        transaction(state.database) {
            val project = DaoProject[projectId]
            val page = DaoBuskPage.new { this.project = project; name = "Page 1"; sortOrder = 0 }
            val column = DaoBuskColumn.new { this.page = page; row = 0; sortOrder = 0; width = 12 }
            val bank = DaoBuskBank.new { this.column = column; sortOrder = 0; name = "Bank"; solo = false; flow = BuskFlow.WRAP.name }
            DaoBuskPad.new { this.bank = bank; sortOrder = 0; cue = DaoCue[used] }
            DaoCueSlot.new { this.project = project; this.page = 0; slotIndex = 2; cue = DaoCue[used] }
            val book = DaoPromptBook.new { this.project = project; scriptHash = "a".repeat(64); pageCount = 4; coverPages = 0 }
            DaoPromptBookAnchor.new {
                promptBook = book; entry = DaoCueStackEntry[entryId]
                region = listOf(PromptBookRectDto(page = 0, x = 0.1, y = 0.1, w = 0.5, h = 0.05)); label = "LX 1"
            }
            DaoControlSurfaceBinding.new {
                this.project = project
                deviceTypeKey = "xtouch-mini"; controlId = "button-7"
                targetType = "fireCue"
                targetPayload = """{"type":"fireCue","cueId":$used,"cueUuid":"${DaoCue[used].uuid}"}"""
                sortOrder = 0
            }
        }

        val refused = client.delete("$base/cues/$used")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val uses = refused.body<CueInUseResponse>().uses.single()
        assertEquals(CODE_CUE_IN_USE, refused.body<CueInUseResponse>().code)
        assertEquals(listOf(entryId), uses.entries.map { it.entryId })
        assertEquals(listOf("Page 1"), uses.pads.map { it.pageName })
        assertEquals(1, uses.slots.size)
        assertEquals(listOf("LX 1"), uses.anchors.map { it.label })
        assertEquals(1, uses.bindings.size)

        // The bulk door: a dry run lists, and without force nothing goes.
        val dry = client.post("$base/cues/delete") {
            contentType(ContentType.Application.Json)
            setBody(DeleteCuesRequest(cueIds = listOf(used, unused), dryRun = true))
        }.body<DeleteCuesResponse>()
        assertEquals(listOf(used), dry.uses.map { it.cueId })
        assertTrue(dry.deleted.isEmpty())
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("$base/cues/delete") {
                contentType(ContentType.Application.Json)
                setBody(DeleteCuesRequest(cueIds = listOf(used, unused)))
            }.status,
        )
        assertEquals(HttpStatusCode.OK, client.get("$base/cues/$unused").status, "all or none")

        val done = client.post("$base/cues/delete") {
            contentType(ContentType.Application.Json)
            setBody(DeleteCuesRequest(cueIds = listOf(used, unused), force = true))
        }.body<DeleteCuesResponse>()
        assertEquals(listOf(used, unused), done.deleted)
        transaction(state.database) {
            assertEquals(0L, DaoCueStackEntry.count(DaoCueStackEntries.cue eq used))
            assertEquals(0L, DaoBuskPad.count(DaoBuskPads.cue eq used))
            assertEquals(0L, DaoCueSlot.count(DaoCueSlots.cue eq used))
            assertEquals(0L, DaoPromptBookAnchor.all().count())
            assertNull(DaoCue.findById(unused))
        }
        assertTrue(client.stackRows(a).isEmpty())
    }

    @Test
    fun `an unused loose cue deletes straight away`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val loose = cue("Loose")
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/cues/$loose").status)
        assertEquals(HttpStatusCode.NotFound, client.get("$base/cues/$loose").status)
    }

    @Test
    fun `a cue made or recorded without a stack is loose`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val made = client.post("$base/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Pre-show"))
        }
        assertEquals(HttpStatusCode.Created, made.status)
        assertTrue(made.body<CueDetails>().entries.isEmpty())

        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", "200", 0)
        val recorded = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", name = "Recorded"))
        }
        assertEquals(HttpStatusCode.Created, recorded.status, "CREATE needs no stack")
        val cue = transaction(state.database) { DaoCue.find { DaoCues.name eq "Recorded" }.single() }
        transaction(state.database) { assertTrue(entriesOfCue(cue).isEmpty()) }
    }

    @Test
    fun `anchors are per entry, so a shared cue has one in each place`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = stack("A", 0)
        val b = stack("B", 1)
        val shared = cue("Shared")
        client.addCue(a, shared)
        client.addCue(b, shared)
        val inA = client.stackRows(a).single().entryId
        val inB = client.stackRows(b).single().entryId
        transaction(state.database) {
            DaoPromptBook.new { project = DaoProject[projectId]; scriptHash = "b".repeat(64); pageCount = 10; coverPages = 0 }
        }
        for ((entry, page) in listOf(inA to 1, inB to 6)) {
            val response = client.put("$base/prompt-book/anchors/$entry") {
                contentType(ContentType.Application.Json)
                setBody("""{"region":[{"page":$page,"x":0.1,"y":0.2,"w":0.5,"h":0.05}],"label":"LX"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        val locations = client.get("/api/rest/projects/$projectId/cue-locations").body<List<CueLocationDto>>()
        assertEquals(setOf(inA to 1, inB to 6), locations.map { it.entryId to it.page }.toSet())
        assertTrue(locations.all { it.cueId == shared })

        assertEquals(HttpStatusCode.NoContent, client.delete("$base/prompt-book/anchors/$inA").status)
        assertEquals(listOf(inB), client.get("/api/rest/projects/$projectId/cue-locations").body<List<CueLocationDto>>().map { it.entryId })
        assertNotNull(transaction(state.database) { DaoCue.findById(shared) })
    }
}
