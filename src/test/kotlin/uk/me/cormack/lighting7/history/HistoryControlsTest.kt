package uk.me.cormack.lighting7.history

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.mcp.McpProtocol
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.fx.takeLooseCue
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoScript
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The undo key's surface and Claude doors (undo plan session 6): a surface's `undo` takes its own
 * entries, and `list_history` and `undo_changes` answer for the calling grant's alone.
 */
class HistoryControlsTest : RouteIntegrationTest() {

    private fun write(source: HistorySource, block: () -> Unit) {
        WriteOrigin.with(WriteOrigin(source)) { transaction(state.database) { block() } }
    }

    private fun look(name: String) = DaoLook.new {
        project = DaoProject[projectId]
        this.name = name
    }

    private fun looks(): Set<String> = transaction(state.database) { DaoLook.all().map { it.name }.toSet() }

    private fun stacks(): Set<String> = transaction(state.database) { DaoCueStack.all().map { it.name }.toSet() }

    @Test
    fun `a surface's undo takes its own entry and not another source's`() {
        state.ensureHistory(projectId)
        val surface = HistorySource.surface("x-touch-left")
        write(surface) { look("Surface wash") }
        write(HistorySource.DESK) { look("Desk wash") }
        val actions = DefaultSurfaceActions(state)

        val result = runBlocking { actions.surfaceHistoryMove(projectId, surface, HistoryMove(undo = true)) }
        val done = assertIs<RecordRestorer.Outcome.Done>(assertIs<HistoryMoveResult.Show>(result).outcome)
        assertEquals("Look “Surface wash” added", done.entries.single().label)
        assertEquals(setOf("Desk wash"), looks())

        assertEquals(HistoryMoveResult.Empty, runBlocking { actions.surfaceHistoryMove(projectId, surface, HistoryMove(undo = true)) })
        assertEquals(setOf("Desk wash"), looks(), "the desk's change is not the surface's to take")

        runBlocking { actions.surfaceHistoryMove(projectId, surface, HistoryMove(undo = false)) }
        assertEquals(setOf("Desk wash", "Surface wash"), looks(), "redo brings it back")
    }

    @Test
    fun `surface presses run one at a time in press order`() {
        state.ensureHistory(projectId)
        val surface = HistorySource.surface("x-touch-left")
        write(surface) { look("Surface wash") }
        val actions = DefaultSurfaceActions(state)
        val undo = actions.launchHistoryMove(surface, HistoryMove(undo = true))!!
        val redo = actions.launchHistoryMove(surface, HistoryMove(undo = false))!!
        runBlocking { joinAll(undo, redo) }
        assertEquals(setOf("Surface wash"), looks(), "the redo ran after the undo it brings back")
        assertTrue(state.history.entries(projectId).none { it.undone })
    }

    private val claude = AuthenticatedUser(1, UUID.randomUUID(), "chris", "Chris", UserRole.ADMIN, "cloud-grant:7")
    private val otherClaude = AuthenticatedUser(1, UUID.randomUUID(), "chris", "Chris", UserRole.ADMIN, "cloud-grant:8")

    private fun call(user: AuthenticatedUser, tool: String, arguments: String): Pair<Boolean, JsonObject> {
        val answer = runBlocking {
            McpProtocol(state).handle(
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""),
                user,
            )
        }!!["result"]!!.jsonObject
        val text = answer["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        return (answer["isError"]!!.jsonPrimitive.content == "true") to Json.parseToJsonElement(text).jsonObject
    }

    private fun ok(user: AuthenticatedUser, tool: String, arguments: String): JsonObject =
        call(user, tool, arguments).also { (error, body) -> assertTrue(!error, "$tool: $body") }.second

    private fun refusal(user: AuthenticatedUser, tool: String, arguments: String): String =
        call(user, tool, arguments).also { (error, body) -> assertTrue(error, "$tool: $body") }.second["code"]!!.jsonPrimitive.content

    @Test
    fun `list_history lists only the grant's own changes, newest first`() {
        ok(claude, "create_cue_stack", """{"name":"Act 1"}""")
        ok(otherClaude, "create_cue_stack", """{"name":"Act 2"}""")
        ok(claude, "create_cue_stack", """{"name":"Act 3"}""")

        val changes = ok(claude, "list_history", "{}")["changes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Act 3", "Act 1"), changes.map { it["label"]!!.jsonPrimitive.content.substringAfter("“").substringBefore("”") })
        assertEquals("cueStacks", changes.first()["records"]!!.jsonArray.single().jsonObject["folder"]!!.jsonPrimitive.content)
        assertEquals("false", changes.first()["undone"]!!.jsonPrimitive.content)

