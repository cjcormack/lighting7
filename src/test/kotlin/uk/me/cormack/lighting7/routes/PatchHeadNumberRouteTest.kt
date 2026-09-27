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
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The operator's head number (`headNumber`) on a patch: optional, 1–99999, and unique in the
 * project on every write path. The bulk route judges uniqueness against the batch's final state,
 * which is what lets one request renumber — or swap — several heads at once.
 */
class PatchHeadNumberRouteTest : RouteIntegrationTest() {

    @Test
    fun `a head number is taken on create and on PUT, and null clears it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "foh-1", 1, headNumber = 12)
        assertEquals(12, get(client, id).headNumber)
        assertNull(get(client, createPatch(client, "foh-2", 2)).headNumber, "unnumbered by default")

        val resp = put(client, id, "headNumber" to JsonPrimitive(40))
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals(40, resp.body<FixturePatchDto>().headNumber)
        val listed = client.get("/api/rest/projects/$projectId/patches").body<List<FixturePatchDto>>().single { it.id == id }
        assertEquals(40, listed.headNumber, "the list read agrees with the PUT's answer")

        put(client, id, "displayName" to JsonPrimitive("FOH One"))
        assertEquals(40, get(client, id).headNumber, "absent leaves it alone")
        assertEquals(HttpStatusCode.OK, put(client, id, "headNumber" to JsonNull).status)
        assertNull(get(client, id).headNumber)
    }

    @Test
    fun `another head's number is a conflict on every path, and a refusal writes nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        createPatch(client, "foh-1", 1, headNumber = 12)
        val other = createPatch(client, "foh-2", 2)

        val create = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = "generic-dimmer", key = "foh-3", name = "foh-3", startChannel = 3, headNumber = 12))
        }
        assertEquals(HttpStatusCode.Conflict, create.status, create.bodyAsText())
        assertTrue(create.bodyAsText().contains("foh-1"), "names the head that holds it: ${create.bodyAsText()}")

        val own = put(client, other, "displayName" to JsonPrimitive("Renamed"), "headNumber" to JsonPrimitive(12))
        assertEquals(HttpStatusCode.Conflict, own.status, own.bodyAsText())
        val after = get(client, other)
        assertEquals("foh-2", after.displayName, "a refused PUT commits none of its fields")
        assertNull(after.headNumber)

        // A head keeping its own number is not a clash with itself.
        val self = client.get("/api/rest/projects/$projectId/patches").body<List<FixturePatchDto>>().single { it.key == "foh-1" }
        assertEquals(HttpStatusCode.OK, put(client, self.id, "headNumber" to JsonPrimitive(12)).status)

        val bulk = bulk(client, entry(other, JsonPrimitive(12)))
        assertEquals(HttpStatusCode.BadRequest, bulk.status, bulk.bodyAsText())
        assertNull(get(client, other).headNumber)
    }

    @Test
    fun `head numbers are range- and type-checked, never a 500`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "foh-1", 1)
        for (bad in listOf(JsonPrimitive(0), JsonPrimitive(-3), JsonPrimitive(100000), JsonPrimitive(1.5), JsonPrimitive("12"))) {
            val resp = put(client, id, "headNumber" to bad)
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$bad: ${resp.bodyAsText()}")
            val viaBulk = bulk(client, entry(id, bad))
            assertEquals(HttpStatusCode.BadRequest, viaBulk.status, "bulk $bad: ${viaBulk.bodyAsText()}")
        }
        val create = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = "generic-dimmer", key = "foh-2", name = "foh-2", startChannel = 2, headNumber = 0))
        }
        assertEquals(HttpStatusCode.BadRequest, create.status, create.bodyAsText())
        assertNull(get(client, id).headNumber)
    }

    @Test
    fun `the bulk route renumbers in one write, a swap included, without rebuilding the registry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = createPatch(client, "foh-1", 1, headNumber = 1)
        val b = createPatch(client, "foh-2", 2, headNumber = 2)
        val c = createPatch(client, "foh-3", 3, headNumber = 3)
        val before = state.show.fixtures.untypedFixture("foh-1")

        // A and B swap; C moves onto a number nobody held.
        val resp = bulk(client, entry(a, JsonPrimitive(2)), entry(b, JsonPrimitive(1)), entry(c, JsonPrimitive(10)))
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals(mapOf(a to 2, b to 1, c to 10), resp.body<BulkPlacementResponse>().updated.associate { it.id to it.headNumber })
        assertSame(before, state.show.fixtures.untypedFixture("foh-1"), "a head number is a label, never built from")

        // And the single PUT skips the rebuild for it too.
        put(client, a, "headNumber" to JsonPrimitive(20))
        assertSame(before, state.show.fixtures.untypedFixture("foh-1"))

        // Two entries of one batch on one number: both refused, and nothing written.
        val clash = bulk(client, entry(b, JsonPrimitive(5)), entry(c, JsonPrimitive(5)))
        assertEquals(HttpStatusCode.BadRequest, clash.status, clash.bodyAsText())
        assertEquals(listOf(1, 10), listOf(get(client, b).headNumber, get(client, c).headNumber))
    }

    @Test
    fun `a non-atomic batch refuses what would clash once its siblings are refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = createPatch(client, "foh-1", 1, headNumber = 1)
        val b = createPatch(client, "foh-2", 2, headNumber = 2)
        val c = createPatch(client, "foh-3", 3)

        // B's move is refused (its rigging does not exist), so B stays on 2 — which A was moving
        // onto. A must be refused too, not written onto a number B still holds. C lands.
        val resp = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(
                BulkPlacementRequest(
                    updates = listOf(
                        entry(a, JsonPrimitive(2)),
                        buildJsonObject {
                            put("patchId", JsonPrimitive(b))
                            put("headNumber", JsonPrimitive(3))
                            put("riggingUuid", JsonPrimitive("00000000-0000-0000-0000-000000000000"))
                        },
                        entry(c, JsonPrimitive(4)),
                    ),
                    atomic = false,
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = resp.body<BulkPlacementResponse>()
        assertEquals(setOf(a, b), body.failed.map { it.patchId }.toSet(), resp.bodyAsText())
        assertEquals(listOf(c), body.updated.map { it.id })
        assertEquals(listOf(1, 2, 4), listOf(a, b, c).map { get(client, it).headNumber })
    }

    // — helpers ————————————————————————————————————————————————————————

    private fun entry(patchId: Int, headNumber: JsonElement): JsonObject = buildJsonObject {
        put("patchId", JsonPrimitive(patchId))
        put("headNumber", headNumber)
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

    private suspend fun createPatch(client: HttpClient, key: String, startChannel: Int, headNumber: Int? = null): Int {
        val resp = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "generic-dimmer",
                    key = key,
                    name = key,
                    startChannel = startChannel,
                    headNumber = headNumber,
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<FixturePatchDto>().id
    }
}
