package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.models.CODE_MOVE_IN_DARK_INVALID
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.MoveInDarkDto
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stack's *Move in dark* setting on the wire (move-in-dark plan D2): a new stack is on unless told
 * otherwise, a `PUT` without it keeps what is stored, and a bad one is refused naming every problem.
 */
class CueStackMoveInDarkRoutesTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId/cue-stacks"

    private fun stored(name: String): MoveInDarkSettings = transaction(state.database) {
        DaoCueStack.find { (DaoCueStacks.project eq projectId) and (DaoCueStacks.name eq name) }.single().moveInDarkSettings
    }

    @Test
    fun `a new stack is on at the defaults, and a PUT without it keeps what is stored`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val created = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val details = created.body<CueStackDetails>()
        assertEquals(MoveInDarkSettings.NEW_STACK.toDto(), details.moveInDark)
        assertEquals(listOf("POSITION", "COLOUR", "BEAM"), details.moveInDark.families)

        val put = client.put("$base/${details.id}") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1", loop = true, moveInDark = MoveInDarkDto(`when` = "LATE", delayMs = 2500)))
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals(MoveInDarkSettings.NEW_STACK.copy(whenMode = uk.me.cormack.lighting7.models.MoveInDarkWhen.LATE, delay = java.time.Duration.ofMillis(2500)), stored("Act 1"))

        client.put("$base/${details.id}") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1 renamed"))
        }
        assertEquals("LATE", stored("Act 1 renamed").whenMode.name, "absent keeps what is stored")
    }

    @Test
    fun `a bad setting is refused naming every problem, and nothing is written`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val refused = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 2", moveInDark = MoveInDarkDto(delayMs = 31_000, timeMs = -1, families = listOf("INTENSITY"), `when` = "SOON")))
        }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val error = refused.body<ErrorResponse>()
        assertEquals(CODE_MOVE_IN_DARK_INVALID, error.code)
        for (problem in listOf("delayMs", "timeMs", "INTENSITY", "SOON")) assertTrue(problem in error.error, error.error)
        transaction(state.database) {
            assertTrue(DaoCueStack.find { (DaoCueStacks.project eq projectId) and (DaoCueStacks.name eq "Act 2") }.empty())
        }

        val off = client.post(base) {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 3", moveInDark = MoveInDarkDto(on = false, families = listOf())))
        }
        assertEquals(HttpStatusCode.BadRequest, off.status, "an empty family list is refused")
    }

    @Test
    fun `create_cue_stack and build_cue_stack make a stack on unless told otherwise`() = runBlocking {
        val tools = AiTools(state)
        val made = tools.executeTool("create_cue_stack", Json.parseToJsonElement("""{"name":"Tool A"}""").jsonObject)
        assertTrue(made.success, made.result)
        assertEquals(MoveInDarkSettings.NEW_STACK, stored("Tool A"))

        val off = tools.executeTool(
            "create_cue_stack",
            Json.parseToJsonElement("""{"name":"Tool B","moveInDark":{"on":false,"families":["POSITION"]}}""").jsonObject,
        )
        assertTrue(off.success, off.result)
        assertEquals(false, stored("Tool B").on)
        assertEquals(setOf(uk.me.cormack.lighting7.fx.AttributeFamily.POSITION), stored("Tool B").families)

        val bad = tools.executeTool(
            "create_cue_stack",
            Json.parseToJsonElement("""{"name":"Tool C","moveInDark":{"timeMs":40000}}""").jsonObject,
        )
        assertTrue(!bad.success && "timeMs" in bad.result, bad.result)

        val built = SetupTools(state, tools).executeTool(
            "build_cue_stack",
            Json.parseToJsonElement("""{"stackName":"Built","moveInDark":{"when":"LATE"},"cues":[{"number":"1","name":"Dawn"}]}""").jsonObject,
            caller = "Tester",
        )
        assertTrue(built.success, built.result)
        assertEquals(uk.me.cormack.lighting7.models.MoveInDarkWhen.LATE, stored("Built").whenMode)
        assertEquals(true, stored("Built").on)
        assertEquals("Built", Json.parseToJsonElement(built.result).jsonObject["stackName"]!!.jsonPrimitive.content)
    }
}
