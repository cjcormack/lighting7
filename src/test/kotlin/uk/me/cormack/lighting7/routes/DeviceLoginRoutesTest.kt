package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.auth.DeviceLoginLookup
import uk.me.cormack.lighting7.auth.DeviceLoginStatus
import uk.me.cormack.lighting7.auth.DeviceLoginStatusDto
import uk.me.cormack.lighting7.auth.SESSION_COOKIE
import uk.me.cormack.lighting7.models.DaoUser
import uk.me.cormack.lighting7.models.SessionOrigin
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.MdnsService
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TEST_PASSWORD
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device-login QR end to end: someone mints a code for their own account on the desk, a
 * phone reads it without a cookie and exchanges it for a session.
 *
 * This flow hands out a **credential**, which the reset flow deliberately does not — a reset
 * token can only ever set a password and burns itself doing so. So the tests that matter most
 * here are the negative ones: the interlocks that retire a live code, and the fact that a
 * disabled account can't walk in through it.
 */
class DeviceLoginRoutesTest : RouteIntegrationTest() {

    /** The raw token from a minted QR URL. */
    private fun DeviceLoginResponse.rawToken(): String = url.substringAfterLast("/device/")

    @Test
    fun `mint then redeem gives the phone a working session, and burns the code`() = testApplication {
        mountTestApp(state)
        val boss = seedUser(state, "boss", role = UserRole.ADMIN)
        val client = jsonClient()
        val desk = client.loginCookieHeader("boss")

        val minted = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, desk) }
        assertEquals(HttpStatusCode.Created, minted.status, minted.bodyAsText())
        val code = minted.body<DeviceLoginResponse>()
        assertEquals("Test boss", code.displayName)
        assertTrue(code.expiresAtMs > System.currentTimeMillis(), "a fresh code must be in the future")

        // The public GET names the account with no cookie at all...
        val info = client.get("/api/rest/auth/device/${code.rawToken()}")
        assertEquals(HttpStatusCode.OK, info.status, info.bodyAsText())
        assertEquals("boss", info.body<DeviceLoginInfoDto>().username)

        // ...and must NOT have consumed it. A scanner that prefetches, a link preview, or a
        // StrictMode double-render would otherwise burn the code before anyone saw the screen.
        assertEquals(
            DeviceLoginStatus.PENDING,
            client.get("/api/rest/auth/device-logins/${code.id}") { header(HttpHeaders.Cookie, desk) }
                .body<DeviceLoginStatusDto>().status,
            "a lookup must not consume the code",
        )

        val redeemed = client.post("/api/rest/auth/device/${code.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemDeviceLoginRequest())
        }
        assertEquals(HttpStatusCode.OK, redeemed.status, redeemed.bodyAsText())
        val phoneCookie = assertNotNull(redeemed.setCookie().firstOrNull { it.name == SESSION_COOKIE })
        assertEquals("boss", redeemed.body<AuthStatusDto>().user?.username)

        // The cookie is a real session: it reaches a gated route.
        val whoami = client.get("/api/rest/auth/status") {
            header(HttpHeaders.Cookie, "${phoneCookie.name}=${phoneCookie.value}")
        }.body<AuthStatusDto>()
        assertEquals(true, whoami.authenticated)
        assertEquals("boss", whoami.user?.username)

        // Single-use.
        val again = client.post("/api/rest/auth/device/${code.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemDeviceLoginRequest())
        }
        assertEquals(HttpStatusCode.Gone, again.status)
        assertEquals(DeviceLoginStatus.USED.name, again.body<ErrorResponse>().code)

        // The desk's poll sees the redemption, and can name the device that took it — the only
        // signal there is, since nothing confirms a scan.
        val polled = client.get("/api/rest/auth/device-logins/${code.id}") {
            header(HttpHeaders.Cookie, desk)
        }.body<DeviceLoginStatusDto>()
        assertEquals(DeviceLoginStatus.USED, polled.status)
        assertNotNull(polled.sessionId, "the redeeming session must be identifiable")

        // And the session says how it was created, so the devices list can flag it.
        val sessions = state.authService.sessionsFor(boss.userId, currentTokenHash = "none")
        assertEquals(
            setOf(SessionOrigin.PASSWORD, SessionOrigin.QR),
            sessions.map { it.createdVia }.toSet(),
            "the desk logged in with a password, the phone by QR",
        )
    }

    /** Any role: signing your own phone in is not an administrative act. */
    @Test
    fun `an operator can mint a code for their own account`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val operator = client.loginCookieHeader("op")

        val minted = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, operator) }
        assertEquals(HttpStatusCode.Created, minted.status, minted.bodyAsText())
        assertEquals("Test op", minted.body<DeviceLoginResponse>().displayName)
    }

    @Test
    fun `poll and cancel are scoped to the caller, and cancelling kills the code`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val desk = client.loginCookieHeader("boss")
        val operator = client.loginCookieHeader("op")

        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, desk) }
            .body<DeviceLoginResponse>()

        // Someone else's code id tells them nothing and cannot be revoked by them.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/rest/auth/device-logins/${code.id}") { header(HttpHeaders.Cookie, operator) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/api/rest/auth/device-logins/${code.id}") { header(HttpHeaders.Cookie, operator) }.status,
        )

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/rest/auth/device-logins/${code.id}") { header(HttpHeaders.Cookie, desk) }.status,
        )
        val dead = client.post("/api/rest/auth/device/${code.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemDeviceLoginRequest())
        }
        assertEquals(HttpStatusCode.Gone, dead.status)
        assertEquals(DeviceLoginStatus.CANCELLED.name, dead.body<ErrorResponse>().code)
    }

    /** Minting again supersedes: at most one live code per account, as with reset links. */
    @Test
    fun `minting a second code retires the first`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val client = jsonClient()
        val desk = client.loginCookieHeader("boss")

        val first = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, desk) }
            .body<DeviceLoginResponse>()
        client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, desk) }

        val stale = client.post("/api/rest/auth/device/${first.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemDeviceLoginRequest())
        }
        assertEquals(HttpStatusCode.Gone, stale.status)
        assertEquals(DeviceLoginStatus.CANCELLED.name, stale.body<ErrorResponse>().code)
    }

    // ─── The interlocks ────────────────────────────────────────────────
    //
    // Each of these events says "this account's credentials just moved", and a QR that survived
    // one would be a way in that outlived the thing meant to close it. One test each, rather
    // than one test with three arms, so a regression names which interlock broke.

    /**
     * The sharpest of the three: "sign out everywhere else" is the button someone presses when
     * they think they have been compromised. A live QR left exchangeable through it would
     * defeat the one action taken specifically to shut an intruder out.
     */
    @Test
    fun `signing out everywhere else retires a live code`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")

        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, op) }
            .body<DeviceLoginResponse>()
        client.delete("/api/rest/auth/sessions") { header(HttpHeaders.Cookie, op) }

        assertEquals(HttpStatusCode.Gone, redeem(client, code).status)
    }

    /**
     * Plain logout, which is a weaker signal than revoke-all and was the interlock originally
     * missed. A QR still on the screen you have just walked away from is a fresh 30-day session
     * for whoever photographs it.
     */
    @Test
    fun `logging out retires a live code`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")

        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, op) }
            .body<DeviceLoginResponse>()
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/api/rest/auth/logout") { header(HttpHeaders.Cookie, op) }.status,
        )

        val dead = redeem(client, code)
        assertEquals(HttpStatusCode.Gone, dead.status, dead.bodyAsText())
        assertEquals(DeviceLoginStatus.CANCELLED.name, dead.body<ErrorResponse>().code)
    }

    @Test
    fun `changing your own password retires a live code`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")

        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, op) }
            .body<DeviceLoginResponse>()
        val changed = client.put("/api/rest/auth/password") {
            header(HttpHeaders.Cookie, op)
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest(TEST_PASSWORD, "a-brand-new-password"))
        }
        assertEquals(HttpStatusCode.NoContent, changed.status, changed.bodyAsText())

        assertEquals(HttpStatusCode.Gone, redeem(client, code).status)
    }

    @Test
    fun `an admin disabling the account retires a live code`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")
        val op = client.loginCookieHeader("op")

        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, op) }
            .body<DeviceLoginResponse>()
        val disabled = client.put("/api/rest/users/${operator.userId}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(disabled = true))
        }
        assertEquals(HttpStatusCode.OK, disabled.status, disabled.bodyAsText())

        assertEquals(HttpStatusCode.Gone, redeem(client, code).status)
    }

    /** An admin deleting the account takes its codes with it — and must not 500 doing so. */
    @Test
    fun `deleting the account retires a live code without faulting the public endpoint`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")
        val op = client.loginCookieHeader("op")

        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, op) }
            .body<DeviceLoginResponse>()
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/rest/users/${operator.userId}") { header(HttpHeaders.Cookie, admin) }.status,
        )

        // Gone, not 500: the account behind the code no longer exists, and a public endpoint
        // must answer that as a refusal rather than a server fault.
        assertEquals(HttpStatusCode.Gone, redeem(client, code).status)
        assertEquals(HttpStatusCode.Gone, client.get("/api/rest/auth/device/${code.rawToken()}").status)
    }

    private suspend fun redeem(client: HttpClient, code: DeviceLoginResponse) =
        client.post("/api/rest/auth/device/${code.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemDeviceLoginRequest())
        }

    /**
     * The **belt** behind the interlocks' braces: even holding a code that somehow survived,
     * a disabled account must not get a session. This is the opposite call from a reset
     * redemption, which deliberately *re-enables* — handing someone their password back means
     * handing the account back, whereas signing in must never be a way around being disabled.
     *
     * Disabling by writing the column directly rather than through `setUserDisabled`, because
     * that would retire the code via the interlock and test *that* instead of the guard inside
     * `mintSession`. The in-memory user cache still reads "enabled", so the redemption gets
     * all the way to the session mint — which is exactly the path under test.
     */
    @Test
    fun `a disabled account cannot redeem a code, even past the interlocks`() = testApplication {
        mountTestApp(state)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val (_, rawToken) = state.authService.createDeviceLogin(operator.userId)
        transaction(state.database) { DaoUser.findById(operator.userId)!!.disabled = true }

        val client = jsonClient()
        val refused = client.post("/api/rest/auth/device/$rawToken") {
            contentType(ContentType.Application.Json)
            setBody(RedeemDeviceLoginRequest())
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertTrue(
            state.authService.sessionsFor(operator.userId, currentTokenHash = "none").isEmpty(),
            "a refused redemption must not leave a session row behind",
        )
    }

    /**
     * Expiry, driven by an injected clock rather than a sleep. Uses its own [AuthService] over
     * a database with no users of its own — the codes live in that instance's memory, so this
     * cannot reach through the routes; the route's 410 mapping is covered by the cancelled and
     * superseded cases above.
     */
    @Test
    fun `a code past its TTL reads as expired`() = testApplication {
        mountTestApp(state)
        val boss = seedUser(state, "boss", role = UserRole.ADMIN)

        var now = System.currentTimeMillis()
        val service = AuthService(state.database, bcryptCost = 4, clock = { now })
        val (_, rawToken) = service.createDeviceLogin(boss.userId)
        assertIs<DeviceLoginLookup.Live>(service.lookupDeviceLogin(rawToken))

        now += 3 * 60 * 1000
        val dead = assertIs<DeviceLoginLookup.Dead>(service.lookupDeviceLogin(rawToken))
        assertEquals(DeviceLoginStatus.EXPIRED, dead.status)
    }

    @Test
    fun `an unknown code is a 404 and no session appears`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val client = jsonClient()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/rest/auth/device/not-a-code").status)
        val post = client.post("/api/rest/auth/device/not-a-code") {
            contentType(ContentType.Application.Json)
            setBody(RedeemDeviceLoginRequest())
        }
        assertEquals(HttpStatusCode.NotFound, post.status)
        assertNull(post.setCookie().firstOrNull { it.name == SESSION_COOKIE })
    }

    /**
     * The CSRF pin. `docs/desk-accounts.md` rests the answer on "SameSite=Lax plus JSON-only
     * endpoints"; a public POST that accepted a form encoding would break the second half,
     * because a cross-origin auto-submitting form needs no preflight and would leave a victim
     * signed in as an account the attacker controls.
     */
    @Test
    fun `the public exchange refuses a form-encoded post`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val client = jsonClient()
        val desk = client.loginCookieHeader("boss")
        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, desk) }
            .body<DeviceLoginResponse>()

        val formPost = client.post("/api/rest/auth/device/${code.rawToken()}") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("noise=1")
        }
        assertEquals(HttpStatusCode.BadRequest, formPost.status, formPost.bodyAsText())
        assertNull(formPost.setCookie().firstOrNull { it.name == SESSION_COOKIE })

        // And the code survived the attempt, rather than being burned by a refused request.
        assertEquals(
            DeviceLoginStatus.PENDING,
            client.get("/api/rest/auth/device-logins/${code.id}") { header(HttpHeaders.Cookie, desk) }
                .body<DeviceLoginStatusDto>().status,
        )
    }

    /** Same reasoning as the reset flow: a QR encoding `localhost` would resolve to the phone. */
    @Test
    fun `the minted URL never points at loopback when the LAN has an address`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val client = jsonClient()
        val desk = client.loginCookieHeader("boss")

        val code = client.post("/api/rest/auth/device-logins") { header(HttpHeaders.Cookie, desk) }
            .body<DeviceLoginResponse>()
        val everyUrl = listOf(code.url) + code.alternateUrls
        assertTrue(everyUrl.all { it.contains("/device/") }, "every URL must be a sign-in link")
        assertTrue(everyUrl.any { it.contains(".local:") }, "the mDNS name is always offered: $everyUrl")
        if (MdnsService.pickLanAddresses().isNotEmpty()) {
            assertTrue(code.url.contains(".local:"), "expected the mDNS fallback, got ${code.url}")
            assertFalse(
                everyUrl.any { it.contains("localhost") || it.contains("127.") },
                "no offered URL may be loopback: $everyUrl",
            )
        }
    }
}
