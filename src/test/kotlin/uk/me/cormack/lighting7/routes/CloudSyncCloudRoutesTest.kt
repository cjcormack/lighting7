package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.InstallSigner
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.RepoKind
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.CloudRepoRow
import uk.me.cormack.lighting7.sync.CloudSyncImporter
import uk.me.cormack.lighting7.sync.JGitClient
import uk.me.cormack.lighting7.sync.RemoteSyncEngine
import uk.me.cormack.lighting7.sync.SyncOutcome
import uk.me.cormack.lighting7.sync.SyncWorkingTree
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.CloudCredentialSource
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.FakeCloudRepos
import uk.me.cormack.lighting7.testsupport.RemoteDesk
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Sync to the cloud* and *Import from the cloud* (cloud-p2 plan D6) against [FakeCloud]: the desk
 * under test through its routes, and where a second desk is needed, one driven through the engine as
 * `CloudRepoSyncTest`'s are.
 */
class CloudSyncCloudRoutesTest : RouteIntegrationTest() {

    private val fake = FakeCloud()
    private val remote by lazy { RemoteDesk(state, fake) }
    private val tmp = Files.createTempDirectory("cloud-sync-routes-")
    private val others = mutableListOf<OtherDesk>()

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    @After
    fun closeFake() {
        others.forEach { runCatching { it.close() } }
        runCatching { state.credentialStore.deleteBlob(state.cloud.keys.blobName) }
        runCatching { fake.close() }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    /** Another adopted desk, syncing through the engine rather than its routes. */
    private inner class OtherDesk(name: String) {
        val state = State(
            testAppConfig(
                "database.path" to tmp.resolve("$name.db").toString(),
                "sync.workingTreeRoot" to tmp.resolve("$name-sync").toString(),
            ),
        )
        private val api = CloudApi(fake.baseUrl, Duration.ofSeconds(5))
        private val signer = InstallKeys.generate().let { pair ->
            fake.installs["inst-$name"] = FakeCloud.Install("inst-$name", "adp-$name", pair.public)
            InstallSigner("inst-$name", pair.private)
        }
        private val auth = AuthResolver(
            InMemoryCredentialStore(), tokenStore = null, tokenProvider = null,
            cloud = CloudCredentialSource(fake.baseUrl, enabled = true, signer = { signer }, adopted = { true }, devToken = null, api = { api }),
        )
        private val engine = RemoteSyncEngine(state, auth)

        init {
            others += this
        }

        fun attach(projectId: Int, url: String, name: String) = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?: DaoSyncConfig.new { project = DaoProject[projectId] }
            cfg.repoUrl = url
            cfg.repoName = name
        }

        fun sync(projectId: Int): SyncOutcome {
            val (uuid, install) = transaction(state.database) { DaoProject[projectId].uuid to DaoInstall.all().first() }
            return runBlocking { engine.runSync(projectId, uuid, install.uuid, install.friendlyName) }.outcome
        }

        fun importRepo(url: String): Int = runBlocking { CloudSyncImporter(state, auth).import(url, null, null) }.projectId

        fun uuidOf(projectId: Int): String = transaction(state.database) { DaoProject[projectId].uuid.toString() }

        fun addStack(projectId: Int, name: String) = transaction(state.database) {
            DaoCueStack.new { project = DaoProject[projectId]; this.name = name }
        }

        fun close() {
            runCatching { state.shutdown() }
            api.close()
        }
    }

    private fun HttpResponse.code(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    private fun stacks(projectId: Int): Set<String> = transaction(state.database) {
        DaoCueStack.find { DaoCueStacks.project eq projectId }.map { it.name }.toSet()
    }

    private suspend fun HttpClient.syncToCloud(cookie: String, id: Int = projectId): HttpResponse =
        post("/api/rest/projects/$id/sync/cloud") { header(HttpHeaders.Cookie, cookie) }

    private suspend fun HttpClient.repos(cookie: String): HttpResponse =
        get("/api/rest/cloud-sync/repos") { header(HttpHeaders.Cookie, cookie) }

    private suspend fun HttpClient.import(cookie: String, url: String): HttpResponse =
        post("/api/rest/cloud-sync/import") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(ImportFromRemoteRequest(repoUrl = url))
        }

