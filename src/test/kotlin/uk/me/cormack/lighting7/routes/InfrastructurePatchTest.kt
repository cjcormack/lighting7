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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The patch's `infrastructure` flag: a patch that is DMX but not a lighting fixture (a dimmer on
 * hard power), which every surface but the Patches and Channels views hides.
 *
 * The frontend filters on the flag as it rides the **live fixture list**, so what matters here is
 * that `GET /fixtures` reports it — after the PUT that sets it, and still after a later
 * metadata-only PUT that refreshes the patch-metadata cache without a rebuild.
 */
class InfrastructurePatchTest : RouteIntegrationTest() {

    @Test
    fun `infrastructure round-trips, reaches the fixture list, and survives a metadata-only edit`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "generic-dimmer",
                    key = "hazer-power",
                    name = "Hazer power",
                    startChannel = 40,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val patch = created.body<FixturePatchDto>()
        assertEquals(false, patch.infrastructure, "a patch is a lighting fixture by default")
        assertEquals(false, fixtureListInfrastructure(client, "hazer-power"))

        val announced = AtomicInteger()
        state.show.fixtures.registerListener(object : FixturesChangeListener {
            override fun fixturesChanged() { announced.incrementAndGet() }
        })

        val before = state.show.fixtures.untypedFixture("hazer-power")
        val mark = client.put("/api/rest/projects/$projectId/patches/${patch.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("infrastructure", JsonPrimitive(true)) })
        }
        assertEquals(HttpStatusCode.OK, mark.status, mark.bodyAsText())
        assertEquals(true, mark.body<FixturePatchDto>().infrastructure)
        // No rebuild — the controllers of a live rig are not torn down for a view flag — but the
        // `fixturesChanged` a rebuild would have sent is still announced, which is how every other
        // window drops the fixture from its views.
        assertSame(before, state.show.fixtures.untypedFixture("hazer-power"), "marking infrastructure does not rebuild")
        assertEquals(1, announced.get(), "a flip announces fixturesChanged once")
        assertEquals(true, fixtureListInfrastructure(client, "hazer-power"))

        // Re-sending the same value is not a flip, so it announces nothing.
        client.put("/api/rest/projects/$projectId/patches/${patch.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("infrastructure", JsonPrimitive(true)) })
        }
        assertEquals(1, announced.get())
        assertEquals(
            true,
            client.get("/api/rest/projects/$projectId/patches/${patch.id}").body<FixturePatchDto>().infrastructure,
            "infrastructure must persist",
        )

        // A metadata-only edit rewrites the cache entry the fixture list reads the flag from, and
        // must not reset it.
        val gel = client.put("/api/rest/projects/$projectId/patches/${patch.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("gelCode", JsonPrimitive("L201")) })
        }
        assertEquals(HttpStatusCode.OK, gel.status, gel.bodyAsText())
        assertEquals(true, gel.body<FixturePatchDto>().infrastructure)
        assertEquals(true, fixtureListInfrastructure(client, "hazer-power"), "a gel edit must keep the flag")

        val unmark = client.put("/api/rest/projects/$projectId/patches/${patch.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("infrastructure", JsonPrimitive(false)) })
        }
        assertEquals(HttpStatusCode.OK, unmark.status, unmark.bodyAsText())
        assertEquals(false, unmark.body<FixturePatchDto>().infrastructure)
        assertEquals(false, fixtureListInfrastructure(client, "hazer-power"))
    }

    @Test
    fun `infrastructure can be set on create`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "generic-dimmer",
                    key = "relay-1",
                    name = "Relay 1",
                    startChannel = 41,
                    infrastructure = true,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals(true, created.body<FixturePatchDto>().infrastructure)
        assertEquals(true, fixtureListInfrastructure(client, "relay-1"))
    }

    /** The `infrastructure` field of one entry of `GET /fixtures`; an omitted default reads false. */
    private suspend fun fixtureListInfrastructure(client: io.ktor.client.HttpClient, key: String): Boolean {
        val list = client.get("/api/rest/fixtures").body<JsonArray>()
        val entry = list.map { it.jsonObject }.single { it["key"]?.jsonPrimitive?.content == key }
        return entry["infrastructure"]?.jsonPrimitive?.boolean ?: false
    }
}
