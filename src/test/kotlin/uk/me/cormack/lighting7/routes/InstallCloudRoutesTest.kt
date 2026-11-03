package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import uk.me.cormack.lighting7.plugins.CloudStateOutMessage
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.CloudStateDto
import uk.me.cormack.lighting7.mcp.publicModule
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `/install/cloud` (cloud-p0 plan D5, D14) against [FakeCloud]. */
class InstallCloudRoutesTest : RouteIntegrationTest() {

    private val fake = FakeCloud()

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    @After
    fun closeFake() {
        // The test credential store is a file under build/test-data, shared across tests.
        runCatching { state.credentialStore.deleteBlob(state.cloud.keys.blobName) }
        fake.close()
    }

    private fun HttpResponse.code(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    private fun ApplicationTestBuilder.mountPublic() {
        environment { config = testAppConfig() }
        application { publicModule(state) }
    }

    private suspend fun HttpClient.cloud(cookie: String): CloudStateDto =
        get("/api/rest/install/cloud") { header(HttpHeaders.Cookie, cookie) }.body()

    @Test
    fun `an admin adopts the desk from its own listener, and unadopts it`() = testApplication {
        seedUser(state, "admin")
        state.cloud.pollDelay = { 20.milliseconds }
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val before = http.cloud(cookie)
        assertTrue(before.configured)
        assertTrue(before.enabled)
        assertNull(before.adoption)

        val started = http.post("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
        assertEquals(fake.adoption.userCode, started.body<CloudStateDto>().adoption?.userCode)

        val again = http.post("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals(CloudErrorCodes.ADOPTION_IN_PROGRESS, again.code())

        fake.decision = FakeCloud.Decision.APPROVE
        val adopted = withTimeout(10.seconds) {
            var s = http.cloud(cookie)
            while (s.adopted == null) {
                delay(20)
                s = http.cloud(cookie)
            }
            s
        }
        assertEquals("Test Org", adopted.adopted?.orgName)

        val removed = http.delete("/api/rest/install/cloud") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())
        assertNull(removed.body<CloudStateDto>().adopted)
        assertTrue(fake.installs.isEmpty())
    }

    @Test
    fun `cancel answers the state, and unadopt answers when nothing is adopted`() = testApplication {
        seedUser(state, "admin")
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        http.post("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }
        val cancelled = http.delete("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertNull(cancelled.body<CloudStateDto>().adoption)
        assertFalse(state.credentialStore.containsBlob(state.cloud.keys.blobName))

        val none = http.delete("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(CloudErrorCodes.NO_ADOPTION, none.code())
        val notAdopted = http.delete("/api/rest/install/cloud?force=true") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Conflict, notAdopted.status)
        assertEquals(CloudErrorCodes.NOT_ADOPTED, notAdopted.code())
    }

    @Test
    fun `unadopt from an unreachable cloud is 409, and force forgets locally`() = testApplication {
        seedUser(state, "admin")
        state.cloud.pollDelay = { 20.milliseconds }
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        fake.decision = FakeCloud.Decision.APPROVE
        http.post("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }
        withTimeout(10.seconds) { while (http.cloud(cookie).adopted == null) delay(20) }

        fake.refuse = true
        val refused = http.delete("/api/rest/install/cloud") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CloudErrorCodes.UNREACHABLE, refused.code())
        assertNotNull(http.cloud(cookie).adopted)

        val forced = http.delete("/api/rest/install/cloud?force=true") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, forced.status, forced.bodyAsText())
        assertNull(forced.body<CloudStateDto>().adopted)
        assertFalse(state.credentialStore.containsBlob(state.cloud.keys.blobName))
    }

    @Test
    fun `a desk with no accounts cannot be adopted`() = testApplication {
        mountTestApp(state)
        val refused = jsonClient().post("/api/rest/install/cloud/adoption")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CloudErrorCodes.NEEDS_ACCOUNT, refused.code())
        assertTrue(fake.adoptions.isEmpty())
    }

    @Test
    fun `an operator cannot see or change the cloud`() = testApplication {
        seedUser(state, "admin")
        seedUser(state, "op", UserRole.OPERATOR)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("op")
        assertEquals(HttpStatusCode.Forbidden, http.get("/api/rest/install/cloud") { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.Forbidden, http.post("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }.status)
        assertEquals(HttpStatusCode.Forbidden, http.delete("/api/rest/install/cloud") { header(HttpHeaders.Cookie, cookie) }.status)
        assertTrue(fake.adoptions.isEmpty())
    }

    @Test
    fun `adoption is refused on the public listener, and unadopt is not`() = testApplication {
        seedUser(state, "admin")
        mountPublic()
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        val refused = http.post("/api/rest/install/cloud/adoption") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertEquals(CloudErrorCodes.ADOPT_LOCAL_ONLY, refused.code())
        assertTrue(fake.adoptions.isEmpty())

        // Reaches the handler: refused only because nothing is adopted.
        val unadopt = http.delete("/api/rest/install/cloud") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(CloudErrorCodes.NOT_ADOPTED, unadopt.code())
        assertEquals(HttpStatusCode.OK, http.get("/api/rest/install/cloud") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun `a pending adoption's code reaches no one on the public listener`() = testApplication {
        val admin = seedUser(state, "admin")
        runBlocking { state.cloud.startAdoption(admin.userId) }
        assertNotNull(state.cloud.state.value.adoption?.userCode)
        mountPublic()
        val client = createWsClient()
        val cookie = client.loginCookieHeader("admin")

        val remote = client.cloud(cookie).adoption
        assertNotNull(remote, "a remote admin still sees that an adoption is pending")
        assertNull(remote.userCode)
        assertNull(remote.verificationUri)
        assertNull(remote.verificationUriComplete)

        client.webSocket("/api", request = { header(HttpHeaders.Cookie, cookie) }) {
            val frame = awaitOfType<CloudStateOutMessage>().state.adoption
            assertNotNull(frame)
            assertNull(frame.userCode)
            assertNull(frame.verificationUriComplete)
        }
    }

    @Test
    fun `the desk's own listener shows the code on the socket`() = testApplication {
        val admin = seedUser(state, "admin")
        runBlocking { state.cloud.startAdoption(admin.userId) }
        mountTestApp(state)
        val client = createWsClient()
        val cookie = client.loginCookieHeader("admin")
        client.webSocket("/api", request = { header(HttpHeaders.Cookie, cookie) }) {
            assertEquals(fake.adoption.userCode, awaitOfType<CloudStateOutMessage>().state.adoption?.userCode)
        }
    }
}