        assertEquals(1, ok(claude, "list_history", """{"limit":1}""")["changes"]!!.jsonArray.size)
        for (arguments in listOf("""{"limit":"5"}""", """{"limit":2.5}""", """{"limit":null}""", """{"limit":0}""")) {
            val (error, body) = call(claude, "list_history", arguments)
            assertTrue(error, "$arguments: $body")
        }
    }

    @Test
    fun `undo_changes refuses another grant's change and takes back its own`() {
        ok(claude, "create_cue_stack", """{"name":"Act 1"}""")
        ok(otherClaude, "create_cue_stack", """{"name":"Act 2"}""")
        val theirs = state.history.entries(projectId).first { it.source.key == "mcp:cloud:8" }.id
        val mine = state.history.entries(projectId).first { it.source.key == "mcp:cloud:7" }.id

        assertEquals("HISTORY_NOT_YOURS", refusal(claude, "undo_changes", """{"entryId":"$theirs"}"""))
        assertEquals(setOf("Act 1", "Act 2"), stacks(), "nothing was undone")

        val undone = ok(claude, "undo_changes", """{"entryId":"$mine"}""")["undone"]!!.jsonArray
        assertEquals(mine, undone.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(setOf("Act 2"), stacks())
        assertEquals("HISTORY_EMPTY", refusal(claude, "undo_changes", """{"entryId":"$mine"}"""), "already undone")
        assertTrue(state.history.entries(projectId).single { it.id == mine }.undone)
        assertTrue(state.history.entries(projectId).none { it.source.key == "mcp:cloud:7" && !it.undone }, "the undo made no entry of its own")
    }

    @Test
    fun `undo_changes with a count takes its newest changes, newest first, and stops when it has none`() {
        ok(claude, "create_cue_stack", """{"name":"Act 1"}""")
        ok(claude, "create_cue_stack", """{"name":"Act 2"}""")
        ok(otherClaude, "create_cue_stack", """{"name":"Act 3"}""")
        ok(claude, "create_cue_stack", """{"name":"Act 4"}""")

        val undone = ok(claude, "undo_changes", """{"count":2}""")["undone"]!!.jsonArray
            .map { it.jsonObject["label"]!!.jsonPrimitive.content.substringAfter("“").substringBefore("”") }
        assertEquals(listOf("Act 4", "Act 2"), undone)
        assertEquals(setOf("Act 1", "Act 3"), stacks())

        assertEquals(1, ok(claude, "undo_changes", """{"count":5}""")["undone"]!!.jsonArray.size, "only one of its own was left")
        assertEquals(setOf("Act 3"), stacks())
        assertEquals("HISTORY_EMPTY", refusal(claude, "undo_changes", "{}"))
    }

    @Test
    fun `undo_changes is held to the remote script gate`() {
        state.ensureHistory(projectId)
        write(HistorySource.mcp("cloud-grant:7")) { DaoScript.new { project = DaoProject[projectId]; name = "Chase"; script = "println(1)" } }
        assertEquals("REMOTE_SCRIPTS_DISABLED", refusal(claude, "undo_changes", "{}"))
        assertTrue(transaction(state.database) { DaoScript.all().any { it.name == "Chase" } }, "nothing was undone")
    }

    @Test
    fun `undo_changes refuses an entryId or count it cannot read rather than undoing the newest`() {
        ok(claude, "create_cue_stack", """{"name":"Act 1"}""")
        for (arguments in listOf("""{"entryId":7}""", """{"entryId":null}""", """{"entryId":""}""", """{"count":"2"}""", """{"count":null}""", """{"count":1.5}""")) {
            val (error, body) = call(claude, "undo_changes", arguments)
            assertTrue(error, "$arguments: $body")
            assertTrue(body["code"] == null, "$arguments is a bad argument: $body")
        }
        assertEquals(setOf("Act 1"), stacks(), "nothing was undone")
    }

    @Test
    fun `undo_changes' confirm covers the first undo of the call alone`() = testApplication {
        mountTestApp(state)
        val (first, second) = transaction(state.database) {
            val a = look("Warm wash")
            val b = look("Cool wash")
            val cue = DaoCue.new { project = DaoProject[projectId]; name = "Q11" }
            DaoCueLayer.new { this.cue = cue; this.look = a; sortOrder = 0; targets = emptyList() }
            DaoCueLayer.new { this.cue = cue; this.look = b; sortOrder = 1; targets = emptyList() }
            state.show.cueStackManager.holds.takeLooseCue(state, cue.id.value)
            a.id.value to b.id.value
        }
        state.ensureHistory(projectId)
        val grant = HistorySource.mcp("cloud-grant:7")
        write(grant) { DaoLook[first].name = "Warmer wash" }
        write(grant) { DaoLook[second].name = "Cooler wash" }

        val (asked, refusal) = call(claude, "undo_changes", """{"count":2}""")
        assertTrue(asked)
        assertEquals("HISTORY_CONFIRM", refusal["code"]!!.jsonPrimitive.content)
        assertTrue(refusal["moves"]!!.jsonArray.single().jsonPrimitive.content.contains("Cool"), "$refusal")

        val (stopped, body) = call(claude, "undo_changes", """{"count":2,"confirm":true}""")
        assertTrue(stopped, "the second needs its own confirm: $body")
        assertEquals("HISTORY_CONFIRM", body["code"]!!.jsonPrimitive.content)
        assertTrue(body["moves"]!!.jsonArray.single().jsonPrimitive.content.contains("Warm"), "$body")
        assertEquals(1, body["undone"]!!.jsonArray.size)
        assertEquals(setOf("Warmer wash", "Cool wash"), looks())

        ok(claude, "undo_changes", """{"confirm":true}""")
        assertEquals(setOf("Warm wash", "Cool wash"), looks())
    }
}
