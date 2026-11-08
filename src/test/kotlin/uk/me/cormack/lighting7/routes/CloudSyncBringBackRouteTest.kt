package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.DaoPromptBooks
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.deletePlacementsOf
import uk.me.cormack.lighting7.venue.ShowHides
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import org.jetbrains.exposed.v1.core.and
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.sync.BringBackDto
import uk.me.cormack.lighting7.sync.BringBackRecordRef
import uk.me.cormack.lighting7.sync.BringBackRequest
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.RecordFolders
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.RestoreChange
import uk.me.cormack.lighting7.sync.RestoreReviewDto
import uk.me.cormack.lighting7.sync.SnapshotResponse
import uk.me.cormack.lighting7.sync.SnapshotRestore
import uk.me.cormack.lighting7.sync.canonicalJson
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST …/sync/restore/records` (sync-restore plan session 3): chosen records of a snapshot, and
 * what they need, brought back as one show-history entry that ⌘Z takes back.
 */
class CloudSyncBringBackRouteTest : RouteIntegrationTest() {

    private class Seeded(val look: UUID, val cue: UUID, val entry: UUID, val layer: UUID, val kept: UUID)

    /** A stack of two cues, one layering a Look. */
    private fun seed(): Seeded = transaction(state.database) {
        val project = DaoProject[projectId]
        val look = DaoLook.new { this.project = project; name = "Blue wash" }
        val stack = DaoCueStack.new { this.project = project; name = "Act 1" }
        val storm = DaoCue.new { this.project = project; name = "Storm" }
        val calm = DaoCue.new { this.project = project; name = "Calm" }
        DaoCueStackEntry.new { this.stack = stack; cue = calm; ordinal = 1.0 }
        val entry = DaoCueStackEntry.new { this.stack = stack; cue = storm; ordinal = 2.0 }
        val layer = DaoCueLayer.new { cue = storm; this.look = look; targets = emptyList() }
        Seeded(look.uuid, storm.uuid, entry.uuid, layer.uuid, calm.uuid)
    }

    /** The cue, its place and its layer deleted, and the Look it layered, as their routes leave them. */
    private fun deleteStorm(s: Seeded) = transaction(state.database) {
        DaoCueLayer.find { DaoCueLayers.uuid eq s.layer }.single().delete()
        DaoCueStackEntry.find { DaoCueStackEntries.uuid eq s.entry }.single().delete()
        DaoCue.find { DaoCues.uuid eq s.cue }.single().delete()
        DaoLook.find { DaoLooks.uuid eq s.look }.single().delete()
    }

    private fun records(): Map<RecordKey, String> = transaction(state.database) {
        ProjectExporter(state).exportRecords(projectId).images.mapValues { it.value.hash }
    }

    private suspend fun HttpClient.snapshot(): SnapshotResponse =
        post("/api/rest/projects/$projectId/sync/snapshot") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.body()

    private suspend fun HttpClient.bringBack(body: BringBackRequest, cookie: String? = null): HttpResponse =
        post("/api/rest/projects/$projectId/sync/restore/records") {
            contentType(ContentType.Application.Json)
            setBody(body)
            cookie?.let { header(HttpHeaders.Cookie, it) }
        }

    private fun code(response: HttpResponse): String = runBlocking {
        TestJson.parseToJsonElement(response.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
    }

    private fun ref(folder: String, uuid: UUID) = BringBackRecordRef(folder, uuid.toString())

    @Test
    fun `a deleted cue comes back with its stack entry and its Look, as one entry undo takes back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val s = seed()
        val first = client.snapshot().commit!!
        val firstRecords = records()
        deleteStorm(s)
        client.snapshot()

        val review = client.get("/api/rest/projects/$projectId/sync/restore/${first.sha}").body<RestoreReviewDto>()
        assertEquals(4, review.counts.back)

        val asked = client.bringBack(BringBackRequest(first.sha, listOf(ref(RecordFolders.CUES, s.cue)), dryRun = true))
        assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
        val selection = asked.body<BringBackDto>()
        assertFalse(selection.applied)
        assertEquals("Brought back from ${first.shortSha}", selection.label)
        assertEquals(RecordKey(RecordFolders.CUES, s.cue), selection.records.first().let { RecordKey(it.folder, UUID.fromString(it.uuid)) })
        assertFalse(selection.records.first().needed, "the chosen record is listed first")
        assertEquals(
            setOf(RecordFolders.CUE_STACK_ENTRIES to s.entry, RecordFolders.CUE_LAYERS to s.layer, RecordFolders.LOOKS to s.look),
            selection.records.filter { it.needed }.map { it.folder to UUID.fromString(it.uuid) }.toSet(),
            "its place and its layer are parts of it, and the layer names the Look",
        )
        assertTrue(selection.records.all { it.change == RestoreChange.BACK })
        assertEquals(firstRecords.keys - records().keys, selection.records.map { RecordKey(it.folder, UUID.fromString(it.uuid)) }.toSet())

        val applied = client.bringBack(BringBackRequest(first.sha, listOf(ref(RecordFolders.CUES, s.cue)), selection.token))
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val done = applied.body<BringBackDto>()
        assertTrue(done.applied)
        assertNotNull(done.entryId)
        assertEquals(firstRecords, records(), "every record is back as the snapshot has it")

        val history = TestJson.parseToJsonElement(client.get("/api/rest/projects/$projectId/history").bodyAsText()).jsonObject
        val top = history["rows"]!!.jsonArray.first().jsonObject
        assertEquals(done.entryId, top["id"]!!.jsonPrimitive.content)
        assertEquals("Brought back from ${first.shortSha}", top["label"]!!.jsonPrimitive.content)

        val undone = client.post("/api/rest/projects/$projectId/history/undo") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, undone.status, undone.bodyAsText())
        transaction(state.database) {
            assertTrue(DaoCue.find { DaoCues.uuid eq s.cue }.empty(), "undo takes the cue away again")
            assertTrue(DaoLook.find { DaoLooks.uuid eq s.look }.empty(), "and the Look it needed")
            assertFalse(DaoCue.find { DaoCues.uuid eq s.kept }.empty())
        }
    }

    @Test
    fun `a name taken since refuses with nothing written`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val s = seed()
        val first = client.snapshot().commit!!
        deleteStorm(s)
        transaction(state.database) { DaoLook.new { project = DaoProject[projectId]; name = "Blue wash" } }
        val before = records()

        val chosen = listOf(ref(RecordFolders.LOOKS, s.look))
        val selection = client.bringBack(BringBackRequest(first.sha, chosen, dryRun = true)).body<BringBackDto>()
        val refused = client.bringBack(BringBackRequest(first.sha, chosen, selection.token))
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertEquals(CODE_HISTORY_UNDO_BLOCKED, code(refused))
        assertEquals(before, records())
    }

    @Test
    fun `a record the review does not offer, a stale token and an empty choice are refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val s = seed()
        val first = client.snapshot().commit!!
        deleteStorm(s)
        val added = transaction(state.database) { DaoCueStack.new { project = DaoProject[projectId]; name = "Added since" }.uuid }
        val before = records()

        val gone = client.bringBack(BringBackRequest(first.sha, listOf(ref(RecordFolders.CUE_STACKS, added)), dryRun = true))
        assertEquals(HttpStatusCode.BadRequest, gone.status)
        assertEquals("RESTORE_INVALID", code(gone))
        val same = client.bringBack(BringBackRequest(first.sha, listOf(ref(RecordFolders.CUES, s.kept)), dryRun = true))
        assertEquals(HttpStatusCode.BadRequest, same.status, "a record as the snapshot has it is not offered")
        assertEquals(HttpStatusCode.BadRequest, client.bringBack(BringBackRequest(first.sha, emptyList(), dryRun = true)).status)
        val project = client.bringBack(BringBackRequest(first.sha, listOf(BringBackRecordRef("project", UUID.randomUUID().toString())), dryRun = true))
        assertEquals(HttpStatusCode.BadRequest, project.status)

        val chosen = listOf(ref(RecordFolders.LOOKS, s.look))
        val selection = client.bringBack(BringBackRequest(first.sha, chosen, dryRun = true)).body<BringBackDto>()
        val stale = client.bringBack(BringBackRequest(first.sha, chosen, "stale"))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val body = TestJson.parseToJsonElement(stale.bodyAsText()).jsonObject
        assertEquals("RESTORE_REVIEW_STALE", body["code"]!!.jsonPrimitive.content)
        assertEquals(selection.token, body["selection"]!!.jsonObject["token"]!!.jsonPrimitive.content)
        assertEquals(before, records(), "no refusal writes anything")
    }

    @Test
    fun `a show's own fixture is not brought back onto a key its venue shows again`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val fixture = seedShowOnVenue(state)
        assertEquals(HideKind.PATCH, fixture.hideKind)
        val show = fixture.showId
        val (venueKey, mine) = transaction(state.database) {
            val snapshot = RigScope.of(DaoProject[show]).venue!!.snapshotId
            val hidden = DaoFixturePatch.find { (DaoFixturePatches.uuid eq fixture.hiddenUuid) and (DaoFixturePatches.project eq snapshot) }.single()
            hidden.key to DaoFixturePatch.new {
                project = DaoProject[show]; universeConfig = hidden.universeConfig; fixtureTypeKey = "hex"
                key = hidden.key; displayName = "Mine"; startChannel = 300; sortOrder = 50
            }.uuid
        }
        val first = client.post("/api/rest/projects/$show/sync/snapshot") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.body<SnapshotResponse>().commit!!
        transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.uuid eq mine }.single().also { deletePlacementsOf(it) }.delete()
            ShowHides.setHiddenByUuid(DaoProject[show], HideKind.PATCH, fixture.hiddenUuid, hidden = false)
        }

        val chosen = listOf(ref(RecordFolders.FIXTURE_PATCHES, mine))
        val asked = client.post("/api/rest/projects/$show/sync/restore/records") {
            contentType(ContentType.Application.Json)
            setBody(BringBackRequest(first.sha, chosen, dryRun = true))
        }
        assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
        val refused = client.post("/api/rest/projects/$show/sync/restore/records") {
            contentType(ContentType.Application.Json)
            setBody(BringBackRequest(first.sha, chosen, asked.body<BringBackDto>().token))
        }
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        val body = TestJson.parseToJsonElement(refused.bodyAsText()).jsonObject
        assertEquals(CODE_HISTORY_UNDO_BLOCKED, body["code"]!!.jsonPrimitive.content)
        assertTrue(body["reason"]!!.jsonPrimitive.content.startsWith("It would clash with the venue"), body.toString())
        assertTrue(
            transaction(state.database) { RigScope.of(DaoProject[show]).own.patches().none { it.key == venueKey } },
            "nothing was brought back",
        )
    }

    @Test
    fun `a cue of a deleted stack brings the stack back with its own place alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        class Act(val stack: UUID, val cueA: UUID, val entryA: UUID, val cueB: UUID, val entryB: UUID)
        val act = transaction(state.database) {
            val project = DaoProject[projectId]
            val stack = DaoCueStack.new { this.project = project; name = "Act 2" }
            val a = DaoCue.new { this.project = project; name = "Dawn" }
            val b = DaoCue.new { this.project = project; name = "Dusk" }
            val ea = DaoCueStackEntry.new { this.stack = stack; cue = a; ordinal = 1.0 }
            val eb = DaoCueStackEntry.new { this.stack = stack; cue = b; ordinal = 2.0 }
            Act(stack.uuid, a.uuid, ea.uuid, b.uuid, eb.uuid)
        }
        val first = client.snapshot().commit!!
        transaction(state.database) {
            DaoCueStackEntry.find { DaoCueStackEntries.uuid eq act.entryA }.single().delete()
            DaoCueStackEntry.find { DaoCueStackEntries.uuid eq act.entryB }.single().delete()
            DaoCue.find { DaoCues.uuid eq act.cueA }.single().delete()
            DaoCue.find { DaoCues.uuid eq act.cueB }.single().delete()
            DaoCueStack.find { DaoCueStacks.uuid eq act.stack }.single().delete()
        }

        val selection = client.bringBack(BringBackRequest(first.sha, listOf(ref(RecordFolders.CUES, act.cueA)), dryRun = true)).body<BringBackDto>()
        assertEquals(
            setOf(RecordFolders.CUES to act.cueA, RecordFolders.CUE_STACK_ENTRIES to act.entryA, RecordFolders.CUE_STACKS to act.stack),
            selection.records.map { it.folder to UUID.fromString(it.uuid) }.toSet(),
            "a stack only needed for one entry brings no other entry of its own",
        )
        val stackToo = client.bringBack(BringBackRequest(first.sha, listOf(ref(RecordFolders.CUE_STACKS, act.stack)), dryRun = true)).body<BringBackDto>()
        assertTrue(stackToo.records.any { it.uuid == act.cueB.toString() }, "a chosen stack brings its entries and their cues")
    }

    @Test
    fun `an anchor brings back the prompt book it sits in`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val s = seed()
        val book = transaction(state.database) {
            val book = DaoPromptBook.new {
                project = DaoProject[projectId]; scriptHash = "a".repeat(64); scriptFileName = "act-one.pdf"; pageCount = 2
            }
            DaoPromptBookAnchor.new {
                promptBook = book; entry = DaoCueStackEntry.find { DaoCueStackEntries.uuid eq s.entry }.single()
                region = listOf(PromptBookRectDto(page = 0, x = 0.1, y = 0.2, w = 0.8, h = 0.05)); label = "LX 1"
            }
            book.uuid
        }
        val first = client.snapshot().commit!!
        transaction(state.database) {
            DaoPromptBookAnchor.all().forEach { it.delete() }
            DaoPromptBook.find { DaoPromptBooks.uuid eq book }.single().delete()
        }
        deleteStorm(s)

        val chosen = listOf(ref(RecordFolders.CUES, s.cue))
        val selection = client.bringBack(BringBackRequest(first.sha, chosen, dryRun = true)).body<BringBackDto>()
        assertTrue(selection.records.any { it.folder == RecordFolders.PROMPT_BOOK_ANCHORS })
        assertTrue(selection.records.any { it.folder == RecordFolders.PROMPT_BOOKS && it.uuid == book.toString() })
        val applied = client.bringBack(BringBackRequest(first.sha, chosen, selection.token))
        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        assertFalse(transaction(state.database) { DaoPromptBook.find { DaoPromptBooks.uuid eq book }.empty() })
    }

    @Test
    fun `a universe brought back with no address here is named`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val universe = transaction(state.database) {
            DaoUniverseConfig.new { project = DaoProject[projectId]; subnet = 0; universe = 3; controllerType = "MOCK"; address = null }.uuid
        }
        val first = client.snapshot().commit!!
        transaction(state.database) { DaoUniverseConfig.find { DaoUniverseConfigs.uuid eq universe }.single().delete() }

        val selection = client.bringBack(
            BringBackRequest(first.sha, listOf(ref(RecordFolders.UNIVERSE_CONFIGS, universe)), dryRun = true),
        ).body<BringBackDto>()
        assertEquals(listOf("Universe 3"), selection.addresses?.missing)
    }

    @Test
    fun `only an admin brings records back`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val refused = client.bringBack(BringBackRequest("abcd", listOf(ref(RecordFolders.LOOKS, UUID.randomUUID())), dryRun = true), op)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
    }

    @Test
    fun `references are read from uuid fields, uuid lists and palette references`() {
        val json = canonicalJson.parseToJsonElement(
            """{"cueUuid":"${UUID(0, 1)}","rows":[{"value":"pal:${UUID(0, 2)}"}],"memberUuids":["${UUID(0, 3)}"],"runUuid":"${UUID(0, 4)}","name":"${UUID(0, 5)}","parameters":{"palettes":"pal:${UUID(0, 6)}, PAL:${UUID(0, 7)}"}}""",
        )
        assertEquals(
            setOf(
                "cueUuid" to UUID(0, 1), "paletteUuid" to UUID(0, 2), "memberUuid" to UUID(0, 3),
                "paletteUuid" to UUID(0, 6), "paletteUuid" to UUID(0, 7),
            ),
            SnapshotRestore.referencesOf(json).toSet(),
        )
    }
}
