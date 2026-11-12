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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Test
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncLinkedRepo
import uk.me.cormack.lighting7.models.DaoSyncLinkedRepos
import uk.me.cormack.lighting7.models.DaoSyncLogEntry
import uk.me.cormack.lighting7.models.DaoSyncLogEntries
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.InstallSigner
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.sync.CloudMove
import uk.me.cormack.lighting7.sync.CloudRepos
import uk.me.cormack.lighting7.sync.RemoteSyncEngine
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.CloudCredentialSource
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.CloudMovePlan
import uk.me.cormack.lighting7.sync.CloudMoveResult
import uk.me.cormack.lighting7.sync.CloudMoveStatus
import uk.me.cormack.lighting7.sync.CloudSyncImporter
import uk.me.cormack.lighting7.sync.SyncLogEvent
import uk.me.cormack.lighting7.sync.SyncOutcome
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.RemoteDesk
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountPublic
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.remote
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * *Move GitHub-synced projects to the cloud* (cloud-p2 plan D7) against [FakeCloud], each GitHub repo a
 * `file://` bare repo standing in for it: SHAs and tags kept, a second desk moving the same project,
 * two desks racing, GitHub unreachable, a refused move leaving the project where it was, and the public
 * listener refused.
 */
class CloudMoveRoutesTest : RouteIntegrationTest() {

    private val fake = FakeCloud()
    private val remote by lazy { RemoteDesk(state, fake) }
    private val tmp: Path = Files.createTempDirectory("cloud-move-")
    private val others = mutableListOf<SecondDesk>()

    override val extraConfig: List<Pair<String, String>> = listOf("cloud.baseUrl" to fake.baseUrl)

    @After
    fun closeFake() {
        others.forEach { runCatching { it.close() } }
        runCatching { state.credentialStore.deleteBlob(state.cloud.keys.blobName) }
        runCatching { fake.close() }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    /** A bare repo on disk standing in for a GitHub repo. */
    private fun github(name: String): String {
        val dir = tmp.resolve("github-$name.git")
        Git.init().setBare(true).setDirectory(dir.toFile()).setInitialBranch("main").call().close()
        return dir.toUri().toString()
    }

    private fun githubHead(url: String): String? = Git.open(Path.of(java.net.URI(url)).toFile()).use { it.repository.resolve("refs/heads/main")?.name }

    private fun githubTags(url: String): Map<String, String> = Git.open(Path.of(java.net.URI(url)).toFile()).use { git ->
        git.repository.refDatabase.getRefsByPrefix(Constants.R_TAGS).associate { ref ->
            ref.name.removePrefix(Constants.R_TAGS) to (git.repository.refDatabase.peel(ref).peeledObjectId ?: ref.objectId).name
        }
    }

    /**
     * Another adopted desk, beside the one under test, with its own install key and credentials in
     * memory: two [State]s in one JVM would share, and clobber, one credential file.
     */
    private inner class SecondDesk(name: String) {
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
        private val credentials = InMemoryCredentialStore()
        private val source = CloudCredentialSource(fake.baseUrl, enabled = true, signer = { signer }, adopted = { true }, devToken = null, api = { api })
        private val auth = AuthResolver(credentials, tokenStore = null, tokenProvider = null, cloud = source)
        private val engine = RemoteSyncEngine(state, auth)
        private val mover = CloudMove(state, engine, auth, CloudRepos(state.database, source))

        init {
            others += this
        }

        fun importFrom(url: String, branch: String? = null): Int {
            credentials.set(url, "pat")
            return runBlocking { CloudSyncImporter(state, auth).import(url, branch, null) }.projectId
        }

        fun publish(venueId: Int, note: String) =
            runBlocking { state.venueVersions.locked { engine.publishVenue(venueId, note, "Tester") } }

        fun move(projectId: Int) = runBlocking { mover.run(listOf(projectId)) }.projects.single()

        fun sync(projectId: Int): SyncOutcome {
            val (uuid, install) = transaction(state.database) { DaoProject[projectId].uuid to DaoInstall.all().first() }
            return runBlocking { engine.runSync(projectId, uuid, install.uuid, install.friendlyName) }.outcome
        }

        fun close() {
            runCatching { state.shutdown() }
            api.close()
        }
    }

    private fun attach(on: State, projectId: Int, url: String) {
        transaction(on.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?: DaoSyncConfig.new { project = DaoProject[projectId] }
            cfg.repoUrl = url
        }
        on.credentialStore.set(url, "pat")
    }

    private fun syncOn(on: State, projectId: Int): SyncOutcome {
        val (uuid, install) = transaction(on.database) { DaoProject[projectId].uuid to DaoInstall.all().first() }
        return runBlocking { on.remoteSyncEngine.runSync(projectId, uuid, install.uuid, install.friendlyName) }.outcome
    }

    private fun repoUrlOf(on: State, projectId: Int): String? =
        transaction(on.database) { DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.first().repoUrl }

    private fun addStack(on: State, projectId: Int, name: String) = transaction(on.database) {
        DaoCueStack.new { project = DaoProject[projectId]; this.name = name }
    }

    private fun stacks(on: State, projectId: Int): Set<String> = transaction(on.database) {
        DaoCueStack.find { DaoCueStacks.project eq projectId }.map { it.name }.toSet()
    }

    private fun moveTruss(on: State, venueId: Int) = transaction(on.database) {
        DaoRigging.find { DaoRiggings.project eq venueId }.orderBy(DaoRiggings.sortOrder to SortOrder.ASC)
            .first().apply { positionX = (positionX ?: 0.0) + 1.0 }
    }

    private fun events(on: State, projectId: Int, event: String): Int = transaction(on.database) {
        DaoSyncLogEntry.find { DaoSyncLogEntries.project eq projectId }.count { it.event == event }
    }

    private fun branchOf(on: State, projectId: Int): String =
        transaction(on.database) { DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.first().branch }

    private fun repoIdOf(url: String): String = url.substringAfterLast('/').removeSuffix(".git")

    private suspend fun HttpClient.plan(cookie: String): HttpResponse =
        get("/api/rest/cloud-sync/move") { header(HttpHeaders.Cookie, cookie) }

    private suspend fun HttpClient.move(cookie: String, vararg ids: Int): HttpResponse =
        post("/api/rest/cloud-sync/move") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(CloudMoveRequest(ids.toList()))
        }

