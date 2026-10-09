package uk.me.cormack.lighting7.mcp

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.MAX_SCENE_IMAGE_TOOL_BYTES
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.SceneImageInfo
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.sceneJpeg
import uk.me.cormack.lighting7.testsupport.scenePng
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `upload_scene_image` (scrim plan D11) against the REST upload it mirrors, and `set_scene` /
 * `get_scene` carrying a cloth's fabric and paint.
 */
class McpSceneImageToolTest : RouteIntegrationTest() {

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun ToolExecutionResult.problems(): List<String> =
        json()["problems"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun upload(bytes: ByteArray, mediaType: String) =
        call("upload_scene_image", """{"mediaType":"$mediaType","base64":"${Base64.getEncoder().encodeToString(bytes)}"}""")

    @Test
    fun `the tool answers what the REST upload answers for the same bytes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val png = scenePng(120, 60, alpha = true)
        val viaTool = upload(png, "image/png")
        assertTrue(viaTool.success, viaTool.result)
        val viaRest = client.post("/api/rest/projects/$projectId/scene-images") {
            contentType(ContentType.Image.PNG)
            setBody(png)
        }.body<SceneImageInfo>()
        val tool = viaTool.json()
        assertEquals(viaRest.hash, tool["hash"]!!.jsonPrimitive.content)
        assertEquals(viaRest.width, tool["width"]!!.jsonPrimitive.int)
        assertEquals(viaRest.height, tool["height"]!!.jsonPrimitive.int)
        assertEquals(viaRest.hasAlpha, tool["hasAlpha"]!!.jsonPrimitive.boolean)
        assertEquals(viaRest.mediaType, tool["mediaType"]!!.jsonPrimitive.content)
        assertTrue("holes" in tool["note"]!!.jsonPrimitive.content)

        // A pasted data URL is the same image.
        val dataUrl = call(
            "upload_scene_image",
            """{"mediaType":"image/png","base64":"data:image/png;base64,${Base64.getEncoder().encodeToString(png)}"}""",
        )
        assertEquals(viaRest.hash, dataUrl.json()["hash"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the tool refuses what the route refuses, every problem at once, and an oversized body before decoding`() {
        val none = call("upload_scene_image", """{"colour":"red"}""")
        assertFalse(none.success)
        val problems = none.problems()
        assertTrue(problems.any { "mediaType is required" in it }, problems.toString())
        assertTrue(problems.any { "base64 is required" in it }, problems.toString())
        assertTrue(problems.any { "unknown field 'colour'" in it || "colour" in it }, problems.toString())

        val gif = upload("GIF89a.....".toByteArray(), "image/png")
        assertFalse(gif.success)
        assertTrue(gif.problems().single().contains("neither a PNG nor a JPEG"), gif.result)

        val webp = upload(scenePng(4, 4), "image/webp")
        assertFalse(webp.success)
        assertTrue(webp.problems().single().contains("PNG or a JPEG"), webp.result)

        val garbled = call("upload_scene_image", """{"mediaType":"image/png","base64":"%%%not base64%%%"}""")
        assertFalse(garbled.success)
        assertTrue(garbled.problems().single().startsWith("base64 does not decode"), garbled.result)

        // 16 MB decoded is the tool's cap; the length says so before anything is decoded.
        val tooLong = "A".repeat((MAX_SCENE_IMAGE_TOOL_BYTES / 3 + 2) * 4)
        val big = call("upload_scene_image", """{"mediaType":"image/png","base64":"$tooLong"}""")
        assertFalse(big.success)
        assertTrue(big.problems().single().contains("over 16 MB decoded"), big.result)
    }

    @Test
    fun `set_scene paints a cloth with an uploaded image, and get_scene reads it back with the image's size`() {
        val front = upload(scenePng(200, 100, alpha = true), "image/png").json()["hash"]!!.jsonPrimitive.content
        val back = upload(sceneJpeg(160, 90), "image/jpeg").json()["hash"]!!.jsonPrimitive.content
        val unknown = "9".repeat(64)

        val refused = call(
            "set_scene",
            """{"elements":[{"name":"Gauze","kind":"DRAPE","x":0,"y":3,"z":0,"widthM":10,"depthM":0.05,"heightM":5,
                "params":{"role":"BACKCLOTH","operation":"FLY","fabric":"tulle","paint":{"front":"$unknown"}}}]}""",
        )
        assertFalse(refused.success)
        assertTrue(refused.problems().any { "elements[0] ('Gauze').params.paint.front names no stored image" in it }, refused.result)
        assertTrue(refused.problems().any { "elements[0] ('Gauze').params.fabric must be one of" in it }, refused.result)

        val written = call(
            "set_scene",
            """{"elements":[{"name":"Gauze","kind":"DRAPE","x":0,"y":3,"z":0,"widthM":10,"depthM":0.05,"heightM":5,
                "params":{"role":"BACKCLOTH","operation":"FLY","fabric":"sharkstooth","paint":{"front":"$front","back":"$back"}}}]}""",
        )
        assertTrue(written.success, written.result)

        val row = call("get_scene", "{}").json()["elements"]!!.jsonArray.single().jsonObject
        val params = row["params"]!!.jsonObject
        assertEquals("SHARKSTOOTH", params["fabric"]!!.jsonPrimitive.content)
        assertEquals(front, params["paint"]!!.jsonObject["front"]!!.jsonPrimitive.content)
        val images = row["paintImages"]!!.jsonObject
        assertEquals(200, images["front"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        assertTrue(images["front"]!!.jsonObject["hasAlpha"]!!.jsonPrimitive.boolean)
        assertEquals(90, images["back"]!!.jsonObject["height"]!!.jsonPrimitive.int)

        // A row read back is sent back as it stands: paintImages is read-only, not refused.
        val resent = call("set_scene", """{"elements":[$row]}""")
        assertTrue(resent.success, resent.result)
    }

    @Test
    fun `the MCP server lists upload_scene_image, not read-only`() {
        val protocol = McpProtocol(state)
        val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")
        val listed = runBlocking {
            protocol.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val tool = listed.getValue("upload_scene_image").jsonObject
        assertTrue(tool["annotations"]?.jsonObject?.get("readOnlyHint")?.jsonPrimitive?.boolean != true)
        assertEquals(
            listOf("base64", "mediaType"),
            tool["inputSchema"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(),
        )
    }
}
