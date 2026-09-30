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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.fixture.lantern.ShutterBlade
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A generic dimmer's lantern and its focus (stage-view plan session 7): the library on
 * `GET /lanterns`, the seven fields on a patch and on each placement, the kind derived from the
 * lantern, and the refusals every write path shares.
 */
class PatchLanternRouteTest : RouteIntegrationTest() {

    @Test
    fun `the library and the types that take a lantern are on the wire`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val lanterns = client.get("/api/rest/lanterns").body<JsonArray>()
        assertTrue(lanterns.size in 20..32, "about 25 lanterns, got ${lanterns.size}")
        val s4 = lanterns.single { it.jsonObject["id"]!!.jsonPrimitive.content == "s4-19" }.jsonObject
        assertEquals("profile", s4["archetype"]!!.jsonPrimitive.content)
        assertEquals("PROFILE", s4["family"]!!.jsonPrimitive.content)
        assertEquals(listOf("PROFILE"), s4["defaultFor"]!!.jsonArray.map { it.jsonPrimitive.content })
        val cp62 = lanterns.single { it.jsonObject["id"]!!.jsonPrimitive.content == "par64-cp62" }.jsonObject
        assertEquals(21.0, cp62["oval"]!!.jsonObject["narrowDeg"]!!.jsonPrimitive.content.toDouble())

