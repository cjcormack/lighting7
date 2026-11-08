package uk.me.cormack.lighting7.cloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.cloud.protocol.Capability
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.TokenScope
import uk.me.cormack.lighting7.mcp.publicModule
import uk.me.cormack.lighting7.models.DaoCloudRemoteSessions
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Bearer tokens on the public listener (cloud-p1 plan session 2, D3–D6): a remote session opened in
 * the registry, its requests judged by tokens [FakeCloud] signs, and every way it ends.
 */
class RemoteSessionsTest : RouteIntegrationTest() {

    private val fake = FakeCloud().apply {
        heartbeatS = 1
        remoteOrigin = REMOTE_ORIGIN
    }

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    private var adminId = 0
    private lateinit var installId: String
    private lateinit var secret: String
    private val sessions get() = state.cloud.remoteSessions

    @Before
    fun adopt() {
        adminId = seedUser(state, "admin").userId
        state.cloud.pollDelay = { 20.milliseconds }
        state.cloud.handshakeTimeout = 2.seconds
        state.cloud.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { state.cloud.startAdoption(adminId) }
        eventually("adopted") { state.cloud.state.value.adopted != null }
        installId = state.cloud.state.value.adopted!!.installId
        eventually("online with the cloud's keys") {
            state.cloud.state.value.connection == CloudConnectionStatus.ONLINE && state.cloud.jwks.keys.isNotEmpty()
        }
        state.cloud.setSwitches(remoteAccess = true)
        secret = sessions.newLinkSecret()
    }

    @After
    fun closeFake() {
        runCatching { state.credentialStore.deleteBlob(state.cloud.keys.blobName) }
        fake.close()
    }

