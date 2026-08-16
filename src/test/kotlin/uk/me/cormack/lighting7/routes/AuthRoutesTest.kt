package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
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
import io.ktor.http.setCookie
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.auth.SESSION_COOKIE
import uk.me.cormack.lighting7.auth.SessionInfo
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TEST_PASSWORD
import uk.me.cormack.lighting7.testsupport.cookieClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The seven `/api/rest/auth` endpoints from multi-user-auth plan session 1. */
class AuthRoutesTest : RouteIntegrationTest() {

    @Test
    fun `status reports setupRequired until setup runs, then authenticated`() = testApplication {
        mountTestApp(state)
        val client = cookieClient()

        val before = client.get("/api/rest/auth/status").body<AuthStatusDto>()
        assertEquals(AuthStatusDto(setupRequired = true, authenticated = false), before)

        val setup = client.post("/api/rest/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest("Boss", "The Boss", "a-password"))
        }
        assertEquals(HttpStatusCode.OK, setup.status, setup.bodyAsText())
        val setupBody = setup.body<AuthStatusDto>()
        assertTrue(setupBody.authenticated)
        assertEquals("boss", setupBody.user?.username, "username is stored lowercase")
        assertEquals(UserRole.ADMIN, setupBody.user?.role)

        // The cookie jar carries the session — status now says authenticated.
        val after = client.get("/api/rest/auth/status").body<AuthStatusDto>()
        assertEquals(false, after.setupRequired)
        assertTrue(after.authenticated)
        assertEquals("boss", after.user?.username)
    }

    @Test
    fun `second setup answers 409`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val first = client.post("/api/rest/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest("boss", "The Boss", "a-password"))
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/api/rest/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest("boss2", "Impostor", "a-password"))
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    @Test
    fun `login sets the session cookie with the agreed attributes`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()

        val response = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("alice", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())

        val cookie = response.setCookie().first { it.name == SESSION_COOKIE }
        assertTrue(cookie.httpOnly, "cookie must be httpOnly")
        assertEquals("/", cookie.path)
        assertEquals("Lax", cookie.extensions["SameSite"])
        assertEquals(30 * 24 * 60 * 60, cookie.maxAge)
        assertEquals(false, cookie.secure, "plain LAN HTTP — a Secure cookie would never be sent")
    }

    @Test
    fun `wrong password and unknown username answer identical 401 bodies`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()

        val wrongPassword = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("alice", "not-the-password"))
        }
        val unknownUser = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("nobody", "not-the-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals(HttpStatusCode.Unauthorized, unknownUser.status)
        assertEquals(wrongPassword.bodyAsText(), unknownUser.bodyAsText(), "responses must not reveal which half was wrong")
    }

    @Test
    fun `disabled account answers 403`() = testApplication {
        mountTestApp(state)
        val record = seedUser(state, "alice")
        state.authService.setUserDisabled(record.userId, true)
        val client = jsonClient()

        val response = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("alice", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `logout clears the cookie and kills the session server-side`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()
        val cookie = client.loginCookieHeader("alice")

        val logout = client.post("/api/rest/auth/logout") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        val cleared = logout.setCookie().first { it.name == SESSION_COOKIE }
        assertEquals(0, cleared.maxAge, "logout must expire the cookie")

        // The revoke is server-side: replaying the old cookie no longer works.
        val replay = client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
    }

    @Test
    fun `changing the password keeps the caller's session and revokes the other`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()
        val keeper = client.loginCookieHeader("alice")
        val doomed = client.loginCookieHeader("alice")

        val change = client.put("/api/rest/auth/password") {
            header(HttpHeaders.Cookie, keeper)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(currentPassword = TEST_PASSWORD, newPassword = "a-new-password"))
        }
        assertEquals(HttpStatusCode.NoContent, change.status, change.bodyAsText())

        assertEquals(HttpStatusCode.OK, client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, keeper) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, doomed) }.status)
    }

    @Test
    fun `wrong current password answers 401 and revokes nothing`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()
        val cookie = client.loginCookieHeader("alice")

        val change = client.put("/api/rest/auth/password") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(currentPassword = "not-the-password", newPassword = "a-new-password"))
        }
        assertEquals(HttpStatusCode.Unauthorized, change.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `sessions list marks exactly the calling session current, delete spares it`() = testApplication {
        mountTestApp(state)
        seedUser(state, "alice")
        val client = jsonClient()
        val mine = client.loginCookieHeader("alice")
        val other = client.loginCookieHeader("alice")

        val sessions = client.get("/api/rest/auth/sessions") { header(HttpHeaders.Cookie, mine) }.body<List<SessionInfo>>()
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.count { it.current })
        assertNotNull(sessions.firstOrNull { !it.current })

        val revoke = client.delete("/api/rest/auth/sessions") { header(HttpHeaders.Cookie, mine) }
        assertEquals(HttpStatusCode.NoContent, revoke.status)

        assertEquals(HttpStatusCode.OK, client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, mine) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, other) }.status)

        val remaining = client.get("/api/rest/auth/sessions") { header(HttpHeaders.Cookie, mine) }.body<List<SessionInfo>>()
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().current)
    }

    @Test
    fun `password policy failures answer 400`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val short = client.post("/api/rest/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(SetupRequest("boss", "The Boss", "short"))
        }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        // The failed setup must not have created the user.
        assertNull(state.authService.findUserByUsername("boss"))
    }
}
