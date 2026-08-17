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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.auth.ResetTokenHistoryEntry
import uk.me.cormack.lighting7.auth.ResetTokenStatus
import uk.me.cormack.lighting7.models.DaoPasswordResetToken
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The QR reset flow end to end: an admin mints a token, the phone reads it and redeems it
 * without a cookie, and everything that was signed in as that user stops working.
 *
 * The URL assertions matter as much as the status codes — a QR encoding `localhost` would
 * resolve to the *phone*, which is the one failure this flow can't recover from.
 */
class PasswordResetRoutesTest : RouteIntegrationTest() {

    /** Mint a token as `boss` and return the raw token from the redemption URL. */
    private fun ResetTokenResponse.rawToken(): String = url.substringAfterLast("/reset/")

    @Test
    fun `mint then redeem sets a new password, kills sessions, and burns the token`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")
        val victim = client.loginCookieHeader("op")

        val minted = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }
        assertEquals(HttpStatusCode.Created, minted.status, minted.bodyAsText())
        val token = minted.body<ResetTokenResponse>()
        assertEquals("op", token.username)
        assertTrue(token.expiresAtMs > System.currentTimeMillis(), "a fresh token must be in the future")

        // The phone has no cookie — that's the whole point of the exempt path.
        val info = client.get("/api/rest/auth/reset/${token.rawToken()}")
        assertEquals(HttpStatusCode.OK, info.status, info.bodyAsText())
        assertEquals("Test op", info.body<ResetTokenInfoDto>().displayName)

        val poll = client.get("/api/rest/users/${operator.userId}/reset-tokens/${token.id}") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenStatusDto>()
        assertEquals(ResetTokenStatus.PENDING, poll.status)

        val redeem = client.post("/api/rest/auth/reset/${token.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("phone-set-password"))
        }
        assertEquals(HttpStatusCode.NoContent, redeem.status, redeem.bodyAsText())

        // The admin's sheet learns about it by polling.
        val after = client.get("/api/rest/users/${operator.userId}/reset-tokens/${token.id}") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenStatusDto>()
        assertEquals(ResetTokenStatus.USED, after.status)

        // Their old session is gone and the old password no longer works...
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/rest/project/list") { header(HttpHeaders.Cookie, victim) }.status,
        )
        val oldPassword = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("op", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldPassword.status)

        // ...and the new one does.
        val newPassword = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("op", "phone-set-password"))
        }
        assertEquals(HttpStatusCode.OK, newPassword.status, newPassword.bodyAsText())

        // Single use: the same link cannot be replayed.
        val replay = client.post("/api/rest/auth/reset/${token.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("another-password"))
        }
        assertEquals(HttpStatusCode.Gone, replay.status)
        assertEquals(ResetTokenStatus.USED.name, replay.body<ErrorResponse>().code)
    }

    @Test
    fun `an unknown token answers 404 on both verbs`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss")
        val client = jsonClient()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/rest/auth/reset/not-a-real-token").status)
        val redeem = client.post("/api/rest/auth/reset/not-a-real-token") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("a-password"))
        }
        assertEquals(HttpStatusCode.NotFound, redeem.status)
    }

    @Test
    fun `an expired token answers 410`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val token = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()

        // Age the row rather than the clock: `State` builds its own AuthService, so there
        // is no injectable clock to advance from a route test.
        expireResetToken(token.id)

        val info = client.get("/api/rest/auth/reset/${token.rawToken()}")
        assertEquals(HttpStatusCode.Gone, info.status)
        assertEquals(ResetTokenStatus.EXPIRED.name, info.body<ErrorResponse>().code)

        val redeem = client.post("/api/rest/auth/reset/${token.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("a-new-password"))
        }
        assertEquals(HttpStatusCode.Gone, redeem.status)

        // The password must be untouched — an expired link is not a password change.
        val login = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("op", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `cancelling from the admin sheet kills the link`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val token = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()

        val cancel = client.delete("/api/rest/users/${operator.userId}/reset-tokens/${token.id}") {
            header(HttpHeaders.Cookie, admin)
        }
        assertEquals(HttpStatusCode.NoContent, cancel.status)

        val info = client.get("/api/rest/auth/reset/${token.rawToken()}")
        assertEquals(HttpStatusCode.Gone, info.status)
        assertEquals(ResetTokenStatus.CANCELLED.name, info.body<ErrorResponse>().code)
    }

    @Test
    fun `minting a second token cancels the first`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val first = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()
        val second = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()

        val stale = client.get("/api/rest/auth/reset/${first.rawToken()}")
        assertEquals(HttpStatusCode.Gone, stale.status, "a photographed QR must stop working when a new one is made")
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/auth/reset/${second.rawToken()}").status)
    }

    @Test
    fun `a rejected password leaves the token redeemable`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val token = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()

        val tooShort = client.post("/api/rest/auth/reset/${token.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("short"))
        }
        assertEquals(HttpStatusCode.BadRequest, tooShort.status)

        // A typo on the phone must not burn the link — the user has one QR, not two.
        val retry = client.post("/api/rest/auth/reset/${token.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("a-long-enough-password"))
        }
        assertEquals(HttpStatusCode.NoContent, retry.status, retry.bodyAsText())
    }

    /**
     * The dangerous ordering: a link handed out *before* the admin changed their mind.
     * Because redemption re-enables the account, an uncancelled link would let a
     * just-disabled operator walk back in with a password of their own choosing.
     */
    @Test
    fun `disabling a user kills the reset link they already hold`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val token = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()

        val disable = client.put("/api/rest/users/${operator.userId}") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(disabled = true))
        }
        assertEquals(HttpStatusCode.OK, disable.status, disable.bodyAsText())

        val redeem = client.post("/api/rest/auth/reset/${token.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("let-me-back-in"))
        }
        assertEquals(HttpStatusCode.Gone, redeem.status)
        assertEquals(ResetTokenStatus.CANCELLED.name, redeem.body<ErrorResponse>().code)
        assertTrue(assertNotNull(state.authService.findUser(operator.userId)).disabled, "still disabled")
    }

    /** Same rule the other way round: a password just set by hand retires the old link. */
    @Test
    fun `setting a password directly kills the reset link`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val token = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()

        val set = client.put("/api/rest/users/${operator.userId}/password") {
            header(HttpHeaders.Cookie, admin)
            contentType(ContentType.Application.Json)
            setBody(SetUserPasswordRequest("admin-chose-this"))
        }
        assertEquals(HttpStatusCode.NoContent, set.status)

        val stale = client.get("/api/rest/auth/reset/${token.rawToken()}")
        assertEquals(HttpStatusCode.Gone, stale.status, "the holder must not be able to overwrite it")
    }

    @Test
    fun `redeeming re-enables a disabled account`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")
        state.authService.setUserDisabled(operator.userId, true)

        val token = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()
        val redeem = client.post("/api/rest/auth/reset/${token.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("back-in-business"))
        }
        assertEquals(HttpStatusCode.NoContent, redeem.status)

        // Handing an account back means it can sign in, not that it 403s on the new password.
        val login = client.post("/api/rest/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("op", "back-in-business"))
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
    }

    @Test
    fun `the minted URL never points at loopback when the LAN has an address`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        // `testApplication` sends `Host: localhost`, so this exercises exactly the
        // fallback path an admin browsing the desk's own screen hits (Decision 12).
        val token = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()

        val everyUrl = listOf(token.url) + token.alternateUrls
        assertTrue(everyUrl.all { it.contains("/reset/") }, "every URL must be a redemption link")
        assertTrue(everyUrl.any { it.contains(".local:") }, "the mDNS name is always offered: $everyUrl")
        if (MdnsService.pickLanAddresses().isNotEmpty()) {
            // With a LAN address available, the loopback Host header must lose to the
            // mDNS name — a QR pointing at loopback would resolve to the phone itself.
            assertTrue(token.url.contains(".local:"), "expected the mDNS fallback, got ${token.url}")
            assertFalse(
                everyUrl.any { it.contains("localhost") || it.contains("127.") },
                "no offered URL may be loopback: $everyUrl",
            )
        }
    }

    /**
     * `FU-AUTH-RESET-TOKEN-HISTORY`. Closing the sheet no longer cancels the link it was
     * showing, so the history list is what keeps a live one visible and revocable. Every
     * status has to be distinguishable, or "there is a live link right now" — the question the
     * list exists to answer — can't be read off it.
     */
    @Test
    fun `reset history lists every status and never leaks a token`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        fun mint(): ResetTokenResponse = runBlocking {
            client.post("/api/rest/users/${operator.userId}/reset-tokens") {
                header(HttpHeaders.Cookie, admin)
            }.body()
        }
        suspend fun history(): List<ResetTokenHistoryEntry> =
            client.get("/api/rest/users/${operator.userId}/reset-tokens") {
                header(HttpHeaders.Cookie, admin)
            }.body()

        assertEquals(emptyList(), history(), "a user who never had a link has no history")

        // Minting cancels the outstanding token: one live link per account is the standing
        // rule, so a superseded row is the ordinary way a CANCELLED one appears.
        val superseded = mint()
        val used = mint()
        client.post("/api/rest/auth/reset/${used.rawToken()}") {
            contentType(ContentType.Application.Json)
            setBody(RedeemResetRequest("a-new-password"))
        }
        val live = mint()

        val rows = history()
        assertEquals(listOf(live.id, used.id, superseded.id), rows.map { it.id }, "newest first")
        assertEquals(
            mapOf(
                live.id to ResetTokenStatus.PENDING,
                used.id to ResetTokenStatus.USED,
                superseded.id to ResetTokenStatus.CANCELLED,
            ),
            rows.associate { it.id to it.status },
        )
        assertEquals(
            listOf("Test boss"),
            rows.map { it.createdByDisplayName }.distinct(),
            "the minting admin is named, so a history row says who to ask about it",
        )

        // The list revokes links; it must never be a way to reissue one. Assert on the raw
        // body, because a token leaking through an unexpected field would still deserialise.
        val raw = client.get("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.bodyAsText()
        assertFalse(raw.contains(live.rawToken()), "no raw token may appear in the history")
        assertFalse(raw.contains("tokenHash"), "no hash either: $raw")
        // EXPIRED has to be reached by ageing the *newest* token, not an earlier one: minting
        // over a token cancels it, and `statusAt` reports CANCELLED ahead of EXPIRED. That
        // ordering is deliberate — "an admin revoked this" outranks "it timed out" — so a
        // superseded-then-expired row can never read as expired.
        expireResetToken(live.id)
        assertEquals(
            ResetTokenStatus.EXPIRED,
            assertNotNull(history().firstOrNull { it.id == live.id }).status,
        )

        val unknown = client.get("/api/rest/users/9999/reset-tokens") { header(HttpHeaders.Cookie, admin) }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }

    /**
     * The prune that makes the history durable. It used to delete every non-PENDING row at
     * startup, so history could only ever describe the current uptime; it now ages rows out
     * of a 30-day window instead. Driven through a second [AuthService] over the same
     * database, which is what a restart looks like from the table's point of view.
     */
    @Test
    fun `spent reset tokens survive a restart and age out after the retention window`() = testApplication {
        mountTestApp(state)
        seedUser(state, "boss", role = UserRole.ADMIN)
        val operator = seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val admin = client.loginCookieHeader("boss")

        val first = client.post("/api/rest/users/${operator.userId}/reset-tokens") {
            header(HttpHeaders.Cookie, admin)
        }.body<ResetTokenResponse>()
        // Cancelled — under the old prune this row would not have survived the next boot.
        client.delete("/api/rest/users/${operator.userId}/reset-tokens/${first.id}") {
            header(HttpHeaders.Cookie, admin)
        }

        val rebooted = AuthService(state.database, bcryptCost = 4)
        assertEquals(
            listOf(first.id),
            rebooted.resetTokenHistory(operator.userId).map { it.id },
            "a spent row must outlive the process that made it",
        )

        // ...but not forever. A clock 31 days ahead is a boot 31 days later.
        val muchLater = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000
        val aged = AuthService(state.database, bcryptCost = 4, clock = { muchLater })
        assertEquals(
            emptyList(),
            aged.resetTokenHistory(operator.userId),
            "past the retention window the row is gone",
        )
    }

    /** Push a token's expiry into the past so the expired-token paths can be exercised. */
    private fun expireResetToken(tokenId: Int) {
        transaction(state.database) {
            DaoPasswordResetToken.findById(tokenId)!!.expiresAtMs = System.currentTimeMillis() - 1_000
        }
    }
}
