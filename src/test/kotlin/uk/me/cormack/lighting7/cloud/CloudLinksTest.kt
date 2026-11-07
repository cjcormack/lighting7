package uk.me.cormack.lighting7.cloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.auth.REMOTE_SIGN_IN_LOCKOUT_FAILURES
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.LinkOutcome
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.mcp.publicModule
import uk.me.cormack.lighting7.models.CloudLinkEventKind
import uk.me.cormack.lighting7.models.CloudLinkVia
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoCloudLinkEvents
import uk.me.cormack.lighting7.models.DaoCloudLinkInvites
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.routes.ProveCloudLinkRequest
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TEST_PASSWORD
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Linking cloud accounts (cloud-p1 plan session 6, D12): invites carried to their acceptance or
 * refusal through [FakeCloud], prove-once through a remote session, unlinking from either side, and
 * the audit row each change writes.
 */
class CloudLinksTest : RouteIntegrationTest() {

    private val fake = FakeCloud().apply { heartbeatS = 1 }

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    private var adminId = 0
    private var operatorId = 0
    private lateinit var installId: String
    private lateinit var secret: String
    private val links get() = state.cloud.links
    private val sessions get() = state.cloud.remoteSessions

    @Before
    fun adopt() {
        adminId = seedUser(state, "admin").userId
        operatorId = seedUser(state, "op", UserRole.OPERATOR).userId
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

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun HttpResponse.code(): String? = runBlocking { runCatching { json(bodyAsText())["code"]?.jsonPrimitive?.content }.getOrNull() }

    private fun assertRefused(response: HttpResponse, status: HttpStatusCode, code: String) {
        val body = runBlocking { response.bodyAsText() }
        assertEquals(status, response.status, body)
        assertEquals(code, response.code(), body)
    }

    private fun events(kind: CloudLinkEventKind) = transaction(state.database) {
        DaoCloudLinkEvents.selectAll().where { DaoCloudLinkEvents.kind eq kind }.toList()
    }

    private fun linkOf(userId: Int) = transaction(state.database) {
        DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.user eq userId }.firstOrNull()
    }

    /** The ack the desk sent replying to the cloud's message [id], once it has. */
    private fun ack(id: String): Message.LinkAck {
        eventually("the link.ack") { fake.replyTo(installId, id) != null }
        return fake.replyTo(installId, id)!!.body as Message.LinkAck
    }

    private fun inviteOperator(email: String = "op@example.com"): Message.LinkInvite {
        val before = fake.sent<Message.LinkInvite>(installId).size
        links.invite(operatorId, email, state.authService.findUser(adminId))
        eventually("the link.invite") { fake.sent<Message.LinkInvite>(installId).size > before }
        return fake.sent<Message.LinkInvite>(installId).last()
    }

    // ─── Invites ──────────────────────────────────────────────────────

