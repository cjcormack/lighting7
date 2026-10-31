package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.models.CODE_MOVE_IN_DARK_INVALID
import uk.me.cormack.lighting7.models.CueMoveInDarkDto
import uk.me.cormack.lighting7.models.CueMoveInDarkMode
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cue's *Move in dark* setting on the wire (move-in-dark plan D3), the stack's static plan
 * (`GET /cue-stacks/{id}/move-in-dark`, §3) and the MCP tools that write and report both.
 */
class CueMoveInDarkRoutesTest : RouteIntegrationTest() {

    private val cues get() = "/api/rest/projects/$projectId/cues"

    private fun stored(cueId: Int) = transaction(state.database) { DaoCue[cueId].moveInDarkSettings }

    @Test
    fun `a cue carries its setting, a PATCH changes only what it names, and a PUT without it keeps it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot", 1)
        val client = jsonClient()
        val created = client.post(cues) {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Fly","moveInDark":{"mode":"late","delayMs":1500,"live":["spot","ghost"," spot "]}}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val details = created.body<CueDetails>()
        assertEquals(CueMoveInDarkDto("LATE", 1500, null, listOf("spot", "ghost"), unknownLive = listOf("ghost")), details.moveInDark)

        val patched = client.patch("$cues/${details.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"moveInDark":{"delayMs":null,"timeMs":800}}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        val afterPatch = stored(details.id)
        assertEquals(CueMoveInDarkMode.LATE, afterPatch.mode, "a field left out keeps what is stored")
        assertEquals(null, afterPatch.delay, "null is the stack's")
        assertEquals(Duration.ofMillis(800), afterPatch.time)
        assertEquals(listOf("spot", "ghost"), afterPatch.live)

        val put = client.put("$cues/${details.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Fly in"}""")
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals(afterPatch, stored(details.id), "a PUT without it keeps it")

        val reset = client.patch("$cues/${details.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"moveInDark":null}""")
        }
        assertEquals(HttpStatusCode.OK, reset.status)
        assertEquals(uk.me.cormack.lighting7.models.CueMoveInDarkSettings.DEFAULT, stored(details.id))
    }

    @Test
    fun `a bad cue setting is refused naming every problem, and nothing is written`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val refused = client.post(cues) {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Bad","moveInDark":{"mode":"SOON","delayMs":31000,"timeMs":"fast","live":[3],"families":["POSITION"]}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val error = refused.body<ErrorResponse>()
        assertEquals(CODE_MOVE_IN_DARK_INVALID, error.code)
        for (problem in listOf("SOON", "delayMs", "timeMs", "live", "families")) assertTrue(problem in error.error, error.error)
        transaction(state.database) { assertTrue(DaoCue.all().none { it.name == "Bad" }) }

        val made = client.post(cues) {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Good"}""")
        }.body<CueDetails>()
        val patch = client.patch("$cues/${made.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Renamed","moveInDark":{"mode":"NEVER"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, patch.status)
        transaction(state.database) { assertEquals("Good", DaoCue[made.id].name, "a refused PATCH writes nothing") }
    }

    /** Q1 lights the spot, Q2 takes it out over 500 ms and follows into Q3 after [follow] ms, Q3 lights it elsewhere; Q4 is Off. */
    private fun seedStack(follow: Long?): Pair<Int, List<Int>> {
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot", 1)
        return transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project; name = "Act 1"; type = CueStackType.STACK.name
                moveInDarkSettings = MoveInDarkSettings.NEW_STACK
            }
            fun cue(name: String, fadeMs: Long, vararg rows: Pair<String, String>) = DaoCue.new {
                this.project = project; this.name = name; fadeDuration = Duration.ofMillis(fadeMs)
            }.also { cue ->
                rows.forEachIndexed { index, (attribute, value) ->
                    DaoCueAttributeAssignment.new {
                        this.cue = cue; targetType = "fixture"; targetKey = "spot"
                        this.attribute = attribute; this.value = value; sortOrder = index
                    }
                }
            }
            val q4 = cue("Q4", 0, "dimmer" to "255", "position" to "90,90")
            q4.moveInDarkSettings = uk.me.cormack.lighting7.models.CueMoveInDarkSettings(CueMoveInDarkMode.OFF)
            val list = listOf(
                cue("Q1", 0, "dimmer" to "255", "position" to "10,10"),
                cue("Q2", 500, "dimmer" to "0"),
                cue("Q3", 0, "dimmer" to "255", "position" to "200,100"),
                cue("Q5", 0, "dimmer" to "0"),
                q4,
            )
            stack.id.value to list.mapIndexed { index, cue ->
                DaoCueStackEntry.new {
                    this.stack = stack; this.cue = cue; ordinal = index.toDouble(); cueNumber = cue.name.drop(1)
                    if (index == 1 && follow != null) { autoAdvance = true; autoAdvanceDelay = Duration.ofMillis(follow) }
                }.id.value
            }
        }
    }

    @Test
    fun `the static plan answers each entry's moves, its heads moving lit and why, and the summary`() = testApplication {
        mountTestApp(state)
        val (stackId, entries) = seedStack(follow = null)
        val plan = jsonClient().get("/api/rest/projects/$projectId/cue-stacks/$stackId/move-in-dark").body<StackMoveInDarkPlanDto>()
        assertTrue(plan.rigLoaded)
        assertEquals(entries, plan.entries.map { it.entryId })
        val q3 = plan.entries[2]
        assertEquals(listOf(MoveInDarkHeadPlanDto("spot", entries[1], listOf("POSITION"))), q3.heads)
        val q4 = plan.entries[4]
        assertEquals(listOf(MoveInDarkLiveHeadDto("spot", "CUE_OFF")), q4.live)
        assertEquals(MoveInDarkSummaryDto(heads = 1, cues = 1, live = 1, off = 1), plan.summary)
    }

    @Test
    fun `a follow too soon for the delay and the move time is too quick`() = testApplication {
        mountTestApp(state)
        // Delay 1 s + move 2 s; Q2's 500 ms fade comes off its follow.
        val (stackId, _) = seedStack(follow = 3_499)
        val quick = jsonClient().get("/api/rest/projects/$projectId/cue-stacks/$stackId/move-in-dark").body<StackMoveInDarkPlanDto>()
        assertEquals(listOf(MoveInDarkLiveHeadDto("spot", "TOO_QUICK")), quick.entries[2].live)
        assertTrue(quick.entries[2].heads.isEmpty())
    }

    @Test
    fun `a project that is not current answers its entries empty`() = testApplication {
        mountTestApp(state)
        seedStack(follow = null)
        val (otherId, stackId) = transaction(state.database) {
            val other = DaoProject.new { name = "Other" }
            val stack = DaoCueStack.new { project = other; name = "Elsewhere"; type = CueStackType.STACK.name }
            val cue = DaoCue.new { project = other; name = "Lit" }
            DaoCueAttributeAssignment.new { this.cue = cue; targetType = "fixture"; targetKey = "spot"; attribute = "dimmer"; value = "255" }
            DaoCueStackEntry.new { this.stack = stack; this.cue = cue; ordinal = 0.0 }
            other.id.value to stack.id.value
        }
        val plan = jsonClient().get("/api/rest/projects/$otherId/cue-stacks/$stackId/move-in-dark").body<StackMoveInDarkPlanDto>()
        assertEquals(false, plan.rigLoaded)
        assertEquals(1, plan.entries.size)
        assertTrue(plan.entries.single().heads.isEmpty())
        val elsewhere = jsonClient().get("/api/rest/projects/$projectId/cue-stacks/$stackId/move-in-dark")
        assertEquals(HttpStatusCode.NotFound, elsewhere.status, "another project's stack is not found")
    }

    @Test
    fun `set_move_in_dark writes a stack's and a cue's setting, and create_cue and build_cue_stack take one`() = runBlocking {
        val (stackId, _) = seedStack(follow = null)
        val tools = AiTools(state)
        val setup = SetupTools(state, tools)
        fun json(text: String) = Json.parseToJsonElement(text).jsonObject

        val stack = setup.executeTool("set_move_in_dark", json("""{"stackId":$stackId,"moveInDark":{"when":"LATE","families":["POSITION"]}}"""))
        assertTrue(stack.success, stack.result)
        val stackSettings = transaction(state.database) { DaoCueStack[stackId].moveInDarkSettings }
        assertEquals(uk.me.cormack.lighting7.models.MoveInDarkWhen.LATE, stackSettings.whenMode)
        assertEquals(true, stackSettings.on, "a field left out keeps what is stored")

        val created = tools.executeTool("create_cue", json("""{"name":"Tool cue","moveInDark":{"mode":"EARLY","live":["spot"]}}"""))
        assertTrue(created.success, created.result)
        val cueId = transaction(state.database) { DaoCue.all().single { it.name == "Tool cue" }.id.value }
        assertEquals(CueMoveInDarkMode.EARLY, stored(cueId).mode)

        val cue = setup.executeTool("set_move_in_dark", json("""{"cueId":$cueId,"moveInDark":{"timeMs":null,"live":[]}}"""))
        assertTrue(cue.success, cue.result)
        assertEquals(uk.me.cormack.lighting7.models.CueMoveInDarkSettings(CueMoveInDarkMode.EARLY), stored(cueId))

        val both = setup.executeTool("set_move_in_dark", json("""{"stackId":$stackId,"cueId":$cueId,"moveInDark":{}}"""))
        assertTrue(!both.success)
        val wrongShape = setup.executeTool("set_move_in_dark", json("""{"stackId":$stackId,"moveInDark":{"mode":"OFF"}}"""))
        assertTrue(!wrongShape.success && "mode" in wrongShape.result, wrongShape.result)

        val built = setup.executeTool(
            "build_cue_stack",
            json("""{"stackName":"Built","cues":[{"number":"1","name":"Dawn","moveInDark":{"mode":"OFF"}}]}"""),
            caller = "Tester",
        )
        assertTrue(built.success, built.result)
        val dawn = transaction(state.database) { DaoCue.all().single { it.name == "Dawn" }.id.value }
        assertEquals(CueMoveInDarkMode.OFF, stored(dawn).mode)

        val current = tools.executeTool("get_current_state", json("""{"include":["cue_stacks"]}"""))
        val act = Json.parseToJsonElement(current.result).jsonObject["cueStacks"]!!.jsonArray
            .map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.int == stackId }
        val reported = act["moveInDark"]!!.jsonObject
        assertEquals("LATE", reported["when"]!!.jsonPrimitive.content)
        assertEquals(1, reported["cuesOff"]!!.jsonPrimitive.int)
    }
}
