package uk.me.cormack.lighting7.history

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.mcp.McpProtocol
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.DESK_WINDOW_HEADER
import uk.me.cormack.lighting7.plugins.HISTORY_ENTRY_HEADER
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.ParkChannelInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerKeyStackInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerKeyStackOutMessage
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The show history's journal (undo plan session 3): a stored write through any door is one entry of
 * its source's, named from the records it changed, and nothing else — a rollback, an untracked write —
 * makes one.
 */
class ShowJournalTest : RouteIntegrationTest() {

    private val history get() = state.history

    private fun entries(project: Int = projectId) = history.entries(project)

    private suspend fun HttpClient.send(
        method: String,
        path: String,
        body: String? = null,
        window: String? = null,
    ): HttpResponse {
        val url = "/api/rest/projects/$projectId$path"
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            window?.let { header(DESK_WINDOW_HEADER, it) }
        }
        val answer = when (method) {
            "POST" -> post(url, build)
            "PUT" -> put(url, build)
            "PATCH" -> patch(url, build)
            "DELETE" -> delete(url, build)
            else -> error(method)
        }
        assertTrue(answer.status.value in 200..299, "$method $path: ${answer.status} ${answer.bodyAsText()}")
        return answer
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `a REST write is one entry of the desk's, named from what it changed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val answer = client.send("POST", "/looks", """{"name":"Warm wash","rows":[]}""", window = "w-7")
        val entry = entries().single()
        assertEquals(HistorySource.DESK, entry.source)
        assertEquals("w-7", entry.window)
        assertTrue(answer.headers[HISTORY_ENTRY_HEADER]!! in entry.requestIds, "the answer's id is the entry's")
        assertEquals("Look “Warm wash” added", entry.label)
        assertEquals(HistoryEntry.Counts(added = 1, removed = 0, changed = 0), entry.counts())
    }

    @Test
    fun `a signed-in account's write is its own, with its window, and the sheet marks it mine`() = testApplication {
        mountTestApp(state)
        uk.me.cormack.lighting7.testsupport.seedUser(state, "chris")
        val client = jsonClient()
        val cookie = client.loginCookieHeader("chris")
        val answer = client.post("/api/rest/projects/$projectId/looks") {
            header(io.ktor.http.HttpHeaders.Cookie, cookie)
            header(DESK_WINDOW_HEADER, "w-3")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Warm wash","rows":[]}""")
        }
        assertEquals(HttpStatusCode.Created.value / 100, answer.status.value / 100, answer.bodyAsText())
        val entry = entries().single()
        assertEquals(HistorySource.Kind.ACCOUNT, entry.source.kind)
        assertEquals("Test chris", entry.source.name)
        assertEquals("w-3", entry.window)

        val rows = client.get("/api/rest/projects/$projectId/history") { header(io.ktor.http.HttpHeaders.Cookie, cookie) }
            .json()["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals("true", rows.single()["mine"]!!.jsonPrimitive.content)
        assertEquals("w-3", rows.single()["source"]!!.jsonObject["window"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a route that names its entry is shown by that name`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        state.show.programmerStore.put(
            uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, "hex-1", "dimmer",
            uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Slider(200u),
        )
        val client = jsonClient()
        val stack = client.send("POST", "/cue-stacks", """{"name":"Act 1"}""").json()["id"]!!.jsonPrimitive.int
        client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody("""{"projectId":"$projectId","mode":"CREATE","cueStackId":$stack,"name":"Rose garden"}""")
        }.also { assertTrue(it.status.value in 200..299, it.bodyAsText()) }
        assertEquals("Record “Rose garden”", entries().first().label)
    }

    @Test
    fun `a read names no entry and a write that changes nothing makes none`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        assertNull(client.get("/api/rest/projects/$projectId/looks").headers[HISTORY_ENTRY_HEADER])
        val look = client.send("POST", "/looks", """{"name":"Warm wash","rows":[]}""").json()["id"]!!.jsonPrimitive.int
        client.send("PUT", "/looks/$look", """{"name":"Warm wash"}""")
        assertEquals(1, entries().size)
    }

    @Test
    fun `a sample of routes from each area each make one entry with their counts`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        fun last() = entries().first()

        val stack = client.send("POST", "/cue-stacks", """{"name":"Act 1"}""").json()["id"]!!.jsonPrimitive.int
        assertEquals("Stack “Act 1” added", last().label)

        val cue = client.send("POST", "/cues", """{"name":"Rose garden","cueStackId":$stack}""").json()["id"]!!.jsonPrimitive.int
        assertEquals(HistoryEntry.Counts(added = 2, removed = 0, changed = 0), last().counts(), "the cue and its entry")
        assertEquals("Cue “Rose garden” added", last().label)

        client.send(
            "PATCH", "/cues/$cue",
            """{"attributeAssignments":[{"targetType":"fixture","targetKey":"hex-1","attribute":"dimmer","value":"200"}]}""",
        )
        assertEquals("Cue “Rose garden”: 1 value", last().label)

        client.send("POST", "/palettes", """{"name":"Half","rows":[{"targetType":"deferred","targetKey":"","attribute":"dimmer","value":"pct:50"}]}""")
        assertEquals("Palette “Half” added", last().label)

        client.send("POST", "/busk/pages", """{"name":"Colours"}""")
        assertEquals("Busk page “Colours” added", last().label)

        client.send("POST", "/scripts", """{"name":"Chase","script":"println(1)"}""")
        assertEquals("Script “Chase” added", last().label)

        client.send(
            "POST", "/stage-elements",
            """{"name":"Back wall","kind":"FLAT","layer":"SET","widthM":4.0,"depthM":0.1,"heightM":3.0}""",
        )
        assertEquals("Scene element “Back wall” added", last().label)

        val patch = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoFixturePatch.find { uk.me.cormack.lighting7.models.DaoFixturePatches.key eq "hex-1" }.single().id.value
        }
        client.send("PUT", "/patches/$patch", """{"stageX":1.5}""")
        assertEquals(HistoryEntry.Counts(added = 0, removed = 0, changed = 1), last().counts())
        assertTrue(last().label.startsWith("Fixture “"), last().label)

        client.send("DELETE", "/cues/$cue?force=true")
        assertEquals(
            HistoryEntry.Counts(added = 0, removed = 3, changed = 0), last().counts(),
            "the cue, its entry and its value: ${last().records.map { it.key }}",
        )
        assertEquals("Cue “Rose garden” deleted", last().label)
        assertEquals(9, entries().size, entries().joinToString { it.label })
        assertTrue(entries().all { it.source == HistorySource.DESK })
    }

    @Test
    fun `a park from the socket is one entry, and a nudge within the window folds into it`() = testApplication {
        mountTestApp(state)
        createWsClient().webSocket("/api") {
            sendSerialized<InMessage>(ParkChannelInMessage(universe = 0, channel = 5, value = 100u))
            sendSerialized<InMessage>(ParkChannelInMessage(universe = 0, channel = 5, value = 120u))
            barrier()
        }
        val entry = entries().single()
        assertEquals("Park 0/5 added", entry.label)
        assertEquals(HistoryEntry.Counts(added = 1, removed = 0, changed = 0), entry.counts())
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.barrier() {
        val id = UUID.randomUUID().toString()
        sendSerialized<InMessage>(ProgrammerKeyStackInMessage("fixture", "none", "dimmer", requestId = id))
        awaitOfType<ProgrammerKeyStackOutMessage> { it.requestId == id }
    }

    @Test
    fun `a Claude tool call is one entry under its grant, and one on another project lands there`() = testApplication {
        mountTestApp(state)
        val other = seedMinimalProject(state, projectName = "Other show").also {
            transaction(state.database) { DaoProject[it].isCurrent = false }
        }
        val claude = AuthenticatedUser(1, UUID.randomUUID(), "chris", "Chris", UserRole.ADMIN, "cloud-grant:7")
        call(claude, "create_cue_stack", """{"name":"Act 2"}""")
        val entry = entries().single()
        assertEquals(HistorySource.Kind.MCP, entry.source.kind)
        assertEquals("mcp:cloud:7", entry.source.key)

        call(claude, "set_stage", """{"projectId":$other,"stage":{"widthM":10,"depthM":8}}""")
        assertTrue(entries(other).isNotEmpty(), "journaled on the project it named")
        assertEquals(1, entries().size, "and not on the current one")
    }

    private fun call(user: AuthenticatedUser, tool: String, arguments: String) {
        val answer = runBlocking {
            McpProtocol(state).handle(
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""),
                user,
            )
        }
        assertEquals("false", answer!!["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content, "$answer")
    }

    @Test
    fun `writes within the merge window to the same records are one entry, and to other records are not`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val look = client.send("POST", "/looks", """{"name":"Warm wash","rows":[]}""").json()["id"]!!.jsonPrimitive.int
        client.send("PUT", "/looks/$look", """{"name":"Warm"}""")
        client.send("PUT", "/looks/$look", """{"name":"Warmer"}""")
        assertEquals("Look “Warmer” added", entries().single().label, "a create and two quick renames are one entry")
        client.send("POST", "/looks", """{"name":"Cool","rows":[]}""")
        assertEquals(2, entries().size, "another record is another entry")
    }

    @Test
    fun `a rollback makes no entry`() = testApplication {
        mountTestApp(state)
        history.ensure(projectId)
        runCatching {
            WriteOrigin.with(WriteOrigin(HistorySource.DESK)) {
                transaction(state.database) {
                    DaoLook.new {
                        project = DaoProject[projectId]
                        name = "Never"
                    }
                    error("rolled back")
                }
            }
        }
        assertTrue(entries().isEmpty())
        assertTrue(history.isJournaled(projectId))
    }

    @Test
    fun `a pending insert that fails at commit still fails the commit`() = testApplication {
        mountTestApp(state)
        jsonClient().send("POST", "/looks", """{"name":"Warm","rows":[]}""")
        val attempt = runCatching {
            WriteOrigin.with(WriteOrigin(HistorySource.DESK)) {
                transaction(state.database) {
                    // Neither row's id is read, so both inserts wait for the commit's flush, where "Warm" clashes.
                    DaoLook.new { project = DaoProject[projectId]; name = "Other" }
                    DaoLook.new { project = DaoProject[projectId]; name = "Warm" }
                }
            }
        }
        assertTrue(attempt.isFailure, "the clash reaches the caller, as it did before the journal")
        assertEquals(listOf("Warm"), transaction(state.database) { DaoLook.all().map { it.name } })
        assertEquals(1, entries().size)
    }

    @Test
    fun `a request merged into another's entry finds it again on its next write`() = testApplication {
        mountTestApp(state)
        history.ensure(projectId)
        val look = transaction(state.database) { DaoLook.new { project = DaoProject[projectId]; name = "Warm" }.id.value }
        fun write(origin: WriteOrigin, block: () -> Unit) = WriteOrigin.with(origin) { transaction(state.database) { block() } }
        val first = WriteOrigin(HistorySource.DESK)
        val second = WriteOrigin(HistorySource.DESK)
        write(first) { DaoLook[look].name = "Warm 1" }
        write(second) { DaoLook[look].name = "Warm 2" }
        write(second) { DaoLook.new { project = DaoProject[projectId]; name = "Cool" } }
        val entry = entries().single()
        assertEquals(setOf(first.requestId, second.requestId), entry.requestIds)
        assertEquals(HistoryEntry.Counts(added = 1, removed = 0, changed = 1), entry.counts())
    }

    @Test
    fun `an untracked write to an array an entry changed clears the history, and one it undoes drops out`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val master = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoSpeedMaster.find { DaoSpeedMasters.project eq projectId }.first().id.value
        }
        val original = transaction(state.database) { uk.me.cormack.lighting7.models.DaoSpeedMaster[master].bpm }
        client.send("PUT", "/speed-masters/$master", """{"bpm":130}""")
        assertEquals(1, entries().size)
        // The flush lands the tempo the entry moved from: the change is now nothing, and goes.
        transaction(state.database) { uk.me.cormack.lighting7.models.DaoSpeedMaster[master].bpm = original }
        assertTrue(entries().isEmpty(), "an entry whose change the flush undid is gone: ${entries().map { it.label }}")

        val look = client.send(
            "POST", "/looks",
            """{"name":"Warm","rows":[{"targetType":"fixture","targetKey":"hex-1","attribute":"dimmer","value":"100"}]}""",
        ).json()["id"]!!.jsonPrimitive.int
        // Another record between, so the edit below is an entry of its own with the old rows as its before.
        client.send("POST", "/looks", """{"name":"Cool","rows":[]}""")
        client.send(
            "PUT", "/looks/$look",
            """{"rows":[{"targetType":"fixture","targetKey":"hex-1","attribute":"dimmer","value":"200"}]}""",
        )
        assertTrue(entries().isNotEmpty())
        transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoLookRow.all().forEach { it.value = "50" }
        }
        assertTrue(entries().isEmpty(), "rows the entry changed cannot be merged, so the history is cleared")
        assertEquals(HistoryService.BACKGROUND_REASON, history.clearPoints(projectId).first().reason)
    }

    @Test
    fun `the tempo flush makes no entry and is rebased into the entries that hold its master`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val master = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoSpeedMaster.find { DaoSpeedMasters.project eq projectId }.first().let { it.id.value to it.uuid }
        }
        client.send("PUT", "/speed-masters/${master.first}", """{"name":"Fast"}""")
        val key = RecordKey("speedMasters", master.second)
        val renamed = entries().single()
        assertEquals("Speed master “Fast” changed", renamed.label)

        // The debounced flush writes with no origin, as the clock's work does.
        transaction(state.database) { uk.me.cormack.lighting7.models.DaoSpeedMaster[master.first].bpm = 141.0 }
        assertEquals(1, entries().size, "no entry of its own")
        val cached = assertNotNull(history.recordOf(projectId, key))
        assertTrue(cached.json.contains("141"), "the cache took the flush")
        val held = entries().single().records.single()
        assertEquals(cached.hash, held.after!!.hash, "the entry's after is the record as it stands, so it reads as no conflict")
        assertTrue(held.before!!.json.contains("141"), "and its before carries the tempo too, so an undo would not put it back")

        client.send("POST", "/looks", """{"name":"Cool","rows":[]}""")
        assertTrue(entries().first().records.none { it.key.tableName == "speedMasters" }, "a later entry holds no bpm")
    }

    @Test
    fun `a cue editor's flushes within the window are one entry`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val cue = client.send("POST", "/cues", """{"name":"Rose garden"}""").json()["id"]!!.jsonPrimitive.int
        Thread.sleep(HistoryService.MERGE_MS + 100)
        client.send("PATCH", "/cues/$cue", """{"notes":"one"}""")
        client.send("PATCH", "/cues/$cue", """{"notes":"two"}""")
        client.send("PATCH", "/cues/$cue", """{"notes":"three"}""")
        assertEquals(listOf("Cue “Rose garden” changed", "Cue “Rose garden” added"), entries().map { it.label })
    }

    @Test
    fun `history state names the socket's own newest entry as what the key takes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        createWsClient().webSocket("/api") {
            awaitOfType<uk.me.cormack.lighting7.plugins.HistoryStateOutMessage>()
            client.send("POST", "/looks", """{"name":"Warm wash","rows":[]}""")
            val frame = awaitOfType<uk.me.cormack.lighting7.plugins.HistoryStateOutMessage> { it.undo != null }
            assertEquals("SHOW", frame.undo!!.kind)
            assertEquals("Look “Warm wash” added", frame.undo.label)
            assertEquals(projectId, frame.projectId)
            assertTrue(frame.showRevision > 0, "the show history's revision rides beside the programmer's")
        }
    }

    @Test
    fun `a clear point drops the show history until a door names the project again`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.send("POST", "/looks", """{"name":"Warm wash","rows":[]}""")
        history.clear(projectId, "Pulled from GitHub")
        assertTrue(entries().isEmpty())
        assertEquals("Pulled from GitHub", history.clearPoints(projectId).single().reason)
        assertTrue(!history.isJournaled(projectId))
        client.send("POST", "/looks", """{"name":"Cool wash","rows":[]}""")
        assertEquals("Look “Cool wash” added", entries().single().label)
    }

    @Test
    fun `the history route lists entries newest first with their sources and clear points`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.send("POST", "/looks", """{"name":"Warm wash","rows":[]}""")
        history.clear(projectId, "Pulled from GitHub")
        client.send("POST", "/looks", """{"name":"Cool wash","rows":[]}""")
        val page = client.get("/api/rest/projects/$projectId/history").let {
            assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
            it.json()
        }
        val rows = page["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("SHOW", "CLEAR"), rows.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("Look “Cool wash” added", rows[0]["label"]!!.jsonPrimitive.content)
        assertEquals("Desk", rows[0]["source"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Pulled from GitHub", rows[1]["label"]!!.jsonPrimitive.content)
    }
}
