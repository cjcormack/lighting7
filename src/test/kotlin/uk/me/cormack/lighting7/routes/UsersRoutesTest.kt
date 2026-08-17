package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
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
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.auth.UserMutation
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.BootProgressStateOutMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TEST_PASSWORD
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `/api/rest/users` — CRUD, the last-admin and self guards, operator lockout, and the
 * two things a disable has to do beyond flipping a column: kill the REST session and
 * close an already-open socket (plan 3.5).
 */
class UsersRoutesTest : RouteIntegrationTest() {

    @Test
    fun `admin can list, create, read, update and delete users`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val created = client.post("/api/rest/users") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(NewUserRequest("Ops", "Ops Person", UserRole.OPERATOR, "a-password"))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = created.body<UserDto>()
        assertEquals("ops", dto.username, "username is stored lowercase")
        assertEquals(UserRole.OPERATOR, dto.role)
        assertEquals(false, dto.disabled)
        assertNull(dto.lastLoginAtMs, "a brand new account has never logged in")

        val list = client.get("/api/rest/users") { header(HttpHeaders.Cookie, admin) }.body<List<UserDto>>()
        assertEquals(listOf("boss", "ops"), list.map { it.username }, "listed in username order")

        val fetched = client.get("/api/rest/users/${dto.id}") { header(HttpHeaders.Cookie, admin) }.body<UserDto>()
        assertEquals(dto, fetched)

