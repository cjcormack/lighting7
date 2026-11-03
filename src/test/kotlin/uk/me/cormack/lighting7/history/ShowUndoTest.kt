package uk.me.cormack.lighting7.history

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.BuskFlow
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskColumn
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPage
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueSlot
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.testsupport.inStack
import uk.me.cormack.lighting7.testsupport.entryIdOf
import uk.me.cormack.lighting7.fx.takeLooseCue
import uk.me.cormack.lighting7.plugins.HISTORY_ENTRY_HEADER
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Undo and redo of show entries through the route (undo plan session 4, §3.5): records put back with
 * their ids and what named them, conflicts and back-to-here, the confirm while the show runs, and the
 * refusals.
 */
class ShowUndoTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private fun snapshot(project: Int = projectId) =
        ProjectExporter(state).exportRecords(project).images.mapValues { (_, image) -> image.hash to image.rowId }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpClient.send(method: String, path: String, body: String? = null, cookie: String? = null): HttpResponse {
        val url = if (path.startsWith("/api")) path else "$base$path"
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            contentType(ContentType.Application.Json)
            setBody(body ?: "{}")
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }
        return when (method) {
            "POST" -> post(url, build)
            "PUT" -> put(url, build)
            "DELETE" -> delete(url) { cookie?.let { header(HttpHeaders.Cookie, it) } }
            else -> error(method)
        }
    }

    private suspend fun HttpClient.ok(method: String, path: String, body: String? = null, cookie: String? = null): HttpResponse =
        send(method, path, body, cookie).also { assertTrue(it.status.value in 200..299, "$method $path: ${it.status} ${it.bodyAsText()}") }

    private suspend fun HttpClient.undo(body: String = "{}", cookie: String? = null) = send("POST", "/history/undo", body, cookie)

    private suspend fun HttpClient.redo(body: String = "{}") = send("POST", "/history/redo", body)

    /** The entry holding the request [answer] made. */
    private fun entryOf(answer: HttpResponse): HistoryEntry =
        state.history.entries(projectId).single { answer.headers[HISTORY_ENTRY_HEADER] in it.requestIds }

    private fun stack(name: String, sort: Int): Int = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject[projectId]
            this.name = name; loop = false
            type = CueStackType.STACK.name; sortOrder = sort
        }.id.value
    }

    @Test
    fun `undoing a forced cue delete puts back the cue, both its places, its pad, its slot and its anchor, ids kept`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val a = stack("Act 1", 0)
        val b = stack("Act 2", 1)
        val cueId = transaction(state.database) {
            val project = DaoProject[projectId]
            val cue = DaoCue.new { this.project = project; name = "Rose garden" }.inStack(DaoCueStack[a], sortOrder = 0)
            DaoCueAttributeAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"; attribute = "dimmer"; value = "200"; sortOrder = 0
            }
            cue.inStack(DaoCueStack[b], sortOrder = 0)
            val page = DaoBuskPage.new { this.project = project; name = "Page 1"; sortOrder = 0 }
            val column = DaoBuskColumn.new { this.page = page; row = 0; sortOrder = 0; width = 12 }
            val bank = DaoBuskBank.new { this.column = column; sortOrder = 0; name = "Bank"; solo = false; flow = BuskFlow.WRAP.name }
            DaoBuskPad.new { this.bank = bank; sortOrder = 0; this.cue = cue }
            DaoCueSlot.new { this.project = project; this.page = 0; slotIndex = 2; this.cue = cue }
            val book = DaoPromptBook.new { this.project = project; scriptHash = "a".repeat(64); pageCount = 4; coverPages = 0 }
            DaoPromptBookAnchor.new {
                promptBook = book; entry = entriesOf(DaoCueStack[a]).single()
                region = listOf(PromptBookRectDto(page = 0, x = 0.1, y = 0.1, w = 0.5, h = 0.05)); label = "LX 1"
            }
            cue.id.value
        }
        val client = jsonClient()
        state.ensureHistory(projectId)
        val before = snapshot()

        val deleted = entryOf(client.ok("DELETE", "/cues/$cueId?force=true"))
        assertNull(transaction(state.database) { DaoCue.findById(cueId) })

        val answer = client.undo().also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.json()
        assertEquals(deleted.id, answer["undone"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(before, snapshot(), "every record back, row ids and all")
        transaction(state.database) {
            val cue = DaoCue[cueId]
            assertEquals(listOf(a, b), DaoCueStackEntry.all().filter { it.cue?.id == cue.id }.map { it.stackId }.sorted())
            assertEquals(cue.id, DaoBuskPad.all().single().cue?.id)
            assertEquals(cue.id, DaoCueSlot.all().single().cue?.id)
            assertEquals(a, DaoPromptBookAnchor.all().single().entry.stackId)
        }
        assertEquals(HttpStatusCode.OK, client.get("$base/cue-stacks/$a").status, "the stack still opens by its id")
        assertEquals(HttpStatusCode.OK, client.get("$base/cues/$cueId").status, "and the cue by its id")

        val history = answer["history"]!!.jsonObject
        assertTrue(history["undo"] == null || history["undo"] is kotlinx.serialization.json.JsonNull, "nothing left to undo")
        assertEquals("SHOW", history["redo"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.OK, client.redo().status)
        assertNull(transaction(state.database) { DaoCue.findById(cueId) }, "redo deletes it again")
    }

    @Test
    fun `an entry something has changed since answers the later entries, and back to here takes them too`() = testApplication {
        mountTestApp(state)
        seedUser(state, "chris")
        seedUser(state, "sam")
        val client = jsonClient()
        val chris = client.loginCookieHeader("chris")
        val sam = client.loginCookieHeader("sam")
        // Three sources in turn, so no two neighbouring entries are one source's to merge (D8).
        val look = client.ok("POST", "/looks", """{"name":"Warm wash","rows":[]}""", sam).json()["id"]!!.jsonPrimitive.int
        val created = snapshot()
        val rename = client.ok("PUT", "/looks/$look", """{"name":"Warmer"}""", chris)
        client.ok("PUT", "/looks/$look", """{"name":"Hottest"}""", sam)
        val renamed = entryOf(rename)

        val refused = client.undo("""{"entryId":"${renamed.id}"}""", chris)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val body = refused.json()
        assertEquals("HISTORY_CONFLICT", body["code"]!!.jsonPrimitive.content)
        assertEquals("Test sam", body["later"]!!.jsonArray.single().jsonObject["source"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val taken = client.undo("""{"entryId":"${renamed.id}","backToHere":true}""", chris)
        assertEquals(HttpStatusCode.OK, taken.status, taken.bodyAsText())
        assertEquals(2, taken.json()["undone"]!!.jsonArray.size, "sam's change, then chris's")
        assertEquals(created, snapshot())
        assertEquals("Warm wash", transaction(state.database) { DaoLook[look].name })
    }

    @Test
    fun `an entry that collides with nothing is undone on its own`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val first = client.ok("POST", "/looks", """{"name":"Warm wash","rows":[]}""")
        client.ok("POST", "/palettes", """{"name":"Half","rows":[{"targetType":"deferred","targetKey":"","attribute":"dimmer","value":"pct:50"}]}""")
        val answer = client.undo("""{"entryId":"${entryOf(first).id}"}""")
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        transaction(state.database) {
            assertTrue(DaoLook.all().none(), "the Look is gone")
            assertEquals(1, uk.me.cormack.lighting7.models.DaoPalette.all().count(), "the palette after it stays")
        }
    }

    @Test
    fun `undoing a Look edit a live cue uses asks first, then moves the stage`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val look = client.ok(
            "POST", "/looks",
            """{"name":"Warm wash","rows":[{"targetType":"fixture","targetKey":"hex-1","attribute":"colour","value":"#ff8800"}]}""",
        ).json()["id"]!!.jsonPrimitive.int
        val cueId = transaction(state.database) {
            val cue = DaoCue.new { project = DaoProject[projectId]; name = "Q11" }
            DaoCueLayer.new { this.cue = cue; this.look = DaoLook[look]; sortOrder = 0; targets = emptyList() }
            cue.id.value
        }
        state.show.cueStackManager.holds.takeLooseCue(state, cueId)
        client.ok(
            "PUT", "/looks/$look",
            """{"rows":[{"targetType":"fixture","targetKey":"hex-1","attribute":"colour","value":"#0000ff"}]}""",
        )
        assertEquals("#0000ff", cueColour())

        val asked = client.undo()
        assertEquals(HttpStatusCode.Conflict, asked.status, asked.bodyAsText())
        val body = asked.json()
        assertEquals("HISTORY_CONFIRM", body["code"]!!.jsonPrimitive.content)
        assertEquals("Live cue “Q11” uses Look “Warm wash”", body["moves"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("#0000ff", cueColour(), "nothing moved before the confirm")

        assertEquals(HttpStatusCode.OK, client.undo("""{"confirm":true}""").status)
        assertEquals("#ff8800", cueColour(), "the live cue follows the Look back")
    }

    private fun cueColour(): String {
        val value = state.show.fxEngine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("hex-1", "rgbColour")]
        return (value as CueAssignmentResolver.AttributeValue.Colour).value.toSerializedString()
    }

    @Test
    fun `an undo whose name someone has taken since is refused whole`() = testApplication {
        mountTestApp(state)
        // Made before the history starts, so its delete is an entry of its own rather than undoing its create.
        val look = transaction(state.database) { DaoLook.new { project = DaoProject[projectId]; name = "Warm wash" }.id.value }
        val client = jsonClient()
        val deleted = client.ok("DELETE", "/looks/$look")
        client.ok("POST", "/looks", """{"name":"Warm wash","rows":[]}""")
        val after = snapshot()

        val refused = client.undo("""{"entryId":"${entryOf(deleted).id}"}""")
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        val body = refused.json()
        assertEquals("HISTORY_UNDO_BLOCKED", body["code"]!!.jsonPrimitive.content)
        assertEquals("Look “Warm wash”", body["record"]!!.jsonPrimitive.content)
        assertEquals(after, snapshot(), "nothing changed")
        assertTrue(state.history.entries(projectId).none { it.undone })
    }

    @Test
    fun `a patch edit undoes, and the key takes the newer of a step and an entry`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val patch = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoFixturePatch.all().single().id.value
        }
        val before = snapshot()
        client.ok("PUT", "/patches/$patch", """{"stageX":1.5}""")
        assertEquals(HttpStatusCode.OK, client.undo().status)
        assertEquals(before, snapshot(), "the patch is back where it was")

        client.ok("POST", "/looks", """{"name":"Warm wash","rows":[]}""")
        uk.me.cormack.lighting7.history.WriteOrigin.with(WriteOrigin(HistorySource.DESK)) {
            state.show.programmerStore.put(ProgrammerOwner.WEB, "hex-1", "dimmer", CueAssignmentResolver.AttributeValue.Slider(200u))
        }
        val step = client.undo().json()
        assertTrue(step["undone"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content.startsWith("p-"), "the programmer step is newer")
        val entry = client.undo().json()
        assertEquals("Look “Warm wash” added", entry["undone"]!!.jsonArray.single().jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an operator cannot undo a show entry onto a venue`() = testApplication {
        mountTestApp(state)
        val venue = seedMinimalProject(state, projectName = "Hall").also {
            transaction(state.database) { DaoProject[it].kind = ProjectKind.VENUE; DaoProject[it].isCurrent = false }
        }
        seedUser(state, "admin")
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val refused = client.send("POST", "/api/rest/projects/$venue/history/undo", """{"entryId":"e-1"}""", op)
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertEquals("VENUE_ADMIN_ONLY", refused.json()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `undoing a record's creation another source has since named collides with that change`() = testApplication {
        mountTestApp(state)
        seedUser(state, "chris")
        seedUser(state, "sam")
        val a = stack("Act 1", 0)
        val client = jsonClient()
        val chris = client.loginCookieHeader("chris")
        val sam = client.loginCookieHeader("sam")
        val before = snapshot()
        val created = client.ok("POST", "/cues", """{"name":"Loose"}""", chris)
        val cueId = created.json()["id"]!!.jsonPrimitive.int
        client.ok("POST", "/cue-stacks/$a/add-cue", """{"cueId":$cueId}""", sam)

        val refused = client.undo("""{"entryId":"${entryOf(created).id}"}""", chris)
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertEquals("HISTORY_CONFLICT", refused.json()["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.undo("""{"entryId":"${entryOf(created).id}","backToHere":true}""", chris).status)
        assertEquals(before, snapshot(), "the cue and its place both gone, nothing left naming it")
    }

    @Test
    fun `undoing the add of a running stack's live entry leaves the stack to play on from there`() = testApplication {
        mountTestApp(state)
        val a = stack("Act 1", 0)
        val q2 = transaction(state.database) {
            DaoCue.new { project = DaoProject[projectId]; name = "Q1" }.inStack(DaoCueStack[a], sortOrder = 0)
            DaoCue.new { project = DaoProject[projectId]; name = "Q2" }.id.value
        }
        val client = jsonClient()
        client.ok("POST", "/cue-stacks/$a/add-cue", """{"cueId":$q2}""")
        val entry = transaction(state.database) { entriesOf(DaoCueStack[a]).single { it.cue?.id?.value == q2 }.id.value }
        client.ok("POST", "/cue-stacks/$a/activate", """{"entryId":$entry}""")

        assertEquals(HttpStatusCode.OK, client.undo().status)
        assertTrue(a in state.show.cueStackManager.detachedPlaces(), "its live entry is detached, as Remove from stack leaves it")
    }

    @Test
    fun `an explicit number comes back though a neighbour's auto number took it meanwhile`() = testApplication {
        mountTestApp(state)
        val a = stack("Act 1", 0)
        val q2 = transaction(state.database) {
            val project = DaoProject[projectId]
            DaoCue.new { this.project = project; name = "Q1" }.inStack(DaoCueStack[a], sortOrder = 0, cueNumber = "1", cueNumberAuto = true)
            val q2 = DaoCue.new { this.project = project; name = "Q2" }.inStack(DaoCueStack[a], sortOrder = 1, cueNumber = "2")
            DaoCue.new { this.project = project; name = "Q3" }.inStack(DaoCueStack[a], sortOrder = 2, cueNumber = "3", cueNumberAuto = true)
            q2.id.value
        }
        val client = jsonClient()
        val entry = state.entryIdOf(q2)
        client.ok("DELETE", "/cue-stacks/$a/entries/$entry")
        fun numbers() = transaction(state.database) { entriesOf(DaoCueStack[a]).map { it.cue!!.name to it.cueNumber } }
        assertEquals(listOf("Q1" to "1", "Q3" to "2"), numbers(), "Q3 took the number")

        val answer = client.undo()
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        assertEquals(listOf("Q1" to "1", "Q2" to "2", "Q3" to "3"), numbers())
    }

    @Test
    fun `undoing a forced Look delete marks the live cue edited, and redoing it takes the Look off stage`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val (look, cueId) = transaction(state.database) {
            val look = DaoLook.new { project = DaoProject[projectId]; name = "Warm wash" }
            uk.me.cormack.lighting7.models.DaoLookRow.new {
                this.look = look; targetType = "fixture"; targetKey = "hex-1"; attribute = "colour"; value = "#ff8800"; sortOrder = 0
            }
            val cue = DaoCue.new { project = DaoProject[projectId]; name = "Q11" }
            DaoCueLayer.new { this.cue = cue; this.look = look; sortOrder = 0; targets = emptyList() }
            look.id.value to cue.id.value
        }
        state.show.cueStackManager.holds.takeLooseCue(state, cueId)
        assertEquals("#ff8800", cueColour())
        val client = jsonClient()
        client.ok("DELETE", "/looks/$look?force=true")
        assertNull(colourOrNull(), "the delete took the Look off the live cue")

        // The Look and the cue's layer come back as a stored change: the cue is edited since GO, as a
        // cue edit leaves it, and nothing on stage moves, so nothing asks.
        assertEquals(HttpStatusCode.OK, client.undo().status)
        assertNull(colourOrNull())
        assertTrue(state.show.cueStackManager.holds.isEdited(cueId), "the live cue is edited since GO")
        // The redo republishes the held cue that layers the Look, so it asks first.
        assertEquals(HttpStatusCode.Conflict, client.redo().status)
        assertEquals(HttpStatusCode.OK, client.redo("""{"confirm":true}""").status)
        assertNull(colourOrNull(), "the redo takes it off again")
    }

    private fun colourOrNull(): String? =
        (state.show.fxEngine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("hex-1", "rgbColour")]
            as? CueAssignmentResolver.AttributeValue.Colour)?.value?.toSerializedString()

    @Test
    fun `a Look's rows and FX keep their ids through an undo`() = testApplication {
        mountTestApp(state)
        val look = transaction(state.database) {
            val look = DaoLook.new { project = DaoProject[projectId]; name = "Warm wash" }
            uk.me.cormack.lighting7.models.DaoLookRow.new {
                this.look = look; targetType = "fixture"; targetKey = "hex-1"; attribute = "dimmer"; value = "200"; sortOrder = 0
            }
            look.id.value
        }
        fun rowIds() = transaction(state.database) { DaoLook[look].rows.map { it.id.value } }
        val ids = rowIds()
        val client = jsonClient()
        client.ok("PUT", "/looks/$look", """{"name":"Warmer"}""")
        assertEquals(HttpStatusCode.OK, client.undo().status)
        assertEquals("Warm wash", transaction(state.database) { DaoLook[look].name })
        assertEquals(ids, rowIds())
    }

    @Test
    fun `back to here over changes that cancel out marks them all undone`() = testApplication {
        mountTestApp(state)
        seedUser(state, "chris")
        seedUser(state, "sam")
        val look = transaction(state.database) { DaoLook.new { project = DaoProject[projectId]; name = "Warm wash" }.id.value }
        val client = jsonClient()
        val chris = client.loginCookieHeader("chris")
        val sam = client.loginCookieHeader("sam")
        val first = client.ok("PUT", "/looks/$look", """{"name":"Warmer"}""", chris)
        client.ok("PUT", "/looks/$look", """{"name":"Warm wash"}""", sam)
        val answer = client.undo("""{"entryId":"${entryOf(first).id}","backToHere":true}""", chris)
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        assertTrue(state.history.entries(projectId).all { it.undone })
    }

    @Test
    fun `what an undo sets off in the programmer takes no step of its own`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val created = client.ok(
            "POST", "/looks",
            """{"name":"Warm wash","rows":[{"targetType":"fixture","targetKey":"hex-1","attribute":"dimmer","value":"200"}]}""",
        )
        val look = created.json()["id"]!!.jsonPrimitive.int
        client.ok("POST", "/looks/$look/toggle", """{"targets":[]}""")
        val steps = state.show.programmerHistory.frame().undo.map { it.id }
        assertEquals(1, steps.size, "the press is a step")

        assertEquals(HttpStatusCode.OK, client.undo("""{"entryId":"${entryOf(created).id}"}""").status)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "the Look's layer went with it")
        assertEquals(steps, state.show.programmerHistory.frame().undo.map { it.id }, "and took no step")
    }

    @Test
    fun `the key on another project with nothing of the caller's answers nothing to undo`() = testApplication {
        mountTestApp(state)
        val other = seedMinimalProject(state, projectName = "Other show").also {
            transaction(state.database) { DaoProject[it].isCurrent = false }
        }
        val answer = jsonClient().send("POST", "/api/rest/projects/$other/history/undo")
        assertEquals(HttpStatusCode.Conflict, answer.status)
        assertEquals("HISTORY_EMPTY", answer.json()["code"]!!.jsonPrimitive.content)
    }
}
