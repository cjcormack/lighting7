package uk.me.cormack.lighting7.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.routes.LoginRequest
import uk.me.cormack.lighting7.routes.NewScript
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.RemoteDesk
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TEST_PASSWORD
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountPublic
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.remote
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The public listener's remote hardening (`mcp/RemoteRequests.kt`, `docs/mcp-engineering.md`
 * §"Remote hardening"): every request on it is remote, and remote gets a stricter desk. A caller
 * there is a remote session through the cloud or no one, so the tests sign in as one ([RemoteDesk]).
 */
class RemoteHardeningTest : RouteIntegrationTest() {

    private val fake = FakeCloud().apply { heartbeatS = 1 }
    private val desk by lazy { RemoteDesk(state, fake) }

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    @After
    fun closeFake() {
        desk.close()
    }

    /** Seed [username] (an admin unless [role] says otherwise) and adopt the desk as the first admin seeded. */
    private fun remoteUser(username: String, role: UserRole = UserRole.ADMIN) {
        val user = seedUser(state, username, role = role)
        if (!adopted) {
            desk.adopt(user.userId)
            adopted = true
        }
    }

    private var adopted = false

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
        mountPublic(state)
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
    fun `password sign-in is the desk's own, and a session cookie means nothing remotely`() {
        seedUser(state, "alice")
        var cookie = ""
        testApplication {
            mountTestApp(state)
            cookie = jsonClient().loginCookieHeader("alice")
        }
        testApplication {
            mountPublic(state)
            val http = jsonClient()
            val login = http.login("alice")
            assertEquals(HttpStatusCode.Forbidden, login.status)
            assertEquals(CODE_REMOTE_PASSWORD_SIGN_IN, login.code())
            assertNull(login.headers[HttpHeaders.SetCookie])
            val projects = http.get("/api/rest/projects") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.Unauthorized, projects.status)
            val status = Json.parseToJsonElement(http.get("/api/rest/auth/status") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()).jsonObject
            assertEquals("false", status["authenticated"]!!.jsonPrimitive.content)
        }
        testApplication {
            mountTestApp(state)
            assertEquals(HttpStatusCode.OK, jsonClient().get("/api/rest/projects") { header(HttpHeaders.Cookie, cookie) }.status)
        }
    }

    @Test
    fun `no spelling of a refused path reaches its handler`() {
        testApplication {
            mountPublic(state)
            val http = jsonClient()
            // No accounts yet: every spelling of an API path is closed.
            for (path in listOf("/api//rest/auth/status", "/%61pi/rest/auth/status")) {
                val response = http.get(path)
                assertEquals(HttpStatusCode.Forbidden, response.status, path)
                assertEquals("REMOTE_NEEDS_ACCOUNT", response.code(), path)
            }
        }
        seedUser(state, "alice")
        testApplication {
            mountPublic(state)
            val http = jsonClient()
            for (path in listOf("/api/rest/auth//login", "/api//rest/auth/login", "/api/rest/auth/%6Cogin")) {
                val login = http.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice", TEST_PASSWORD))
                }
                assertEquals(HttpStatusCode.Forbidden, login.status, path)
                assertEquals(CODE_REMOTE_PASSWORD_SIGN_IN, login.code(), path)
                assertNull(login.headers[HttpHeaders.SetCookie], path)
            }
            assertEquals(HttpStatusCode.NotFound, http.get("/api/rest/auth//reset/some-token").status)
        }
    }

    @Test
    fun `the tunnel, the desk's OAuth server and MCP straight to the desk are gone`() {
        seedUser(state, "alice")
        testApplication {
            mountPublic(state)
            val http = jsonClient()
            for (path in listOf(
                "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp",
                "/.well-known/oauth-authorization-server", "/oauth/authorize",
            )) {
                assertEquals(HttpStatusCode.NotFound, http.get(path).status, path)
            }
            for (path in listOf("/oauth/register", "/oauth/token", "/oauth/revoke", "/mcp")) {
                assertEquals(HttpStatusCode.NotFound, http.post(path) { contentType(ContentType.Application.Json); setBody("{}") }.status, path)
            }
        }
        testApplication {
            mountTestApp(state)
            val http = jsonClient()
            val cookie = http.loginCookieHeader("alice")
            // A GET of an unknown path is the SPA's page, so the routes are asked by their writes.
            val tunnel = http.put("/api/rest/install/tunnel") {
                header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json); setBody("""{"enabled":true}""")
            }
            assertEquals(HttpStatusCode.NotFound, tunnel.status)
            assertEquals(HttpStatusCode.NotFound, http.delete("/api/rest/auth/connected-apps/1") { header(HttpHeaders.Cookie, cookie) }.status)
        }
    }

    @Test
    fun `a state-changing request from a foreign origin is refused`() = testApplication {
        mountPublic(state)
        remoteUser("alice")
        val http = jsonClient()
        suspend fun logout(origin: String?) = http.post("/api/rest/auth/logout") {
            remote(desk.caller("alice"))
            origin?.let { header(HttpHeaders.Origin, it) }
        }
        val foreign = logout("https://evil.example")
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertEquals("REMOTE_ORIGIN_REFUSED", foreign.code())
        assertEquals(HttpStatusCode.NoContent, logout("http://localhost:8414").status)
        // No Origin at all is not a browser, so not a cross-site request.
        assertEquals(HttpStatusCode.NoContent, logout(null).status)

        // A WebSocket upgrade carries no CORS protection at all: the origin check is the lock.
        // (The test client refuses to set `Upgrade` itself, so the rule is asked directly.)
        val upgrade = remoteRefusal(state, "/api", HttpMethod.Get, "https://evil.example", webSocket = true)
        assertEquals(HttpStatusCode.Forbidden, upgrade?.first)
        assertNull(remoteRefusal(state, "/api", HttpMethod.Get, "http://localhost:8414", webSocket = true))
        assertEquals(HttpStatusCode.Forbidden, remoteRefusal(state, "/api", HttpMethod.Get, "https://desk.ngrok-free.app", webSocket = true)?.first)
        // A read from anywhere is harmless: it carries no cookie cross-site, and CORS hides the answer.
        assertEquals(HttpStatusCode.OK, http.get("/api/rest/auth/status") { header(HttpHeaders.Origin, "https://evil.example") }.status)
    }

    @Test
    fun `the QR redemption flows and the API docs are not there remotely`() = testApplication {
        mountPublic(state)
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
        remoteUser("alice")
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
            mountPublic(state)
            val http = jsonClient()
            val caller = desk.caller("alice")
            suspend fun send(path: String, body: String, put: Boolean = false): HttpResponse {
                val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                    remote(caller)
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
                http.get("/api/script-editor/versions") { remote(caller) },
            )
            refused.forEach { r ->
                assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
                assertEquals("REMOTE_SCRIPTS_DISABLED", r.code())
            }
            // A rename sends the code back unchanged, and is not authoring one.
            val rename = send("$scripts/$scriptId", """{"name":"renamed","script":"println(1)"}""", put = true)
            assertEquals(HttpStatusCode.OK, rename.status, rename.bodyAsText())

            state.remotePolicy.update(allowScripts = true)
            assertEquals(HttpStatusCode.OK, http.get("/api/script-editor/versions") { remote(caller) }.status)
            val edit = send("$scripts/$scriptId", """{"name":"renamed","script":"println(3)"}""", put = true)
            assertEquals(HttpStatusCode.OK, edit.status, edit.bodyAsText())
        }
    }

    @Test
    fun `fixture commands are refused remotely until an admin allows them`() {
        remoteUser("alice")
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
            mountPublic(state)
            val http = jsonClient()
            val caller = desk.caller("alice")
            val path = "/api/rest/projects/$projectId/patches/$patchId/commands/resetPanTilt"
            val refused = http.post(path) { remote(caller) }
            assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
            assertEquals("REMOTE_COMMANDS_DISABLED", refused.code())
            assertNull(state.show.commandOutput.runningOn("rev"))

            state.remotePolicy.update(allowCommands = true)
            try {
                val ran = http.post(path) { remote(caller) }
                assertEquals(HttpStatusCode.OK, ran.status, ran.bodyAsText())
            } finally {
                state.remotePolicy.update(allowCommands = false)
            }
        }
    }

    @Test
    fun `bulk commands and make rig safe are held to the commands setting remotely, and quit is never remote`() {
        remoteUser("admin")
        testApplication {
            mountPublic(state)
            val http = jsonClient()
            val caller = desk.caller("admin")
            for (path in listOf("projects/$projectId/commands", "projects/$projectId/rig-safe", "projects/$projectId/rig-safe/undo")) {
                val refused = http.post("/api/rest/$path") {
                    remote(caller)
                    contentType(ContentType.Application.Json)
                    setBody("""{"role":"LAMP_OFF"}""")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status, "$path: ${refused.bodyAsText()}")
                assertEquals("REMOTE_COMMANDS_DISABLED", refused.code())
            }
            var exited = false
            state.exitDesk = { exited = true }
            val quit = http.post("/api/rest/install/quit") {
                remote(caller)
                contentType(ContentType.Application.Json)
                setBody("""{"force":true}""")
            }
            assertEquals(HttpStatusCode.Forbidden, quit.status, quit.bodyAsText())
            assertEquals("QUIT_LOCAL_ONLY", quit.code())
            Thread.sleep(700)
            assertFalse(exited)
        }
    }

    @Test
    fun `the policy switches live on the Cloud tab and outlast a restart`() {
        seedUser(state, "admin")
        testApplication {
            mountTestApp(state)
            val http = jsonClient()
            val cookie = http.loginCookieHeader("admin")
            val saved = http.put("/api/rest/install/cloud/remote") {
                header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json)
                setBody("""{"allowScripts":true,"allowCommands":true}""")
            }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        }
        assertEquals(RemotePolicySettingsOf(scripts = true, commands = true), state.remotePolicy.settings)
        assertEquals(RemotePolicySettingsOf(scripts = true, commands = true), uk.me.cormack.lighting7.cloud.RemotePolicy(state.database).settings)
    }

    @Suppress("FunctionName")
    private fun RemotePolicySettingsOf(scripts: Boolean = false, effects: Boolean = false, commands: Boolean = false) =
        uk.me.cormack.lighting7.cloud.RemotePolicySettings(scripts, effects, commands)
}