        val types = client.get("/api/rest/fixture-types").body<List<FixtureTypeDetails>>()
        assertTrue(types.single { it.typeKey == "generic-dimmer" }.acceptsLantern)
        assertTrue(types.filter { it.acceptsLantern }.size == 1, "only a conventional dimmer is hung with a lantern")
        val revolution = types.first { it.typeKey.startsWith("etc-source4-revolution") }
        assertEquals("MOVER", revolution.body?.archetype?.name, "a Revolution declares a mover's body")
        assertNull(types.single { it.typeKey == "hex" }.body, "an undeclared body is left to the kind")
    }

    @Test
    fun `a lantern and its focus are stored on create, on PUT and per placement`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val create = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0, fixtureTypeKey = "generic-dimmer", key = "adv2", name = "ADV2", startChannel = 1,
                    lanternType = "s4-zoom-25-50", zoomDeg = 30.0,
                    shutters = listOf(ShutterBlade(0.28, 0.0), ShutterBlade(), ShutterBlade(0.1, -12.0), ShutterBlade()),
                    gateRotationDeg = 8.0, iris = 0.75, focusSoftness = 0.3,
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        val created = create.body<FixturePatchDto>()
        assertEquals("s4-zoom-25-50", created.lanternType)
        assertEquals("PROFILE", created.kindOverride, "the kind is derived from the lantern")
        assertEquals(30.0, created.zoomDeg)
        assertEquals(0.28, created.shutters!![0].depth)
        assertEquals(-12.0, created.shutters[2].angleDeg)
        assertEquals(0.75, created.iris)

        val resp = put(
            client, created.id,
            "lanternType" to JsonPrimitive("par64-cp62"),
            "lampRotationDeg" to JsonPrimitive(90.0),
            "extraPlacements" to buildJsonArray {
                add(buildJsonObject {
                    put("label", JsonPrimitive("SR")); put("stageX", JsonPrimitive(2.0))
                    put("lanternType", JsonPrimitive("cantata-f")); put("zoomDeg", JsonPrimitive(20.0))
                    put("shutters", blades(0.2 to 5.0, 0.0 to 0.0, 0.0 to 0.0, 0.3 to 0.0))
                })
                add(buildJsonObject { put("label", JsonPrimitive("SL")); put("stageX", JsonPrimitive(-2.0)); put("lampRotationDeg", JsonPrimitive(-30.0)) })
            },
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val dto = resp.body<FixturePatchDto>()
        assertEquals("par64-cp62", dto.lanternType)
        assertEquals("PAR", dto.kindOverride, "a new lantern re-derives the kind")
        assertNull(dto.zoomDeg, "a zoom the new lantern cannot take is cleared, when the write sent none")
        assertEquals(90.0, dto.lampRotationDeg)
        assertEquals(0.28, dto.shutters!![0].depth, "a key the write did not carry is unchanged")
        val (sr, sl) = dto.extraPlacements
        assertEquals("cantata-f", sr.lanternType)
        assertEquals(20.0, sr.zoomDeg)
        assertEquals(0.3, sr.shutters!![3].depth)
        assertNull(sl.lanternType, "a placement that names none takes the patch's lantern")
        assertEquals(-30.0, sl.lampRotationDeg)

        val listed = client.get("/api/rest/projects/$projectId/patches").body<List<FixturePatchDto>>().single { it.id == dto.id }
        assertEquals(dto, listed, "the list read agrees with the PUT's answer")

        // Clearing the lantern clears the kind it derived — the type's own kind shows through again —
        // and the focus is cleared field by field.
        val cleared = put(client, dto.id, "lanternType" to JsonNull, "shutters" to JsonNull)
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        val after = cleared.body<FixturePatchDto>()
        assertNull(after.lanternType)
        assertNull(after.shutters)
        assertNull(after.kindOverride, "the derived kind goes with its lantern")

        // With no lantern named the kind is the operator's again: a hazer on a dimmer is an effect.
        val hazer = put(client, dto.id, "kindOverride" to JsonPrimitive("EFFECT"))
        assertEquals(HttpStatusCode.OK, hazer.status, hazer.bodyAsText())
        assertEquals("EFFECT", hazer.body<FixturePatchDto>().kindOverride)
        // And clearing a lantern that was never there leaves a kind the operator set alone.
        assertEquals("EFFECT", put(client, dto.id, "lanternType" to JsonNull, "iris" to JsonNull).body<FixturePatchDto>().kindOverride)
    }

    @Test
    fun `a lantern change clears the zoom of the placements that inherit it, on every write path`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "pair", "generic-dimmer", 40)
        val set = put(
            client, id,
            "lanternType" to JsonPrimitive("s4-zoom-15-30"),
            "extraPlacements" to buildJsonArray {
                add(buildJsonObject { put("label", JsonPrimitive("SR")); put("zoomDeg", JsonPrimitive(20.0)) })
                add(buildJsonObject { put("label", JsonPrimitive("SL")); put("lanternType", JsonPrimitive("s4-zoom-25-50")); put("zoomDeg", JsonPrimitive(40.0)) })
            },
        )
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())

        // The patch goes to a fixed 19°, sending no placements: the SR placement inherits it and its
        // zoom goes; the SL placement names its own zoom lantern and keeps its zoom.
        val fixed = put(client, id, "lanternType" to JsonPrimitive("s4-19"))
        assertEquals(HttpStatusCode.OK, fixed.status, fixed.bodyAsText())
        val (sr, sl) = fixed.body<FixturePatchDto>().extraPlacements
        assertNull(sr.zoomDeg, "an inheriting placement's zoom the new lantern cannot take is cleared")
        assertEquals(40.0, sl.zoomDeg, "a placement with a lantern of its own is untouched")

        // So the next write that re-sends the placements whole — the patch editor's Save — lands.
        val resend = put(
            client, id,
            "extraPlacements" to buildJsonArray {
                add(buildJsonObject { put("uuid", JsonPrimitive(sr.uuid)); put("label", JsonPrimitive("SR")) })
                add(buildJsonObject { put("uuid", JsonPrimitive(sl.uuid)); put("label", JsonPrimitive("SL")); put("lanternType", JsonPrimitive("s4-zoom-25-50")); put("zoomDeg", JsonPrimitive(40.0)) })
            },
        )
        assertEquals(HttpStatusCode.OK, resend.status, resend.bodyAsText())

        // The bulk route does the same, and keeps a zoom the new lantern can still take.
        put(client, id, "lanternType" to JsonPrimitive("s4-zoom-15-30"), "extraPlacements" to buildJsonArray {
            add(buildJsonObject { put("label", JsonPrimitive("SR")); put("zoomDeg", JsonPrimitive(15.0)) })
            add(buildJsonObject { put("label", JsonPrimitive("SL")); put("zoomDeg", JsonPrimitive(20.0)) })
        })
        val viaBulk = bulk(client, buildJsonObject { put("patchId", JsonPrimitive(id)); put("lanternType", JsonPrimitive("prelude-16-30")) })
        assertEquals(HttpStatusCode.OK, viaBulk.status, viaBulk.bodyAsText())
        val (short, inRange) = get(client, id).extraPlacements
        assertNull(short.zoomDeg, "15° is outside a Prelude's 16–30°")
        assertEquals(20.0, inRange.zoomDeg, "20° still fits, so it is kept")
    }

    @Test
    fun `every write path refuses what the type and the library do not take, and writes nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val dimmer = createPatch(client, "dimmer", "generic-dimmer", 1)
        val hex = createPatch(client, "hex", "hex", 20)

        val refusals = listOf(
            listOf("lanternType" to JsonPrimitive("no-such-lantern")) to "unknown lanternType",
            listOf("lanternType" to JsonPrimitive("s4-19"), "zoomDeg" to JsonPrimitive(25.0)) to "fixed",
            listOf("lanternType" to JsonPrimitive("s4-zoom-25-50"), "zoomDeg" to JsonPrimitive(60.0)) to "outside",
            listOf("lanternType" to JsonPrimitive("s4-19"), "kindOverride" to JsonPrimitive("FRESNEL")) to "derived from the lantern",
            listOf("shutters" to blades(0.2 to 0.0)) to "four blades",
            listOf("shutters" to blades(1.2 to 0.0, 0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0)) to "depth",
            listOf("shutters" to blades(0.2 to 45.0, 0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0)) to "angleDeg",
            listOf("iris" to JsonPrimitive(1.5)) to "iris",
            listOf("focusSoftness" to JsonPrimitive(-0.1)) to "focusSoftness",
            listOf("zoomDeg" to JsonPrimitive("wide")) to "must be a number",
        )
        for ((fields, expected) in refusals) {
            val resp = put(client, dimmer, "displayName" to JsonPrimitive("Renamed"), *fields.toTypedArray())
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$fields: ${resp.bodyAsText()}")
            assertTrue(resp.bodyAsText().contains(expected), "$fields: ${resp.bodyAsText()}")
        }
        val onHex = put(client, hex, "lanternType" to JsonPrimitive("s4-19"))
        assertEquals(HttpStatusCode.BadRequest, onHex.status, onHex.bodyAsText())
        assertTrue(onHex.bodyAsText().contains("carries its own body"), onHex.bodyAsText())
        val placementOnHex = put(
            client, hex,
            "extraPlacements" to buildJsonArray { add(buildJsonObject { put("iris", JsonPrimitive(0.5)) }) },
        )
        assertEquals(HttpStatusCode.BadRequest, placementOnHex.status, placementOnHex.bodyAsText())
        val placementZoom = put(
            client, dimmer,
            "lanternType" to JsonPrimitive("s4-19"),
            "extraPlacements" to buildJsonArray { add(buildJsonObject { put("zoomDeg", JsonPrimitive(30.0)) }) },
        )
        assertEquals(HttpStatusCode.BadRequest, placementZoom.status, "a placement inherits the patch's fixed lantern")

        val after = get(client, dimmer)
        assertEquals("dimmer", after.displayName, "a refused PUT commits none of its fields")
        assertNull(after.lanternType)
        assertTrue(after.extraPlacements.isEmpty())

        val bulk = bulk(client, buildJsonObject { put("patchId", JsonPrimitive(hex)); put("iris", JsonPrimitive(0.5)) })
        assertEquals(HttpStatusCode.BadRequest, bulk.status, bulk.bodyAsText())
        assertNull(get(client, hex).iris)
    }

    @Test
    fun `the bulk route writes a lantern and its focus without rebuilding the registry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "front", "generic-dimmer", 1)
        val before = state.show.fixtures.untypedFixture("front")

        val resp = bulk(
            client,
            buildJsonObject {
                put("patchId", JsonPrimitive(id))
                put("lanternType", JsonPrimitive("rama-175"))
                put("zoomDeg", JsonPrimitive(12.0))
                put("shutters", blades(0.1 to 0.0, 0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0))
                put("extraPlacements", buildJsonArray { add(buildJsonObject { put("lanternType", JsonPrimitive("s4-26")); put("iris", JsonPrimitive(0.4)) }) })
            },
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val updated = resp.body<BulkPlacementResponse>().updated.single()
        assertEquals("rama-175", updated.lanternType)
        assertEquals("FRESNEL", updated.kindOverride)
        assertEquals(12.0, updated.zoomDeg)
        assertEquals(0.4, updated.extraPlacements.single().iris)
        assertSame(before, state.show.fixtures.untypedFixture("front"), "a lantern is drawn, never built from")

        put(client, id, "focusSoftness" to JsonPrimitive(0.6))
        assertSame(before, state.show.fixtures.untypedFixture("front"), "and the single PUT skips the rebuild for it too")
    }

    // — helpers ————————————————————————————————————————————————————————

    private fun blades(vararg b: Pair<Double, Double>): JsonArray = buildJsonArray {
        b.forEach { (depth, angle) -> add(buildJsonObject { put("depth", JsonPrimitive(depth)); put("angleDeg", JsonPrimitive(angle)) }) }
    }

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
