package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A patch's **extra placements** — a paired dimmer's other lanterns — through
 * `PUT /patches/{id}` and the bulk placement route. The list is written whole: entries keep
 * their identity by uuid, the rest are created, the absent deleted.
 */
class PatchExtraPlacementsRouteTest : RouteIntegrationTest() {

    @Test
    fun `a PUT replaces the list in order, keeping identity by uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val bar = createRigging(client, "LX1")
        val id = createPatch(client, "pair-1", 1)

        val first = put(
            client, id,
            "extraPlacements" to buildJsonArray {
                add(placement("label" to JsonPrimitive(" SR "), "riggingUuid" to JsonPrimitive(bar.uuid), "stageX" to JsonPrimitive(3.0)))
                add(placement("stageX" to JsonPrimitive(-2.0), "stageY" to JsonPrimitive(1.5)))
            },
        )
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val written = first.body<FixturePatchDto>().extraPlacements
        assertEquals(listOf("SR", null), written.map { it.label }, "labels are trimmed, order kept")
        assertEquals(bar.uuid, written[0].riggingUuid)
        assertEquals(-2.0, written[1].stageX)

        val listed = client.get("/api/rest/projects/$projectId/patches").body<List<FixturePatchDto>>()
            .single { it.id == id }
        assertEquals(written, listed.extraPlacements, "the list read agrees with the PUT's answer")

        // Edit the first, drop the second, add a new one ahead of it.
        val second = put(
            client, id,
            "extraPlacements" to buildJsonArray {
                add(placement("stageX" to JsonPrimitive(9.0)))
                add(placement("uuid" to JsonPrimitive(written[0].uuid), "label" to JsonPrimitive("SR"), "stageX" to JsonPrimitive(4.0)))
            },
        )
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        val rewritten = second.body<FixturePatchDto>().extraPlacements
        assertEquals(2, rewritten.size)
        assertEquals(written[0].uuid, rewritten[1].uuid, "an edited placement keeps its uuid")
        assertEquals(4.0, rewritten[1].stageX)
        assertNull(rewritten[1].riggingUuid, "an entry is a whole placement: an absent field is null")
        assertTrue(rewritten.none { it.uuid == written[1].uuid }, "the dropped placement is deleted")
        assertEquals(2, placementRowCount(), "no orphan rows")

        // Absent key leaves the list alone; explicit null clears it.
        put(client, id, "displayName" to JsonPrimitive("Pair 1"))
        assertEquals(rewritten, get(client, id).extraPlacements)
        val cleared = put(client, id, "extraPlacements" to JsonNull)
        assertEquals(HttpStatusCode.OK, cleared.status, cleared.bodyAsText())
        assertTrue(cleared.body<FixturePatchDto>().extraPlacements.isEmpty())
        assertEquals(0, placementRowCount())
    }

    @Test
    fun `a uuid that names no placement of this patch is a new placement with a fresh identity`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = createPatch(client, "pair-a", 1)
        val b = createPatch(client, "pair-b", 2)
        val aPlacement = put(client, a, "extraPlacements" to buildJsonArray { add(placement("stageX" to JsonPrimitive(1.0))) })
            .body<FixturePatchDto>().extraPlacements.single()

        // Patch b names patch a's placement: it must not move it, nor mint a duplicate identity.
        val resp = put(
            client, b,
            "extraPlacements" to buildJsonArray { add(placement("uuid" to JsonPrimitive(aPlacement.uuid), "stageX" to JsonPrimitive(5.0))) },
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertNotEquals(aPlacement.uuid, resp.body<FixturePatchDto>().extraPlacements.single().uuid)
        assertEquals(listOf(aPlacement), get(client, a).extraPlacements, "patch a's placement is untouched")
    }

    @Test
    fun `malformed lists are refused and write nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "bad", 1)

        val cases = listOf(
            "not an array" to JsonPrimitive("SR"),
            "entry not an object" to buildJsonArray { add(JsonPrimitive(1)) },
            "coordinate out of range" to buildJsonArray { add(placement("stageX" to JsonPrimitive(9000.0))) },
            "label too long" to buildJsonArray { add(placement("label" to JsonPrimitive("x".repeat(41)))) },
            "wrong type" to buildJsonArray { add(placement("stageX" to JsonPrimitive("left"))) },
            "bad uuid" to buildJsonArray { add(placement("uuid" to JsonPrimitive("nope"))) },
            "too many" to JsonArray(List(17) { placement() }),
        )
        for ((why, value) in cases) {
            val resp = put(client, id, "displayName" to JsonPrimitive("Renamed"), "extraPlacements" to value)
            assertEquals(HttpStatusCode.BadRequest, resp.status, "$why: ${resp.bodyAsText()}")
        }

        val saved = put(client, id, "extraPlacements" to buildJsonArray { add(placement("stageX" to JsonPrimitive(1.0))) })
            .body<FixturePatchDto>().extraPlacements.single()
        val duplicate = put(
            client, id,
            "extraPlacements" to buildJsonArray {
                add(placement("uuid" to JsonPrimitive(saved.uuid)))
                add(placement("uuid" to JsonPrimitive(saved.uuid)))
            },
        )
        assertEquals(HttpStatusCode.BadRequest, duplicate.status, duplicate.bodyAsText())

        // An unknown rigging is only found inside the transaction: it must still write nothing,
        // the displayName beside it included.
        val unknownRig = put(
            client, id,
            "displayName" to JsonPrimitive("Renamed"),
            "extraPlacements" to buildJsonArray { add(placement("riggingUuid" to JsonPrimitive("00000000-0000-0000-0000-000000000000"))) },
        )
        assertEquals(HttpStatusCode.Conflict, unknownRig.status, unknownRig.bodyAsText())
        val after = get(client, id)
        assertEquals("bad", after.displayName, "a refused PUT commits none of its fields")
        assertEquals(listOf(saved), after.extraPlacements)
    }

    @Test
    fun `writing placements never rebuilds the fixtures registry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "no-rebuild", 30)
        val before = state.show.fixtures.untypedFixture("no-rebuild")

        put(client, id, "extraPlacements" to buildJsonArray { add(placement("stageX" to JsonPrimitive(2.0))) })
        assertSame(before, state.show.fixtures.untypedFixture("no-rebuild"))
    }

    @Test
    fun `the bulk route writes placements and warns past the end of a truss`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val bar = client.post("/api/rest/projects/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "Short bar", lengthM = 2.0))
        }.body<RiggingDto>()
        val id = createPatch(client, "bulk-pair", 1)

        val resp = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(
                BulkPlacementRequest(
                    updates = listOf(
                        buildJsonObject {
                            put("patchId", JsonPrimitive(id))
                            put("extraPlacements", buildJsonArray {
                                add(placement("label" to JsonPrimitive("SR"), "riggingUuid" to JsonPrimitive(bar.uuid), "stageX" to JsonPrimitive(3.0)))
                            })
                        },
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = resp.body<BulkPlacementResponse>()
        assertEquals(3.0, body.updated.single().extraPlacements.single().stageX)
        assertTrue(body.warnings.single().startsWith("bulk-pair (SR): 3.00 m is past the end of Short bar"), body.warnings.toString())

        // A bad entry in an atomic batch writes nothing.
        val bad = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(
                BulkPlacementRequest(
                    updates = listOf(
                        buildJsonObject {
                            put("patchId", JsonPrimitive(id))
                            put("extraPlacements", buildJsonArray { add(placement("stageX" to JsonPrimitive(9999.0))) })
                        },
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
        assertEquals(3.0, get(client, id).extraPlacements.single().stageX)

        // A later edit that does not touch the lanterns still reports the one past the end, as the
        // fixture's own placement is reported whatever an entry changes.
        val unrelated = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(
                BulkPlacementRequest(
                    updates = listOf(buildJsonObject {
                        put("patchId", JsonPrimitive(id))
                        put("gelCode", JsonPrimitive("L201"))
                    }),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, unrelated.status, unrelated.bodyAsText())
        val again = unrelated.body<BulkPlacementResponse>()
        assertTrue(again.warnings.single().startsWith("bulk-pair (SR):"), again.warnings.toString())
        assertEquals(3.0, again.updated.single().extraPlacements.single().stageX, "the answer carries the lanterns")

        // Two patches hanging lanterns on one rigging in one batch both resolve it.
        val other = createPatch(client, "bulk-pair-2", 2)
        val shared = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(
                BulkPlacementRequest(
                    updates = listOf(id, other).map { patchId ->
                        buildJsonObject {
                            put("patchId", JsonPrimitive(patchId))
                            put("extraPlacements", buildJsonArray {
                                add(placement("riggingUuid" to JsonPrimitive(bar.uuid), "stageX" to JsonPrimitive(0.5)))
                            })
                        }
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, shared.status, shared.bodyAsText())
        val sharedBody = shared.body<BulkPlacementResponse>()
        assertEquals(listOf(bar.uuid, bar.uuid), sharedBody.updated.map { it.extraPlacements.single().riggingUuid })
        assertTrue(sharedBody.warnings.isEmpty(), sharedBody.warnings.toString())
    }

    @Test
    fun `deleting a rigging detaches placements and deleting a patch deletes them`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val bar = createRigging(client, "Doomed")
        val id = createPatch(client, "pair-del", 1)
        put(
            client, id,
            "extraPlacements" to buildJsonArray {
                add(placement("riggingUuid" to JsonPrimitive(bar.uuid), "stageX" to JsonPrimitive(1.0)))
            },
        )

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/riggings/${bar.id}").status)
        val detached = get(client, id).extraPlacements.single()
        assertNull(detached.riggingUuid, "the placement survives its rigging, detached")
        assertEquals(1.0, detached.stageX, "offsets are left as they were")

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/patches/$id").status)
        assertEquals(0, placementRowCount(), "a patch delete takes its placements with it")
    }

    // — helpers ————————————————————————————————————————————————————————

    private fun placement(vararg fields: Pair<String, JsonElement>): JsonObject =
        buildJsonObject { for ((k, v) in fields) put(k, v) }

    private fun placementRowCount(): Int = transaction(state.database) { DaoFixturePatchPlacement.all().count().toInt() }

    private suspend fun put(client: HttpClient, patchId: Int, vararg fields: Pair<String, JsonElement>): HttpResponse =
        client.put("/api/rest/projects/$projectId/patches/$patchId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { for ((k, v) in fields) put(k, v) })
        }

    private suspend fun get(client: HttpClient, patchId: Int): FixturePatchDto =
        client.get("/api/rest/projects/$projectId/patches/$patchId").body()

    private suspend fun createRigging(client: HttpClient, name: String): RiggingDto {
        val resp = client.post("/api/rest/projects/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = name))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun createPatch(client: HttpClient, key: String, startChannel: Int): Int {
        val resp = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "generic-dimmer",
                    key = key,
                    name = key,
                    startChannel = startChannel,
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<FixturePatchDto>().id
    }
}
