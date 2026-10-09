package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.SceneImageInfo
import uk.me.cormack.lighting7.state.SceneImageStore
import uk.me.cormack.lighting7.sync.Overrides
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.pngHeaderOnly
import uk.me.cormack.lighting7.testsupport.sceneJpeg
import uk.me.cormack.lighting7.testsupport.scenePng
import uk.me.cormack.lighting7.testsupport.seedUser
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `scene-images` and the *Full detail* switch over REST (scrim plan §3.4), and the element write's
 * paint check: a hash must name an image this project's store holds.
 */
class SceneImageRoutesTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private suspend fun HttpClient.upload(bytes: ByteArray, type: String, cookie: String? = null): HttpResponse =
        post("$base/scene-images") {
            contentType(ContentType.parse(type))
            cookie?.let { header(HttpHeaders.Cookie, it) }
            setBody(bytes)
        }

    private suspend fun HttpClient.element(json: String): HttpResponse = post("$base/stage-elements") {
        contentType(ContentType.Application.Json)
        setBody(Json.parseToJsonElement(json).jsonObject)
    }

    @Test
    fun `an upload answers what it stored, idempotently, and serves it back immutable`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val bytes = scenePng(400, 200, alpha = true)

        val first = client.upload(bytes, "image/png")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val info = first.body<SceneImageInfo>()
        assertEquals(400 to 200, info.width to info.height)
        assertTrue(info.hasAlpha)
        assertEquals("image/png", info.mediaType)
        assertEquals(info, client.upload(bytes, "image/png").body<SceneImageInfo>(), "the same bytes answer the same image")
        assertEquals(listOf(info), client.get("$base/scene-images").body<List<SceneImageInfo>>())

        val original = client.get("$base/scene-images/${info.hash}")
        assertEquals(HttpStatusCode.OK, original.status)
        assertContentEquals(bytes, original.readRawBytes())
        assertEquals("public, max-age=31536000, immutable", original.headers[HttpHeaders.CacheControl])
        assertTrue(original.headers[HttpHeaders.ContentType]!!.startsWith("image/png"))

        for (variant in listOf("display", "detail", "mask")) {
            val copy = client.get("$base/scene-images/${info.hash}?variant=$variant")
            assertEquals(HttpStatusCode.OK, copy.status, variant)
            assertEquals("public, max-age=31536000, immutable", copy.headers[HttpHeaders.CacheControl])
            val image = ImageIO.read(copy.readRawBytes().inputStream())
            if (variant == "mask") assertEquals(256 to 128, image.width to image.height) else assertEquals(400 to 200, image.width to image.height)
        }
    }

    @Test
    fun `refusals carry SCENE_IMAGE_INVALID, an oversized body is 413, an unknown hash 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        suspend fun HttpResponse.code() = runCatching { body<ErrorResponse>().code }.getOrNull()
        val gif = client.upload("GIF89a.....".toByteArray(), "image/gif")
        assertEquals(HttpStatusCode.BadRequest, gif.status)
        assertEquals(SceneImageStore.CODE_INVALID, gif.code())

        val lying = client.upload(scenePng(10, 10), "image/jpeg")
        assertEquals(HttpStatusCode.BadRequest, lying.status)
        assertTrue("not the image/jpeg" in lying.bodyAsText(), lying.bodyAsText())

        val huge = client.upload(pngHeaderOnly(20_000, 20_000), "image/png")
        assertEquals(HttpStatusCode.BadRequest, huge.status)
        assertTrue("8192 px on a side" in huge.bodyAsText(), huge.bodyAsText())

        val tooBig = client.upload(ByteArray(SceneImageStore.MAX_UPLOAD_BYTES + 1), "image/png")
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
        assertEquals(SceneImageStore.CODE_INVALID, tooBig.code())

        val unknown = client.get("$base/scene-images/${"e".repeat(64)}")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(SceneImageStore.CODE_UNKNOWN, unknown.code())
        assertEquals(HttpStatusCode.NotFound, client.get("$base/scene-images/not-a-hash").status)
        val stored = client.upload(sceneJpeg(16, 16), "image/jpeg").body<SceneImageInfo>()
        assertEquals(HttpStatusCode.BadRequest, client.get("$base/scene-images/${stored.hash}?variant=huge").status)

        assertEquals(listOf(stored), client.get("$base/scene-images").body<List<SceneImageInfo>>(), "nothing refused was stored")
    }

    @Test
    fun `a stored original no copy can be made of answers 422, not a server error`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        // Hand-placed (or older than the store's checks): a PNG signature over bytes that do not decode.
        val bytes = pngHeaderOnly(16, 16)
        val hash = uk.me.cormack.lighting7.sync.RecordHasher.sha256Hex(bytes)
        val path = state.sceneImages.path(projectUuid(), hash, uk.me.cormack.lighting7.state.SceneImageFormat.PNG)
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.write(path, bytes)

        val copy = client.get("$base/scene-images/$hash?variant=display")
        assertEquals(HttpStatusCode.UnprocessableEntity, copy.status, copy.bodyAsText())
        assertEquals(SceneImageStore.CODE_INVALID, copy.body<ErrorResponse>().code)
        // The original itself is still served as the bytes it is.
        assertEquals(HttpStatusCode.OK, client.get("$base/scene-images/$hash").status)
    }

    @Test
    fun `the routes answer only a signed-in desk account once one exists, either role`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val bytes = scenePng(8, 8)
        assertEquals(HttpStatusCode.Unauthorized, client.upload(bytes, "image/png").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$base/scene-images").status)

        val cookie = client.loginCookieHeader("op")
        val ok = client.upload(bytes, "image/png", cookie)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val hash = ok.body<SceneImageInfo>().hash
        assertEquals(HttpStatusCode.Unauthorized, client.get("$base/scene-images/$hash").status)
        assertEquals(HttpStatusCode.OK, client.get("$base/scene-images/$hash") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `an element paints only with a stored image, and an empty paint is written as absent`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val front = client.upload(scenePng(200, 100, alpha = true), "image/png").body<SceneImageInfo>().hash
        val back = client.upload(sceneJpeg(200, 100), "image/jpeg").body<SceneImageInfo>().hash
        val unknown = "f".repeat(64)

        val refused = client.element(
            """{"name":"Cloth","kind":"drape","widthM":12,"depthM":0.05,"heightM":6,
               "params":{"role":"BACKCLOTH","fabric":"lace","paint":{"front":"$unknown","back":"XYZ"}}}""",
        )
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val text = refused.bodyAsText()
        assertTrue("params.fabric must be one of CANVAS, MUSLIN, SHARKSTOOTH, BOBBINET" in text, text)
        assertTrue("params.paint.front names no stored image" in text, text)
        assertTrue("params.paint.back must be an image's SHA-256" in text, text)

        val created = client.element(
            """{"name":"Cloth","kind":"drape","widthM":12,"depthM":0.05,"heightM":6,
               "params":{"role":"BACKCLOTH","fabric":"muslin","paint":{"front":"${front.uppercase()}","back":"$back"}}}""",
        )
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = created.body<StageElementDto>()
        assertEquals(
            Json.parseToJsonElement("""{"fabric":"MUSLIN","paint":{"back":"$back","front":"$front"},"role":"BACKCLOTH"}"""),
            dto.params,
        )
        assertFalse(dto.fullDetail)

        val cleared = client.put("$base/stage-elements/${dto.id}") {
            contentType(ContentType.Application.Json)
            setBody(Json.parseToJsonElement("""{"params":{"role":"BACKCLOTH","paint":{}}}""").jsonObject)
        }
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertEquals(Json.parseToJsonElement("""{"role":"BACKCLOTH"}"""), cleared.body<StageElementDto>().params)

        val flat = client.element(
            """{"name":"Flat","kind":"flat","widthM":2.4,"depthM":0.1,"heightM":2.4,"params":{"paint":{"back":"$back"}}}""",
        )
        assertEquals(HttpStatusCode.Created, flat.status, flat.bodyAsText())
        val sofa = client.element(
            """{"name":"Sofa","kind":"object","widthM":2,"depthM":1,"heightM":1,"params":{"paint":{"front":"$front"},"fabric":"canvas"}}""",
        )
        assertEquals(HttpStatusCode.BadRequest, sofa.status)
        assertTrue("params.paint is a drape's or a flat's" in sofa.bodyAsText() && "params.fabric is a drape's" in sofa.bodyAsText(), sofa.bodyAsText())
    }

    @Test
    fun `a stored hash missing on this machine does not refuse an edit that leaves it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val hash = client.upload(scenePng(20, 10), "image/png").body<SceneImageInfo>().hash
        val dto = client.element(
            """{"name":"Cloth","kind":"drape","widthM":2,"depthM":0.05,"heightM":1,"params":{"role":"BACKCLOTH","paint":{"front":"$hash"}}}""",
        ).body<StageElementDto>()
        // A partial import: the element came, its image did not.
        state.sceneImages.deleteProject(projectUuid())

        val renamed = client.put("$base/stage-elements/${dto.id}") {
            contentType(ContentType.Application.Json)
            setBody(Json.parseToJsonElement("""{"name":"Forest"}""").jsonObject)
        }
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        val repainted = client.put("$base/stage-elements/${dto.id}") {
            contentType(ContentType.Application.Json)
            setBody(Json.parseToJsonElement("""{"params":{"role":"BACKCLOTH","paint":{"front":"$hash","back":"$hash"}}}""").jsonObject)
        }
        assertEquals(HttpStatusCode.OK, repainted.status, "the hash the element already holds is let stand on either side")
    }

    @Test
    fun `Full detail is a machine-local override, answered on the element and gone with it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val dto = client.element(
            """{"name":"Cloth","kind":"drape","widthM":2,"depthM":0.05,"heightM":1,"params":{"role":"BACKCLOTH"}}""",
        ).body<StageElementDto>()

        suspend fun detail(full: Boolean) = client.put("$base/stage-elements/${dto.id}/display-detail") {
            contentType(ContentType.Application.Json)
            setBody(Json.parseToJsonElement("""{"full":$full}""").jsonObject)
        }
        val on = detail(true)
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        assertTrue(on.body<StageElementDto>().fullDetail)
        assertTrue(client.get("$base/stage-elements").body<List<StageElementDto>>().single().fullDetail)
        assertTrue(client.get("$base/stage-elements/${dto.id}").body<StageElementDto>().fullDetail)
        val uuid = UUID.fromString(dto.uuid)
        transaction(state.database) {
            assertEquals("4096", Overrides.getString(projectId, "stage_elements", uuid, "displayDetail"))
        }
        assertFalse(detail(false).body<StageElementDto>().fullDetail)
        transaction(state.database) { assertNull(Overrides.getString(projectId, "stage_elements", uuid, "displayDetail")) }

        detail(true)
        assertEquals(HttpStatusCode.NoContent, client.delete("$base/stage-elements/${dto.id}").status)
        transaction(state.database) { assertNull(Overrides.getString(projectId, "stage_elements", uuid, "displayDetail")) }
        assertEquals(HttpStatusCode.NotFound, detail(true).status)
    }

    private fun projectUuid(): String = transaction(state.database) { DaoProject.findById(projectId)!!.uuid.toString() }
}
