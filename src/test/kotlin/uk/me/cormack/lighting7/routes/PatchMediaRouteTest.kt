package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.fixture.media.FittedSlot
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A unit's fitted media (fixture optics plan session 3): the gel library on `GET /gels`, the loadable
 * settings on `GET /fixture-types`, `media` on a patch and on each placement, and the refusals every
 * write path shares — every problem at once.
 */
class PatchMediaRouteTest : RouteIntegrationTest() {

    private val rev = "etc-source4-revolution-base-frame"

    @Test
    fun `the gel library and the Revolution's loadable settings are on the wire`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val gels = client.get("/api/rest/gels").body<JsonArray>()
        assertEquals(40, gels.size)
        val r25 = gels.single { it.jsonObject["code"]!!.jsonPrimitive.content == "R25" }.jsonObject
        assertEquals("Orange Red", r25["name"]!!.jsonPrimitive.content)
        assertEquals("Rosco", r25["brand"]!!.jsonPrimitive.content)
        assertEquals("true", r25["estimate"]!!.jsonPrimitive.content)

        val types = client.get("/api/rest/fixture-types").body<List<FixtureTypeDetails>>()
        val settings = types.single { it.typeKey == rev }.properties.filterIsInstance<SettingPropertyDescriptor>().associateBy { it.name }
        assertEquals("GEL", settings["gelScroller"]?.media)
        assertEquals("GOBO_OR_GEL", settings["fbWheelPos"]?.media)
        assertEquals("GEL", settings["mediaFrame"]?.media)
        assertEquals(false, settings["fbWheelPos"]?.options?.first()?.loadable)
    }

    @Test
    fun `media is stored on create, kept when absent, cleared by null, and per placement`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val create = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0, fixtureTypeKey = rev, key = "rev-1", name = "Rev 1", startChannel = 1,
                    media = slots("gelScroller", "L201_FULL_CT_BLUE", """{"gel": "R26"}"""),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        val created = create.body<FixturePatchDto>()
        assertEquals(FittedSlot(gel = "R26"), created.media?.slot("gelScroller", "L201_FULL_CT_BLUE"))
        assertEquals(created.media, state.show.fixtures.fittedMediaFor("rev-1"), "the runtime metadata carries it")

        val moved = put(client, created.id, "stageX" to JsonPrimitive(1.0))
        assertEquals(created.media, moved.body<FixturePatchDto>().media, "a write without media keeps it")

        val withPlacement = put(
            client, created.id,
            "extraPlacements" to buildJsonArray {
                add(buildJsonObject {
                    put("label", JsonPrimitive("SL")); put("stageX", JsonPrimitive(-2.0))
                    put("media", slots("mediaFrame", "IN", """{"gel": "L202"}"""))
                })
            },
        )
        assertEquals(HttpStatusCode.OK, withPlacement.status, withPlacement.bodyAsText())
        val placement = withPlacement.body<FixturePatchDto>().extraPlacements.single()
        assertEquals(FittedSlot(gel = "L202"), placement.media?.slot("mediaFrame", "IN"))

        val cleared = put(client, created.id, "media" to JsonNull)
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        val dto = cleared.body<FixturePatchDto>()
        assertNull(dto.media, "null clears the patch's media")
        assertEquals(FittedSlot(gel = "L202"), dto.extraPlacements.single().media?.slot("mediaFrame", "IN"), "and leaves the placement's")
        assertNull(state.show.fixtures.fittedMediaFor("rev-1"), "and the runtime metadata follows")
    }

    @Test
    fun `every problem with a write's media is refused together, the patch's and each placement's`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "rev-1", rev, 1)
        val resp = put(
            client, id,
            "media" to Json.parseToJsonElement(
                """{"slots": {"gelScroller": {"L201_FULL_CT_BLUE": {"gel": "R999"}, "OPEN_LEADER": {"gobo": "breakup"}},
                   "fbWheelPos": {"OPEN": {"gel": "R26"}, "SLOT_9": {}}, "zoom": {"X": {}}}}""",
            ),
            "extraPlacements" to buildJsonArray {
                add(buildJsonObject { put("media", slots("mediaFrame", "OUT", """{"gel": "R26"}""")) })
            },
        )
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val message = resp.bodyAsText()
        for (expected in listOf(
            "media.slots.gelScroller.L201_FULL_CT_BLUE: unknown gel 'R999'",
            "media.slots.gelScroller.OPEN_LEADER: gelScroller's slots take gels, not gobos",
            "media.slots.fbWheelPos.OPEN: OPEN is not a slot",
            "media.slots.fbWheelPos.SLOT_9: fbWheelPos has no option SLOT_9",
            "media.slots.zoom: zoom is not loadable",
            "extraPlacements[0].media.slots.mediaFrame.OUT: OUT is not a slot",
        )) {
            assertTrue(message.contains(expected), "expected '$expected' in: $message")
        }
        assertNull(get(client, id).media, "a refused write writes nothing")

        val shape = put(client, id, "media" to JsonPrimitive("R26"))
        assertEquals(HttpStatusCode.BadRequest, shape.status)
        assertTrue(shape.bodyAsText().contains("media must be an object"))
    }

    @Test
    fun `a type with no loadable settings refuses media on every path`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val create = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0, fixtureTypeKey = "hex", key = "hex-1", name = "Hex 1", startChannel = 1,
                    media = slots("gelScroller", "L201_FULL_CT_BLUE", """{"gel": "R26"}"""),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, create.status)
        assertTrue(create.bodyAsText().contains("'hex' has no loadable settings"), create.bodyAsText())

        val id = createPatch(client, "hex-1", "hex", 1)
        val put = put(client, id, "media" to slots("dimmer", "X", "{}"))
        assertEquals(HttpStatusCode.BadRequest, put.status)
        val bulk = bulk(client, buildJsonObject { put("patchId", JsonPrimitive(id)); put("media", slots("dimmer", "X", "{}")) })
        assertEquals(HttpStatusCode.BadRequest, bulk.status)
        assertTrue(bulk.bodyAsText().contains("'hex' has no loadable settings"), bulk.bodyAsText())
        assertEquals(HttpStatusCode.OK, put(client, id, "media" to JsonNull).status, "clearing nothing is never refused")
    }

    @Test
    fun `the bulk route writes media without rebuilding the registry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "rev-1", rev, 1)
        val before = state.show.fixtures.untypedFixture("rev-1")
        val resp = bulk(
            client,
            buildJsonObject {
                put("patchId", JsonPrimitive(id))
                put("media", slots("fbWheelPos", "SLOT_1", """{"gobo": "breakup"}"""))
            },
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val updated = resp.body<BulkPlacementResponse>().updated.single()
        assertEquals(FittedSlot(gobo = "breakup"), updated.media?.slot("fbWheelPos", "SLOT_1"))
        assertEquals(updated.media, state.show.fixtures.fittedMediaFor("rev-1"))
        assertSame(before, state.show.fixtures.untypedFixture("rev-1"), "media is drawn and snapped to, never built from")

        put(client, id, "media" to slots("gelScroller", "R25_ORANGE_RED", """{"gel": "R26"}"""))
        assertSame(before, state.show.fixtures.untypedFixture("rev-1"), "and the single PUT skips the rebuild for it too")
        assertEquals(FittedSlot(gel = "R26"), state.show.fixtures.fittedMediaFor("rev-1")?.slot("gelScroller", "R25_ORANGE_RED"))
    }

    @Test
    fun `a template snaps the scroller to the frame this unit holds the colour in`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "rev-1", rev, 1)
        // R26 Light Red, which the stock string does not hold: stock snaps elsewhere, fitted to frame 9.
        val request = TemplateResolveRequest(
            rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", "#ee5b5e")),
            targets = listOf(TemplateTargetDto("fixture", "rev-1")),
        )
        suspend fun resolve(): TemplateResolutionDto = client.post("/api/rest/projects/$projectId/templates/resolve") {
            contentType(ContentType.Application.Json); setBody(request)
        }.body<TemplateResolveResponse>().entries.single()

        val stock = resolve()
        assertEquals("gelScroller", stock.resolvedPropertyName)
        assertTrue(stock.value != "165", "the stock string holds no R26: $stock")

        put(client, id, "media" to slots("gelScroller", "L201_FULL_CT_BLUE", """{"gel": "R26"}"""))
        val fitted = resolve()
        assertEquals("165", fitted.value, "frame 9 holds R26 on this unit: $fitted")
        assertEquals(0.0, fitted.deltaE)
        assertEquals("L201_FULL_CT_BLUE (R26)", fitted.detail, "the snap names the fitted gel")
    }

    // — helpers ————————————————————————————————————————————————————————

    private fun slots(property: String, option: String, content: String): JsonElement =
        Json.parseToJsonElement("""{"slots": {"$property": {"$option": $content}}}""")

    private suspend fun put(client: HttpClient, patchId: Int, vararg fields: Pair<String, JsonElement>): HttpResponse =
        client.put("/api/rest/projects/$projectId/patches/$patchId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { for ((k, v) in fields) put(k, v) })
        }

    private suspend fun bulk(client: HttpClient, vararg updates: JsonObject): HttpResponse =
        client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(BulkPlacementRequest(updates = updates.toList()))
        }

    private suspend fun get(client: HttpClient, patchId: Int): FixturePatchDto =
        client.get("/api/rest/projects/$projectId/patches/$patchId").body()

    private suspend fun createPatch(client: HttpClient, key: String, typeKey: String, startChannel: Int): Int {
        val resp = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = typeKey, key = key, name = key, startChannel = startChannel))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<FixturePatchDto>().id
    }
}