    @Test
    fun `an invite sent from Users is accepted in the portal and links the user`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        val created = http.post("/api/rest/users/$operatorId/cloud-invite") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody("""{"email":" Op@Example.com "}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        eventually("the link.invite") { fake.sent<Message.LinkInvite>(installId).isNotEmpty() }
        val invite = fake.sent<Message.LinkInvite>(installId).single()
        assertEquals("Op@Example.com", invite.email)
        assertEquals("op", invite.userName)
        assertEquals("Test admin", invite.invitedByName)
        val listed = json(http.get("/api/rest/users/$operatorId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
        assertEquals(invite.inviteId, listed["cloudInvite"]!!.jsonObject["inviteId"]!!.jsonPrimitive.content)

        val id = fake.acceptInvite(installId, invite.inviteId, "acct-op", "op@example.com")
        assertEquals(Message.LinkAck(invite.inviteId, LinkOutcome.LINKED), ack(id))
        val link = assertNotNull(linkOf(operatorId))
        assertEquals("acct-op", link[DaoCloudIdentityLinks.cloudAccountId])
        assertEquals(CloudLinkVia.INVITE, link[DaoCloudIdentityLinks.linkedVia])
        val after = json(http.get("/api/rest/users/$operatorId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
        assertEquals("INVITE", after["cloudLink"]!!.jsonObject["linkedVia"]!!.jsonPrimitive.content)
        assertTrue(after["cloudInvite"] == null || after["cloudInvite"] is kotlinx.serialization.json.JsonNull)
        assertEquals(listOf(CloudLinkVia.INVITE), events(CloudLinkEventKind.LINKED).mapNotNull { it[DaoCloudLinkEvents.via] }.filter { it != CloudLinkVia.ADOPTION })
        assertEquals(1, events(CloudLinkEventKind.INVITED).size)

        // The cloud never heard the ack and sends the acceptance again: still linked.
        assertEquals(LinkOutcome.LINKED, ack(fake.acceptInvite(installId, invite.inviteId, "acct-op", "op@example.com")).outcome)
    }

    @Test
    fun `an invite declined in the portal is gone`() {
        val invite = inviteOperator()
        fake.declineInvite(installId, invite.inviteId)
        eventually("the invite removed") { links.forUser(operatorId).invite == null }
        assertEquals(1, events(CloudLinkEventKind.INVITE_REFUSED).size)
        val ack = ack(fake.acceptInvite(installId, invite.inviteId, "acct-op", "op@example.com"))
        assertEquals(Message.LinkAck(invite.inviteId, LinkOutcome.REFUSED, ErrorCode.LINK_INVITE_NOT_LIVE), ack)
        assertNull(linkOf(operatorId))
    }

    @Test
    fun `an expired invite is refused and pruned`() {
        val invite = inviteOperator()
        transaction(state.database) {
            DaoCloudLinkInvites.update({ DaoCloudLinkInvites.inviteId eq invite.inviteId }) { it[expiresAt] = nowUtc().minusSeconds(1) }
        }
        assertNull(links.forUser(operatorId).invite)
        val ack = ack(fake.acceptInvite(installId, invite.inviteId, "acct-op", "op@example.com"))
        assertEquals(ErrorCode.LINK_INVITE_NOT_LIVE, ack.reason)
        assertNull(linkOf(operatorId))
        assertEquals(1, events(CloudLinkEventKind.INVITE_EXPIRED).size)
        assertEquals(0, transaction(state.database) { DaoCloudLinkInvites.selectAll().count() })
    }

    @Test
    fun `a cancelled invite tells the cloud, and its acceptance is refused`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        val invite = inviteOperator()
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/rest/users/$operatorId/cloud-invite") { header(HttpHeaders.Cookie, cookie) }.status)
        eventually("the link.cancelled") { fake.sent<Message.LinkCancelled>(installId).isNotEmpty() }
        assertEquals(invite.inviteId, fake.sent<Message.LinkCancelled>(installId).single().inviteId)
        assertRefused(http.delete("/api/rest/users/$operatorId/cloud-invite") { header(HttpHeaders.Cookie, cookie) }, HttpStatusCode.NotFound, CloudErrorCodes.NO_INVITE)

        val ack = ack(fake.acceptInvite(installId, invite.inviteId, "acct-op", "op@example.com"))
        assertEquals(Message.LinkAck(invite.inviteId, LinkOutcome.REFUSED, ErrorCode.LINK_INVITE_NOT_LIVE), ack)
        assertEquals(1, events(CloudLinkEventKind.INVITE_CANCELLED).size)
        assertEquals(1, events(CloudLinkEventKind.ACCEPTANCE_REFUSED).size)
    }

    @Test
    fun `an acceptance by another email is refused and the invite kept`() {
        val invite = inviteOperator()
        val ack = ack(fake.acceptInvite(installId, invite.inviteId, "acct-op", "someone-else@example.com"))
        assertEquals(Message.LinkAck(invite.inviteId, LinkOutcome.REFUSED, ErrorCode.LINK_EMAIL_MISMATCH), ack)
        assertNull(linkOf(operatorId))
        assertNotNull(links.forUser(operatorId).invite)
        assertEquals(ErrorCode.LINK_EMAIL_MISMATCH, events(CloudLinkEventKind.ACCEPTANCE_REFUSED).single()[DaoCloudLinkEvents.reason])
    }

    @Test
    fun `an acceptance heard after a reconnect links the user`() {
        val invite = inviteOperator()
        val attempts = fake.channelAttempts.get()
        fake.dropChannel()
        eventually("back online") {
            fake.channelAttempts.get() > attempts && fake.online.contains(installId) && state.cloud.state.value.connection == CloudConnectionStatus.ONLINE
        }
        val ack = ack(fake.acceptInvite(installId, invite.inviteId, "acct-op", "op@example.com"))
        assertEquals(LinkOutcome.LINKED, ack.outcome)
        assertNotNull(linkOf(operatorId))
    }

    @Test
    fun `an invite is refused while offline, for a linked user, and twice`() {
        assertEquals(
            CloudErrorCodes.LINK_EXISTS,
            runCatching { links.invite(adminId, "admin@example.com", null) }.exceptionOrNull().let { (it as CloudException).code },
        )
        assertEquals(CloudErrorCodes.EMAIL_INVALID, (runCatching { links.invite(operatorId, "not-an-email", null) }.exceptionOrNull() as CloudException).code)
        inviteOperator()
        assertEquals(CloudErrorCodes.INVITE_PENDING, (runCatching { links.invite(operatorId, "other@example.com", null) }.exceptionOrNull() as CloudException).code)
        val third = seedUser(state, "third", UserRole.OPERATOR).userId
        assertEquals(CloudErrorCodes.INVITE_PENDING, (runCatching { links.invite(third, "OP@example.com", null) }.exceptionOrNull() as CloudException).code)

        fake.channelStall = true
        fake.dropChannel()
        eventually("offline") { state.cloud.state.value.connection != CloudConnectionStatus.ONLINE }
        assertEquals(CloudErrorCodes.OFFLINE, (runCatching { links.invite(third, "third@example.com", null) }.exceptionOrNull() as CloudException).code)
        assertNull(links.forUser(third).invite)
    }

    @Test
    fun `an account already linked to another user cannot accept`() {
        // The adopting admin's account, acct-1, is linked to admin already.
        val invite = inviteOperator(fake.account.email)
        val ack = ack(fake.acceptInvite(installId, invite.inviteId, fake.account.id, fake.account.email))
        assertEquals(Message.LinkAck(invite.inviteId, LinkOutcome.REFUSED, ErrorCode.LINK_TAKEN), ack)
        assertNull(linkOf(operatorId))
        assertEquals(adminId, transaction(state.database) {
            DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.cloudAccountId eq fake.account.id }.single()[DaoCloudIdentityLinks.user].value
        })
    }

    // ─── Prove once ───────────────────────────────────────────────────

    private fun open(sid: String, accountId: String): RemoteSession =
        (sessions.open(sid, accountId, "$accountId@example.com", "Someone") as OpenResult.Opened).session

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(RemoteSessions.LINK_HEADER, secret)
    }

    private fun ApplicationTestBuilder.mountPublic() {
        environment { config = testAppConfig() }
        application { publicModule(state) }
    }

    private suspend fun HttpClient.prove(token: String, username: String, password: String = TEST_PASSWORD) =
        post("/api/rest/cloud/link/prove") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ProveCloudLinkRequest.serializer(), ProveCloudLinkRequest(username, password)))
        }

    @Test
    fun `an unlinked session proves once and carries on as the user`() = testApplication {
        open("sid-prove", "acct-op")
        mountPublic()
        val http = jsonClient()
        val token = fake.mint(installId, sid = "sid-prove", sub = "acct-op")
        assertRefused(http.get("/api/rest/projects") { bearer(token) }, HttpStatusCode.Forbidden, ErrorCode.CLOUD_ACCOUNT_NOT_LINKED)

        val proved = http.prove(token, "op")
        assertEquals(HttpStatusCode.OK, proved.status, proved.bodyAsText())
        assertEquals("op", json(proved.bodyAsText())["user"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, http.get("/api/rest/projects") { bearer(token) }.status)
        val status = json(http.get("/api/rest/auth/status") { bearer(token) }.bodyAsText())
        assertEquals("OPERATOR", status["user"]!!.jsonObject["role"]!!.jsonPrimitive.content)

        eventually("the link.created") { fake.sent<Message.LinkCreated>(installId).isNotEmpty() }
        assertEquals(Message.LinkCreated("acct-op", "op"), fake.sent<Message.LinkCreated>(installId).single())
        assertEquals(CloudLinkVia.PROVED, linkOf(operatorId)!![DaoCloudIdentityLinks.linkedVia])
        assertEquals(operatorId, sessions.find("sid-prove")!!.userId)

        // A linked session cannot prove again.
        assertRefused(http.prove(token, "op"), HttpStatusCode.Conflict, CloudErrorCodes.LINK_EXISTS)
    }

    @Test
    fun `a wrong password is refused and counted, and a locked-out username refused outright`() = testApplication {
        open("sid-wrong", "acct-op")
        mountPublic()
        val http = jsonClient()
        val token = fake.mint(installId, sid = "sid-wrong", sub = "acct-op")
        assertEquals(HttpStatusCode.Unauthorized, http.prove(token, "op", "wrong-password").status)
        assertNull(linkOf(operatorId))
        // Still the same unlinked session: a wrong password ends nothing.
        assertNotNull(sessions.find("sid-wrong"))

        repeat(REMOTE_SIGN_IN_LOCKOUT_FAILURES) { state.authService.recordRemoteSignInFailure("op") }
        assertRefused(http.prove(token, "op"), HttpStatusCode.TooManyRequests, "SIGN_IN_LOCKED")
        assertNull(linkOf(operatorId))
    }

    @Test
    fun `a user linked to one account cannot be proved by another`() = testApplication {
        open("sid-other", "acct-other")
        mountPublic()
        val http = jsonClient()
        val token = fake.mint(installId, sid = "sid-other", sub = "acct-other")
        assertRefused(http.prove(token, "admin"), HttpStatusCode.Conflict, CloudErrorCodes.LINK_EXISTS)
        assertEquals(fake.account.id, linkOf(adminId)!![DaoCloudIdentityLinks.cloudAccountId])
        assertNull(sessions.find("sid-other")!!.userId)
    }

    @Test
    fun `proving cancels the user's invite`() = testApplication {
        val invite = inviteOperator()
        open("sid-inv", "acct-op")
        mountPublic()
        assertEquals(HttpStatusCode.OK, jsonClient().prove(fake.mint(installId, sid = "sid-inv", sub = "acct-op"), "op").status)
        eventually("the link.cancelled") { fake.sent<Message.LinkCancelled>(installId).isNotEmpty() }
        assertEquals(invite.inviteId, fake.sent<Message.LinkCancelled>(installId).single().inviteId)
        assertNull(links.forUser(operatorId).invite)
    }

    // ─── Unlinking ────────────────────────────────────────────────────

    /** A socket on a session linked to [userId] through [accountId], closed 4401 within 5 s of [unlink]. */
    private fun closedOnUnlink(userId: Int, accountId: String, unlink: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        if (linkOf(userId) == null) links.prove(open("sid-pre", accountId).also { sessions.end(it.sid, RemoteSessionEnd.ENDED) }, state.authService.findUser(userId)!!)
        open("sid-ws", accountId)
        mountPublic()
        var close: CloseReason? = null
        createWsClient().webSocket("/api", request = { bearer(fake.mint(installId, sid = "sid-ws", sub = accountId)) }) {
            incoming.receive()
            unlink()
            close = withTimeout(5.seconds) {
                while (incoming.receiveCatching().isSuccess) continue
                closeReason.await()
            }
        }
        assertEquals(4401.toShort(), close?.code)
        assertNull(sessions.find("sid-ws"))
        assertNull(linkOf(userId))
        eventually("the link.removed") { fake.sent<Message.LinkRemoved>(installId).any { it.accountId == accountId } }
    }

    @Test
    fun `an admin's unlink closes the user's sessions and tells the cloud`() = closedOnUnlink(operatorId, "acct-op") {
        assertTrue(links.unlink(operatorId, uk.me.cormack.lighting7.models.CloudUnlinkReason.BY_ADMIN, state.authService.findUser(adminId)))
        assertEquals("BY_ADMIN", events(CloudLinkEventKind.UNLINKED).single()[DaoCloudLinkEvents.reason])
    }

    @Test
    fun `the users route unlinks`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        links.prove(open("sid-r", "acct-op"), state.authService.findUser(operatorId)!!)
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/rest/users/$operatorId/cloud-link") { header(HttpHeaders.Cookie, cookie) }.status)
        assertNull(sessions.find("sid-r"))
        assertRefused(http.delete("/api/rest/users/$operatorId/cloud-link") { header(HttpHeaders.Cookie, cookie) }, HttpStatusCode.NotFound, CloudErrorCodes.NO_LINK)
        val users = Json.parseToJsonElement(http.get("/api/rest/users") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()).jsonArray
        assertEquals("admin@example.com", users.map { it.jsonObject }.single { it["username"]!!.jsonPrimitive.content == "admin" }["cloudLink"]!!.jsonObject["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a user's own unlink from their profile`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        links.prove(open("sid-self", "acct-op"), state.authService.findUser(operatorId)!!)
        val cookie = http.loginCookieHeader("op")
        val own = json(http.get("/api/rest/cloud/link") { header(HttpHeaders.Cookie, cookie) }.bodyAsText())
        assertEquals("acct-op@example.com", own["link"]!!.jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NoContent, http.delete("/api/rest/cloud/link") { header(HttpHeaders.Cookie, cookie) }.status)
        assertNull(sessions.find("sid-self"))
        assertEquals("BY_USER", events(CloudLinkEventKind.UNLINKED).single()[DaoCloudLinkEvents.reason])
        eventually("the link.removed") { fake.sent<Message.LinkRemoved>(installId).any { it.accountId == "acct-op" } }
    }

    @Test
    fun `deleting a user closes its sessions and tells the cloud of its link and invite`() {
        val third = seedUser(state, "third", UserRole.OPERATOR).userId
        val invite = links.invite(third, "third@example.com", null)
        closedOnUnlink(operatorId, "acct-op") {
            assertTrue(state.authService.deleteUser(operatorId) is uk.me.cormack.lighting7.auth.UserMutation.Done)
        }
        assertEquals("USER_DELETED", events(CloudLinkEventKind.UNLINKED).single()[DaoCloudLinkEvents.reason])
        assertTrue(state.authService.deleteUser(third) is uk.me.cormack.lighting7.auth.UserMutation.Done)
        eventually("the link.cancelled") { fake.sent<Message.LinkCancelled>(installId).any { it.inviteId == invite.inviteId } }
        assertEquals(0, transaction(state.database) { DaoCloudLinkInvites.selectAll().count() })
        // The audit outlives the user, by name.
        assertTrue(events(CloudLinkEventKind.UNLINKED).all { it[DaoCloudLinkEvents.user] == null && it[DaoCloudLinkEvents.username] == "op" })
    }

    @Test
    fun `an unlink while the channel is down reaches the cloud at the next connection`() {
        links.prove(open("sid-q", "acct-op"), state.authService.findUser(operatorId)!!)
        fake.channelStall = true
        fake.dropChannel()
        eventually("offline") { state.cloud.state.value.connection != CloudConnectionStatus.ONLINE }
        assertTrue(links.unlink(operatorId, uk.me.cormack.lighting7.models.CloudUnlinkReason.BY_ADMIN, null))
        fake.releaseChannel()
        eventually("the link.removed after reconnecting") { fake.sent<Message.LinkRemoved>(installId).any { it.accountId == "acct-op" } }
    }

    @Test
    fun `adoption's link and unadoption's clear are audited`() {
        assertEquals(CloudLinkVia.ADOPTION, events(CloudLinkEventKind.LINKED).single()[DaoCloudLinkEvents.via])
        inviteOperator()
        runBlocking { state.cloud.unadopt(force = true) }
        assertEquals("UNADOPTED", events(CloudLinkEventKind.UNLINKED).single()[DaoCloudLinkEvents.reason])
        assertEquals("UNADOPTED", events(CloudLinkEventKind.INVITE_CANCELLED).single()[DaoCloudLinkEvents.reason])
        assertEquals(0, transaction(state.database) { DaoCloudLinkInvites.selectAll().count() })
    }

    @Test
    fun `an invite past its expiry is pruned at startup`() {
        inviteOperator()
        transaction(state.database) { DaoCloudLinkInvites.update { it[expiresAt] = Instant.now().minusSeconds(5) } }
        CloudLinks(state.database, null, sessions, adopted = { true }, send = { _, _ -> true })
        assertEquals(0, transaction(state.database) { DaoCloudLinkInvites.selectAll().count() })
        assertEquals(1, events(CloudLinkEventKind.INVITE_EXPIRED).size)
    }

    // ─── Races and ordering ───────────────────────────────────────────

    /** A [CloudLinks] over this desk whose sends go to [sent] while [up] says so. */
    private class Probe(val sent: MutableList<Message> = mutableListOf(), var up: Boolean = true)

    private fun probeLinks(probe: Probe, adopted: Boolean = true) = CloudLinks(
        state.database, state.authService, sessions, adopted = { adopted },
        send = { message, _ -> if (probe.up) { probe.sent += message; true } else false },
    )

    @Test
    fun `a prove or invite for a user deleted meanwhile writes nothing`() {
        val gone = state.authService.findUser(operatorId)!!
        val session = open("sid-gone", "acct-gone")
        assertTrue(state.authService.deleteUser(operatorId) is uk.me.cormack.lighting7.auth.UserMutation.Done)
        val probe = Probe()
        val probeLinks = probeLinks(probe)
        assertEquals(CloudErrorCodes.NO_USER, (runCatching { probeLinks.prove(session, gone) }.exceptionOrNull() as CloudException).code)
        assertNull(linkOf(operatorId))
        assertTrue(probe.sent.isEmpty())
    }

    @Test
    fun `a prove or invite after unadoption writes nothing, whatever the caller last saw`() {
        val op = state.authService.findUser(operatorId)!!
        val session = open("sid-late", "acct-late")
        runBlocking { state.cloud.unadopt(force = true) }
        // Built claiming adoption, as a caller that checked just before the unadopt committed would.
        val probe = Probe()
        val stale = probeLinks(probe, adopted = true)
        assertEquals(CloudErrorCodes.NOT_ADOPTED, (runCatching { stale.prove(session, op) }.exceptionOrNull() as CloudException).code)
        assertEquals(CloudErrorCodes.NOT_ADOPTED, (runCatching { stale.invite(operatorId, "op@example.com", null) }.exceptionOrNull() as CloudException).code)
        assertEquals(0, transaction(state.database) { DaoCloudIdentityLinks.selectAll().count() + DaoCloudLinkInvites.selectAll().count() })
    }

    @Test
    fun `an invite that cannot be sent leaves no invite and no audit row`() {
        val probe = Probe(up = false)
        assertEquals(CloudErrorCodes.OFFLINE, (runCatching { probeLinks(probe).invite(operatorId, "op@example.com", null) }.exceptionOrNull() as CloudException).code)
        assertNull(links.forUser(operatorId).invite)
        assertEquals(0, events(CloudLinkEventKind.INVITED).size)
    }

    @Test
    fun `an invite goes after the messages already waiting, never before them`() {
        val probe = Probe()
        val probeLinks = probeLinks(probe)
        val first = probeLinks.invite(operatorId, "op@example.com", null)
        probe.up = false
        assertTrue(probeLinks.cancelInvite(operatorId, null))
        probe.up = true
        probeLinks.invite(operatorId, "op@example.com", null)
        assertEquals(
            listOf("link.invite", "link.cancelled", "link.invite"),
            probe.sent.map { it.type },
        )
        assertEquals(first.inviteId, (probe.sent[1] as Message.LinkCancelled).inviteId)
    }

    @Test
    fun `a refused acceptance that removed the invite tells Users`() {
        val invite = inviteOperator(fake.account.email)
        val changes = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { state.authService.userChanges.collect { changes += it } }
        try {
            runBlocking { delay(100) }
            ack(fake.acceptInvite(installId, invite.inviteId, fake.account.id, fake.account.email))
            eventually("a user change for the operator") { operatorId in changes }
        } finally {
            job.cancel()
        }
    }
}
