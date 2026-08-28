package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
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
 * `PUT /project/{id}/patches/placements` — the bulk placement route the frontend's
 * align / distribute / nudge / array-along-truss operations use.
 *
 * The behaviours worth pinning down are the ones that make it safe to move many
 * fixtures as one action: the key allowlist (which is what structurally guarantees
 * the expensive fixture-registry rebuild is never triggered), atomicity, and the
 * off-the-end-of-the-truss warning that only the server can produce.
 */
class BulkPlacementRouteTest : RouteIntegrationTest() {

    @Test
    fun `moves many patches in one request`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val a = createPatch(client, "bulk-a", 1)
        val b = createPatch(client, "bulk-b", 2)
        val c = createPatch(client, "bulk-c", 3)

        val resp = placements(
            client,
            entry(a, "stageX" to JsonPrimitive(1.0), "stageY" to JsonPrimitive(2.0)),
            entry(b, "stageX" to JsonPrimitive(3.0), "stageY" to JsonPrimitive(4.0)),
            entry(c, "stageX" to JsonPrimitive(5.0), "stageY" to JsonPrimitive(6.0)),
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body: BulkPlacementResponse = resp.body()
        assertEquals(3, body.updated.size)
        assertTrue(body.failed.isEmpty())

        val list = client.get("/api/rest/projects/$projectId/patches").body<List<FixturePatchDto>>()
        assertEquals(1.0, list.first { it.key == "bulk-a" }.stageX)
        assertEquals(6.0, list.first { it.key == "bulk-c" }.stageY)
    }

    @Test
    fun `absent key leaves a field unchanged and explicit null clears it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "tri-state", 1)

        placements(client, entry(id, "stageX" to JsonPrimitive(7.0), "stageZ" to JsonPrimitive(3.0)))
        placements(client, entry(id, "stageZ" to JsonNull)).let {
            assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
        }

        val patch = client.get("/api/rest/projects/$projectId/patches/$id").body<FixturePatchDto>()
        assertEquals(7.0, patch.stageX, "stageX was absent from the second request, so it must be unchanged")
        assertNull(patch.stageZ, "an explicit JSON null must clear the field")
    }

    @Test
    fun `rejects any key outside the placement allowlist`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "guarded", 1)

        // `startChannel` needs per-patch channel-overlap validation and `key` needs
        // a uniqueness check — neither belongs on a route whose whole premise is
        // that it can skip the fixture-registry rebuild.
        for (forbidden in listOf("startChannel", "key", "displayName", "addToGroup")) {
            val resp = placements(client, entry(id, forbidden to JsonPrimitive("x")))
            assertEquals(
                HttpStatusCode.BadRequest,
                resp.status,
                "$forbidden must be refused on the bulk route",
            )
            assertTrue(resp.bodyAsText().contains(forbidden), resp.bodyAsText())
        }

        // And nothing was written.
        val patch = client.get("/api/rest/projects/$projectId/patches/$id").body<FixturePatchDto>()
        assertNull(patch.stageX)
    }

    @Test
    fun `requires patchId on every entry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        createPatch(client, "needs-id", 1)

        val resp = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(
                BulkPlacementRequest(
                    updates = listOf(buildJsonObject { put("stageX", JsonPrimitive(1.0)) }),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("patchId"), resp.bodyAsText())
    }

