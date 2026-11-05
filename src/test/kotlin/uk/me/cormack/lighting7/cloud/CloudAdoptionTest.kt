package uk.me.cormack.lighting7.cloud

import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.cloud.protocol.AdoptionPollPayload
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.SignatureHeaders
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.models.CloudLinkVia
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Adoption and unadoption against [FakeCloud] (cloud-p0 plan session 4). */
class CloudAdoptionTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private lateinit var state: State
    private lateinit var fake: FakeCloud
    private val store = InMemoryCredentialStore()
    private val services = mutableListOf<CloudService>()
    private var adminId = 0
    private lateinit var keyName: String

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        fake = FakeCloud()
        adminId = seedUser(state, "admin").userId
        keyName = InstallKeyStore.blobNameFor(
            transaction(state.database) { uk.me.cormack.lighting7.models.DaoInstall.all().first().uuid.toString() },
        )
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.close() } }
        runCatching { fake.close() }
        runCatching { state.shutdown() }
    }

    private fun service(
        baseUrl: String? = fake.baseUrl,
        enabled: Boolean = true,
        requestTimeout: Duration = Duration.ofSeconds(5),
    ): CloudService = CloudService(
        database = state.database,
        config = CloudConfig(baseUrl, enabled),
        credentialStore = { store },
        userChanges = state.authService.userChanges,
        apiFactory = { CloudApi(it, requestTimeout) },
    ).also {
        it.pollDelay = { 20.milliseconds }
        services += it
        it.start()
    }

    private fun CloudService.await(what: String, predicate: (CloudStateDto) -> Boolean): CloudStateDto = runBlocking {
        try {
            withTimeout(10.seconds) { state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state is ${state.value}", e)
        }
    }

    private fun refusal(block: suspend () -> Unit): CloudException =
        assertFailsWith<CloudException> { runBlocking { block() } }

    private fun adopted(service: CloudService): CloudStateDto {
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { service.startAdoption(adminId) }
        return service.await("adopted") { it.adopted != null }
    }

    private fun rows(): Int = transaction(state.database) { DaoCloudConnections.selectAll().count().toInt() }
    private fun links(): Int = transaction(state.database) { DaoCloudIdentityLinks.selectAll().count().toInt() }

    @Test
    fun `an approved adoption adopts the desk and links the admin who started it`() {
        val service = service()
        runBlocking { service.startAdoption(adminId) }

        val adopting = assertNotNull(service.state.value.adoption)
        assertEquals(fake.adoption.userCode, adopting.userCode)
        assertEquals("${fake.baseUrl}/adopt?code=${adopting.userCode}", adopting.verificationUriComplete)
        assertEquals(InstallKeys.fingerprint(fake.adoption.key), adopting.fingerprint)
        val request = fake.adoption.request
        val install = transaction(state.database) { uk.me.cormack.lighting7.models.DaoInstall.all().first() }
        assertEquals(install.uuid.toString(), request.deskInstallUuid)
        assertEquals(install.friendlyName, request.friendlyName)
        assertTrue(store.containsBlob(keyName))

        fake.decision = FakeCloud.Decision.APPROVE
        val adopted = assertNotNull(service.await("adopted") { it.adopted != null }.adopted)
        assertNull(service.state.value.adoption)
        assertEquals(fake.installs.keys.single(), adopted.installId)
        assertEquals("Test Org", adopted.orgName)
        assertEquals("Test admin", adopted.adoptedBy)
        assertEquals("admin@example.com", adopted.linkedEmail)
        transaction(state.database) {
            val link = DaoCloudIdentityLinks.selectAll().single()
            assertEquals(adminId, link[DaoCloudIdentityLinks.user].value)
            assertEquals("acct-1", link[DaoCloudIdentityLinks.cloudAccountId])
            assertEquals(CloudLinkVia.ADOPTION, link[DaoCloudIdentityLinks.linkedVia])
        }
        assertTrue(fake.polls.all { it.signed }, "every poll verified against the adoption's key")
    }

    @Test
    fun `a denied adoption forgets the key and says so`() {
        val service = service()
        runBlocking { service.startAdoption(adminId) }
        fake.decision = FakeCloud.Decision.DENY
        val after = service.await("denied") { it.adoption == null }
        assertEquals("The adoption was refused in the cloud.", after.notice)
        assertNull(after.adopted)
        assertEquals(0, rows())
        assertEquals(0, links())
        assertFalse(store.containsBlob(keyName))
    }

    @Test
    fun `an adoption the cloud expires is forgotten`() {
        val service = service()
        runBlocking { service.startAdoption(adminId) }
        fake.decision = FakeCloud.Decision.EXPIRE
        val after = service.await("expired") { it.adoption == null }
        assertEquals(CloudAdoptionService.EXPIRED_NOTICE, after.notice)
        assertNotNull(after.noticeAt, "a notice is stamped, so a dismissal of one hides no later one")
        assertEquals(0, rows())
        assertFalse(store.containsBlob(keyName))

        runBlocking { service.startAdoption(adminId) }
        assertNull(service.state.value.notice)
        assertNull(service.state.value.noticeAt)
    }

    @Test
    fun `an adoption nobody approves expires on the desk's own clock`() {
        fake.expiresInS = 1
        val service = service()
        runBlocking { service.startAdoption(adminId) }
        val after = service.await("expired") { it.adoption == null }
        assertEquals(CloudAdoptionService.EXPIRED_NOTICE, after.notice)
        assertEquals(0, rows())
        assertFalse(store.containsBlob(keyName))
    }

    @Test
    fun `slow_down lengthens the interval between polls`() {
        val service = service()
        service.pollDelay = { (it * 100).milliseconds }
        fake.slowDownOnce = 4
        runBlocking { service.startAdoption(adminId) }
        runBlocking { withTimeout(10.seconds) { while (fake.polls.size < 3) kotlinx.coroutines.delay(10) } }
        val gaps = fake.polls.zipWithNext { a, b -> (b.at - a.at) / 1_000_000 }
        assertTrue(gaps[0] >= 380, "after slow_down the desk waits the new interval: ${gaps[0]} ms")
        assertTrue(gaps[1] >= 380, "and keeps waiting it: ${gaps[1]} ms")
    }

    @Test
    fun `a poll whose signature is wrong is refused, and the adoption is not approved`() {
        val service = service()
        runBlocking { service.startAdoption(adminId) }
        val adoptionId = fake.adoption.id

        // The fake checks the signature, not just its presence.
        val other = InstallKeys.generate()
        val timestamp = Timestamps.format(Instant.now())
        val forged = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("${fake.baseUrl}/v1/adoptions/$adoptionId/poll"))
                .header(SignatureHeaders.TIMESTAMP, timestamp)
                .header(SignatureHeaders.SIGNATURE, InstallKeys.sign(other.private, AdoptionPollPayload(adoptionId, timestamp)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, forged.statusCode())
        assertTrue(forged.body().contains("BAD_SIGNATURE"), forged.body())

        // The desk's key replaced behind its back: its polls no longer verify.
        store.setBlob(keyName, InstallKeys.encodePrivateKey(other.private))
        val failing = service.await("a refused poll") { it.lastError?.code == CloudErrorCodes.POLL_FAILED }
        val error = assertNotNull(failing.lastError)
        assertTrue(error.message.contains("BAD_SIGNATURE"), error.message)
        // Approving now would approve any poll that verified; none does.
        fake.decision = FakeCloud.Decision.APPROVE
        val refusedSoFar = fake.polls.count { !it.signed }
        runBlocking { withTimeout(10.seconds) { while (fake.polls.count { !it.signed } < refusedSoFar + 3) kotlinx.coroutines.delay(10) } }
        assertNotNull(service.state.value.adoption)
        assertTrue(fake.installs.isEmpty())
    }

    @Test
    fun `cancel forgets the adoption and stops polling`() {
        val service = service()
        runBlocking { service.startAdoption(adminId) }
        runBlocking { service.cancelAdoption() }
        assertNull(service.state.value.adoption)
        assertNull(service.state.value.notice)
        assertEquals(0, rows())
        assertFalse(store.containsBlob(keyName))
        Thread.sleep(200) // a poll already on the wire still lands
        val polled = fake.polls.size
        Thread.sleep(200)
        assertEquals(polled, fake.polls.size)
        assertEquals(CloudErrorCodes.NO_ADOPTION, refusal { service.cancelAdoption() }.code)
    }

    @Test
    fun `an adoption cannot start twice, nor without a cloud`() {
        val service = service()
        runBlocking { service.startAdoption(adminId) }
        assertEquals(CloudErrorCodes.ADOPTION_IN_PROGRESS, refusal { service.startAdoption(adminId) }.code)
        fake.decision = FakeCloud.Decision.APPROVE
        service.await("adopted") { it.adopted != null }
        assertEquals(CloudErrorCodes.ALREADY_ADOPTED, refusal { service.startAdoption(adminId) }.code)
        assertEquals(1, fake.adoptions.size)

        assertEquals(CloudErrorCodes.NOT_CONFIGURED, refusal { service(baseUrl = null).startAdoption(adminId) }.code)
        assertEquals(CloudErrorCodes.DISABLED, refusal { service(enabled = false).startAdoption(adminId) }.code)
    }

    @Test
    fun `a cloud that cannot be reached starts no adoption and keeps no key`() {
        fake.refuse = true
        val service = service()
        assertEquals(CloudErrorCodes.REFUSED, refusal { service.startAdoption(adminId) }.code)
        fake.close()
        assertEquals(CloudErrorCodes.UNREACHABLE, refusal { service.startAdoption(adminId) }.code)
        assertEquals(0, rows())
        assertFalse(store.containsBlob(keyName))
    }

    @Test
    fun `unadopt tells the cloud and forgets everything`() {
        val service = service()
        val installId = adopted(service).adopted!!.installId
        runBlocking { service.unadopt(force = false) }
        assertEquals(listOf(installId), fake.unadopts)
        assertTrue(fake.installs.isEmpty())
        assertNull(service.state.value.adopted)
        assertEquals(0, rows())
        assertEquals(0, links())
        assertFalse(store.containsBlob(keyName))
        assertEquals(CloudErrorCodes.NOT_ADOPTED, refusal { service.unadopt(force = false) }.code)
    }

    @Test
    fun `unadopt from a cloud that is down is refused, and force forgets locally`() {
        val service = service(requestTimeout = Duration.ofMillis(500))
        adopted(service)

        fake.refuse = true
        assertEquals(CloudErrorCodes.UNREACHABLE, refusal { service.unadopt(force = false) }.code)
        fake.refuse = false
        fake.stall = true
        assertEquals(CloudErrorCodes.UNREACHABLE, refusal { service.unadopt(force = false) }.code)
        fake.release()
        assertNotNull(service.current().adopted)
        assertEquals(1, rows())
        assertTrue(store.containsBlob(keyName))

        fake.close()
        assertEquals(CloudErrorCodes.UNREACHABLE, refusal { service.unadopt(force = false) }.code)
        runBlocking { service.unadopt(force = true) }
        assertNull(service.state.value.adopted)
        assertEquals(0, rows())
        assertEquals(0, links())
        assertFalse(store.containsBlob(keyName))
    }

    @Test
    fun `unadopt of an install the cloud already removed succeeds`() {
        val service = service()
        adopted(service)
        fake.installs.clear()
        runBlocking { service.unadopt(force = false) }
        assertEquals(0, rows())
    }

    @Test
    fun `a restart mid-adoption resumes polling from the stored row`() {
        val first = service()
        runBlocking { first.startAdoption(adminId) }
        val code = first.state.value.adoption!!.userCode
        first.close()

        val second = service()
        assertEquals(code, second.state.value.adoption?.userCode)
        fake.decision = FakeCloud.Decision.APPROVE
        val adopted = second.await("adopted after the restart") { it.adopted != null }
        assertEquals("admin@example.com", adopted.adopted!!.linkedEmail)
    }

    @Test
    fun `a pending adoption the desk no longer polls still expires`() {
        fake.expiresInS = 1
        val first = service()
        runBlocking { first.startAdoption(adminId) }
        first.close()
        Thread.sleep(200) // a poll already on the wire still lands
        val polled = fake.polls.size

        val off = service(enabled = false)
        assertNotNull(off.state.value.adoption)
        val after = off.await("expired") { it.adoption == null }
        assertEquals(CloudAdoptionService.EXPIRED_NOTICE, after.notice)
        assertEquals(polled, fake.polls.size)
        assertFalse(store.containsBlob(keyName))
    }

    @Test
    fun `an adoption approved while the desk was down past its expiry is still adopted`() {
        val first = service()
        runBlocking { first.startAdoption(adminId) }
        first.close()
        transaction(state.database) {
            DaoCloudConnections.update { it[adoptionExpiresAt] = Instant.now().minusSeconds(60) }
        }
        fake.decision = FakeCloud.Decision.APPROVE
        val second = service()
        assertNotNull(second.await("adopted by the last poll") { it.adopted != null }.adopted)
    }

    @Test
    fun `an adoption past its expiry that the last poll finds pending ends`() {
        val first = service()
        runBlocking { first.startAdoption(adminId) }
        first.close()
        transaction(state.database) {
            DaoCloudConnections.update { it[adoptionExpiresAt] = Instant.now().minusSeconds(60) }
        }
        val second = service()
        val after = second.await("expired") { it.adoption == null }
        assertEquals(CloudAdoptionService.EXPIRED_NOTICE, after.notice)
        assertTrue(fake.polls.isNotEmpty(), "the cloud was asked once more")
    }

    @Test
    fun `an install key the desk cannot read is retried, not deleted`() {
        val service = service()
        runBlocking { service.startAdoption(adminId) }
        store.setBlob(keyName, "not a key")
        val failing = service.await("a failed poll") { it.lastError?.code == CloudErrorCodes.POLL_FAILED }
        assertNotNull(failing.adoption)
        assertEquals("not a key", store.getBlob(keyName))
    }

    @Test
    fun `another install's key on the same machine is left alone`() {
        val other = InstallKeyStore.blobNameFor("00000000-0000-0000-0000-000000000001")
        val otherKey = InstallKeys.encodePrivateKey(InstallKeys.generate().private)
        store.setBlob(other, otherKey)
        val service = service()
        adopted(service)
        runBlocking { service.unadopt(force = false) }
        assertEquals(otherKey, store.getBlob(other))
        assertTrue(keyName != other)
    }

    @Test
    fun `an answer too large to read counts as no answer`() {
        fake.oversize = true
        val service = service()
        assertEquals(CloudErrorCodes.UNREACHABLE, refusal { service.startAdoption(adminId) }.code)
        assertFalse(store.containsBlob(keyName))
    }

    @Test
    fun `slow_down cannot stretch the interval past the desk's limit`() {
        val service = service()
        service.pollDelay = { (it * 10).milliseconds }
        fake.slowDownOnce = 86_400
        runBlocking { service.startAdoption(adminId) }
        runBlocking { withTimeout(10.seconds) { while (fake.polls.size < 2) kotlinx.coroutines.delay(10) } }
    }

    @Test
    fun `a key with no adoption behind it is deleted at startup`() {
        store.setBlob(keyName, InstallKeys.encodePrivateKey(InstallKeys.generate().private))
        val service = service()
        runBlocking { withTimeout(5.seconds) { while (store.containsBlob(keyName)) kotlinx.coroutines.delay(10) } }
        assertNull(service.state.value.adoption)
    }

    @Test
    fun `deleting the admin who adopted removes their link`() {
        seedUser(state, "second", UserRole.ADMIN)
        val service = service()
        adopted(service)
        assertEquals(1, links())
        state.authService.deleteUser(adminId)
        assertEquals(0, links())
        val after = service.await("the account gone") { it.adopted?.adoptedBy == null }
        assertNull(after.adopted!!.linkedEmail)
        transaction(state.database) { assertNull(DaoCloudConnections.selectAll().single()[DaoCloudConnections.adoptedBy]) }
    }

    @Test
    fun `a revoke forgets the adoption and names the organisation`() {
        val service = service()
        adopted(service)
        runBlocking { service.revoked() }
        assertEquals("This desk was removed from Test Org in the cloud.", service.state.value.notice)
        assertNull(service.state.value.adopted)
        assertEquals(0, rows())
        assertFalse(store.containsBlob(keyName))
    }

    @Test
    fun `the base URL must be https unless the cloud is on this machine`() {
        fun ok(url: String) = assertTrue(CloudConfig.checkBaseUrl(url) is BaseUrlCheck.Ok, url)
        fun refused(url: String) = assertTrue(CloudConfig.checkBaseUrl(url) is BaseUrlCheck.Refused, url)
        ok("https://cloud.example.com")
        ok("https://cloud.example.com/")
        ok("http://localhost:8080")
        ok("http://127.0.0.1:9000")
        ok("http://[::1]:9000")
        refused("http://cloud.example.com")
        refused("http://192.168.1.10")
        refused("http://localhost.example.com")
        refused("http://127.0.0.1.nip.io")
        refused("http://1example.com")
        refused("ftp://cloud.example.com")
        refused("https://user:pw@cloud.example.com")
        refused("https://cloud.example.com/?x=1")
        refused("cloud.example.com")
        assertEquals("https://cloud.example.com", (CloudConfig.checkBaseUrl("https://cloud.example.com/") as BaseUrlCheck.Ok).url)
    }

    @Test
    fun `an absent base URL is the production cloud and an empty one is no cloud`() {
        fun from(vararg pairs: Pair<String, String>) = CloudConfig.from(MapApplicationConfig(*pairs))
        assertEquals(CloudConfig.DEFAULT_BASE_URL, from().baseUrl)
        assertTrue(CloudConfig.checkBaseUrl(CloudConfig.DEFAULT_BASE_URL) is BaseUrlCheck.Ok)
        assertNull(from("cloud.baseUrl" to "").baseUrl)
        assertNull(from("cloud.baseUrl" to "  ").baseUrl)
        assertFalse(from("cloud.baseUrl" to "").configured)
        assertEquals("https://cloud.example.com", from("cloud.baseUrl" to "https://cloud.example.com/").baseUrl)
    }
}
