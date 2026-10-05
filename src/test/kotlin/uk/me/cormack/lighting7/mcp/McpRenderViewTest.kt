package uk.me.cormack.lighting7.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SavedView
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.ai.ViewpointResolution
import uk.me.cormack.lighting7.ai.resolveRenderViewpoint
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.ElementPose
import uk.me.cormack.lighting7.models.SeatingElement
import uk.me.cormack.lighting7.models.SeatingParams
import uk.me.cormack.lighting7.models.StageViewpointKind
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.StageRenderRequestOutMessage
import uk.me.cormack.lighting7.plugins.WindowsAnnounceInMessage
import uk.me.cormack.lighting7.plugins.WindowsStateOutMessage
import uk.me.cormack.lighting7.routes.StageRenderFailureRequest
import uk.me.cormack.lighting7.state.StageRenderService
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `render_view` (stage-view plan session 4): the viewpoint vocabulary, the named errors, and the
 * whole round trip over a real signed-in socket — the request to one window, the upload bound to
 * its request id, token and session, the cap, and the refusals once it is spent or remote.
 */
class McpRenderViewTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool("render_view", Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.code(): String? =
        Json.parseToJsonElement(result).jsonObject["error"]?.jsonPrimitive?.content

    // ─── The viewpoint vocabulary ───────────────────────────────────────

    private val stallsUuid = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val stalls = SeatingElement(
        "Stalls",
        ElementPose(0.0, -4.0, -0.95, 0.0),
        SeatingParams(rows = 12, seatsPerRow = 12, rowPitchM = 0.95, seatPitchM = 0.52),
    )
    private val deskView = SavedView(UUID.randomUUID(), "Desk", StageViewpointKind.EYE, null, null)
    private val rowF = SavedView(UUID.randomUUID(), "Row F centre", StageViewpointKind.SEAT, stallsUuid, "F6")

    private fun resolve(arg: String, seatings: Map<UUID, SeatingElement> = mapOf(stallsUuid to stalls)) =
        resolveRenderViewpoint(Json.parseToJsonElement(arg), listOf(deskView, rowF), seatings)

    @Test
    fun `a camera, a saved view by name or uuid, and a seat resolve to the Stage view's vocabulary`() {
        assertEquals(ViewpointResolution.Resolved("plan", "plan"), resolve("\"Plan\""))
        assertEquals(ViewpointResolution.Resolved(deskView.uuid.toString(), "Desk"), resolve("\"Desk\""))
        assertEquals(ViewpointResolution.Resolved(deskView.uuid.toString(), "Desk"), resolve("\"${deskView.uuid}\""))
        // A saved seat view goes as its row, so the window lands the row's own target and lens.
        assertEquals(ViewpointResolution.Resolved(rowF.uuid.toString(), "Row F centre"), resolve("\"Row F centre\""))
        assertEquals(
            ViewpointResolution.Resolved("seat:$stallsUuid:F6", "Stalls F6"),
            resolve("""{"seating":"Stalls","seat":"f6"}"""),
        )
        assertEquals(
            ViewpointResolution.Resolved("seat:$stallsUuid:A1", "Stalls A1"),
            resolve("""{"seat":"A1"}"""),
            "the one seating need not be named",
        )
    }

    @Test
    fun `an unknown viewpoint or seat is a named refusal that says what there is`() {
        val unknown = assertIs<ViewpointResolution.Refused>(resolve("\"Balcony rail\""))
        assertEquals("RENDER_UNKNOWN_VIEWPOINT", unknown.code)
        assertTrue("'Desk'" in unknown.message && "orbit" in unknown.message, unknown.message)

        val noSeat = assertIs<ViewpointResolution.Refused>(resolve("""{"seating":"Stalls","seat":"Z99"}"""))
        assertEquals("RENDER_UNKNOWN_SEAT", noSeat.code)
        assertTrue("rows A–L, seats 1–12" in noSeat.message, noSeat.message)

        assertEquals("RENDER_UNKNOWN_SEAT", assertIs<ViewpointResolution.Refused>(resolve("""{"seating":"Circle","seat":"A1"}""")).code)
        assertEquals("RENDER_UNKNOWN_SEAT", assertIs<ViewpointResolution.Refused>(resolve("""{"seat":"A1"}""", emptyMap())).code)
        // A saved seat view whose seating has gone cannot be landed, as the picker shows it disabled.
        assertEquals("RENDER_UNKNOWN_SEAT", assertIs<ViewpointResolution.Refused>(resolve("\"Row F centre\"", emptyMap())).code)
        assertEquals("RENDER_INVALID_REQUEST", assertIs<ViewpointResolution.Refused>(resolve("""{"row":"F"}""")).code)
        assertEquals("RENDER_INVALID_REQUEST", assertIs<ViewpointResolution.Refused>(resolve("42")).code)
    }

    // ─── The tool ───────────────────────────────────────────────────────

    @Test
    fun `render_view is offered read-only over MCP`() {
        val protocol = McpProtocol(state)
        val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")
        val listed = runBlocking {
            protocol.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val tool = listed.getValue("render_view").jsonObject
        assertTrue(tool["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("viewpoint"), tool["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `with no window open the tool says so by name`() {
        val result = call("""{"viewpoint":"plan"}""")
        assertFalse(result.success)
        assertEquals("RENDER_NO_WINDOW", result.code())
    }

    @Test
    fun `a bad size, source or viewpoint is refused before any window is asked`() {
        assertEquals("RENDER_INVALID_REQUEST", call("""{"viewpoint":"plan","width":4000}""").code())
        // One side alone is 16:9 to it, or refused — never clamped into another shape.
        assertEquals("RENDER_INVALID_REQUEST", call("""{"viewpoint":"plan","width":200}""").code(), "200 wide is 113 high")
        assertEquals("RENDER_INVALID_REQUEST", call("""{"viewpoint":"plan","height":1200}""").code(), "1200 high is 2133 wide")
        // A long side of 1920, but not a 1920 square.
        assertEquals("RENDER_INVALID_REQUEST", call("""{"viewpoint":"plan","width":1920,"height":1920}""").code())
        assertEquals("RENDER_INVALID_REQUEST", call("""{"viewpoint":"plan","height":"720"}""").code())
        assertEquals("RENDER_INVALID_REQUEST", call("""{"viewpoint":"plan","source":"dmx"}""").code())
        assertEquals("RENDER_INVALID_REQUEST", call("""{"viewpoint":"plan","zoom":2}""").code())
        assertEquals("RENDER_INVALID_REQUEST", call("""{}""").code())
        assertEquals("RENDER_UNKNOWN_VIEWPOINT", call("""{"viewpoint":"Row F centre"}""").code())
        assertEquals("RENDER_UNKNOWN_SEAT", call("""{"viewpoint":{"seat":"F6"}}""").code(), "this project has no seating")
    }

    // ─── Over a real socket ─────────────────────────────────────────────

    private fun png(width: Int = 4, height: Int = 3): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    private suspend fun HttpClient.upload(
        requestId: String,
        token: String?,
        cookie: String,
        body: ByteArray,
    ): HttpResponse = post("/api/rest/stage-renders/$requestId") {
        header(HttpHeaders.Cookie, cookie)
        token?.let { header("X-Render-Token", it) }
        contentType(ContentType.Image.PNG)
        setBody(body)
    }

    private fun HttpResponse.errorCode(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    /** Sign in, open one window on the busk view, and hand the socket and its cookie to [block]. */
    private suspend fun ApplicationTestBuilder.withOneWindow(
        block: suspend DefaultClientWebSocketSession.(client: HttpClient, cookie: String) -> Unit,
    ) {
        mountTestApp(state)
        seedUser(state, "op", UserRole.OPERATOR)
        val client = createWsClient()
        val cookie = client.loginCookieHeader("op")
        client.webSocket("/api", request = { header(HttpHeaders.Cookie, cookie) }) {
            sendSerialized<InMessage>(WindowsAnnounceInMessage("tab-1", "Screen 1", "/projects/$projectId/busk"))
            awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }
            awaitEligible()
            block(client, cookie)
        }
    }

    /**
     * A socket is attached as its request collector subscribes, which runs on its own coroutine
     * after setup — so an announce can land a moment before the window may be asked. A call in that
     * moment is an honest `RENDER_NO_WINDOW`; a test that expects a request must wait it out.
     */
    private suspend fun awaitEligible() {
        repeat(200) {
            if (state.stageRender.eligibleWindows(projectId).isNotEmpty()) return
            delay(10)
        }
        error("the window never became eligible to render")
    }

    @Test
    fun `a window renders it, the PNG comes back as MCP image content, and the request is spent`() = testApplication {
        withOneWindow { client, cookie ->
            val protocol = McpProtocol(state)
            val user = AuthenticatedUser(1, UUID.randomUUID(), "claude", "Claude", UserRole.ADMIN, "mcp-grant:1")
            val answer = async {
                protocol.handle(
                    Json.parseToJsonElement(
                        """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"render_view","arguments":{"viewpoint":"side","width":640}}}""",
                    ),
                    user,
                )
            }
            val request = awaitOfType<StageRenderRequestOutMessage>()
            assertEquals(projectId, request.projectId)
            assertEquals("side", request.viewpoint)
            assertEquals(640 to 360, request.width to request.height, "16:9 to the width given")
            assertEquals("output", request.source)

            val frame = png()
            assertEquals(HttpStatusCode.NoContent, client.upload(request.requestId, request.token, cookie, frame).status)

            val result = answer.await()!!["result"]!!.jsonObject
            assertFalse(result["isError"]!!.jsonPrimitive.boolean)
            val content = result["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "image"), content.map { it["type"]!!.jsonPrimitive.content })
            val image = content[1]
            assertEquals("image/png", image["mimeType"]!!.jsonPrimitive.content)
            assertTrue(frame.contentEquals(Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content)))
            val text = Json.parseToJsonElement(content[0]["text"]!!.jsonPrimitive.content).jsonObject
            assertEquals("Screen 1", text["renderedBy"]!!.jsonPrimitive.content)

            // Answered: the same upload again finds nothing to answer.
            val again = client.upload(request.requestId, request.token, cookie, frame)
            assertEquals(HttpStatusCode.NotFound, again.status)
            assertEquals("RENDER_REQUEST_UNKNOWN", again.errorCode())
        }
    }

    @Test
    fun `an upload with the wrong request id, the wrong token or another session is refused`() = testApplication {
        withOneWindow { client, cookie ->
            seedUser(state, "other", UserRole.ADMIN)
            val otherCookie = client.loginCookieHeader("other")
            val answer = async { tools.executeTool("render_view", buildJsonObject { put("viewpoint", "plan") }) }
            val request = awaitOfType<StageRenderRequestOutMessage>()

            assertEquals(HttpStatusCode.NotFound, client.upload(UUID.randomUUID().toString(), request.token, cookie, png()).status)
            val guessed = client.upload(request.requestId, "guessed", cookie, png())
            assertEquals(HttpStatusCode.Forbidden, guessed.status)
            assertEquals("RENDER_REQUEST_NOT_YOURS", guessed.errorCode())
            assertEquals(HttpStatusCode.Forbidden, client.upload(request.requestId, null, cookie, png()).status)
            // Another socket's session, even holding the token, is not the window it was sent to.
            assertEquals(HttpStatusCode.Forbidden, client.upload(request.requestId, request.token, otherCookie, png()).status)

            // None of that spent it: the window itself can still say why it will not render.
            val failed = client.post("/api/rest/stage-renders/${request.requestId}/failure") {
                header(HttpHeaders.Cookie, cookie)
                header("X-Render-Token", request.token)
                contentType(ContentType.Application.Json)
                setBody(StageRenderFailureRequest("WebGL is unavailable in this browser"))
            }
            assertEquals(HttpStatusCode.NoContent, failed.status, failed.bodyAsText())
            val result = answer.await()
            assertEquals("RENDER_FAILED", result.code())
            assertTrue("WebGL is unavailable" in result.result && "Screen 1" in result.result, result.result)
        }
    }

    @Test
    fun `an oversize or non-PNG upload is refused, and ends the render with the reason`() = testApplication {
        withOneWindow { client, cookie ->
            val answer = async { tools.executeTool("render_view", buildJsonObject { put("viewpoint", "front") }) }
            val request = awaitOfType<StageRenderRequestOutMessage>()
            val huge = ByteArray(StageRenderService.MAX_RENDER_BYTES + 1).also { png().copyInto(it) }
            val tooBig = client.upload(request.requestId, request.token, cookie, huge)
            assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
            assertEquals("RENDER_TOO_LARGE", tooBig.errorCode())
            val result = answer.await()
            assertEquals("RENDER_FAILED", result.code())
            assertTrue("smaller size" in result.result, result.result)

            val next = async { tools.executeTool("render_view", buildJsonObject { put("viewpoint", "front") }) }
            val second = awaitOfType<StageRenderRequestOutMessage>()
            val notPng = client.upload(second.requestId, second.token, cookie, "<html>".toByteArray())
            assertEquals("RENDER_NOT_PNG", notPng.errorCode())
            assertEquals("RENDER_FAILED", next.await().code())
        }
    }

    @Test
    fun `a failure report that will not parse still ends the render, with a reason`() = testApplication {
        withOneWindow { client, cookie ->
            val answer = async { tools.executeTool("render_view", buildJsonObject { put("viewpoint", "plan") }) }
            val request = awaitOfType<StageRenderRequestOutMessage>()
            val garbled = client.post("/api/rest/stage-renders/${request.requestId}/failure") {
                header(HttpHeaders.Cookie, cookie)
                header("X-Render-Token", request.token)
                contentType(ContentType.Application.Json)
                setBody("{not json")
            }
            assertEquals(HttpStatusCode.BadRequest, garbled.status)
            val result = answer.await()
            assertEquals("RENDER_FAILED", result.code(), "not a 30 s timeout")
            assertTrue("could not be read" in result.result, result.result)
        }
    }

    @Test
    fun `a window that does not answer in time is a named timeout, and its late upload is refused`() = testApplication {
        state.stageRender.answerTimeout = 200.milliseconds
        withOneWindow { client, cookie ->
            val answer = async { tools.executeTool("render_view", buildJsonObject { put("viewpoint", "orbit") }) }
            val request = awaitOfType<StageRenderRequestOutMessage>()
            assertEquals(200L, request.timeoutMs)
            val result = answer.await()
            assertEquals("RENDER_TIMEOUT", result.code())
            assertTrue("'Screen 1'" in result.result, result.result)
            assertEquals(HttpStatusCode.NotFound, client.upload(request.requestId, request.token, cookie, png()).status)
        }
    }

    @Test
    fun `a window on another project is not asked`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", UserRole.OPERATOR)
        val client = createWsClient()
        val cookie = client.loginCookieHeader("op")
        client.webSocket("/api", request = { header(HttpHeaders.Cookie, cookie) }) {
            // Eligible on this project first, so the refusal below is the project's doing.
            sendSerialized<InMessage>(WindowsAnnounceInMessage("tab-1", "Screen 1", "/projects/$projectId/stage"))
            awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }
            awaitEligible()
            sendSerialized<InMessage>(WindowsAnnounceInMessage("tab-1", "Screen 1", "/projects/${projectId + 1}/stage"))
            awaitOfType<WindowsStateOutMessage> { it.windows.singleOrNull()?.view?.startsWith("/projects/${projectId + 1}") == true }
            assertEquals("RENDER_NO_WINDOW", tools.executeTool("render_view", JsonObject(mapOf("viewpoint" to JsonPrimitive("plan")))).code())
        }
    }

    // ─── Never remote ───────────────────────────────────────────────────

    @Test
    fun `a socket on the public listener is never asked, and the upload route is not there remotely`() = testApplication {
        environment { config = testAppConfig() }
        application { publicModule(state) }
        seedUser(state, "op", UserRole.ADMIN)
        val client = createWsClient()
        val cookie = client.loginCookieHeader("op")
        client.webSocket("/api", request = { header(HttpHeaders.Cookie, cookie) }) {
            sendSerialized<InMessage>(WindowsAnnounceInMessage("tab-1", "Phone", "/projects/$projectId/busk"))
            awaitOfType<WindowsStateOutMessage> { it.windows.isNotEmpty() }
            assertTrue(state.stageRender.eligibleWindows(projectId).isEmpty(), "announced, signed in, and still not a render window")
            assertEquals("RENDER_NO_WINDOW", tools.executeTool("render_view", JsonObject(mapOf("viewpoint" to JsonPrimitive("plan")))).code())
        }
        val remote = client.upload(UUID.randomUUID().toString(), "any", cookie, png())
        assertEquals(HttpStatusCode.NotFound, remote.status)
        assertEquals("RENDER_REQUEST_UNKNOWN", remote.errorCode())
    }
}