    @Test
    fun `atomic request rolls the whole batch back on a bad entry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val good = createPatch(client, "atomic-good", 1)
        val bad = createPatch(client, "atomic-bad", 2)

        // 9999 m is outside the ±500 m coordinate bound.
        val resp = placements(
            client,
            entry(good, "stageX" to JsonPrimitive(2.0), "stageY" to JsonPrimitive(2.0)),
            entry(bad, "stageX" to JsonPrimitive(9999.0)),
        )
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())

        val list = client.get("/api/rest/projects/$projectId/patches").body<List<FixturePatchDto>>()
        assertNull(
            list.first { it.key == "atomic-good" }.stageX,
            "the valid entry must not be applied when an atomic batch fails",
        )
    }

    /**
     * The sibling test above fails in the pre-transaction validation pass, so no
     * transaction is ever opened. This one fails on a check that can only be made
     * after the rows are read — and `return@transaction` is a normal return, which
     * Exposed *commits*. If the abort happened partway through the mutation loop,
     * entry one would be flushed to the database while the response said 400.
     */
    @Test
    fun `atomic request writes nothing when a later entry fails inside the transaction`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val good = createPatch(client, "in-txn-good", 1)

        val resp = placements(
            client,
            entry(good, "stageX" to JsonPrimitive(3.0), "stageY" to JsonPrimitive(3.0)),
            entry(999_999, "stageX" to JsonPrimitive(1.0)),
        )
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())

        val patch = client.get("/api/rest/projects/$projectId/patches/$good").body<FixturePatchDto>()
        assertNull(patch.stageX, "the earlier entry must not have been committed")
    }

    /** Same, for a rigging uuid that only fails to resolve once the transaction is open. */
    @Test
    fun `atomic request writes nothing when a rigging fails to resolve`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val good = createPatch(client, "rig-good", 1)
        val bad = createPatch(client, "rig-bad", 2)

        val resp = placements(
            client,
            entry(good, "stageX" to JsonPrimitive(3.0), "stageY" to JsonPrimitive(3.0)),
            entry(bad, "riggingUuid" to JsonPrimitive("6f1e5a4c-0000-4000-8000-000000000000")),
        )
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())

        val patch = client.get("/api/rest/projects/$projectId/patches/$good").body<FixturePatchDto>()
        assertNull(patch.stageX, "the earlier entry must not have been committed")
    }

    @Test
    fun `non-atomic request applies the good entries and reports the rest`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val good = createPatch(client, "partial-good", 1)
        val missing = 999_999

        val resp = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(
                BulkPlacementRequest(
                    updates = listOf(
                        entry(good, "stageX" to JsonPrimitive(4.0), "stageY" to JsonPrimitive(4.0)),
                        entry(missing, "stageX" to JsonPrimitive(1.0)),
                    ),
                    atomic = false,
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body: BulkPlacementResponse = resp.body()
        assertEquals(1, body.updated.size)
        assertEquals(1, body.failed.size)
        assertEquals(missing, body.failed.first().patchId)

        val patch = client.get("/api/rest/projects/$projectId/patches/$good").body<FixturePatchDto>()
        assertEquals(4.0, patch.stageX)
    }

    @Test
    fun `rejects a patch from another project`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "cross-project", 1)

        // Same patch id, wrong project — the id must not be reachable.
        val resp = client.put("/api/rest/projects/${projectId + 5000}/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(BulkPlacementRequest(updates = listOf(entry(id, "stageX" to JsonPrimitive(1.0)))))
        }
        assertTrue(
            resp.status == HttpStatusCode.NotFound || resp.status == HttpStatusCode.BadRequest,
            "expected the request to be refused, got ${resp.status}: ${resp.bodyAsText()}",
        )
    }

    @Test
    fun `warns when a placement falls past the end of its truss`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val rigging = client.post("/api/rest/projects/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "Short Bar", lengthM = 4.0))
        }.body<RiggingDto>()

        val id = createPatch(client, "overhang", 1)

        // The bar is 4 m, so local X may run to ±2. 5 m is well past the end — and
        // nothing else would catch it: the coordinate bound is ±500 m.
        val resp = placements(
            client,
            entry(
                id,
                "riggingUuid" to JsonPrimitive(rigging.uuid),
                "stageX" to JsonPrimitive(5.0),
                "stageY" to JsonPrimitive(0.0),
            ),
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body: BulkPlacementResponse = resp.body()
        assertEquals(1, body.warnings.size, body.warnings.toString())
        assertTrue(body.warnings.first().contains("Short Bar"), body.warnings.first())

        // Reported, never clamped — the value the user asked for is what's stored.
        val patch = client.get("/api/rest/projects/$projectId/patches/$id").body<FixturePatchDto>()
        assertEquals(5.0, patch.stageX)
    }

    @Test
    fun `no warning for a placement inside the truss`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rigging = client.post("/api/rest/projects/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "Long Bar", lengthM = 12.0))
        }.body<RiggingDto>()
        val id = createPatch(client, "inside", 1)

        val resp = placements(
            client,
            entry(
                id,
                "riggingUuid" to JsonPrimitive(rigging.uuid),
                "stageX" to JsonPrimitive(5.0),
                "stageY" to JsonPrimitive(0.0),
            ),
        )
        val body: BulkPlacementResponse = resp.body()
        assertTrue(body.warnings.isEmpty(), body.warnings.toString())
    }

    /**
     * The allowlist exists to make the rebuild-skip structural rather than a thing
     * someone has to remember. Asserted the same way as the single-PUT test: the
     * runtime `Fixture` instance must survive the write.
     */
    @Test
    fun `bulk placement never rebuilds the fixtures registry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = createPatch(client, "no-rebuild", 20)

        val before = state.show.fixtures.untypedFixture("no-rebuild")
        val resp = placements(
            client,
            entry(id, "stageX" to JsonPrimitive(2.0), "stageY" to JsonPrimitive(3.0)),
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())

        assertSame(
            before,
            state.show.fixtures.untypedFixture("no-rebuild"),
            "bulk placement must reuse the same runtime fixture — no DbFixtureLoader rebuild",
        )
    }

    @Test
    fun `empty update list is a no-op`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val resp = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(BulkPlacementRequest(updates = emptyList()))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body: BulkPlacementResponse = resp.body()
        assertTrue(body.updated.isEmpty())
    }

    @Test
    fun `rejects an oversized batch`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val updates = (1..501).map { entry(it, "stageX" to JsonPrimitive(0.0)) }
        val resp = client.put("/api/rest/projects/$projectId/patches/placements") {
            contentType(ContentType.Application.Json)
            setBody(BulkPlacementRequest(updates = updates))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // — helpers ————————————————————————————————————————————————————————

    private fun entry(patchId: Int, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject {
            put("patchId", JsonPrimitive(patchId))
            for ((k, v) in fields) put(k, v)
        }

    private suspend fun placements(
        client: io.ktor.client.HttpClient,
        vararg updates: JsonObject,
    ) = client.put("/api/rest/projects/$projectId/patches/placements") {
        contentType(ContentType.Application.Json)
        setBody(BulkPlacementRequest(updates = updates.toList()))
    }

    private suspend fun createPatch(
        client: io.ktor.client.HttpClient,
        key: String,
        startChannel: Int,
    ): Int {
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