    private fun eventually(what: String, check: () -> Boolean) = runBlocking {
        try {
            withTimeout(15.seconds) { while (!check()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun open(sid: String = SID, accountId: String = fake.account.id): RemoteSession =
        (sessions.open(sid, accountId, "$accountId@example.com", "Someone") as OpenResult.Opened).session

    private fun token(sid: String = SID, sub: String = fake.account.id, iat: Long = Instant.now().epochSecond, ttlS: Long = 300) =
        fake.mint(installId, sid = sid, sub = sub, iat = iat, ttlS = ttlS)

    private fun HttpRequestBuilder.bearer(token: String, link: String? = secret, origin: String? = null) {
        header(HttpHeaders.Authorization, "Bearer $token")
        link?.let { header(RemoteSessions.LINK_HEADER, it) }
        origin?.let { header(HttpHeaders.Origin, it) }
    }

    private fun ApplicationTestBuilder.mountPublic() {
        environment { config = testAppConfig() }
        application { publicModule(state) }
    }

    private fun HttpResponse.code(): String? = runBlocking {
        runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
    }

    private suspend fun HttpClient.projects(token: String, link: String? = secret) = get("/api/rest/projects") { bearer(token, link) }

    private suspend fun HttpClient.status(token: String) = get("/api/rest/auth/status") { bearer(token) }

    private fun assertRefused(response: HttpResponse, status: HttpStatusCode, code: String) {
        val body = runBlocking { response.bodyAsText() }
        assertEquals(status, response.status, body)
        assertEquals(code, response.code(), body)
    }

    @Test
    fun `a linked token reads as its user on the public listener, and nowhere else`() {
        open()
        val token = token()
        testApplication {
            mountPublic()
            val http = jsonClient()
            assertEquals(HttpStatusCode.OK, http.projects(token).status)
            val status = Json.parseToJsonElement(http.status(token).bodyAsText()).jsonObject
            assertEquals("true", status["authenticated"]!!.jsonPrimitive.content)
            assertEquals("admin", status["user"]!!.jsonObject["username"]!!.jsonPrimitive.content)

            // Without the sidecar's secret, or with another, the same token is refused.
            assertRefused(http.projects(token, link = null), HttpStatusCode.Unauthorized, RemoteSessions.UNAUTHENTICATED)
            assertRefused(http.projects(token, link = "not-the-secret"), HttpStatusCode.Unauthorized, RemoteSessions.UNAUTHENTICATED)
            // A new launch of the sidecar retires the old secret.
            val old = secret
            secret = sessions.newLinkSecret()
            assertRefused(http.projects(token, link = old), HttpStatusCode.Unauthorized, RemoteSessions.UNAUTHENTICATED)
            assertEquals(HttpStatusCode.OK, http.projects(token).status)
        }
        testApplication {
            mountTestApp(state)
            assertRefused(jsonClient().projects(token), HttpStatusCode.Unauthorized, "unauthenticated")
        }
    }

    @Test
    fun `the WebSocket upgrade takes a linked token`() = testApplication {
        open()
        mountPublic()
        createWsClient().webSocket("/api", request = { bearer(token()) }) {
            assertNotNull(incoming.receive())
            assertNull(closeReason.takeIf { it.isCompleted })
        }
    }

    @Test
    fun `a token for a session the desk never opened is refused, and one naming another account ends its session`() = testApplication {
        mountPublic()
        val http = jsonClient()
        assertRefused(http.projects(token(sid = "never-opened")), HttpStatusCode.Unauthorized, ErrorCode.REMOTE_SESSION_UNKNOWN)

        open()
        assertRefused(http.projects(token(sub = "acct-other")), HttpStatusCode.Unauthorized, ErrorCode.REMOTE_SESSION_MISMATCH)
        assertNull(sessions.find(SID))
        assertRefused(http.projects(token()), HttpStatusCode.Unauthorized, ErrorCode.REMOTE_SESSION_UNKNOWN)

        open("sid-2")
        val otherOrg = fake.mint(installId, sid = "sid-2", orgId = "org-other")
        assertRefused(http.projects(otherOrg), HttpStatusCode.Unauthorized, ErrorCode.REMOTE_SESSION_MISMATCH)
        assertNull(sessions.find("sid-2"))
    }

    @Test
    fun `an unlinked session reaches only its status, linking and its own refresh, and outlives its first token`() = testApplication {
        open(accountId = "acct-unlinked")
        mountPublic()
        val http = jsonClient()
        val now = Instant.now().epochSecond
        val first = token(sub = "acct-unlinked", iat = now - 100, ttlS = 200)

        val status = Json.parseToJsonElement(http.status(first).bodyAsText()).jsonObject
        assertEquals("true", status["unlinked"]!!.jsonPrimitive.content)
        assertEquals("false", status["authenticated"]!!.jsonPrimitive.content)
        assertEquals("acct-unlinked@example.com", status["email"]!!.jsonPrimitive.content)
        val refused = http.projects(first)
        assertRefused(refused, HttpStatusCode.Forbidden, ErrorCode.CLOUD_ACCOUNT_NOT_LINKED)
        assertTrue(refused.bodyAsText().contains("invite"), refused.bodyAsText())
        // The prove-once route is open to it: a body-less request is the route's 400, not the gate's 403.
        assertEquals(HttpStatusCode.BadRequest, http.post("/api/rest/cloud/link/prove") { bearer(first) }.status)

        val deadlineBefore = sessions.find(SID)!!.deadline
        val second = token(sub = "acct-unlinked", iat = now, ttlS = 300)
        assertEquals(HttpStatusCode.NoContent, http.post("/api/rest/cloud/session/token") { bearer(second) }.status)
        assertTrue(sessions.find(SID)!!.deadline > deadlineBefore)

        // The same token again, a retry or a request that beat the refresh, is no mismatch.
        assertEquals(HttpStatusCode.OK, http.status(second).status)
        assertEquals(HttpStatusCode.NoContent, http.post("/api/rest/cloud/session/token") { bearer(second) }.status)
        // A "refresh" back to an older token ends the session.
        assertRefused(http.post("/api/rest/cloud/session/token") { bearer(first) }, HttpStatusCode.Unauthorized, ErrorCode.REMOTE_SESSION_MISMATCH)
        assertNull(sessions.find(SID))
    }

    @Test
    fun `each token refusal names itself`() = testApplication {
        open()
        mountPublic()
        val http = jsonClient()
        val now = Instant.now().epochSecond
        assertRefused(http.projects(token(iat = now - 1000, ttlS = 300)), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_EXPIRED)
        assertRefused(http.projects(token(iat = now + 120)), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_NOT_YET_VALID)
        assertRefused(http.projects(fake.mint("another-install")), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_WRONG_AUDIENCE)
        assertRefused(http.projects(fake.mint(installId, kid = "nobody")), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_UNKNOWN_KEY)
        assertRefused(
            http.projects(fake.mint(installId, key = InstallKeys.generate().private)),
            HttpStatusCode.Unauthorized,
            ErrorCode.TOKEN_BAD_SIGNATURE,
        )
        assertRefused(http.projects(fake.mint(installId, scope = TokenScope.MCP)), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_WRONG_SCOPE)
        assertRefused(http.projects("not.a.token"), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_MALFORMED)

        val beforeRotation = token()
        val oldKid = fake.tokenKid
        fake.rotateKeys()
        eventually("the rotated keys") { state.cloud.jwks.keys.keys == setOf(fake.tokenKid) && fake.tokenKid != oldKid }
        assertRefused(http.projects(beforeRotation), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_UNKNOWN_KEY)
        assertEquals(HttpStatusCode.OK, http.projects(token()).status)
    }

    /**
     * A socket open on a remote session, closed 4401 within 5 s of [end], and the session refused
     * afterwards with [after] (unknown, by default: it can never come back).
     */
    private fun closedOn(
        end: () -> Unit,
        after: Pair<HttpStatusCode, String> = HttpStatusCode.Unauthorized to ErrorCode.REMOTE_SESSION_UNKNOWN,
    ) = testApplication {
        open()
        mountPublic()
        val ws = createWsClient()
        var close: CloseReason? = null
        // Minted before the end, so a revoke stamped after it covers it whatever second the check runs in.
        val stillInDate = token()
        ws.webSocket("/api", request = { bearer(token()) }) {
            incoming.receive()
            end()
            close = withTimeout(5.seconds) { awaitClose() }
        }
        assertEquals(4401.toShort(), close?.code)
        assertNull(sessions.find(SID))
        assertRefused(jsonClient().projects(stillInDate), after.first, after.second)
        assertTrue(sessions.open(SID, fake.account.id, "x", "x") is OpenResult.Refused)
    }

    private suspend fun DefaultClientWebSocketSession.awaitClose(): CloseReason? {
        while (incoming.receiveCatching().isSuccess) continue
        return closeReason.await()
    }

    @Test
    fun `a revoke naming the session closes its socket`() = closedOn({ fake.revoke(installId, sid = SID) })

    @Test
    fun `a revoke naming the account closes its socket`() = closedOn(
        { fake.revoke(installId, accountId = fake.account.id) },
        after = HttpStatusCode.Unauthorized to ErrorCode.TOKEN_REVOKED,
    )

    @Test
    fun `a revoke of everything closes its socket`() = closedOn({ fake.revoke(installId, all = true) }, after = HttpStatusCode.Unauthorized to ErrorCode.TOKEN_REVOKED)

    @Test
    fun `a removed link closes its socket`() = closedOn({ sessions.linkRemoved(fake.account.id) })

    @Test
    fun `disabling the user closes its socket`() {
        val other = seedUser(state, "admin2").userId
        assertTrue(other > 0)
        closedOn({ state.authService.updateUser(adminId, null, null, disabled = true) })
    }

    @Test
    fun `a password set closes its socket`() = closedOn({ runBlocking { state.authService.setPasswordAsAdmin(adminId, "another-password-1") } })

    @Test
    fun `signing out everywhere closes its socket`() = closedOn({ state.authService.revokeAllSessionsFor(adminId) })

    @Test
    fun `turning remote sessions off closes its socket`() =
        closedOn({ state.cloud.setSwitches(remoteAccess = false) }, after = HttpStatusCode.Forbidden to ErrorCode.REMOTE_ACCESS_OFF)

    @Test
    fun `a token that runs out unrefreshed closes its socket`() = testApplication {
        open()
        mountPublic()
        // Expiring two seconds from now, counting the minute of skew the desk allows.
        val now = Instant.now().epochSecond
        val lapsing = token(iat = now - 200, ttlS = 200 - 60 + 2)
        var close: CloseReason? = null
        createWsClient().webSocket("/api", request = { bearer(lapsing) }) {
            incoming.receive()
            close = withTimeout(8.seconds) { awaitClose() }
        }
        assertEquals(4401.toShort(), close?.code)
        assertNull(sessions.find(SID))
    }

    @Test
    fun `sign out everywhere else from a remote session spares that session`() = testApplication {
        open()
        mountPublic()
        val http = jsonClient()
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/rest/auth/sessions") { bearer(token()) }.status)
        assertNotNull(sessions.find(SID))
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/rest/cloud/session") { bearer(token()) }.status)
        assertNull(sessions.find(SID))
    }

    @Test
    fun `a revoke covers a token by the cloud's time, whatever the desk's clock says`() = testApplication {
        // The cloud's clock is 30 s ahead of the desk's: within the skew the desk allows.
        fake.clockOffset = Duration.ofSeconds(30)
        val at = Instant.now().plusSeconds(30)
        open()
        mountPublic()
        val http = jsonClient()
        val mintedBefore = token(iat = at.epochSecond - 1)
        fake.revoke(installId, accountId = fake.account.id, at = at)
        eventually("the revoke") { sessions.find(SID) == null }

        open("sid-2")
        val sameSecond = token(sid = "sid-2", iat = at.epochSecond)
        assertRefused(http.projects(sameSecond), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_REVOKED)
        assertNotNull(mintedBefore)
        // Minted after the revoke, by the cloud's clock: accepted, though the desk's clock is still behind it.
        assertEquals(HttpStatusCode.OK, http.projects(token(sid = "sid-2", iat = at.epochSecond + 1)).status)
    }

    @Test
    fun `a demoted admin's next request reads as an operator`() = testApplication {
        seedUser(state, "admin2")
        open()
        mountPublic()
        val http = jsonClient()
        assertEquals(HttpStatusCode.OK, http.get("/api/rest/users") { bearer(token()) }.status)
        state.authService.updateUser(adminId, null, UserRole.OPERATOR, null)
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/rest/users") { bearer(token()) }.status)
    }

    @Test
    fun `the desk's pages are linked through the shell only while remote sessions are on`() {
        assertEquals("${fake.baseUrl}/r/$installId/prompt-book", state.cloud.remoteShellUrl("/prompt-book"))
        state.cloud.setSwitches(remoteAccess = false)
        assertNull(state.cloud.remoteShellUrl("/prompt-book"))
    }

    @Test
    fun `remote sessions off refuses everything, and the switches change only on the desk itself`() = testApplication {
        open()
        mountPublic()
        val http = jsonClient()
        val put = http.put("/api/rest/install/cloud/remote") {
            bearer(token()); contentType(ContentType.Application.Json); setBody("""{"remoteAccess":false}""")
        }
        assertRefused(put, HttpStatusCode.Forbidden, CloudErrorCodes.SETTINGS_LOCAL_ONLY)
        val policy = http.put("/api/rest/install/cloud/remote") {
            bearer(token()); contentType(ContentType.Application.Json); setBody("""{"allowScripts":true}""")
        }
        assertRefused(policy, HttpStatusCode.Forbidden, CloudErrorCodes.SETTINGS_LOCAL_ONLY)
        assertFalse(state.remotePolicy.allowScripts)
        assertTrue(state.cloud.switches.remoteAccess)

        state.cloud.setSwitches(remoteAccess = false)
        assertNull(sessions.find(SID))
        assertRefused(http.projects(token()), HttpStatusCode.Forbidden, ErrorCode.REMOTE_ACCESS_OFF)
        assertEquals(OpenResult.Refused(ErrorCode.REMOTE_ACCESS_OFF), sessions.open("sid-2", fake.account.id, "x", "x"))
    }

    @Test
    fun `the switches are set from the desk's own listener`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        val put = http.put("/api/rest/install/cloud/remote") {
            header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json); setBody("""{"remoteAccess":false,"mcpAccess":true}""")
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals(RemoteSwitches(remoteAccess = false, mcpAccess = true), state.cloud.switches)
        val got = http.get("/api/rest/install/cloud/remote") { header(HttpHeaders.Cookie, cookie) }
        assertTrue(got.bodyAsText().contains("\"mcpAccess\":true"), got.bodyAsText())
    }

    @Test
    fun `scripts, effects and commands stay refused through a remote session`() = testApplication {
        open()
        mountPublic()
        val http = jsonClient()
        suspend fun post(path: String, body: String) = http.post(path) {
            bearer(token()); contentType(ContentType.Application.Json); setBody(body)
        }
        assertRefused(post("/api/rest/projects/$projectId/scripts/compile", """{"name":"x","script":"println(2)"}"""), HttpStatusCode.Forbidden, "REMOTE_SCRIPTS_DISABLED")
        assertRefused(post("/api/rest/projects/$projectId/effects/arm", """{"on":true}"""), HttpStatusCode.Forbidden, "REMOTE_EFFECTS_DISABLED")
        assertRefused(post("/api/rest/projects/$projectId/commands", """{"role":"RESET"}"""), HttpStatusCode.Forbidden, "REMOTE_COMMANDS_DISABLED")
    }

    @Test
    fun `the cloud's origin is allowed for a remote session's requests, and only for them`() = testApplication {
        open()
        mountPublic()
        val http = jsonClient()
        val foreign = http.delete("/api/rest/cloud/session") { bearer(token(), origin = "https://evil.example") }
        assertRefused(foreign, HttpStatusCode.Forbidden, "REMOTE_ORIGIN_REFUSED")
        assertNotNull(sessions.find(SID))
        val noBearer = http.post("/api/rest/auth/logout") { header(HttpHeaders.Origin, REMOTE_ORIGIN) }
        assertRefused(noBearer, HttpStatusCode.Forbidden, "REMOTE_ORIGIN_REFUSED")
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/rest/cloud/session") { bearer(token(), origin = REMOTE_ORIGIN) }.status)
        assertNull(sessions.find(SID))
    }

    @Test
    fun `one audit row per session, written at its open and its close only`() = testApplication {
        val session = open()
        mountPublic()
        val http = jsonClient()
        fun row() = transaction(state.database) { DaoCloudRemoteSessions.selectAll().single() }
        assertNull(row()[DaoCloudRemoteSessions.endedAt])
        assertEquals(adminId, row()[DaoCloudRemoteSessions.user]?.value)
        val before = totalChanges()
        repeat(3) { assertEquals(HttpStatusCode.OK, http.projects(token()).status) }
        assertEquals(before, totalChanges(), "a request wrote to the database")

        session.path = "DIRECT"
        sessions.end(SID, RemoteSessionEnd.ENDED)
        val closed = row()
        assertNotNull(closed[DaoCloudRemoteSessions.endedAt])
        assertEquals("DIRECT", closed[DaoCloudRemoteSessions.path])
        assertEquals(RemoteSessionEnd.ENDED.name, closed[DaoCloudRemoteSessions.endReason])
    }

    @Test
    fun `a row a restart left open is closed at startup, and a year-old one pruned`() {
        open()
        transaction(state.database) {
            DaoCloudRemoteSessions.insert {
                it[sid] = "ancient"
                it[cloudAccountId] = "acct-old"
                it[email] = "old@example.com"
                it[startedAt] = nowUtc().minus(Duration.ofDays(400))
                it[endedAt] = nowUtc().minus(Duration.ofDays(400))
            }
        }
        RemoteSessions(state.database, state.authService, trust = { null }, keys = { emptyMap() })
        val rows = transaction(state.database) { DaoCloudRemoteSessions.selectAll().toList() }
        val left = rows.single()
        assertEquals(SID, left[DaoCloudRemoteSessions.sid])
        assertEquals(RemoteSessionEnd.DESK_RESTARTED.name, left[DaoCloudRemoteSessions.endReason])
        assertNotNull(left[DaoCloudRemoteSessions.endedAt])
    }

    @Test
    fun `a sid the desk ever audited is refused, after a restart too, and one revoked before it opens never opens`() {
        open()
        sessions.end(SID, RemoteSessionEnd.ENDED)
        val restarted = RemoteSessions(
            state.database,
            state.authService,
            trust = { CloudTrust(installId, fake.org.id, remoteAccess = true) },
            keys = { emptyMap() },
        )
        try {
            assertEquals(OpenResult.Refused(ErrorCode.SESSION_ENDED), restarted.open(SID, fake.account.id, "x", "x"))
        } finally {
            restarted.close()
        }
        sessions.revoke(Message.Revoke(at = uk.me.cormack.lighting7.cloud.protocol.Timestamps.format(Instant.now()), sid = "sid-early"))
        assertEquals(OpenResult.Refused(ErrorCode.SESSION_ENDED), sessions.open("sid-early", fake.account.id, "x", "x"))
    }

    @Test
    fun `a clean stop ends open sessions with the time it stopped`() {
        open()
        state.cloud.close()
        val row = transaction(state.database) { DaoCloudRemoteSessions.selectAll().single() }
        assertEquals(RemoteSessionEnd.DESK_STOPPED.name, row[DaoCloudRemoteSessions.endReason])
        assertNotNull(row[DaoCloudRemoteSessions.endedAt])
    }

    @Test
    fun `revoking more accounts than the deny set holds revokes everything, so it stays bounded`() = testApplication {
        mountPublic()
        val at = uk.me.cormack.lighting7.cloud.protocol.Timestamps.format(Instant.now())
        repeat(RemoteSessions.MAX_DENIALS + 1) { sessions.revoke(Message.Revoke(at = at, accountId = "acct-flood-$it")) }
        open(accountId = "acct-unrelated")
        val minted = token(sub = "acct-unrelated", iat = Instant.now().epochSecond - 1)
        assertRefused(jsonClient().projects(minted), HttpStatusCode.Unauthorized, ErrorCode.TOKEN_REVOKED)
    }

    @Test
    fun `deleting the user ends its session and clears the audit row's user`() {
        seedUser(state, "admin2")
        open()
        state.authService.deleteUser(adminId)
        assertNull(sessions.find(SID))
        val row = transaction(state.database) { DaoCloudRemoteSessions.selectAll().where { DaoCloudRemoteSessions.sid eq SID }.single() }
        assertNull(row[DaoCloudRemoteSessions.user])
        assertEquals(RemoteSessionEnd.CREDENTIALS_REVOKED.name, row[DaoCloudRemoteSessions.endReason])
    }

    @Test
    fun `capabilities follow the switches`() {
        val hello = fake.received.first { it.first == installId && it.second.body is Message.Hello }.second.body as Message.Hello
        assertEquals(listOf(Capability.REMOTE_V1, Capability.MCP_V1, Capability.REMOTE_OFF, Capability.MCP_OFF, Capability.REMOTE_NO_SIDECAR), hello.capabilities)
        fun latest() = fake.received.lastOrNull { it.second.body is Message.Capabilities }?.second?.body as Message.Capabilities?
        eventually("capabilities without remote.off") { latest()?.list == listOf(Capability.REMOTE_V1, Capability.MCP_V1, Capability.MCP_OFF, Capability.REMOTE_NO_SIDECAR) }
        state.cloud.setSwitches(remoteAccess = false)
        eventually("capabilities with remote.off") { latest()?.list == listOf(Capability.REMOTE_V1, Capability.MCP_V1, Capability.REMOTE_OFF, Capability.MCP_OFF, Capability.REMOTE_NO_SIDECAR) }
        state.cloud.setSwitches(mcpAccess = true)
        eventually("capabilities without mcp.off") { latest()?.list == listOf(Capability.REMOTE_V1, Capability.MCP_V1, Capability.REMOTE_OFF, Capability.REMOTE_NO_SIDECAR) }
    }

    @Test
    fun `unadopting ends every session`() {
        open()
        runBlocking { state.cloud.unadopt(force = true) }
        assertNull(sessions.find(SID))
        assertTrue(state.cloud.jwks.keys.isEmpty())
    }

    @Test
    fun `a remote origin the cloud names is kept only as a bare https origin`() {
        assertEquals(REMOTE_ORIGIN, state.cloud.remoteOrigin)
        assertEquals("https://desk.example.com", CloudService.usableOrigin("https://Desk.example.com/"))
        assertNull(CloudService.usableOrigin("https://desk.example.com/path"))
        assertNull(CloudService.usableOrigin("http://desk.example.com"))
        assertNull(CloudService.usableOrigin("javascript:alert(1)"))
    }

    private fun totalChanges(): Long = transaction(state.database) {
        exec("SELECT total_changes()") { rs -> rs.next(); rs.getLong(1) }!!
    }

    companion object {
        const val SID = "sid-1"
        const val REMOTE_ORIGIN = "https://desk-1.remote.example"
    }
}