        val updated = client.put("/api/rest/users/${dto.id}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(displayName = "Renamed Person", role = UserRole.ADMIN))
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val updatedDto = updated.body<UserDto>()
        assertEquals("Renamed Person", updatedDto.displayName)
        assertEquals(UserRole.ADMIN, updatedDto.role)
        assertEquals("ops", updatedDto.username, "username is not editable")

        val deleted = client.delete("/api/rest/users/${dto.id}") { header(HttpHeaders.Cookie, admin) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertNull(state.authService.findUser(dto.id))
    }

    @Test
    fun `duplicate username answers 409`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val clash = client.post("/api/rest/users") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(NewUserRequest("BOSS", "Impostor", UserRole.OPERATOR, "a-password"))
        }
        assertEquals(HttpStatusCode.Conflict, clash.status, "the unique index is the race guard")
    }

    @Test
    fun `a short password is rejected before the account exists`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val short = client.post("/api/rest/users") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(NewUserRequest("ops", "Ops", UserRole.OPERATOR, "short"))
        }
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertNull(state.authService.findUserByUsername("ops"))
    }

    @Test
    fun `operators cannot reach any users route`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val cookie = client.loginCookieHeader("op")

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/rest/users") { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/api/rest/users/${operator.userId}") { header(HttpHeaders.Cookie, cookie) }.status,
            "not even their own row",
        )
        val mint = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, cookie)
        }
        assertEquals(HttpStatusCode.Forbidden, mint.status, "an operator must not be able to mint themselves a reset link")
    }

    @Test
    fun `the last enabled admin cannot be demoted, disabled or deleted`() = testApplication {
        mountTestApp(state)
        val boss = seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val demote = client.put("/api/rest/users/${boss.userId}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(role = UserRole.OPERATOR))
        }
        assertEquals(HttpStatusCode.Conflict, demote.status)
        assertEquals(CODE_LAST_ADMIN, demote.body<ErrorResponse>().code)
        assertEquals(UserRole.ADMIN, state.authService.findUser(boss.userId)?.role, "the demotion must not have landed")

        val disable = client.put("/api/rest/users/${boss.userId}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(disabled = true))
        }
        // Self-disable is caught first, so the code says SELF rather than LAST_ADMIN —
        // either way the desk keeps an admin who can sign in.
        assertEquals(HttpStatusCode.Conflict, disable.status)
        assertEquals(CODE_SELF_TARGET, disable.body<ErrorResponse>().code)

        val delete = client.delete("/api/rest/users/${boss.userId}") { header(HttpHeaders.Cookie, admin) }
        assertEquals(HttpStatusCode.Conflict, delete.status)
        assertEquals(CODE_SELF_TARGET, delete.body<ErrorResponse>().code)
        assertNotNull(state.authService.findUser(boss.userId))
    }

    /**
     * The guard has to live inside the mutating transaction, because over HTTP the disable
     * arm is unreachable: any caller is themselves an enabled admin, so a *different* user
     * can never be the last one, and self-disable is answered by the self guard first.
     * Driving the service directly is what actually exercises it — and what would catch a
     * regression that moved the check back into the route, where two concurrent requests
     * could both pass it.
     */
    @Test
    fun `the service refuses to disable or demote the last admin`() = testApplication {
        mountTestApp(state)
        val boss = seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)

        assertEquals(UserMutation.LastAdmin, state.authService.updateUser(boss.userId, null, null, disabled = true))
        assertEquals(UserMutation.LastAdmin, state.authService.updateUser(boss.userId, null, UserRole.OPERATOR, null))
        assertEquals(UserMutation.LastAdmin, state.authService.deleteUser(boss.userId))

        // Nothing landed: still an enabled admin, still able to sign in.
        val after = assertNotNull(state.authService.findUser(boss.userId))
        assertEquals(UserRole.ADMIN, after.role)
        assertEquals(false, after.disabled)

        // A rename alongside a refused demotion is refused too — the whole edit is one
        // transaction, so a half-applied update can't escape.
        assertEquals(
            UserMutation.LastAdmin,
            state.authService.updateUser(boss.userId, "Renamed", UserRole.OPERATOR, null),
        )
        assertEquals("Test boss", assertNotNull(state.authService.findUser(boss.userId)).displayName)
    }

    @Test
    fun `a second admin unlocks demoting the first`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val deputy = seedUser(state, "deputy", role = UserRole.ADMIN)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val demote = client.put("/api/rest/users/${deputy.userId}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(role = UserRole.OPERATOR))
        }
        assertEquals(HttpStatusCode.OK, demote.status, demote.bodyAsText())
        assertEquals(1, state.authService.enabledAdminCount())

        // ...and now the guard bites for the one that's left.
        val second = client.put("/api/rest/users/${deputy.userId}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(role = UserRole.ADMIN))
        }
        assertEquals(HttpStatusCode.OK, second.status, "re-promoting is always allowed")
    }

    @Test
    fun `disabling a user kills their session immediately`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")
        val victim = client.loginCookieHeader("op")

        assertEquals(HttpStatusCode.OK, client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, victim) }.status)

        val disable = client.put("/api/rest/users/${operator.userId}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(disabled = true))
        }
        assertEquals(HttpStatusCode.OK, disable.status, disable.bodyAsText())

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, victim) }.status,
            "a disabled account's live session must stop working, not just stop logging in",
        )
    }

    @Test
    fun `an admin-set password replaces the old one and revokes their sessions`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")
        val victim = client.loginCookieHeader("op")

        val set = client.put("/api/rest/users/${operator.userId}/password") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(SetUserPasswordRequest("brand-new-password"))
        }
        assertEquals(HttpStatusCode.NoContent, set.status, set.bodyAsText())

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, victim) }.status,
        )
        val old = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("op", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.Unauthorized, old.status)
        val new = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("op", "brand-new-password"))
        }
        assertEquals(HttpStatusCode.OK, new.status, new.bodyAsText())
    }

    @Test
    fun `over-long names answer 400 rather than reaching the column`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        // SQLite stores an over-long varchar happily; a length-enforcing database would
        // reject the same row outright, so the route is where this has to be caught.
        val longUsername = client.post("/api/rest/users") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(NewUserRequest("u".repeat(65), "Fine", UserRole.OPERATOR, "a-password"))
        }
        assertEquals(HttpStatusCode.BadRequest, longUsername.status)

        val longDisplayName = client.post("/api/rest/users") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(NewUserRequest("ops", "d".repeat(101), UserRole.OPERATOR, "a-password"))
        }
        assertEquals(HttpStatusCode.BadRequest, longDisplayName.status)
        assertNull(state.authService.findUserByUsername("ops"))
    }

    @Test
    fun `unknown user ids answer 404`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        assertEquals(HttpStatusCode.NotFound, client.get("/api/rest/users/9999") { header(HttpHeaders.Cookie, admin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/rest/users/9999") { header(HttpHeaders.Cookie, admin) }.status)
        val mint = client.post("/api/rest/users/9999/reset-tokens") { header(HttpHeaders.Cookie, admin) }
        assertEquals(HttpStatusCode.NotFound, mint.status)
    }

    /**
     * The point of plan 3.5: the socket auth check runs at upgrade time only, so a
     * disable has to reach an already-streaming connection through the revocation flow.
     */
    @Test
    fun `disabling a user closes their open socket with 4401`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = createWsClient()
        val admin = client.loginCookieHeader("boss")
        val victim = client.loginCookieHeader("op")

        client.webSocket("/api", request = { header(HttpHeaders.Cookie, victim) }) {
            // Wait until the connection is genuinely live before revoking, otherwise the
            // test could disable the account before the collector is registered.
            awaitOfType<BootProgressStateOutMessage>()

            val disable = client.put("/api/rest/users/${operator.userId}") {
                header(HttpHeaders.Cookie, admin)
                contentType(ContentType.Application.Json)
                setBody(UpdateUserRequest(disabled = true))
            }
            assertEquals(HttpStatusCode.OK, disable.status, disable.bodyAsText())

            val reason = closeReason.await()
            assertNotNull(reason)
            assertEquals(4401, reason.code.toInt())
        }
    }
}
