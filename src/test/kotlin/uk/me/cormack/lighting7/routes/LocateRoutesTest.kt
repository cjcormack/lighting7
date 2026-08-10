package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/rest/locate` + `POST /api/rest/locate/toggle` — the Locate toggle.
 *
 * Behaviours pinned here: toggle-on asserts Layer-4 writes (visible in the
 * `DirectWriteStore`) and registers the target; toggle-off clears both; releasing a group
 * locate re-asserts a still-active fixture locate whose writes overlap (the console
 * expectation — the individually-located member must stay in locate state); unknown
 * targets 404 and unknown target types 400 without touching any state.
 */
class LocateRoutesTest : RouteIntegrationTest() {

    @Test
    fun `fixture locate toggles Layer-4 writes on and off`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-loc", startChannel = 1)

        val on: ToggleLocateResponse = toggle(client, "fixture", "hex-loc").body()
        assertTrue(on.active)
        assertTrue(on.writeCount > 0, "hex resolves dimmer + colour + strobe")

        // Hex at channel 1: dimmer=1, R/G/B=2/3/4, amber=5, white=6, uv=7, strobe=8.
        val store = state.show.directWriteStore
        assertEquals(255u.toUByte(), store.get(0, 1), "dimmer full")
        assertEquals(255u.toUByte(), store.get(0, 2), "red full")
        assertEquals(255u.toUByte(), store.get(0, 6), "white engine full")
        assertEquals(0u.toUByte(), store.get(0, 7), "uv zeroed by the colour fan-out")
        assertEquals(0u.toUByte(), store.get(0, 8), "shutter open")

        val listed: LocateStateResponse = client.get("/api/rest/locate").body()
        assertEquals(listOf(LocateTargetDto("fixture", "hex-loc")), listed.targets)

        val off: ToggleLocateResponse = toggle(client, "fixture", "hex-loc").body()
        assertFalse(off.active)
        assertNull(store.get(0, 1), "writes cleared — channels cascade back")
        assertNull(store.get(0, 8))
        assertTrue(client.get("/api/rest/locate").body<LocateStateResponse>().targets.isEmpty())
    }

    @Test
    fun `group locate covers every member and releasing it keeps an overlapping fixture locate asserted`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-a", startChannel = 1)
        seedHex("hex-b", startChannel = 20)
        seedGroup("locate-band", "hex-a", "hex-b")

        // Fixture locate first, then the group containing it on top.
        assertTrue(toggle(client, "fixture", "hex-a").body<ToggleLocateResponse>().active)
        assertTrue(toggle(client, "group", "locate-band").body<ToggleLocateResponse>().active)

        val store = state.show.directWriteStore
        assertEquals(255u.toUByte(), store.get(0, 1), "member a dimmer")
        assertEquals(255u.toUByte(), store.get(0, 20), "member b dimmer")

        // Releasing the group must not release the still-active fixture locate on hex-a.
        assertFalse(toggle(client, "group", "locate-band").body<ToggleLocateResponse>().active)
        assertEquals(255u.toUByte(), store.get(0, 1), "hex-a re-asserted by its own locate")
        assertNull(store.get(0, 20), "hex-b released with the group")

        val listed: LocateStateResponse = client.get("/api/rest/locate").body()
        assertEquals(listOf(LocateTargetDto("fixture", "hex-a")), listed.targets)
    }

    @Test
    fun `unknown fixture is 404 and unknown target type is 400`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val missing = toggle(client, "fixture", "no-such-fixture")
        assertEquals(HttpStatusCode.NotFound, missing.status, missing.bodyAsText())

        val badType = toggle(client, "universe", "0")
        assertEquals(HttpStatusCode.BadRequest, badType.status, badType.bodyAsText())

        assertTrue(client.get("/api/rest/locate").body<LocateStateResponse>().targets.isEmpty())
    }

    private suspend fun toggle(client: HttpClient, type: String, key: String): HttpResponse =
        client.post("/api/rest/locate/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLocateRequest(type, key))
        }

    private fun seedHex(key: String, startChannel: Int) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoFixturePatch.new {
                this.project = project
                universeConfig = DaoUniverseConfig.all().first()
                fixtureTypeKey = "hex"
                this.key = key
                displayName = key
                this.startChannel = startChannel
                sortOrder = startChannel
            }
        }
        reloadFixtures()
    }

    private fun seedGroup(name: String, vararg memberKeys: String) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val group = DaoFixtureGroup.new { this.project = project; this.name = name }
            memberKeys.forEachIndexed { index, memberKey ->
                DaoFixtureGroupMember.new {
                    this.group = group
                    fixturePatch = DaoFixturePatch.find { DaoFixturePatches.key eq memberKey }.first()
                    sortOrder = index
                }
            }
        }
        reloadFixtures()
    }

    private fun reloadFixtures() {
        DbFixtureLoader.loadFixtures(projectId, state.show.fixtures, state.database, parkSource = state.show.parkManager)
    }
}
