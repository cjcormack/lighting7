package uk.me.cormack.lighting7.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.setCookie
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.auth.SESSION_COOKIE
import uk.me.cormack.lighting7.mcp.tunnel.RemoteAccessException
import uk.me.cormack.lighting7.mcp.tunnel.RemoteAccessService
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.routes.LoginRequest
import uk.me.cormack.lighting7.routes.NewScript
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TEST_PASSWORD
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The public listener's remote hardening (`mcp/RemoteRequests.kt`, `docs/mcp-engineering.md`
 * §"Remote hardening"): every request on it is remote, and remote gets a stricter desk.
 */
class RemoteHardeningTest : RouteIntegrationTest() {

    @After
    fun forgetToken() {
        // The test credential store is a file under build/test-data, shared across tests.
        runCatching { state.credentialStore.deleteBlob(RemoteAccessService.AUTHTOKEN_KEY) }
    }

    private fun ApplicationTestBuilder.mountPublic() {
        environment { config = testAppConfig() }
        application { publicModule(state) }
    }

    private fun HttpResponse.code(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    private suspend fun HttpClient.login(username: String, password: String = TEST_PASSWORD, origin: String? = null): HttpResponse =
        post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            origin?.let { header(HttpHeaders.Origin, it) }
            setBody(LoginRequest(username, password))
        }

