package uk.me.cormack.lighting7.routes

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import org.junit.Test
import uk.me.cormack.lighting7.auth.SESSION_COOKIE
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.BootProgressStateOutMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The auth gate itself: bootstrap-open behaviour, cookie enforcement, the exempt list
 * (the plan's "highest risk" — a gated exempt path bricks the UI), the compiler-server
 * subtree, per-method admin checks, and the WebSocket 4401 close.
 */
class AuthGateTest : RouteIntegrationTest() {

    /** An arbitrary show route with no auth-specific behaviour of its own. */
    private val gatedProbe = "/api/rest/project/list"

    @Test
    fun `bootstrap-open - every route answers without a cookie while zero users exist`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        assertEquals(HttpStatusCode.OK, client.get(gatedProbe).status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/status").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/auth/status").status)
    }

    @Test
    fun `once a user exists - gated routes answer 401 without a cookie and 200 with one`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get(gatedProbe).status)

        val cookie = client.loginCookieHeader("alice")
        assertEquals(HttpStatusCode.OK, client.get(gatedProbe) { header(HttpHeaders.Cookie, cookie) }.status)

        // A syntactically fine but unknown cookie is still 401 — the value must resolve.
        val forged = client.get(gatedProbe) { header(HttpHeaders.Cookie, "$SESSION_COOKIE=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun `exempt paths answer without a cookie in both modes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // Zero users.
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/status").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/auth/status").status)

        seedUser(state, "alice")

        // Users exist, still no cookie: the status pair must keep answering (the frontend
        // gate polls them to decide whether to show the login screen at all)...
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/status").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/auth/status").status)

        // ...and login/setup answer their own domain results, not the gate's 401 body.
        val login = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("alice", "not-the-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, login.status)
        assertTrue(login.bodyAsText().contains("Incorrect username or password"), "reached the login handler, not the gate")

        val setup = client.post("/api/rest/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest("boss", "The Boss", "a-password"))
        }
        assertEquals(HttpStatusCode.Conflict, setup.status, "reached the setup handler, not the gate")
    }

    @Test
    fun `static SPA stays ungated with users present`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()
        // No static bundle is baked into the test classpath, so anything but the gate's
        // 401 proves the point — the login page must be loadable while logged out.
        assertNotEquals(HttpStatusCode.Unauthorized, client.get("/").status)
    }

    @Test
    fun `kotlin-compiler-server subtree is gated`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/kotlin-compiler-server/versions").status)

        // With a cookie the gate passes; the proxy then fails to reach a compiler server
        // in tests, which is fine — any non-401 means the gate admitted the call.
        val cookie = client.loginCookieHeader("alice")
        val proxied = client.get("/kotlin-compiler-server/versions") { header(HttpHeaders.Cookie, cookie) }
        assertNotEquals(HttpStatusCode.Unauthorized, proxied.status)
    }

    @Test
    fun `PUT install is admin-only, GET is not`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val adminCookie = client.loginCookieHeader("boss")
        val operatorCookie = client.loginCookieHeader("op")

        assertEquals(HttpStatusCode.OK, client.get("/api/rest/install") { header(HttpHeaders.Cookie, operatorCookie) }.status)

        val denied = client.put("/api/rest/install") {
            header(HttpHeaders.Cookie, operatorCookie)
            contentType(ContentType.Application.Json)
            setBody(UpdateInstallRequest("Renamed by operator"))
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        val allowed = client.put("/api/rest/install") {
            header(HttpHeaders.Cookie, adminCookie)
            contentType(ContentType.Application.Json)
            setBody(UpdateInstallRequest("Renamed by admin"))
        }
        assertEquals(HttpStatusCode.OK, allowed.status, allowed.bodyAsText())
    }

    @Test
    fun `admin-only prefixes answer 403 for an operator`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        val denied = client.get("/api/rest/cloud-sync/configs") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
    }

    @Test
    fun `per-project cloud-sync routes are admin-only`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()

        val operatorCookie = client.loginCookieHeader("op")
        val denied = client.get("/api/rest/project/$projectId/sync/config") { header(HttpHeaders.Cookie, operatorCookie) }
        assertEquals(HttpStatusCode.Forbidden, denied.status, "operators must not reach sync credentials/config")

        val adminCookie = client.loginCookieHeader("boss")
        val allowed = client.get("/api/rest/project/$projectId/sync/config") { header(HttpHeaders.Cookie, adminCookie) }
        assertNotEquals(HttpStatusCode.Forbidden, allowed.status)
        assertNotEquals(HttpStatusCode.Unauthorized, allowed.status)
    }

    @Test
    fun `encoded and double-slashed spellings cannot bypass the admin check`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        // Routing decodes and collapses these to the admin-only route; the gate must
        // judge the same normalised path, not the raw spelling.
        val spellings = listOf(
            "/api/rest//cloud-sync/configs",
            "/api//rest/cloud-sync/configs",
            "/api/rest/cloud-%73ync/configs",
            "/api/rest/cloud-sync/%63onfigs",
        )
        for (spelling in spellings) {
            val response = client.get(spelling) { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.Forbidden, response.status, "spelling $spelling slipped past the admin check")
        }
    }

    // ─── WebSocket ─────────────────────────────────────────────────────

    @Test
    fun `WS upgrade without a cookie closes 4401 once a user exists`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = createWsClient()

        client.webSocket("/api") {
            val reason = closeReason.await()
            assertNotNull(reason)
            assertEquals(4401, reason.code.toInt())
        }
    }

    @Test
    fun `WS upgrade with a valid cookie streams as normal`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val cookie = jsonClient().loginCookieHeader("alice")
        val client = createWsClient()

        client.webSocket("/api", request = { header(HttpHeaders.Cookie, cookie) }) {
            awaitOfType<BootProgressStateOutMessage>()
            // Close gracefully: exiting mid-initial-burst leaves the server sending into a
            // torn-down socket after the test app stops, and the leaked coroutine failure
            // poisons whichever test runs next in this JVM.
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }

    @Test
    fun `WS upgrade with zero users streams as normal (bootstrap-open)`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()
        client.webSocket("/api") {
            awaitOfType<BootProgressStateOutMessage>()
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }
}
