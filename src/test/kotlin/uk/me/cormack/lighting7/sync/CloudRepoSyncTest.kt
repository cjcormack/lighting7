package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.InstallSigner
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallGitTokens
import uk.me.cormack.lighting7.cloud.protocol.InstallKeys
import uk.me.cormack.lighting7.cloud.protocol.RepoDto
import uk.me.cormack.lighting7.cloud.protocol.RepoKind
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.CloudCredentialSource
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.auth.MissingCredentialsException
import uk.me.cormack.lighting7.telemetry.TokenScrubber
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.venue.VenueVersions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cloud sync, the desk's side (cloud-p2 plan session 2): shows and venues synced through [FakeCloud]'s
 * git between desks, each desk an adopted install signing its own git tokens or an unadopted one with a
 * developer token, and the cloud's refusals as the codes the Sync tab and auto-sync act on (D3, D4, D5).
 */
class CloudRepoSyncTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var tmp: Path
    private lateinit var fake: FakeCloud
    private val states = mutableListOf<State>()
    private val apis = mutableListOf<CloudApi>()

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("cloud-repo-sync-")
        fake = FakeCloud()
    }

    @After
    fun tearDown() {
        states.forEach { runCatching { it.shutdown() } }
        apis.forEach { runCatching { it.close() } }
        runCatching { fake.close() }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    /**
     * A desk with its own database and working trees. [adopted] makes it an install the fake holds, which
     * signs its git tokens; otherwise it syncs with [devToken], or with nothing.
     */
    private inner class Desk(name: String, adopted: Boolean = true, devToken: String? = null, push: Boolean = true) {
        val state = State(
            testAppConfig(
                "database.path" to tmp.resolve("$name.db").toString(),
                "sync.workingTreeRoot" to tmp.resolve("$name-sync").toString(),
                "sync.push" to push.toString(),
            ),
        ).also { states += it }
        val installId = "inst-$name"
        val signer: InstallSigner? = if (adopted) {
            val pair = InstallKeys.generate()
            fake.installs[installId] = FakeCloud.Install(installId, "adp-$name", pair.public)
            InstallSigner(installId, pair.private)
        } else {
            null
        }
        val api = CloudApi(fake.baseUrl, Duration.ofSeconds(5)).also { apis += it }
        val source = CloudCredentialSource(fake.baseUrl, enabled = true, signer = { signer }, adopted = { signer != null }, devToken = devToken, api = { api })
        val credentials = InMemoryCredentialStore()
        val auth = AuthResolver(credentials, tokenStore = null, tokenProvider = null, cloud = source)
        val engine = RemoteSyncEngine(state, auth)

        fun attach(projectId: Int, url: String, autoSync: Boolean = false) = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?: DaoSyncConfig.new { project = DaoProject[projectId] }
            cfg.repoUrl = url
            cfg.autoSyncEnabled = autoSync
        }

        fun sync(projectId: Int): SyncRunResult {
            val (uuid, install) = transaction(state.database) { DaoProject[projectId].uuid to DaoInstall.all().first() }
            return runBlocking { engine.runSync(projectId, uuid, install.uuid, install.friendlyName) }
        }

        fun apply(projectId: Int): SyncRunResult {
            val (uuid, install) = transaction(state.database) { DaoProject[projectId].uuid to DaoInstall.all().first() }
            return runBlocking { engine.applySession(projectId, uuid, install.uuid, install.friendlyName) }
        }

        fun importRepo(url: String): Int = runBlocking { CloudSyncImporter(state, auth).import(url, null, null) }.projectId

        fun publish(venueId: Int, note: String) =
            runBlocking { state.venueVersions.locked { engine.publishVenue(venueId, note, "Tester") } }

        fun uuidOf(projectId: Int): String = transaction(state.database) { DaoProject[projectId].uuid.toString() }

        fun addStack(projectId: Int, name: String) = transaction(state.database) {
            DaoCueStack.new { project = DaoProject[projectId]; this.name = name }
        }

        fun stacks(projectId: Int): Set<String> = transaction(state.database) {
            DaoCueStack.find { DaoCueStacks.project eq projectId }.map { it.name }.toSet()
        }

        fun moveTruss(venueId: Int, dx: Double) = transaction(state.database) {
            DaoRigging.find { DaoRiggings.project eq venueId }.orderBy(DaoRiggings.sortOrder to SortOrder.ASC)
                .first().apply { positionX = (positionX ?: 0.0) + dx }
        }
    }

    /** Desk A's show, pushed to a new cloud repo. */
    private fun showOnTheCloud(a: Desk): Pair<Int, RepoDto> {
        val projectId = seedMinimalProject(a.state)
        a.addStack(projectId, "shared")
        val repo = fake.repos.create(a.uuidOf(projectId), RepoKind.SHOW, "Panto")
        a.attach(projectId, repo.url)
        val pushed = a.sync(projectId)
        assertEquals(SyncOutcome.PUSHED, pushed.outcome, pushed.message)
        assertEquals(pushed.headSha, fake.repos.head(repo.repoId))
        return projectId to repo
    }

    @Test
    fun `a show synced to the cloud from one desk is pulled by a second, each signing its own git tokens`() {
        val a = Desk("a")
        val (showA, repo) = showOnTheCloud(a)
        assertTrue(fake.repos.gitRequests.isNotEmpty())
        assertTrue(fake.repos.gitRequests.all { it.second == InstallGitTokens.BASIC_USER }, "${fake.repos.gitRequests}")

        val b = Desk("b")
        val showB = b.importRepo(repo.url)
        assertEquals(setOf("shared"), b.stacks(showB))

        a.addStack(showA, "from-A")
        assertEquals(SyncOutcome.PUSHED, a.sync(showA).outcome)
        val pulled = b.sync(showB)
        assertEquals(SyncOutcome.FAST_FORWARDED, pulled.outcome, pulled.message)
        assertEquals(setOf("shared", "from-A"), b.stacks(showB))
        assertEquals(fake.repos.head(repo.repoId), pulled.headSha)
    }

    @Test
    fun `a conflict made on two desks is resolved on the second and reaches the first through the cloud`() {
        val a = Desk("a")
        val (showA, repo) = showOnTheCloud(a)
        val b = Desk("b")
        val showB = b.importRepo(repo.url)
        fun rename(desk: Desk, projectId: Int, to: String) = transaction(desk.state.database) {
            DaoCueStack.find { DaoCueStacks.project eq projectId }.single().name = to
        }

        rename(a, showA, "by-A")
        assertEquals(SyncOutcome.PUSHED, a.sync(showA).outcome)
        rename(b, showB, "by-B")
        val conflicted = b.sync(showB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, conflicted.outcome, conflicted.message)
        transaction(b.state.database) {
            val session = ConflictSession.findActive(showB)!!
            val conflict = ConflictSession.listConflicts(session).single()
            ConflictSession.resolve(session, listOf(ResolutionEntry(conflict.tableName, conflict.recordUuid, ConflictResolution.LOCAL.name)))
        }
        val applied = b.apply(showB)
        assertEquals(SyncOutcome.MERGED, applied.outcome, applied.message)
        assertEquals(applied.headSha, fake.repos.head(repo.repoId), "the resolution was pushed to the cloud")

        assertEquals(SyncOutcome.FAST_FORWARDED, a.sync(showA).outcome)
        assertEquals(setOf("by-B"), a.stacks(showA))
    }

    @Test
    fun `a venue's versions reach the cloud with their tags, and a second desk publishing a taken number is VENUE_VERSION_TAKEN`() {
        val a = Desk("a")
        val f = seedShowOnVenue(a.state)
        val venueA = f.venueId
        val repo = fake.repos.create(a.uuidOf(venueA), RepoKind.VENUE, "Commemoration Hall")
        a.attach(venueA, repo.url)
        val pushed = a.sync(venueA)
        assertEquals(SyncOutcome.PUSHED, pushed.outcome, pushed.message)
        assertEquals(setOf("v1", "v2"), fake.repos.tags(repo.repoId).keys)

        val b = Desk("b")
        val venueB = b.importRepo(repo.url)
        assertEquals(listOf(1, 2), transaction(b.state.database) { VenueVersions.versionsOf(venueB).map { it.number }.sorted() })

        a.moveTruss(venueA, 1.0)
        assertEquals(3, a.publish(venueA, "A's v3").number)
        assertEquals(setOf("v1", "v2", "v3"), fake.repos.tags(repo.repoId).keys)
        val v3 = fake.repos.tags(repo.repoId).getValue("v3")

        b.moveTruss(venueB, -2.0)
        val refused = assertFailsWith<SyncException> { b.publish(venueB, "B's v3") }
        assertEquals(SyncErrorCode.VENUE_VERSION_TAKEN, refused.code, refused.message)
        assertEquals(v3, fake.repos.tags(repo.repoId).getValue("v3"), "the cloud's v3 is still A's")
    }

    @Test
    fun `a push the cloud refuses as not fast-forward is pulled and pushed again`() {
        val a = Desk("a")
        val (showA, repo) = showOnTheCloud(a)
        a.addStack(showA, "more")
        fake.repos.refuseNextPush = ErrorCode.SYNC_NON_FAST_FORWARD
        val before = fake.repos.pushes.get()

        val result = a.sync(showA)
        assertEquals(SyncOutcome.PUSHED, result.outcome, result.message)
        assertEquals(2, fake.repos.pushes.get() - before, "the refused push, then the retry")
        assertEquals(result.headSha, fake.repos.head(repo.repoId))
    }

    @Test
    fun `a push past the org's storage is SYNC_QUOTA_EXCEEDED in the cloud's words, and auto-sync backs off`() {
        val a = Desk("a")
        val (showA, repo) = showOnTheCloud(a)
        val head = fake.repos.head(repo.repoId)
        a.addStack(showA, "too much")
        fake.repos.refusePushes = ErrorCode.SYNC_QUOTA_EXCEEDED

        val refused = assertFailsWith<SyncException> { a.sync(showA) }
        assertEquals(SyncErrorCode.SYNC_QUOTA_EXCEEDED, refused.code)
        assertTrue("the fake cloud refuses every push" in refused.message!!, refused.message)
        assertTrue("Ask the org's owner for more storage." in refused.message!!, refused.message)
        assertEquals(head, fake.repos.head(repo.repoId))
        val logged = SyncLogger(a.state).list(showA).first()
        assertTrue(logged.message.startsWith("SYNC_QUOTA_EXCEEDED:"), logged.message)

        a.attach(showA, repo.url, autoSync = true)
        val tick = runBlocking { AutoSyncScheduler(a.state, a.engine).runOneTick(showA) }
        assertIs<AutoSyncScheduler.TickResult.Blocked>(tick)
        assertTrue(tick.reason.startsWith("SYNC_QUOTA_EXCEEDED"), tick.reason)
    }

    @Test
    fun `a venue publish past the org's storage is SYNC_QUOTA_EXCEEDED and leaves no version behind`() {
        val a = Desk("a")
        val venueA = seedShowOnVenue(a.state).venueId
        val repo = fake.repos.create(a.uuidOf(venueA), RepoKind.VENUE, "Commemoration Hall")
        a.attach(venueA, repo.url)
        assertEquals(SyncOutcome.PUSHED, a.sync(venueA).outcome)
        a.moveTruss(venueA, 1.0)
        fake.repos.refusePushes = ErrorCode.SYNC_QUOTA_EXCEEDED

        val refused = assertFailsWith<SyncException> { a.publish(venueA, "v3") }
        assertEquals(SyncErrorCode.SYNC_QUOTA_EXCEEDED, refused.code, refused.message)
        assertEquals(setOf("v1", "v2"), fake.repos.tags(repo.repoId).keys)
        assertEquals(2, transaction(a.state.database) { VenueVersions.versionsOf(venueA).maxOf { it.number } })
    }

    @Test
    fun `a repo deleted in the portal is REPO_NOT_FOUND, and an org without cloud sync is SYNC_OFF, each backed off`() {
        val a = Desk("a")
        val (showA, repo) = showOnTheCloud(a)
        a.attach(showA, repo.url, autoSync = true)

        fake.repos.syncOff = true
        val off = assertFailsWith<SyncException> { a.sync(showA) }
        assertEquals(SyncErrorCode.SYNC_OFF, off.code, off.message)
        assertTrue("fake cloud: SYNC_OFF" in off.message!!, off.message)
        assertTrue("turn cloud sync on" in off.message!!, off.message)
        fake.repos.syncOff = false

        fake.repos.delete(repo.repoId)
        val gone = assertFailsWith<SyncException> { a.sync(showA) }
        assertEquals(SyncErrorCode.REPO_NOT_FOUND, gone.code, gone.message)
        assertTrue("fake cloud: REPO_NOT_FOUND" in gone.message!!, gone.message)
        assertTrue("sync to a new one" in gone.message!!, gone.message)

        val tick = runBlocking { AutoSyncScheduler(a.state, a.engine).runOneTick(showA) }
        assertIs<AutoSyncScheduler.TickResult.Blocked>(tick)
        assertTrue(tick.reason.startsWith("REPO_NOT_FOUND"), tick.reason)
    }

    @Test
    fun `an unadopted desk syncs with a developer token, and without one is CLOUD_NOT_ADOPTED`() {
        val a = Desk("a")
        val (showA, repo) = showOnTheCloud(a)
        val token = "l7_dev_01TEST_" + "x".repeat(43)
        fake.repos.devTokens[token] = false

        val c = Desk("c", adopted = false, devToken = token, push = false)
        val showC = c.importRepo(repo.url)
        assertEquals(setOf("shared"), c.stacks(showC))
        assertTrue(fake.repos.gitRequests.any { it.second == "developer" })
        a.addStack(showA, "later")
        a.sync(showA)
        assertEquals(SyncOutcome.FAST_FORWARDED, c.sync(showC).outcome)
        assertEquals(setOf("shared", "later"), c.stacks(showC))

        val d = Desk("d", adopted = false)
        assertEquals(SyncErrorCode.CLOUD_NOT_ADOPTED, assertFailsWith<SyncException> { d.importRepo(repo.url) }.code)
        val showD = seedMinimalProject(d.state)
        d.attach(showD, repo.url)
        val refused = assertFailsWith<SyncException> { d.sync(showD) }
        assertEquals(SyncErrorCode.CLOUD_NOT_ADOPTED, refused.code)
        assertTrue("Cloud tab" in refused.message!!, refused.message)
    }

    @Test
    fun `a 401 or 403 in a remote's words is an auth failure, the digits in its URL never one`() {
        val url = "https://cloud.example/git/org-403/repo-401.git"
        assertFalse(JGitClient.isAuthFailure("$url: not found.", url))
        assertFalse(JGitClient.isAuthFailure("Remote repository https://cloud.example/git/o/r403.git is unavailable", null))
        assertTrue(JGitClient.isAuthFailure("$url: 403 Forbidden", url))
        assertTrue(JGitClient.isAuthFailure("https://github.com/owner/show.git: not authorized", null))
        assertTrue(JGitClient.isAuthFailure("https://github.com/owner/show.git: Authentication is required but no CredentialsProvider has been registered", null))
        assertTrue(JGitClient.isAuthFailure("https://github.com/owner/show.git: 401 Unauthorized", null))
    }

    @Test
    fun `importing a repo deleted in the portal is REPO_NOT_FOUND, and with cloud sync off SYNC_OFF`() {
        val a = Desk("a")
        val (_, repo) = showOnTheCloud(a)
        val b = Desk("b")

        fake.repos.syncOff = true
        val off = assertFailsWith<SyncException> { b.importRepo(repo.url) }
        assertEquals(SyncErrorCode.SYNC_OFF, off.code, off.message)
        assertTrue("turn cloud sync on" in off.message!!, off.message)
        fake.repos.syncOff = false

        fake.repos.delete(repo.repoId)
        val gone = assertFailsWith<SyncException> { b.importRepo(repo.url) }
        assertEquals(SyncErrorCode.REPO_NOT_FOUND, gone.code, gone.message)
        assertTrue("sync to a new one" in gone.message!!, gone.message)
    }

    @Test
    fun `sync_changed brings auto-sync forward for its project, at most once in ten seconds, and never for one with auto-sync off`() {
        val a = Desk("a")
        val (showA, repo) = showOnTheCloud(a)
        val offShow = seedMinimalProject(a.state, projectName = "Off")
        a.attach(showA, repo.url, autoSync = true)
        a.attach(offShow, repo.url, autoSync = false)
        a.addStack(showA, "nudged")
        val now = AtomicLong(0)
        val nudges = SyncNudges()
        val githubShow = seedMinimalProject(a.state, projectName = "On GitHub")
        val github = "https://github.com/owner/show.git"
        a.attach(githubShow, github, autoSync = true)
        val scheduler = AutoSyncScheduler(a.state, a.engine, nudges, cloudBaseUrl = fake.baseUrl, nanoClock = { now.get() })
        try {
            val pushedBefore = lastSynced(a, showA)
            scheduler.start()
            assertTrue(nudges.offer(repo.url))
            runBlocking {
                withTimeout(30.seconds) { while (lastSynced(a, showA) == pushedBefore) delay(50) }
            }
            assertEquals(lastSynced(a, showA), fake.repos.head(repo.repoId), "the nudge ran the sync, which pushed")
            assertEquals(null, lastSynced(a, offShow))

            assertEquals(emptySet(), scheduler.nudge(repo.url), "a second nudge within ten seconds")
            now.addAndGet(AutoSyncScheduler.NUDGE_SPACING_NANOS)
            assertEquals(setOf(showA), scheduler.nudge(repo.url))
            assertEquals(emptySet(), scheduler.nudge("${fake.baseUrl}/git/org-1/other.git"))
            now.addAndGet(AutoSyncScheduler.NUDGE_SPACING_NANOS)
            assertEquals(emptySet(), scheduler.nudge(github), "a URL that is not this cloud's wakes nothing")
            val respelled = java.net.URI(repo.url)
                .let { java.net.URI(it.scheme.uppercase(), null, it.host.uppercase(), it.port, it.path, null, null).toString() }
            assertEquals(setOf(showA), scheduler.nudge(respelled), "another spelling of the repo's URL")
        } finally {
            scheduler.stop()
        }
    }

    private fun lastSynced(desk: Desk, projectId: Int): String? = transaction(desk.state.database) {
        DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()?.lastSyncedSha
    }

    @Test
    fun `a git token and a developer token are scrubbed from a log line`() {
        val a = Desk("a")
        val (_, repo) = showOnTheCloud(a)
        val credentials = runBlocking { a.auth.resolveFor(repo.url) }
        assertEquals(InstallGitTokens.BASIC_USER, credentials.username)
        val line = "push to ${repo.url} as ${credentials.username}:${credentials.secret} with l7_dev_01TEST_abc-DEF_123 failed"
        val scrubbed = TokenScrubber.scrub(line)
        assertFalse(credentials.secret in scrubbed, scrubbed)
        assertFalse("l7_dev_" in scrubbed, scrubbed)
        assertTrue(repo.url in scrubbed, scrubbed)
    }

    @Test
    fun `a GitHub repo still resolves through its own credentials beside the cloud`() {
        val a = Desk("a")
        val github = "https://github.com/owner/show.git"
        assertFalse(a.auth.isCloudRepo(github))
        assertFailsWith<MissingCredentialsException> { runBlocking { a.auth.resolveFor(github) } }
        a.credentials.set(github, "a-pat")
        assertEquals(GitCredentials("x-access-token", "a-pat"), runBlocking { a.auth.resolveFor(github) })
    }
}