    @Test
    fun `a desk with no accounts is closed remotely, so nobody can create the first admin`() = testApplication {
        mountPublic()
        val http = jsonClient()
        val status = http.get("/api/rest/auth/status")
        assertEquals(HttpStatusCode.Forbidden, status.status)
        assertEquals("REMOTE_NEEDS_ACCOUNT", status.code())
        val setup = http.post("/api/rest/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"mallory","displayName":"M","password":"$TEST_PASSWORD"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, setup.status)
        assertFalse(state.authService.hasAnyUser)
        // The QR flows answer a bare 404 even here, rather than a 403 that names the desk's state.
        assertEquals(HttpStatusCode.NotFound, http.get("/api/rest/auth/device/some-token").status)
        assertEquals(HttpStatusCode.NotFound, http.post("/api/rest/auth/reset/some-token") {
            contentType(ContentType.Application.Json); setBody("{}")
        }.status)
    }

    @Test
    fun `the session cookie is Secure remotely and not on the LAN`() {
        seedUser(state, "alice")
        testApplication {
            mountPublic()
            val cookie = jsonClient().login("alice").setCookie().first { it.name == SESSION_COOKIE }
            assertTrue(cookie.secure, "remote session cookie must be Secure")
        }
        testApplication {
            mountTestApp(state)
            val cookie = jsonClient().login("alice").setCookie().first { it.name == SESSION_COOKIE }
            assertFalse(cookie.secure, "a Secure cookie would never come back over the LAN's plain HTTP")
        }
    }

    @Test
    fun `repeated remote failures lock the account remotely but never on the LAN`() {
        seedUser(state, "alice")
        testApplication {
            mountPublic()
            val http = jsonClient()
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, http.login("alice", "wrong-password").status) }
            val locked = http.login("alice")
            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            assertEquals("SIGN_IN_LOCKED", locked.code())
        }
        testApplication {
            mountTestApp(state)
            assertEquals(HttpStatusCode.OK, jsonClient().login("alice").status)
        }
    }

    @Test
    fun `a state-changing request from a foreign origin is refused`() = testApplication {
        mountPublic()
        seedUser(state, "alice")
        val http = jsonClient()
        val foreign = http.login("alice", origin = "https://evil.example")
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertEquals("REMOTE_ORIGIN_REFUSED", foreign.code())
        assertEquals(HttpStatusCode.OK, http.login("alice", origin = "http://localhost:8414").status)
        // No Origin at all is not a browser, so not a cross-site request.
        assertEquals(HttpStatusCode.OK, http.login("alice").status)

        state.remoteAccess.update(domain = "desk.ngrok-free.app", hasAnyUser = true)
        assertEquals(HttpStatusCode.OK, http.login("alice", origin = "https://desk.ngrok-free.app").status)

        // A WebSocket upgrade carries no CORS protection at all: the origin check is the lock.
        // (The test client refuses to set `Upgrade` itself, so the rule is asked directly.)
        val upgrade = remoteRefusal(state, "/api", HttpMethod.Get, "https://evil.example", webSocket = true)
        assertEquals(HttpStatusCode.Forbidden, upgrade?.first)
        assertNull(remoteRefusal(state, "/api", HttpMethod.Get, "https://desk.ngrok-free.app", webSocket = true))
        // A read from anywhere is harmless: it carries no cookie cross-site, and CORS hides the answer.
        assertEquals(HttpStatusCode.OK, http.get("/api/rest/auth/status") { header(HttpHeaders.Origin, "https://evil.example") }.status)
    }

    @Test
    fun `the QR redemption flows and the API docs are not there remotely`() = testApplication {
        mountPublic()
        seedUser(state, "alice")
        val http = jsonClient()
        assertEquals(HttpStatusCode.NotFound, http.get("/api/rest/auth/device/some-token").status)
        assertEquals(HttpStatusCode.NotFound, http.post("/api/rest/auth/device/some-token") {
            contentType(ContentType.Application.Json); setBody("{}")
        }.status)
        assertEquals(HttpStatusCode.NotFound, http.post("/api/rest/auth/reset/some-token") {
            contentType(ContentType.Application.Json); setBody("""{"newPassword":"another-password"}""")
        }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/api.json").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/openapi").status)
    }

    @Test
    fun `scripts are refused remotely until an admin allows them`() {
        seedUser(state, "alice")
        var scriptId = 0
        testApplication {
            mountTestApp(state)
            val http = jsonClient()
            val cookie = http.loginCookieHeader("alice")
            val created = http.post("/api/rest/projects/$projectId/scripts") {
                header(HttpHeaders.Cookie, cookie)
                contentType(ContentType.Application.Json)
                setBody(NewScript("hello", "println(1)"))
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            scriptId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content.toInt()
        }
        testApplication {
            mountPublic()
            val http = jsonClient()
            val cookie = http.loginCookieHeader("alice")
            suspend fun send(path: String, body: String, put: Boolean = false): HttpResponse {
                val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                    header(HttpHeaders.Cookie, cookie)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                return if (put) http.put(path, block) else http.post(path, block)
            }
            val scripts = "/api/rest/projects/$projectId/scripts"
            val refused = listOf(
                send(scripts, """{"name":"x","script":"println(2)"}"""),
                send("$scripts/compile", """{"name":"x","script":"println(2)"}"""),
                send("$scripts/run", """{"name":"x","script":"println(2)"}"""),
                send("$scripts/$scriptId", """{"name":"hello","script":"println(3)"}""", put = true),
                send("/api/rest/fx/definitions/compile", """{"script":"1"}"""),
                http.get("/api/script-editor/versions") { header(HttpHeaders.Cookie, cookie) },
            )
            refused.forEach { r ->
                assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
                assertEquals("REMOTE_SCRIPTS_DISABLED", r.code())
            }
            // A rename sends the code back unchanged, and is not authoring one.
            val rename = send("$scripts/$scriptId", """{"name":"renamed","script":"println(1)"}""", put = true)
            assertEquals(HttpStatusCode.OK, rename.status, rename.bodyAsText())

            state.remoteAccess.update(allowScripts = true, hasAnyUser = true)
            assertEquals(HttpStatusCode.OK, http.get("/api/script-editor/versions") { header(HttpHeaders.Cookie, cookie) }.status)
            val edit = send("$scripts/$scriptId", """{"name":"renamed","script":"println(3)"}""", put = true)
            assertEquals(HttpStatusCode.OK, edit.status, edit.bodyAsText())
        }
    }

    @Test
    fun `fixture commands are refused remotely until an admin allows them`() {
        seedUser(state, "alice")
        var patchId = 0
        testApplication {
            mountTestApp(state)
            val http = jsonClient()
            val cookie = http.loginCookieHeader("alice")
            val created = http.post("/api/rest/projects/$projectId/patches") {
                header(HttpHeaders.Cookie, cookie)
                contentType(ContentType.Application.Json)
                setBody("""{"universe":0,"fixtureTypeKey":"etc-source4-revolution-base-frame","key":"rev","name":"Rev","startChannel":1}""")
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            patchId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content.toInt()
        }
        testApplication {
            mountPublic()
            val http = jsonClient()
            val cookie = http.loginCookieHeader("alice")
            val path = "/api/rest/projects/$projectId/patches/$patchId/commands/resetPanTilt"
            val refused = http.post(path) { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
            assertEquals("REMOTE_COMMANDS_DISABLED", refused.code())
            assertNull(state.show.commandOutput.runningOn("rev"))

            state.remoteAccess.update(allowCommands = true, hasAnyUser = true)
            try {
                val ran = http.post(path) { header(HttpHeaders.Cookie, cookie) }
                assertEquals(HttpStatusCode.OK, ran.status, ran.bodyAsText())
            } finally {
                state.remoteAccess.update(allowCommands = false, hasAnyUser = true)
            }
        }
    }

    @Test
    fun `the tunnel settings keep the authtoken write-only and are admin only`() {
        seedUser(state, "admin")
        seedUser(state, "op", role = UserRole.OPERATOR)
        testApplication {
            mountTestApp(state)
            val http = jsonClient()
            val admin = http.loginCookieHeader("admin")
            val op = http.loginCookieHeader("op")
            assertEquals(HttpStatusCode.Forbidden, http.get("/api/rest/install/tunnel") { header(HttpHeaders.Cookie, op) }.status)

            val incomplete = http.put("/api/rest/install/tunnel") {
                header(HttpHeaders.Cookie, admin); contentType(ContentType.Application.Json)
                setBody("""{"enabled":true}""")
            }
            assertEquals(HttpStatusCode.BadRequest, incomplete.status)
            assertEquals("REMOTE_ACCESS_INVALID", incomplete.code())

            val saved = http.put("/api/rest/install/tunnel") {
                header(HttpHeaders.Cookie, admin); contentType(ContentType.Application.Json)
                setBody("""{"domain":"https://Desk.ngrok-free.app/","authtoken":"2abcSECRETtoken"}""")
            }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val body = saved.bodyAsText()
            assertFalse(body.contains("2abcSECRETtoken"), body)
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("desk.ngrok-free.app", json["domain"]!!.jsonPrimitive.content)
            assertEquals("true", json["hasAuthtoken"]!!.jsonPrimitive.content)
            assertEquals("https://desk.ngrok-free.app/mcp", json["connectorUrl"]!!.jsonPrimitive.content)
            assertEquals("off", json["state"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertFalse(http.get("/api/rest/install/tunnel") { header(HttpHeaders.Cookie, admin) }.bodyAsText().contains("SECRET"))

            // Clearing the token turns remote access off with it.
            state.remoteAccess.update(enabled = true, hasAnyUser = true)
            assertTrue(state.remoteAccess.settings.enabled)
            val cleared = http.put("/api/rest/install/tunnel") {
                header(HttpHeaders.Cookie, admin); contentType(ContentType.Application.Json)
                setBody("""{"authtoken":""}""")
            }
            val clearedJson = Json.parseToJsonElement(cleared.bodyAsText()).jsonObject
            assertEquals("false", clearedJson["hasAuthtoken"]!!.jsonPrimitive.content)
            assertEquals("false", clearedJson["enabled"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `remote access cannot be turned on for a desk with no accounts`() {
        val e = assertFailsWith<RemoteAccessException> {
            state.remoteAccess.update(enabled = true, domain = "desk.ngrok-free.app", authtoken = "tok", hasAnyUser = false)
        }
        assertTrue(e.message!!.contains("account"))
        assertFalse(state.remoteAccess.settings.enabled)
    }

    @Test
    fun `domains are normalised, and anything but a bare host is refused`() {
        assertEquals("desk.ngrok-free.app", RemoteAccessService.normaliseDomain(" https://Desk.ngrok-free.app/ "))
        assertNull(RemoteAccessService.normaliseDomain("  "))
        for (bad in listOf("desk", "desk.ngrok-free.app/path", "desk.ngrok-free.app:8080", "evil.com?x=1", "-a.example")) {
            assertFailsWith<RemoteAccessException>(bad) { RemoteAccessService.normaliseDomain(bad) }
        }
    }

    @Test
    fun `the public url prefers local conf, then the ngrok domain, then localhost`() {
        assertEquals("http://localhost:8414", state.remoteAccess.publicUrl())
        state.remoteAccess.update(domain = "desk.ngrok-free.app", hasAnyUser = true)
        assertEquals("https://desk.ngrok-free.app", state.remoteAccess.publicUrl())
        val explicit = RemoteAccessService(
            state.database,
            state.mcpConfig.copy(explicitPublicUrl = "https://mine.example"),
            credentialStore = { state.credentialStore },
            workDir = kotlin.io.path.createTempDirectory("ra"),
        )
        try {
            assertEquals("https://mine.example", explicit.publicUrl())
            assertNotEquals(state.remoteAccess.publicUrl(), explicit.publicUrl())
        } finally {
            explicit.close()
        }
    }
}
