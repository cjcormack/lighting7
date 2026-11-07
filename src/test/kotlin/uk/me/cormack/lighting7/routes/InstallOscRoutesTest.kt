package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.osc.OscSettingsDto
import uk.me.cormack.lighting7.plugins.CloudStateOutMessage
import uk.me.cormack.lighting7.plugins.OscStateOutMessage
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.RemoteDesk
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountPublic
import uk.me.cormack.lighting7.testsupport.remote
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.collectUntilOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `GET/PUT /install/osc` and `osc.state` (OSC plan D5, D12): admin only, never remote, every problem at once. */
class InstallOscRoutesTest : RouteIntegrationTest() {

    private val fake = FakeCloud().apply { heartbeatS = 1 }
    private val desk by lazy { RemoteDesk(state, fake) }

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    @After
    fun closeFake() {
        desk.close()
    }

    private fun HttpResponse.json() = runBlocking { Json.parseToJsonElement(bodyAsText()).jsonObject }

    @Test
    fun `an admin reads and writes the settings, which are stored whole`() = testApplication {
        seedUser(state, "admin")
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val initial = http.get("/api/rest/install/osc") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, initial.status, initial.bodyAsText())
        assertEquals(OscSettingsDto(enabled = false, bindAddress = null, port = 8415, senders = emptyList()), initial.body())

        val put = http.put("/api/rest/install/osc") {
            header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json)
            setBody("""{"enabled":false,"port":9000,"senders":[{"name":"QLab","address":"192.168.1.20"}]}""")
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val stored = put.body<OscSettingsDto>()
        assertEquals(9000, stored.port)
        assertEquals(listOf("QLab" to "192.168.1.20"), stored.senders.map { it.name to it.address })

        // A sender left out of the document is removed; one with its id is kept.
        val id = stored.senders.single().id
        val second = http.put("/api/rest/install/osc") {
            header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json)
            setBody("""{"enabled":false,"port":9000,"senders":[{"id":$id,"name":"QLab Mac","address":"192.168.1.20"},{"name":"Sound","address":"192.168.1.21"}]}""")
        }.body<OscSettingsDto>()
        assertEquals(listOf(id), second.senders.filter { it.name == "QLab Mac" }.map { it.id })
        assertEquals(2, second.senders.size)
    }

    @Test
    fun `a bad document is refused naming every problem, and an operator is refused`() = testApplication {
        seedUser(state, "admin")
        seedUser(state, "op", role = UserRole.OPERATOR)
        mountTestApp(state)
        val http = jsonClient()
        val admin = http.loginCookieHeader("admin")

        val bad = http.put("/api/rest/install/osc") {
            header(HttpHeaders.Cookie, admin); contentType(ContentType.Application.Json)
            setBody("""{"enabled":true,"port":80,"senders":[{"name":"A","address":"10.0.0.1"},{"name":"B","address":"10.0.0.1"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val body = bad.json()
        assertEquals("OSC_SETTINGS_INVALID", body["code"]?.jsonPrimitive?.content)
        assertEquals(2, body["problems"]?.jsonArray?.size)
        assertFalse(state.osc.settings().enabled, "a refused document changes nothing")

        val operator = http.loginCookieHeader("op")
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/rest/install/osc") { header(HttpHeaders.Cookie, operator) }.status)
    }

    @Test
    fun `the public listener is refused both ways, and its sockets never get osc_state`() = testApplication {
        desk.adopt(seedUser(state, "admin").userId)
        mountPublic(state)
        val client = createWsClient()
        val admin = desk.caller("admin")
        for (response in listOf(
            client.get("/api/rest/install/osc") { remote(admin) },
            client.put("/api/rest/install/osc") {
                remote(admin); contentType(ContentType.Application.Json); setBody("""{"enabled":true}""")
            },
        )) {
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("OSC_SETTINGS_LOCAL_ONLY", response.json()["code"]?.jsonPrimitive?.content)
        }
        client.webSocket("/api", request = { remote(admin) }) {
            val frames = collectUntilOfType<CloudStateOutMessage>()
            assertTrue(frames.none { it is OscStateOutMessage })
        }
    }

    @Test
    fun `the address listing is refused on the public listener`() = testApplication {
        desk.adopt(seedUser(state, "admin").userId)
        mountPublic(state)
        val client = createWsClient()
        val response = client.get("/api/rest/projects/$projectId/osc/addresses") { remote(desk.caller("admin")) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("OSC_LOCAL_ONLY", response.json()["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an operator's socket on the desk's own listener gets osc_state`() = testApplication {
        seedUser(state, "op", role = UserRole.OPERATOR)
        mountTestApp(state)
        val client = createWsClient()
        val cookie = client.loginCookieHeader("op")
        client.webSocket("/api", request = { header(HttpHeaders.Cookie, cookie) }) {
            val frame = awaitOfType<OscStateOutMessage>().state
            assertFalse(frame.listening)
        }
    }
}
