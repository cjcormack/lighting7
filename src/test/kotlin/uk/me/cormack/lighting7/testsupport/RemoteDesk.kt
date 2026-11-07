package uk.me.cormack.lighting7.testsupport

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.cloud.CloudConnectionStatus
import uk.me.cormack.lighting7.cloud.OpenResult
import uk.me.cormack.lighting7.cloud.RemoteSessions
import uk.me.cormack.lighting7.mcp.publicModule
import uk.me.cormack.lighting7.state.State
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A desk adopted by [fake] with *Remote sessions* on, and remote sessions on its public listener for
 * any local user — the only way a test signs in there, since the public listener reads no cookie
 * (cloud-p1 plan D18). The test's [State] must be built with `cloud.baseUrl` = [FakeCloud.baseUrl].
 */
class RemoteDesk(private val state: State, val fake: FakeCloud) {
    lateinit var installId: String
        private set
    private lateinit var secret: String
    private var adopterId = 0

    /** Adopt the desk as [adminId], whose cloud account becomes [FakeCloud.account], and wait to be online. */
    fun adopt(adminId: Int) {
        adopterId = adminId
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
        secret = state.cloud.remoteSessions.newLinkSecret()
    }

    private val sids = HashMap<String, String>()
    private var opened = 0

    /**
     * A remote session linked to [username]'s desk account: the adopter's through the adoption's link,
     * anyone else's linked as their prove-once would. One session per name while it stays open, a new
     * one once it has ended (an ended sid is never admitted again); its token is fresh each call.
     */
    fun caller(username: String): RemoteCaller {
        val user = requireNotNull(state.authService.findUserByUsername(username)) { "no user $username" }
        val accountId = if (user.userId == adopterId) fake.account.id else "acct-$username"
        val sid = sids[username]?.takeIf { state.cloud.remoteSessions.find(it) != null } ?: run {
            val sid = "sid-$username-${++opened}"
            val result = state.cloud.remoteSessions.open(sid, accountId, "$username@example.com", username)
            val session = (result as? OpenResult.Opened)?.session ?: error("the desk refused session $sid: $result")
            if (user.userId != adopterId && state.cloud.links.forUser(user.userId).link == null) {
                state.cloud.links.prove(session, user)
            }
            sids[username] = sid
            sid
        }
        return RemoteCaller(fake.mint(installId, sid = sid, sub = accountId), secret)
    }

    /** Stop the fake and forget the install key the adoption stored. */
    fun close() {
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
}

/** A remote session's credentials as the sidecar forwards them: its bearer token and the link secret. */
data class RemoteCaller(val token: String, val secret: String)

/** Send this request as [caller], through the sidecar. */
fun HttpRequestBuilder.remote(caller: RemoteCaller) {
    header(HttpHeaders.Authorization, "Bearer ${caller.token}")
    header(RemoteSessions.LINK_HEADER, caller.secret)
}

/** Mount the public listener's application over [state], as production mounts it on its own port. */
fun ApplicationTestBuilder.mountPublic(state: State) {
    environment { config = testAppConfig() }
    application { publicModule(state) }
}
