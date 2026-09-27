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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A variable-length fixture's installed length (`lengthM`), on the patch and on each of its extra
 * placements. Only a type that declares `acceptsLength` (a lightstrip) may hold one; every write
 * path refuses it for a fixed-length type, and a refusal writes nothing.
 */
class PatchLengthRouteTest : RouteIntegrationTest() {

    @Test
    fun `fixture types say which take a length`() = testApplication {
        mountTestApp(state)
        val types = jsonClient().get("/api/rest/fixture-types").body<List<FixtureTypeDetails>>()
        val strip = types.single { it.typeKey == "lightstrip" }
        assertTrue(strip.acceptsLength)
        assertEquals(1.0, strip.lengthM, "the default drawn until a patch sets its own")
        assertFalse(types.single { it.typeKey == "led-lightbar-12-pixel-48ch" }.acceptsLength, "a pixel bar is the bar it is")
        assertFalse(types.single { it.typeKey == "generic-dimmer" }.acceptsLength)
    }

    @Test
    fun `a lightstrip takes a length on create, on PUT and per placement`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "ring", "lightstrip", 1, lengthM = 7.5)
        assertEquals(7.5, get(client, id).lengthM)

        val resp = put(
            client, id,
            "lengthM" to JsonPrimitive(9.0),
            "extraPlacements" to buildJsonArray {
                add(buildJsonObject { put("label", JsonPrimitive("US")); put("stageY", JsonPrimitive(6.0)); put("lengthM", JsonPrimitive(12.0)) })
                add(buildJsonObject { put("label", JsonPrimitive("SL")); put("stageX", JsonPrimitive(-6.0)) })
            },
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val dto = resp.body<FixturePatchDto>()
        assertEquals(9.0, dto.lengthM)
        assertEquals(listOf(12.0, null), dto.extraPlacements.map { it.lengthM }, "a segment without one takes the patch's")

        val listed = client.get("/api/rest/projects/$projectId/patches").body<List<FixturePatchDto>>().single { it.id == id }
        assertEquals(dto, listed, "the list read agrees with the PUT's answer")

        // Absent leaves it alone; null clears it back to the type's default.
        put(client, id, "displayName" to JsonPrimitive("Ring"))
        assertEquals(9.0, get(client, id).lengthM)
        val cleared = put(client, id, "lengthM" to JsonNull)
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertNull(cleared.body<FixturePatchDto>().lengthM)
    }

    @Test
    fun `a fixed-length type refuses a length on every path and writes nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val create = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0, fixtureTypeKey = "led-lightbar-12-pixel-48ch", key = "bar", name = "bar",
                    startChannel = 100, lengthM = 3.0,
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, create.status, create.bodyAsText())
        assertTrue(create.bodyAsText().contains("fixed length"), create.bodyAsText())

        val id = createPatch(client, "dimmer", "generic-dimmer", 1)

        val own = put(client, id, "displayName" to JsonPrimitive("Renamed"), "lengthM" to JsonPrimitive(2.0))
        assertEquals(HttpStatusCode.BadRequest, own.status, own.bodyAsText())
        val segment = put(
            client, id,
            "displayName" to JsonPrimitive("Renamed"),
            "extraPlacements" to buildJsonArray { add(buildJsonObject { put("stageX", JsonPrimitive(1.0)); put("lengthM", JsonPrimitive(2.0)) }) },
        )
        assertEquals(HttpStatusCode.BadRequest, segment.status, segment.bodyAsText())
        val after = get(client, id)
        assertEquals("dimmer", after.displayName, "a refused PUT commits none of its fields")
        assertNull(after.lengthM)
        assertTrue(after.extraPlacements.isEmpty())

        // Clearing is always allowed: there is nothing to refuse.
        assertEquals(HttpStatusCode.OK, put(client, id, "lengthM" to JsonNull).status)

        val bulk = bulk(client, buildJsonObject { put("patchId", JsonPrimitive(id)); put("lengthM", JsonPrimitive(2.0)) })
        assertEquals(HttpStatusCode.BadRequest, bulk.status, bulk.bodyAsText())
        assertNull(get(client, id).lengthM)
    }

    @Test
    fun `lengths are range-checked`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "ring", "lightstrip", 1)
        for (bad in listOf(0.0, -1.0, 100.5)) {
            val resp = put(client, id, "lengthM" to JsonPrimitive(bad))
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$bad: ${resp.bodyAsText()}")
            val placement = put(
                client, id,
                "extraPlacements" to buildJsonArray { add(buildJsonObject { put("lengthM", JsonPrimitive(bad)) }) },
            )
            assertEquals(HttpStatusCode.BadRequest, placement.status, "placement $bad: ${placement.bodyAsText()}")
        }
        assertNull(get(client, id).lengthM)
    }

    @Test
    fun `the bulk route writes a length without rebuilding the registry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "ring", "lightstrip", 1)
        val before = state.show.fixtures.untypedFixture("ring")

        val resp = bulk(
            client,
            buildJsonObject {
                put("patchId", JsonPrimitive(id))
                put("lengthM", JsonPrimitive(4.25))
                put("extraPlacements", buildJsonArray { add(buildJsonObject { put("lengthM", JsonPrimitive(3.0)) }) })
            },
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val updated = resp.body<BulkPlacementResponse>().updated.single()
        assertEquals(4.25, updated.lengthM)
        assertEquals(3.0, updated.extraPlacements.single().lengthM)
        assertSame(before, state.show.fixtures.untypedFixture("ring"), "a length is drawn, never built from")

        // And the single PUT skips the rebuild for it too.
        put(client, id, "lengthM" to JsonPrimitive(5.0))
        assertSame(before, state.show.fixtures.untypedFixture("ring"))
    }

    // — helpers ————————————————————————————————————————————————————————

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

    private suspend fun createPatch(
        client: HttpClient,
        key: String,
        typeKey: String,
        startChannel: Int,
        lengthM: Double? = null,
    ): Int {
        val resp = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = typeKey,
                    key = key,
                    name = key,
                    startChannel = startChannel,
                    lengthM = lengthM,
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<FixturePatchDto>().id
    }
}