    @Test
    fun `sync to the cloud makes the project's repo, attaches it by name and pushes`() = testApplication {
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val made = http.syncToCloud(cookie)
        assertEquals(HttpStatusCode.OK, made.status, made.bodyAsText())
        val answer = made.body<SyncToCloudResponse>()
        assertFalse(answer.existed)
        assertNull(answer.syncError)
        assertEquals(SyncOutcome.PUSHED, answer.sync?.outcome)
        val repo = fake.repos.repos.values.single().dto
        assertEquals(transaction(state.database) { DaoProject[projectId].uuid.toString() }, repo.projectUuid)
        assertEquals(RepoKind.SHOW, repo.kind)
        assertEquals(answer.sync?.headSha, fake.repos.head(repo.repoId))
        assertEquals(repo.url, answer.config.repoUrl)
        assertEquals(repo.name, answer.config.repoName)
        assertTrue(answer.config.cloud)
        assertTrue(answer.config.autoSyncEnabled)

        val config = http.get("/api/rest/projects/$projectId/sync/config") { header(HttpHeaders.Cookie, cookie) }.body<SyncConfigDto>()
        assertEquals(repo.name, config.repoName)
        assertTrue(config.cloud)

        val again = http.syncToCloud(cookie)
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("REPO_URL_IMMUTABLE", again.code())
        assertEquals(1, fake.repos.repos.size)

        val off = http.post("/api/rest/projects/$projectId/sync/disconnect") { header(HttpHeaders.Cookie, cookie) }.body<SyncConfigDto>()
        assertNull(off.repoName)
        fake.repos.repos.values.single().let { fake.repos.repos[repo.repoId] = FakeCloudRepos.Repo(it.dto.copy(name = "Renamed"), it.git) }
        val back = http.post("/api/rest/projects/$projectId/sync/reconnect") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(ReconnectRequest(repoUrl = repo.url))
        }.body<SyncConfigDto>()
        assertEquals("Renamed", back.repoName)
    }

    @Test
    fun `a project the cloud already has a repo for attaches it, and its first sync merges both desks' work`() = testApplication {
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        assertEquals(HttpStatusCode.OK, http.syncToCloud(cookie).status)
        val url = fake.repos.repos.values.single().dto.url
        http.post("/api/rest/projects/$projectId/sync/disconnect") { header(HttpHeaders.Cookie, cookie) }

        val other = OtherDesk("other")
        val there = other.importRepo(url)
        other.addStack(there, "from-other")
        assertEquals(SyncOutcome.PUSHED, other.sync(there))
        transaction(state.database) { DaoCueStack.new { project = DaoProject[projectId]; name = "from-here" } }

        val attached = http.syncToCloud(cookie)
        assertEquals(HttpStatusCode.OK, attached.status, attached.bodyAsText())
        val answer = attached.body<SyncToCloudResponse>()
        assertTrue(answer.existed)
        assertEquals(url, answer.config.repoUrl)
        assertEquals(SyncOutcome.MERGED, answer.sync?.outcome, answer.sync?.message ?: answer.syncError?.error)
        assertTrue(stacks(projectId).containsAll(setOf("from-here", "from-other")), "${stacks(projectId)}")
        assertEquals(1, fake.repos.repos.size)
        assertEquals(answer.sync?.headSha, fake.repos.head(fake.repos.repos.keys.single()))
    }

    @Test
    fun `an unadopted desk is CLOUD_NOT_ADOPTED, an org without sync SYNC_OFF, an unreachable cloud CLOUD_UNREACHABLE`() = testApplication {
        val admin = seedUser(state, "admin")
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val unadopted = http.syncToCloud(cookie)
        assertEquals(HttpStatusCode.Conflict, unadopted.status)
        assertEquals("CLOUD_NOT_ADOPTED", unadopted.code())
        assertEquals("CLOUD_NOT_ADOPTED", http.repos(cookie).code())

        remote.adopt(admin.userId)
        fake.repos.syncOff = true
        val off = http.syncToCloud(cookie)
        assertEquals(HttpStatusCode.Conflict, off.status)
        assertEquals("SYNC_OFF", off.code())
        val offList = http.repos(cookie)
        assertEquals(HttpStatusCode.Conflict, offList.status)
        assertEquals("SYNC_OFF", offList.code())
        fake.repos.syncOff = false

        fake.repos.refuseApi = HttpStatusCode.BadRequest to ErrorCode.MALFORMED
        val refused = http.syncToCloud(cookie)
        assertEquals(HttpStatusCode.BadGateway, refused.status, refused.bodyAsText())
        assertEquals("CLOUD_REFUSED", refused.code())
        fake.repos.refuseApi = HttpStatusCode.NotFound to ErrorCode.REPO_NOT_FOUND
        assertEquals("REPO_NOT_FOUND", http.repos(cookie).code())
        fake.repos.refuseApi = null
        assertTrue(fake.repos.repos.isEmpty())
        val config = http.get("/api/rest/projects/$projectId/sync/config") { header(HttpHeaders.Cookie, cookie) }.body<SyncConfigDto>()
        assertFalse(config.synced)

        fake.close()
        val down = http.syncToCloud(cookie)
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status, down.bodyAsText())
        assertEquals("CLOUD_UNREACHABLE", down.code())
    }

    @Test
    fun `the org's repos say what syncs here and their kind, a venue imports before its show, and VENUE_MISSING names the cloud repo`() = testApplication {
        val other = OtherDesk("venue-desk")
        val f = seedShowOnVenue(other.state)
        val venueRepo = fake.repos.create(other.uuidOf(f.venueId), RepoKind.VENUE, "Commemoration Hall")
        other.attach(f.venueId, venueRepo.url, venueRepo.name)
        assertEquals(SyncOutcome.PUSHED, other.sync(f.venueId))
        val showRepo = fake.repos.create(other.uuidOf(f.showId), RepoKind.SHOW, "Panto")
        other.attach(f.showId, showRepo.url, showRepo.name)
        assertEquals(SyncOutcome.PUSHED, other.sync(f.showId))

        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val listed = http.repos(cookie).body<CloudReposResponse>().repos.associateBy { it.url }
        assertEquals(RepoKind.VENUE, listed.getValue(venueRepo.url).kind)
        assertEquals(RepoKind.SHOW, listed.getValue(showRepo.url).kind)
        assertTrue(listed.values.none(CloudRepoRow::syncedHere))

        val missing = http.import(cookie, showRepo.url)
        assertEquals(HttpStatusCode.Conflict, missing.status, missing.bodyAsText())
        assertEquals("VENUE_MISSING", missing.code())
        assertTrue("Commemoration Hall" in missing.bodyAsText(), missing.bodyAsText())

        val venue = http.import(cookie, venueRepo.url)
        assertEquals(HttpStatusCode.Created, venue.status, venue.bodyAsText())
        val venueId = venue.body<ImportFromRemoteResponse>().projectId
        assertEquals(HttpStatusCode.Created, http.import(cookie, showRepo.url).status)

        val after = http.repos(cookie).body<CloudReposResponse>().repos.associateBy { it.url }
        assertTrue(after.values.all(CloudRepoRow::syncedHere))
        assertEquals(venueId, after.getValue(venueRepo.url).projectId)
        val config = http.get("/api/rest/projects/$venueId/sync/config") { header(HttpHeaders.Cookie, cookie) }.body<SyncConfigDto>()
        assertEquals("Commemoration Hall", config.repoName)
        assertTrue(config.cloud)
    }

    @Test
    fun `a venue syncs to the cloud with its tags, and a show on it names the venue's cloud repo`() = testApplication {
        val f = seedShowOnVenue(state)
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val venue = http.syncToCloud(cookie, f.venueId)
        assertEquals(HttpStatusCode.OK, venue.status, venue.bodyAsText())
        val venueRepo = fake.repos.repos.values.single { it.dto.kind == RepoKind.VENUE }.dto
        assertEquals(setOf("v1", "v2"), fake.repos.tags(venueRepo.repoId).keys)

        val show = http.syncToCloud(cookie, f.showId).body<SyncToCloudResponse>()
        assertEquals(SyncOutcome.PUSHED, show.sync?.outcome, show.syncError?.error)
        val uuid = transaction(state.database) { DaoProject[f.showId].uuid }
        val projectJson = JGitClient.open(SyncWorkingTree(state).pathFor(uuid))!!.use { JGitClient.readBlob(it, "HEAD", "project.json") }
        assertNotNull(projectJson)
        assertEquals(venueRepo.name, Json.parseToJsonElement(projectJson).jsonObject["venueRepo"]?.jsonPrimitive?.content, projectJson)
    }
}

/** A pull-only desk (`sync.push = false`) never makes a cloud repo it could not push to. */
class CloudSyncPullOnlyRouteTest : RouteIntegrationTest() {

    private val fake = FakeCloud()
    private val remote by lazy { RemoteDesk(state, fake) }

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl, "sync.push" to "false")

    @After
    fun closeFake() {
        runCatching { state.credentialStore.deleteBlob(state.cloud.keys.blobName) }
        runCatching { fake.close() }
    }

    @Test
    fun `sync to the cloud on a pull-only desk is CLOUD_SYNC_PULL_ONLY and makes no repo`() = testApplication {
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val answer = http.post("/api/rest/projects/$projectId/sync/cloud") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Conflict, answer.status, answer.bodyAsText())
        assertEquals("CLOUD_SYNC_PULL_ONLY", Json.parseToJsonElement(answer.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
        assertTrue(fake.repos.repos.isEmpty())
    }
}
