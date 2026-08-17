package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.cookieClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.update.UpdateChannelKind
import uk.me.cormack.lighting7.update.UpdateSettingsRequest
import uk.me.cormack.lighting7.update.UpdateStatusDto
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two deliberate decisions in `routes/update.kt` that a future reader would otherwise be
 * free to "tidy up": reading the status is **not** administration, and nothing under `/update`
 * waits for the show to boot.
 */
class UpdateRoutesTest : RouteIntegrationTest() {

    private fun seedAdminAndOperator() {
        seedUser(state, "admin", UserRole.ADMIN)
        seedUser(state, "operator", UserRole.OPERATOR)
    }

    /**
     * `/api/rest/update` is deliberately absent from `ADMIN_ONLY_PREFIXES`: that gate is
     * method-blind, and an operator standing at the desk should be able to see what version it is
     * on and that it is about to restart. Adding the prefix would silently break this.
     */
    @Test
    fun `an operator can read the status`() = testApplication {
        mountTestApp(state)
        seedAdminAndOperator()
        val client = cookieClient()
        val cookie = client.loginCookieHeader("operator")

        val response = jsonClient().get("/api/rest/update/status") {
            header(HttpHeaders.Cookie, cookie)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `every action is admin-only`() = testApplication {
        mountTestApp(state)
        seedAdminAndOperator()
        val client = cookieClient()
        val cookie = client.loginCookieHeader("operator")
        val plain = jsonClient()

        listOf(
            "/api/rest/update/check",
            "/api/rest/update/download",
            "/api/rest/update/download/cancel",
        ).forEach { path ->
            val response = plain.post(path) { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.Forbidden, response.status, "POST $path should be admin-only")
        }

        val settings = plain.put("/api/rest/update/settings") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(UpdateSettingsRequest(autoCheckEnabled = false))
        }
        assertEquals(HttpStatusCode.Forbidden, settings.status)
    }

    @Test
    fun `an unauthenticated caller gets nothing`() = testApplication {
        mountTestApp(state)
        seedAdminAndOperator()

        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().get("/api/rest/update/status").status,
        )
    }

    /**
     * A dev build reports *why* it can't update rather than erroring, and — critically — makes no
     * network call at all. Tests run with `channel = dev`, which is the same gate a hand-built
     * installer hits.
     */
    @Test
    fun `a dev build reports the DEV channel and offers nothing`() = testApplication {
        mountTestApp(state)
        seedAdminAndOperator()
        val client = cookieClient()
        val cookie = client.loginCookieHeader("admin")

        val status: UpdateStatusDto =
            jsonClient().get("/api/rest/update/status") { header(HttpHeaders.Cookie, cookie) }.body()

        assertEquals(UpdateChannelKind.DEV, status.channel)
        assertEquals(uk.me.cormack.lighting7.update.UpdateAvailability.UNKNOWN, status.availability)
        assertTrue(status.latest == null)
    }

    /**
     * A manual check on a DEV build must be a no-op, not an error and not a network call — the
     * gate lives in the service, so the route answering 200 with an unchanged status is the
     * observable proof.
     */
    @Test
    fun `an admin check on a dev build is a quiet no-op`() = testApplication {
        mountTestApp(state)
        seedAdminAndOperator()
        val client = cookieClient()
        val cookie = client.loginCookieHeader("admin")

        val response = jsonClient().post("/api/rest/update/check") { header(HttpHeaders.Cookie, cookie) }

        assertEquals(HttpStatusCode.OK, response.status)
        val status: UpdateStatusDto = response.body()
        assertEquals(UpdateChannelKind.DEV, status.channel)
    }

    @Test
    fun `applying with nothing staged is a 409, not a 500`() = testApplication {
        mountTestApp(state)
        seedAdminAndOperator()
        val client = cookieClient()
        val cookie = client.loginCookieHeader("admin")

        val response = jsonClient().post("/api/rest/update/apply") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(uk.me.cormack.lighting7.update.ApplyUpdateRequest(confirmVersion = "9.9.9"))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    /**
     * `/update` is in `isWarmupExempt`. A desk whose show *failed* to boot is exactly when you
     * most want to be able to install the fix, so the status has to answer with the show down.
     *
     * Mounted over a **second, never-started** State rather than the base class's: `isShowReady`
     * is `showOrNull?.isStarted == true`, so a State whose show was never initialised is the only
     * way to make the warm-up gate genuinely active. Asserting this against the inherited
     * already-started State would pass without testing anything.
     */
    @Test
    fun `the status answers while the show is not ready`() = testApplication {
        // Same SQLite file as the base class's State — the project and the users already exist,
        // so this one only has to differ in the single respect that matters: its show was never
        // initialised, so `isShowReady` is false and the gate is live.
        seedAdminAndOperator()
        val unstarted = State(testAppConfig())
        try {
            assertEquals(false, unstarted.isShowReady, "the gate must actually be closed")

            mountTestApp(unstarted)
            val cookie = cookieClient().loginCookieHeader("admin")
            val plain = jsonClient()

            // The gate is `if (!isShowReady && !isWarmupExempt(path))`, and the assertion above
            // has already established the first half is true — so a 200 here can only mean the
            // path is exempt. (Probing a second, non-exempt route to "prove" the gate is live
            // does not work: an unrouted /api path falls through to the static handler and
            // answers 200 with index.html, which looks like the gate being open.)
            assertEquals(
                HttpStatusCode.OK,
                plain.get("/api/rest/update/status") { header(HttpHeaders.Cookie, cookie) }.status,
            )
        } finally {
            runCatching { unstarted.shutdown() }
        }
    }
}
