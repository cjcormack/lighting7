package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.sync.Overrides
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-universe Art-Net transmit interval, over
 * `PUT /project/{id}/universe-configs/{configId}`.
 *
 * The value is a **machine-local override**, not a synced column: it exists to suit the
 * node and fixtures physically present at one venue, so it must not follow the show to the
 * next rig. That storage choice is what these tests pin — the DTO reports an effective
 * value plus whether this desk pinned it, and clearing removes the row entirely.
 */
class UniverseRefreshIntervalRouteTest : RouteIntegrationTest() {

    private suspend fun io.ktor.client.HttpClient.universeConfigs(): List<UniverseConfigDto> =
        get("/api/rest/projects/$projectId/universe-configs").body()

    private fun overrideRowCount(field: String): Int = transaction(state.database) {
        Overrides.listForProject(projectId).count { it.fieldName == field }
    }

    @Test
    fun `a universe defaults to the controller default and reports it as not overridden`() =
        testApplication {
            mountTestApp(state)
            val config = jsonClient().universeConfigs().single()

            assertEquals(ArtNetController.DEFAULT_REFRESH_INTERVAL_MS, config.refreshIntervalMs)
            assertFalse(
                config.refreshIntervalOverridden,
                "an untouched universe must report the default as inherited, not pinned",
            )
        }

    @Test
    fun `PUT sets the interval and marks it overridden`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val config = client.universeConfigs().single()

        val response = client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUniverseConfigRequest(refreshIntervalMs = 44))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val updated: UniverseConfigDto = response.body()
        assertEquals(44, updated.refreshIntervalMs)
        assertTrue(updated.refreshIntervalOverridden)
        assertEquals(44, client.universeConfigs().single().refreshIntervalMs)
    }

    @Test
    fun `resetRefreshInterval clears the override row`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val config = client.universeConfigs().single()

        client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUniverseConfigRequest(refreshIntervalMs = 44))
        }
        assertEquals(1, overrideRowCount("refreshIntervalMs"))

        val response = client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUniverseConfigRequest(resetRefreshInterval = true))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val cleared: UniverseConfigDto = response.body()
        assertEquals(ArtNetController.DEFAULT_REFRESH_INTERVAL_MS, cleared.refreshIntervalMs)
        assertFalse(cleared.refreshIntervalOverridden)
        assertEquals(
            0, overrideRowCount("refreshIntervalMs"),
            "reset must delete the override row, not store the default as a pinned value",
        )
    }

    @Test
    fun `an out-of-range interval is rejected and writes nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val config = client.universeConfigs().single()

        // Below the DMX512 floor: a full 513-slot frame occupies ~22.6 ms on the wire, so
        // anything faster is packets the node discards.
        val tooFast = client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUniverseConfigRequest(refreshIntervalMs = 5))
        }
        assertEquals(HttpStatusCode.BadRequest, tooFast.status)

        val tooSlow = client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUniverseConfigRequest(refreshIntervalMs = 5_000))
        }
        assertEquals(HttpStatusCode.BadRequest, tooSlow.status)

        assertEquals(
            0, overrideRowCount("refreshIntervalMs"),
            "validation runs before the transaction, so a rejected value must leave no partial write",
        )
    }

    @Test
    fun `an interval-only PUT does not disturb the address override`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val config = client.universeConfigs().single()

        client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUniverseConfigRequest(address = "10.0.0.7"))
        }

        val updated: UniverseConfigDto =
            client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
                contentType(ContentType.Application.Json)
                setBody(UpdateUniverseConfigRequest(refreshIntervalMs = 44))
            }.body()

        assertEquals("10.0.0.7", updated.address)
        assertEquals(44, updated.refreshIntervalMs)
    }

    @Test
    fun `deleting a universe clears both machine-local overrides`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val config = client.universeConfigs().single()

        client.put("/api/rest/projects/$projectId/universe-configs/${config.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateUniverseConfigRequest(address = "10.0.0.7", refreshIntervalMs = 44))
        }
        assertEquals(1, overrideRowCount("address"))
        assertEquals(1, overrideRowCount("refreshIntervalMs"))

        val response = client.delete("/api/rest/projects/$projectId/universe-configs/${config.id}")
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Overrides FK to the project, not the universe row, so anything left behind is an
        // orphan keyed by a UUID that no longer exists.
        assertEquals(0, overrideRowCount("address"))
        assertEquals(0, overrideRowCount("refreshIntervalMs"))
    }
}
