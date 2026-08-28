package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
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
 * (the plan's "highest risk" — a gated exempt path bricks the UI), the script-editor
 * subtree, the `adminOnly {}` subtrees and per-method admin checks, and the WebSocket
 * 4401 close.
 */
/** Mirrors the `/api/script-editor/versions` element the editor widget parses. */
@kotlinx.serialization.Serializable
private data class CompilerVersionDto(val version: String, val latestStable: Boolean)

class AuthGateTest : RouteIntegrationTest() {

    /** An arbitrary show route with no auth-specific behaviour of its own. */
    private val gatedProbe = "/api/rest/projects"

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
    fun `script-editor subtree is gated`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/script-editor/versions").status)

        val cookie = client.loginCookieHeader("alice")
        val allowed = client.get("/api/script-editor/versions") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, allowed.status)
    }

    /**
     * The editor widget fetches `/versions` once per page and, on *any* failure, silently drops
     * every editor on that page to read-only with highlighting off — logging only a console
     * warning. So the shape matters as much as the status code.
     */
    @Test
    fun `script-editor versions answers in the shape the editor widget requires`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()
        val cookie = client.loginCookieHeader("alice")

        val body = client.get("/api/script-editor/versions") { header(HttpHeaders.Cookie, cookie) }
            .body<List<CompilerVersionDto>>()

        val version = body.single()
        assertTrue(version.latestStable, "the widget picks the entry flagged latestStable")
        assertEquals(KotlinVersion.CURRENT.toString(), version.version)
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
    fun `adminOnly subtrees answer 403 for an operator`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/rest/cloud-sync/configs") { header(HttpHeaders.Cookie, cookie) }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/rest/users") { header(HttpHeaders.Cookie, cookie) }.status,
        )
    }

    /**
     * Project export and import take a caller-supplied absolute filesystem path and read or write
     * it verbatim as the desk process — the one authenticated surface that reaches outside the
     * app's own data directory, so both are admin-only however the payload is shaped.
     */
    @Test
    fun `project export and import are admin-only`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val operatorCookie = client.loginCookieHeader("op")

        val deniedExport = client.post("/api/rest/projects/$projectId/export") {
            header(HttpHeaders.Cookie, operatorCookie)
            contentType(ContentType.Application.Json)
            setBody(ProjectExportRequest(path = null))
        }
        assertEquals(HttpStatusCode.Forbidden, deniedExport.status)

        val deniedImport = client.post("/api/rest/projects/import") {
            header(HttpHeaders.Cookie, operatorCookie)
            contentType(ContentType.Application.Json)
            setBody(ProjectImportRequest(path = "/nowhere"))
        }
        assertEquals(HttpStatusCode.Forbidden, deniedImport.status)

        // The same import as an admin reaches the handler and fails on its own terms.
        val adminCookie = client.loginCookieHeader("boss")
        val reached = client.post("/api/rest/projects/import") {
            header(HttpHeaders.Cookie, adminCookie)
            contentType(ContentType.Application.Json)
            setBody(ProjectImportRequest(path = "/nowhere"))
        }
        assertNotEquals(HttpStatusCode.Forbidden, reached.status)
    }

    /**
     * Scripts are deliberately **not** admin territory, and this is the assertion that stops
     * someone "hardening" them later. Running a script is arbitrary code execution on the desk,
     * but an operator is trusted local crew standing in front of the machine — they can already
     * do anything the desk process can. Locking scripts to admins would only stop the person
     * holding the desk from fixing a cue mid-show.
     */
    @Test
    fun `an operator may compile and run scripts`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        assertNotEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/script-editor/versions") { header(HttpHeaders.Cookie, cookie) }.status,
        )
        assertNotEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/rest/projects/$projectId/scripts") { header(HttpHeaders.Cookie, cookie) }.status,
        )
    }

    @Test
    fun `an operator may edit their own profile`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        // Maintaining your own account is not an administrative act. This is the assertion that
        // stops the route being moved under `/users` or wrapped in `adminOnly {}` later.
        val allowed = client.put("/api/rest/auth/profile") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest("Ops Person"))
        }
        assertEquals(HttpStatusCode.OK, allowed.status, allowed.bodyAsText())
    }

    @Test
    fun `per-project cloud-sync routes are admin-only`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()

        val operatorCookie = client.loginCookieHeader("op")
        val denied = client.get("/api/rest/projects/$projectId/sync/config") { header(HttpHeaders.Cookie, operatorCookie) }
        assertEquals(HttpStatusCode.Forbidden, denied.status, "operators must not reach sync credentials/config")

        val adminCookie = client.loginCookieHeader("boss")
        val allowed = client.get("/api/rest/projects/$projectId/sync/config") { header(HttpHeaders.Cookie, adminCookie) }
        assertNotEquals(HttpStatusCode.Forbidden, allowed.status)
        assertNotEquals(HttpStatusCode.Unauthorized, allowed.status)
    }

    /**
     * The whole point of `adminOnly {}` being a route-tree node: routing decides what the path
     * means, so there is no spelling that satisfies the resolver and misses the check. This used
     * to depend on the gate normalising the path the same way the resolver does.
     */
    @Test
    fun `encoded and double-slashed spellings cannot bypass the admin check`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        // Routing decodes and collapses each of these to the admin-only route, and the check
        // now lives on that route rather than on a string comparison beside it.
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

    @Test
    fun `the reset exemption cannot be walked out of into a gated route`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()

        // `isAuthExempt` matches a *prefix*, so anything that both satisfies the prefix and
        // resolves elsewhere would be an unauthenticated hole. Routing decodes each segment
        // the same way the gate does, so these can only ever reach the reset handler (404 —
        // no such token) or match nothing; what must never appear is a 200 from /users.
        val spellings = listOf(
            "/api/rest/auth/reset/../users",
            "/api/rest/auth/reset/..%2fusers",
            "/api/rest/auth/reset/%2e%2e/users",
            "/api/rest/auth/reset//../users",
            // Same for the device-login exemption, which additionally has an authenticated
            // sibling one path segment away — see the test below for why that matters.
            "/api/rest/auth/device/../users",
            "/api/rest/auth/device/..%2fdevice-logins",
            "/api/rest/auth/device/%2e%2e/device-logins",
            "/api/rest/auth/device//../users",
        )
        for (spelling in spellings) {
            val response = client.get(spelling)
            assertNotEquals(
                HttpStatusCode.OK,
                response.status,
                "spelling $spelling answered 200 without a cookie — an exemption leaked",
            )
        }
    }

    /**
     * The trailing slash in `isAuthExempt`'s `"/api/rest/auth/device/"` is load-bearing:
     * without it the prefix would also match `/api/rest/auth/device-logins`, and minting a
     * sign-in QR for an arbitrary account would need no cookie at all. One character between
     * "sign my own phone in" and "anyone on the network can mint a session".
     */
    @Test
    fun `the device exemption covers redemption but never the mint endpoint`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()

        // Exempt: answers on its own terms (404 for a token that doesn't exist), not 401.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/rest/auth/device/nope").status)

        // Gated, both spellings: the collection and a specific id.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/rest/auth/device-logins").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rest/auth/device-logins/some-id").status)
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