    private fun HttpResponse.code(): String? = runBlocking {
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content
    }

    @Test
    fun `a show and a venue move with every sha and tag the same, the venue first`() = testApplication {
        val f = seedShowOnVenue(state)
        val venueGithub = github("venue")
        val showGithub = github("show")
        attach(state, f.venueId, venueGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, f.venueId))
        attach(state, f.showId, showGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, f.showId))
        val venueTags = githubTags(venueGithub)
        assertEquals(setOf("v1", "v2"), venueTags.keys)

        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        val plan = http.plan(cookie).body<CloudMovePlan>().projects
        assertEquals(listOf(f.venueId, f.showId), plan.map { it.projectId })
        assertEquals(listOf("VENUE", "SHOW"), plan.map { it.kind })
        assertTrue(plan.none { it.cloudHasRepo })

        val moved = http.move(cookie, f.showId, f.venueId)
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
        val rows = moved.body<CloudMoveResult>().projects
        assertEquals(listOf(f.venueId, f.showId), rows.map { it.projectId }, "venues move first")
        rows.forEach { row ->
            assertEquals(CloudMoveStatus.MOVED, row.status, "${row.name}: ${row.message}")
            assertFalse(row.fromWorkingTree)
            assertNotNull(row.verify, "${row.name}: ${row.verifyError}")
        }

        val venueRow = rows.first()
        val venueRepoId = repoIdOf(venueRow.repoUrl!!)
        assertEquals(githubHead(venueGithub), fake.repos.head(venueRepoId))
        assertEquals(venueTags, fake.repos.tags(venueRepoId))
        assertEquals(2, venueRow.tags)
        val showRepoId = repoIdOf(rows.last().repoUrl!!)
        assertEquals(githubHead(showGithub), fake.repos.head(showRepoId))
        assertEquals(rows.last().headSha, fake.repos.head(showRepoId))

        assertEquals(venueRow.repoUrl, repoUrlOf(state, f.venueId))
        val remembered = transaction(state.database) {
            DaoSyncLinkedRepo.find { DaoSyncLinkedRepos.project eq f.showId }.map { it.repoUrl }
        }
        assertTrue(showGithub in remembered, "$remembered")
        val logged = transaction(state.database) {
            DaoSyncLogEntry.find { DaoSyncLogEntries.project eq f.showId }.map { it.event }
        }
        assertTrue(SyncLogEvent.MOVED_TO_CLOUD in logged, "$logged")

        assertTrue(http.plan(cookie).body<CloudMovePlan>().projects.none { it.projectId == f.showId || it.projectId == f.venueId })
        assertEquals(SyncOutcome.NO_OP, syncOn(state, f.showId))

        val again = http.move(cookie, f.showId).body<CloudMoveResult>().projects.single()
        assertEquals("MOVE_NOT_ON_GITHUB", again.code)
        assertTrue("already on the cloud" in again.message.orEmpty(), again.message)
    }

    @Test
    fun `a show whose venue still syncs with GitHub is held back, whether the venue was not asked for or failed`() = testApplication {
        val f = seedShowOnVenue(state)
        val venueGithub = github("held-venue")
        val showGithub = github("held-show")
        attach(state, f.venueId, venueGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, f.venueId))
        attach(state, f.showId, showGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, f.showId))
        val showHead = githubHead(showGithub)
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        val runs = events(state, f.showId, SyncLogEvent.RUN_STARTED)

        val alone = http.move(cookie, f.showId).body<CloudMoveResult>().projects.single()
        assertEquals(CloudMoveStatus.FAILED, alone.status)
        assertEquals("VENUE_NOT_MOVED", alone.code, alone.message)
        assertTrue("Commemoration Hall" in alone.message.orEmpty(), alone.message)
        assertEquals(showGithub, repoUrlOf(state, f.showId))
        assertEquals(showHead, githubHead(showGithub))
        assertEquals(runs, events(state, f.showId, SyncLogEvent.RUN_STARTED), "nothing synced for a held-back show")

        fake.repos.refusePushes = ErrorCode.SYNC_QUOTA_EXCEEDED
        val failed = http.move(cookie, f.venueId, f.showId).body<CloudMoveResult>().projects
        assertEquals(listOf(f.venueId, f.showId), failed.map { it.projectId })
        assertEquals(listOf(CloudMoveStatus.FAILED, CloudMoveStatus.FAILED), failed.map { it.status })
        assertEquals("VENUE_NOT_MOVED", failed.last().code, failed.last().message)
        assertEquals(showGithub, repoUrlOf(state, f.showId))
        assertEquals(runs, events(state, f.showId, SyncLogEvent.RUN_STARTED))
        fake.repos.refusePushes = null

        val both = http.move(cookie, f.venueId, f.showId).body<CloudMoveResult>().projects
        assertEquals(listOf(CloudMoveStatus.MOVED, CloudMoveStatus.MOVED), both.map { it.status }, "${both.map { it.message }}")
    }

    @Test
    fun `a venue's versions pulled from GitHub move at GitHub's tags, and the verifying sync pushes nothing`() = testApplication {
        val f = seedShowOnVenue(state)
        val venueGithub = github("pulled-venue")
        attach(state, f.venueId, venueGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, f.venueId))

        val other = SecondDesk("puller")
        val there = other.importFrom(venueGithub)
        moveTruss(other.state, there)
        assertEquals(3, other.publish(there, "v3 from the second desk").number)
        val tags = githubTags(venueGithub)
        assertEquals(setOf("v1", "v2", "v3"), tags.keys)

        val row = other.move(there)
        assertEquals(CloudMoveStatus.MOVED, row.status, "${row.code}: ${row.message}")
        assertEquals(3, row.tags)
        val repoId = repoIdOf(row.repoUrl!!)
        assertEquals(tags, fake.repos.tags(repoId))
        assertEquals(SyncOutcome.NO_OP, row.verify?.outcome, row.verifyError?.error)
        assertEquals(githubHead(venueGithub), fake.repos.head(repoId))
    }

    @Test
    fun `a project on another branch moves onto the cloud's main and syncs there`() = testApplication {
        val showGithub = github("branched")
        attach(state, projectId, showGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, projectId))
        Git.open(Path.of(java.net.URI(showGithub)).toFile()).use { git ->
            git.repository.updateRef("refs/heads/stage").apply { setNewObjectId(git.repository.resolve("refs/heads/main")) }.update()
        }
        val other = SecondDesk("branch")
        val there = other.importFrom(showGithub, "stage")
        assertEquals("stage", branchOf(other.state, there))
        addStack(other.state, there, "on-stage")
        assertEquals(SyncOutcome.PUSHED, other.sync(there))
        val stageHead = Git.open(Path.of(java.net.URI(showGithub)).toFile()).use { it.repository.resolve("refs/heads/stage").name }

        val row = other.move(there)
        assertEquals(CloudMoveStatus.MOVED, row.status, "${row.code}: ${row.message}")
        assertEquals(stageHead, fake.repos.head(repoIdOf(row.repoUrl!!)))
        assertEquals("main", branchOf(other.state, there))
        assertEquals(SyncOutcome.NO_OP, row.verify?.outcome, row.verifyError?.error)
        addStack(other.state, there, "after-move")
        assertEquals(SyncOutcome.PUSHED, other.sync(there))
    }

    @Test
    fun `an unadopted desk or an org without sync refuses the move before any sync`() = testApplication {
        val showGithub = github("refused")
        attach(state, projectId, showGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, projectId))
        val head = githubHead(showGithub)
        val runs = events(state, projectId, SyncLogEvent.RUN_STARTED)
        val admin = seedUser(state, "admin")
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        assertEquals("CLOUD_NOT_ADOPTED", http.plan(cookie).code())
        val unadopted = http.move(cookie, projectId)
        assertEquals(HttpStatusCode.Conflict, unadopted.status, unadopted.bodyAsText())
        assertEquals("CLOUD_NOT_ADOPTED", unadopted.code())

        remote.adopt(admin.userId)
        fake.repos.syncOff = true
        val off = http.move(cookie, projectId)
        assertEquals(HttpStatusCode.Conflict, off.status, off.bodyAsText())
        assertEquals("SYNC_OFF", off.code())

        assertEquals(head, githubHead(showGithub))
        assertEquals(showGithub, repoUrlOf(state, projectId))
        assertEquals(runs, events(state, projectId, SyncLogEvent.RUN_STARTED))
    }

    @Test
    fun `a second desk moves the same project fast-forward, and a diverged one re-points and merges`() = testApplication {
        val showGithub = github("shared")
        attach(state, projectId, showGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, projectId))
        val other = SecondDesk("other")
        val there = other.importFrom(showGithub)

        // The other desk moves first; this desk, ahead of it with a GitHub commit, moves fast-forward.
        assertEquals(CloudMoveStatus.MOVED, other.move(there).status)
        addStack(state, projectId, "here-before")
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        assertTrue(http.plan(cookie).body<CloudMovePlan>().projects.single().cloudHasRepo)
        val row = http.move(cookie, projectId).body<CloudMoveResult>().projects.single()
        assertEquals(CloudMoveStatus.MOVED, row.status, row.message)
        assertTrue(row.existed)
        assertEquals(githubHead(showGithub), fake.repos.head(repoIdOf(row.repoUrl!!)))
        assertEquals(repoUrlOf(other.state, there), row.repoUrl)

        // A third desk, still on GitHub, changes it apart from the cloud: it re-points and merges.
        val third = SecondDesk("third")
        val far = third.importFrom(showGithub)
        addStack(third.state, far, "on-github")
        addStack(other.state, there, "on-cloud")
        assertEquals(SyncOutcome.MERGED, other.sync(there))
        val diverged = third.move(far)
        assertEquals(CloudMoveStatus.MOVED_DIVERGED, diverged.status, diverged.message)
        assertEquals(row.repoUrl, repoUrlOf(third.state, far))
        assertEquals(SyncOutcome.MERGED, diverged.verify?.outcome, diverged.verifyError?.error)
        assertTrue(stacks(third.state, far).containsAll(setOf("on-github", "on-cloud", "here-before")), "${stacks(third.state, far)}")
    }

    @Test
    fun `two desks racing to move one project end on one cloud repo with both desks' work`() = testApplication {
        val showGithub = github("race")
        attach(state, projectId, showGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, projectId))
        val a = SecondDesk("a")
        val b = SecondDesk("b")
        val onA = a.importFrom(showGithub)
        val onB = b.importFrom(showGithub)
        addStack(a.state, onA, "from-a")
        addStack(b.state, onB, "from-b")

        val rows = runBlocking(Dispatchers.IO) {
            listOf(async { a.move(onA) }, async { b.move(onB) }).awaitAll()
        }
        rows.forEach { assertTrue(it.status != CloudMoveStatus.FAILED, "${it.code}: ${it.message}") }
        assertEquals(1, fake.repos.repos.size)
        assertEquals(repoUrlOf(a.state, onA), repoUrlOf(b.state, onB))

        // Each desk's next syncs bring the other's work in.
        repeat(2) {
            a.sync(onA)
            b.sync(onB)
        }
        assertEquals(setOf("from-a", "from-b"), stacks(a.state, onA) intersect setOf("from-a", "from-b"))
        assertEquals(setOf("from-a", "from-b"), stacks(b.state, onB) intersect setOf("from-a", "from-b"))
    }

    @Test
    fun `GitHub unreachable moves the working tree as it is, and a refused move leaves the project on GitHub`() = testApplication {
        val showGithub = github("gone")
        attach(state, projectId, showGithub)
        assertEquals(SyncOutcome.PUSHED, syncOn(state, projectId))
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")

        fake.repos.refusePushes = ErrorCode.SYNC_QUOTA_EXCEEDED
        val refused = http.move(cookie, projectId).body<CloudMoveResult>().projects.single()
        assertEquals(CloudMoveStatus.FAILED, refused.status)
        assertEquals("SYNC_QUOTA_EXCEEDED", refused.code, refused.message)
        assertEquals(showGithub, repoUrlOf(state, projectId))
        assertTrue(transaction(state.database) { DaoSyncLinkedRepo.find { DaoSyncLinkedRepos.project eq projectId }.empty() })
        fake.repos.refusePushes = null

        addStack(state, projectId, "never-on-github")
        Path.of(java.net.URI(showGithub)).toFile().deleteRecursively()
        val moved = http.move(cookie, projectId).body<CloudMoveResult>().projects.single()
        assertEquals(CloudMoveStatus.MOVED, moved.status, "${moved.code}: ${moved.message}")
        assertTrue(moved.fromWorkingTree)
        assertEquals(moved.headSha, fake.repos.head(repoIdOf(moved.repoUrl!!)))
        assertEquals(SyncOutcome.NO_OP, moved.verify?.outcome, moved.verifyError?.error)

        val other = SecondDesk("reader")
        val there = other.importFrom(moved.repoUrl)
        assertTrue("never-on-github" in stacks(other.state, there))
    }

    @Test
    fun `a project not on GitHub is refused by row, an empty request by the route, and nothing to move asks no cloud`() = testApplication {
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        mountTestApp(state)
        val http = jsonClient()
        val cookie = http.loginCookieHeader("admin")
        fake.repos.syncOff = true
        val plan = http.plan(cookie)
        assertEquals(HttpStatusCode.OK, plan.status, plan.bodyAsText())
        assertTrue(plan.body<CloudMovePlan>().projects.isEmpty())
        fake.repos.syncOff = false
        assertEquals(HttpStatusCode.BadRequest, http.move(cookie).status)

        val row = http.move(cookie, projectId).body<CloudMoveResult>().projects.single()
        assertEquals(CloudMoveStatus.FAILED, row.status)
        assertEquals("MOVE_NOT_ON_GITHUB", row.code)
    }

    @Test
    fun `the move is refused on the public listener`() = testApplication {
        val admin = seedUser(state, "admin")
        remote.adopt(admin.userId)
        attach(state, projectId, github("remote"))
        mountPublic(state)
        val http = jsonClient()
        val caller = remote.caller("admin")
        val plan = http.get("/api/rest/cloud-sync/move") { remote(caller) }
        assertEquals(HttpStatusCode.Forbidden, plan.status, plan.bodyAsText())
        assertEquals(CLOUD_MOVE_LOCAL_ONLY, plan.code())
        val run = http.post("/api/rest/cloud-sync/move") {
            remote(caller)
            contentType(ContentType.Application.Json)
            setBody(CloudMoveRequest(listOf(projectId)))
        }
        assertEquals(HttpStatusCode.Forbidden, run.status, run.bodyAsText())
        assertEquals(CLOUD_MOVE_LOCAL_ONLY, run.code())
        assertTrue(fake.repos.repos.isEmpty())
    }
}
