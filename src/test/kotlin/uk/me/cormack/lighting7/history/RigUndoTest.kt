package uk.me.cormack.lighting7.history

import io.ktor.client.request.post
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.takeLooseCue
import io.ktor.client.request.delete
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.inStack
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotation
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.deletePlacementsOf
import uk.me.cormack.lighting7.models.sceneImageHashesOf
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.venue.ShowHides
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Undo of the rest of the show (undo plan session 5): a patch undone on the live rig, a park, a show's
 * key taken by its venue since, a prompt book's notes, and the scene images a journaled cloth names.
 */
class RigUndoTest : RouteIntegrationTest() {

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun liveKeys(): Set<String> = state.show.fixtures.fixtures.mapTo(HashSet()) { it.key }

    /** [block] in one transaction as a tracked write of [source]: one entry. */
    private fun write(source: HistorySource, block: () -> Unit) {
        WriteOrigin.with(WriteOrigin(source)) { transaction(state.database) { block() } }
    }

    private fun account(name: String) = HistorySource.account(UUID.randomUUID(), name)

    @Test
    fun `undoing a patch added while the show runs asks first, then takes the fixture off the live rig`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        // Before the history starts: an add outside it would clear it.
        val cueId = transaction(state.database) { DaoCue.new { project = DaoProject[projectId]; name = "Q1" }.id.value }
        state.ensureHistory(projectId)
        val added = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody("""{"universe":0,"fixtureTypeKey":"hex","key":"hex-9","name":"Hex 9","startChannel":100}""")
        }
        assertEquals(HttpStatusCode.Created, added.status, added.bodyAsText())
        assertTrue("hex-9" in liveKeys())
        val patchId = transaction(state.database) { DaoFixturePatch.find { DaoFixturePatches.key eq "hex-9" }.single().id.value }
        state.show.cueStackManager.holds.takeLooseCue(state, cueId)

        val asked = client.post("/api/rest/projects/$projectId/history/undo") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.Conflict, asked.status, asked.bodyAsText())
        val body = asked.json()
        assertEquals("HISTORY_CONFIRM", body["code"]!!.jsonPrimitive.content)
        assertEquals("The fixtures reload: Fixture “hex-9”", body["moves"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue("hex-9" in liveKeys(), "nothing moved before the confirm")

        val undone = client.post("/api/rest/projects/$projectId/history/undo") { contentType(ContentType.Application.Json); setBody("""{"confirm":true}""") }
        assertEquals(HttpStatusCode.OK, undone.status, undone.bodyAsText())
        assertTrue("hex-9" !in liveKeys(), "the live rig reloaded without it")

        val redone = client.post("/api/rest/projects/$projectId/history/redo") { contentType(ContentType.Application.Json); setBody("""{"confirm":true}""") }
        assertEquals(HttpStatusCode.OK, redone.status, redone.bodyAsText())
        assertTrue("hex-9" in liveKeys())
        assertEquals(patchId, transaction(state.database) { DaoFixturePatch.find { DaoFixturePatches.key eq "hex-9" }.single().id.value }, "its id kept")
    }

    @Test
    fun `undoing a drawn-only patch edit while the show runs asks nothing and rebuilds nothing`() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val cueId = transaction(state.database) { DaoCue.new { project = DaoProject[projectId]; name = "Q1" }.id.value }
        state.ensureHistory(projectId)
        state.show.cueStackManager.holds.takeLooseCue(state, cueId)
        val fixture = state.show.fixtures.untypedFixture("hex-1")
        write(HistorySource.DESK) {
            DaoFixturePatch.find { DaoFixturePatches.key eq "hex-1" }.single().apply { stageX = 2.5; gelCode = "L201" }
        }
        // As the patch PUT's metadata-only arm leaves it.
        state.show.fixtures.setPatchMetadata("hex-1", uk.me.cormack.lighting7.show.Fixtures.FixturePatchMetadata(gelCode = "L201"))
        assertIs<RecordRestorer.Outcome.Done>(runBlocking { RecordRestorer(state).undo(projectId, null, HistorySource.DESK, null, backToHere = false, confirm = false) })
        assertTrue(state.show.fixtures.untypedFixture("hex-1") === fixture, "the fixture was not rebuilt")
        assertEquals(null, state.show.fixtures.patchMetadataFor("hex-1")?.gelCode, "its metadata follows the undo")
    }

    @Test
    fun `undoing an unpark parks the channel again on the output`() {
        val parks = state.show.parkManager
        val output = state.show.fixtures.controller(Universe(0, 0))
        state.ensureHistory(projectId)
        WriteOrigin.with(WriteOrigin(HistorySource.DESK)) { runBlocking { parks.park(0, 5, 100u) } }
        val unparker = account("Sam")
        WriteOrigin.with(WriteOrigin(unparker)) { runBlocking { parks.unpark(0, 5) } }
        assertTrue(!parks.isParked(0, 5))

        assertIs<RecordRestorer.Outcome.Done>(runBlocking { RecordRestorer(state).undo(projectId, null, unparker, null, backToHere = false, confirm = false) })
        assertEquals(100u.toUByte(), parks.getParkedValue(0, 5))
        assertEquals(100u.toUByte(), output.getValue(5), "the output holds the park again")

        assertIs<RecordRestorer.Outcome.Done>(runBlocking { RecordRestorer(state).undo(projectId, null, HistorySource.DESK, null, backToHere = false, confirm = false) })
        assertTrue(!parks.isParked(0, 5), "undoing the park itself lets the channel go")
        assertEquals(100u.toUByte(), output.getValue(5), "handed down, as an unpark hands it down, not snapped to what lay under it")
    }

    @Test
    fun `a remote caller with scripts off cannot put a script back`() {
        state.ensureHistory(projectId)
        write(HistorySource.DESK) { DaoScript.new { project = DaoProject[projectId]; name = "Chase"; script = "println(1)" } }
        val outcome = runBlocking { RecordRestorer(state).undo(projectId, null, HistorySource.DESK, null, backToHere = false, confirm = false, scriptsAllowed = false) }
        assertEquals(RecordRestorer.Outcome.ScriptsRefused, outcome)
        assertTrue(transaction(state.database) { DaoScript.all().any { it.name == "Chase" } }, "nothing was undone")
    }

    @Test
    fun `undoing a swap of two fixtures' keys puts each back`() {
        LocateTestSupport.seedHex(state, projectId, "spot-1", 1)
        LocateTestSupport.seedHex(state, projectId, "spot-2", 20)
        state.ensureHistory(projectId)
        fun keyed(key: String) = DaoFixturePatch.find { (DaoFixturePatches.project eq projectId) and (DaoFixturePatches.key eq key) }.single()
        val (one, two) = transaction(state.database) { keyed("spot-1").id.value to keyed("spot-2").id.value }
        write(HistorySource.DESK) {
            DaoFixturePatch[one].key = "spot-tmp"
            TransactionManager.current().flushCache()
            DaoFixturePatch[two].key = "spot-1"
            TransactionManager.current().flushCache()
            DaoFixturePatch[one].key = "spot-2"
        }
        assertIs<RecordRestorer.Outcome.Done>(runBlocking { RecordRestorer(state).undo(projectId, null, HistorySource.DESK, null, backToHere = false, confirm = false) })
        assertEquals("spot-1" to "spot-2", transaction(state.database) { DaoFixturePatch[one].key to DaoFixturePatch[two].key })
        assertIs<RecordRestorer.Outcome.Done>(runBlocking { RecordRestorer(state).redo(projectId, null, HistorySource.DESK, null, confirm = false) })
        assertEquals("spot-2" to "spot-1", transaction(state.database) { DaoFixturePatch[one].key to DaoFixturePatch[two].key })
    }

    @Test
    fun `undoing a remove from stack keeps the auto numbers the stack holds in place`() = testApplication {
        mountTestApp(state)
        val (stackId, removed) = transaction(state.database) {
            val project = DaoProject[projectId]
            val stack = DaoCueStack.new { this.project = project; name = "Act 1"; loop = false; type = CueStackType.STACK.name; sortOrder = 0 }
            // An explicit 4.5 holding the auto numbers after it where *Fix Order* has not yet moved them.
            listOf("1" to true, "4.5" to false, "3" to true, "4" to true, "5" to true).forEachIndexed { i, (number, auto) ->
                DaoCue.new { this.project = project; name = "Q$i" }.inStack(stack, sortOrder = i, cueNumber = number, cueNumberAuto = auto)
            }
            stack.id.value to entriesOf(stack).last().id.value
        }
        state.ensureHistory(projectId)
        val before = ProjectExporter(state).exportRecords(projectId).images.mapValues { it.value.hash to it.value.rowId }
        val answer = jsonClient().delete("/api/rest/projects/$projectId/cue-stacks/$stackId/entries/$removed")
        assertTrue(answer.status.value in 200..299, answer.bodyAsText())
        assertIs<RecordRestorer.Outcome.Done>(RecordRestorer(state).undo(projectId, null, HistorySource.DESK, null, backToHere = false, confirm = false))
        assertEquals(before, ProjectExporter(state).exportRecords(projectId).images.mapValues { it.value.hash to it.value.rowId })
    }

    @Test
    fun `a show's own fixture is not put back onto a key its venue has taken since`() {
        val fixture = seedShowOnVenue(state)
        assertEquals(HideKind.PATCH, fixture.hideKind, "the fixture hides a venue patch")
        val show = fixture.showId
        val (venueKey, universe) = transaction(state.database) {
            val snapshot = RigScope.of(DaoProject[show]).venue!!.snapshotId
            val hidden = DaoFixturePatch.find { (DaoFixturePatches.uuid eq fixture.hiddenUuid) and (DaoFixturePatches.project eq snapshot) }.single()
            hidden.key to hidden.universeConfig
        }
        state.history.ensure(show)
        val alex = account("Alex")
        val blake = account("Blake")
        // Alex reuses the hidden venue fixture's key, Blake deletes it, and Alex shows the venue's again.
        write(alex) {
            DaoFixturePatch.new {
                project = DaoProject[show]; universeConfig = universe; fixtureTypeKey = "hex"
                key = venueKey; displayName = "Mine"; startChannel = 300; sortOrder = 50
            }
        }
        write(blake) {
            DaoFixturePatch.find { (DaoFixturePatches.project eq show) and (DaoFixturePatches.key eq venueKey) }.single()
                .also { deletePlacementsOf(it) }.delete()
        }
        write(alex) { ShowHides.setHiddenByUuid(DaoProject[show], HideKind.PATCH, fixture.hiddenUuid, hidden = false) }

        val outcome = runBlocking { RecordRestorer(state).undo(show, null, blake, null, backToHere = false, confirm = false) }
        val blocked = assertIs<RecordRestorer.Outcome.Blocked>(outcome, "$outcome")
        assertEquals("Fixture “$venueKey”", blocked.record)
        assertTrue(blocked.reason.startsWith("It would clash with the venue"), blocked.reason)
        assertTrue(
            transaction(state.database) { RigScope.of(DaoProject[show]).own.patches().none { it.key == venueKey } },
            "nothing was put back",
        )
    }

    @Test
    fun `a prompt book a later note stands in cannot be undone away on its own`() {
        state.ensureHistory(projectId)
        val first = account("Alex")
        write(first) { DaoPromptBook.new { project = DaoProject[projectId]; scriptHash = "b".repeat(64); pageCount = 2; coverPages = 0 } }
        write(account("Blake")) {
            DaoPromptBookAnnotation.new {
                promptBook = DaoProject[projectId].promptBook!!; kind = "NOTE"; text = "Check the gauze"
                region = listOf(PromptBookRectDto(page = 0, x = 0.2, y = 0.2, w = 0.3, h = 0.05))
            }
        }
        val outcome = runBlocking { RecordRestorer(state).undo(projectId, null, first, null, backToHere = false, confirm = false) }
        val conflict = assertIs<RecordRestorer.Outcome.Conflict>(outcome, "$outcome")
        assertEquals(listOf("Prompt-book note added"), conflict.later.map { it.label })
    }

    @Test
    fun `the scene images a journaled cloth paints with are kept from the prune`() {
        val rich = seedRichProject(state)
        state.history.ensure(rich)
        val (elementId, hashes) = transaction(state.database) {
            val painted = DaoStageElement.find { DaoStageElements.project eq rich }.first { sceneImageHashesOf(it.params).isNotEmpty() }
            painted.id.value to sceneImageHashesOf(painted.params)
        }
        write(HistorySource.DESK) { DaoStageElement[elementId].delete() }
        assertTrue(transaction(state.database) { DaoStageElement.findById(elementId) == null })
        assertEquals(hashes, state.history.sceneImageHashes(rich))
    }
}
