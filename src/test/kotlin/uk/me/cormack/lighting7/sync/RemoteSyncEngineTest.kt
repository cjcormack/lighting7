package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests for [RemoteSyncEngine] against a local bare git repo.
 *
 * Each scenario configures `sync_configs` for the seeded project, points it at a bare
 * repo on disk (file:// URL), and drives the full pipeline. Auth-rejection is covered by
 * the JGit-level test; here we exercise the orchestration logic, classification → action
 * mapping, force-push, and last-synced bookkeeping.
 */
class RemoteSyncEngineTest {

    private lateinit var workingRoot: Path
    private lateinit var bareRepo: Path
    private lateinit var state: State
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var engine: RemoteSyncEngine

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-rsync-")
        bareRepo = Files.createTempDirectory("lighting7-rsync-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()

        state = State(testAppConfig("sync.workingTreeRoot" to workingRoot.toString()))
        credentialStore = InMemoryCredentialStore()
        engine = RemoteSyncEngine(state, credentialStore)
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { workingRoot.toFile().deleteRecursively() }
        runCatching { bareRepo.toFile().deleteRecursively() }
    }

    private fun configureSync(projectId: Int, repoUrl: String, pat: String? = "test-pat") {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?: DaoSyncConfig.new { this.project = project }
            cfg.repoUrl = repoUrl
            cfg.enabled = true
        }
        if (pat != null) credentialStore.set(repoUrl, pat)
    }

    private fun runSync(projectId: Int): SyncRunResult {
        val (projectUuid, installUuid, installName) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        return runBlocking {
            engine.runSync(
                projectId = projectId,
                projectUuid = projectUuid,
                installUuid = installUuid,
                installFriendlyName = installName,
            )
        }
    }

    @Test
    fun `first sync pushes the initial commit and updates lastSyncedSha`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, bareRepo.toUri().toString())

        val result = runSync(projectId)
        assertEquals(SyncOutcome.PUSHED, result.outcome)
        assertTrue(result.headSha.length == 40)

        val (sha, ts) = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.first()
            cfg.lastSyncedSha to cfg.lastSyncedAtMs
        }
        assertEquals(result.headSha, sha)
        assertNotNull(ts)
    }

    @Test
    fun `second sync with no changes is a no-op`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, bareRepo.toUri().toString())
        runSync(projectId)
        val second = runSync(projectId)
        assertEquals(SyncOutcome.NO_OP, second.outcome)
    }

    @Test
    fun `local change syncs as PUSHED`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, bareRepo.toUri().toString())
        runSync(projectId)
        // Mutate DB.
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoCueStack.new {
                this.project = project
                this.name = "Stack 1"
                this.palette = emptyList()
            }
        }
        val result = runSync(projectId)
        assertEquals(SyncOutcome.PUSHED, result.outcome)
        assertTrue(result.pushed >= 1)
    }

    @Test
    fun `missing PAT yields MISSING_PAT error`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, bareRepo.toUri().toString(), pat = null)
        try {
            runSync(projectId)
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.MISSING_PAT, e.code)
        }
    }

    @Test
    fun `missing repo URL yields REPO_URL_MISSING error`() {
        val projectId = seedMinimalProject(state)
        // Configure WITHOUT setting repoUrl — `enabled` flips on, repoUrl stays null.
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoSyncConfig.new {
                this.project = project
                this.enabled = true
            }
        }
        try {
            runSync(projectId)
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.REPO_URL_MISSING, e.code)
        }
    }

    @Test
    fun `disabled sync is rejected`() {
        val projectId = seedMinimalProject(state)
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoSyncConfig.new {
                this.project = project
                this.repoUrl = bareRepo.toUri().toString()
                this.enabled = false
            }
        }
        credentialStore.set(bareRepo.toUri().toString(), "pat")
        try {
            runSync(projectId)
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.SYNC_DISABLED, e.code)
        }
    }

    @Test
    fun `format too new on remote aborts sync without modifying DB`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, bareRepo.toUri().toString())
        runSync(projectId)

        // Hand-craft a remote commit whose formatVersion.json is too new.
        val remoteWorkdir = Files.createTempDirectory("lighting7-fakeremote-")
        try {
            val cloneRepo = JGitClient.init(remoteWorkdir)
            cloneRepo.use { repo ->
                JGitClient.setRemote(repo, "origin", bareRepo.toUri().toString())
                JGitClient.fetch(repo, "origin", "main", GitCredentials("", ""))
                val originSha = repo.resolve("refs/remotes/origin/main")!!.name
                Git(repo).branchCreate().setName("main").setStartPoint(originSha).setForce(true).call()
                JGitClient.resetHard(repo, originSha)
                Files.writeString(remoteWorkdir.resolve("formatVersion.json"), """{"formatVersion":99,"minReader":99}""" + "\n")
                JGitClient.stageAll(repo)
                JGitClient.commit(repo, "Future", "future@example.com", "from-the-future")
                JGitClient.push(repo, "origin", "main", GitCredentials("", ""), force = true)
            }
        } finally {
            // Don't delete remoteWorkdir until test ends so the bare repo's pack files have stable names.
        }

        // Take a no-op snapshot first (DB hasn't moved) so we hit the fetch step cleanly.
        // Then sync — should refuse with FORMAT_TOO_NEW. The local DB and last-synced SHA must remain unchanged.
        val (preSha, preAt) = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.first()
            cfg.lastSyncedSha to cfg.lastSyncedAtMs
        }
        try {
            runSync(projectId)
            fail("expected SyncException FORMAT_TOO_NEW")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.FORMAT_TOO_NEW, e.code)
        }
        val (postSha, postAt) = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.first()
            cfg.lastSyncedSha to cfg.lastSyncedAtMs
        }
        assertEquals(preSha, postSha, "lastSyncedSha must not advance on FORMAT_TOO_NEW")
        assertEquals(preAt, postAt, "lastSyncedAtMs must not advance on FORMAT_TOO_NEW")

        runCatching { remoteWorkdir.toFile().deleteRecursively() }
    }

    @Test
    fun `diverged history with disjoint changes auto-merges (Phase 5)`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, bareRepo.toUri().toString())
        runSync(projectId)

        // Stranger commit on the remote — adds a non-record file that the diff ignores.
        // The point is to give us a remote tip that's strictly ahead of our last sync,
        // so when we add a divergent local change we'll classify as `Diverged`.
        val strangerWorkdir = Files.createTempDirectory("lighting7-stranger-")
        try {
            JGitClient.init(strangerWorkdir).use { repo ->
                JGitClient.setRemote(repo, "origin", bareRepo.toUri().toString())
                JGitClient.fetch(repo, "origin", "main", GitCredentials("", ""))
                val originSha = repo.resolve("refs/remotes/origin/main")!!.name
                Git(repo).branchCreate().setName("main").setStartPoint(originSha).setForce(true).call()
                JGitClient.resetHard(repo, originSha)
                Files.writeString(strangerWorkdir.resolve("stranger.txt"), "hi\n")
                JGitClient.stageAll(repo)
                JGitClient.commit(repo, "Stranger", "stranger@example.com", "from-stranger")
                JGitClient.push(repo, "origin", "main", GitCredentials("", ""), force = true)
            }
        } finally {
            runCatching { strangerWorkdir.toFile().deleteRecursively() }
        }

        // Locally, change the DB so we have a divergent local commit too. Phase 5
        // auto-merges since the changes touch disjoint records (the stranger added a
        // non-record file; we added a new cue stack).
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoCueStack.new {
                this.project = project
                this.name = "Local Stack"
                this.palette = emptyList()
            }
        }

        val result = runSync(projectId)
        assertEquals(SyncOutcome.MERGED, result.outcome)
        assertEquals(0, result.replaced, "Phase 5 must not drop remote commits")

        // Local cue stack must survive the merge (we chose TakeLocal for it via the diff).
        val stackNames = transaction(state.database) {
            DaoProject.findById(projectId)!!.cueStacks.map { it.name }.toSet()
        }
        assertTrue("Local Stack" in stackNames, "Auto-merge should preserve local-only records")
    }

    @Test
    fun `pull (RemoteAhead) replaces DB from working tree`() {
        // Seed and sync project A — bare repo now has the initial state at SHA-A.
        val projectId = seedMinimalProject(state)
        configureSync(projectId, bareRepo.toUri().toString())
        runSync(projectId)
        val origUuid = transaction(state.database) { DaoProject.findById(projectId)!!.uuid }

        // Simulate a "remote install" advancing the bare repo by cloning it to a scratch
        // working tree, adding a cue stack JSON, and pushing as a child commit of SHA-A.
        // This produces the linear-history setup the original install will fast-forward
        // through. We talk straight to JGit + the working tree here; spinning up a second
        // RemoteSyncEngine on a separate DB to do the same thing would be functionally
        // equivalent but a lot more setup.
        val scratch = Files.createTempDirectory("lighting7-scratch-")
        try {
            JGitClient.init(scratch).use { repo ->
                JGitClient.setRemote(repo, "origin", bareRepo.toUri().toString())
                JGitClient.fetch(repo, "origin", "main", GitCredentials("", ""))
                val originSha = repo.resolve("refs/remotes/origin/main")!!.name
                Git(repo).branchCreate().setName("main").setStartPoint(originSha).setForce(true).call()
                JGitClient.resetHard(repo, originSha)

                // Add a single cue stack JSON file with the canonical layout the importer
                // expects. The UUID is fresh; the cueStackUuid → project linkage isn't
                // present here because cue stacks reference project via FK only, not via
                // a JSON pointer.
                val stackUuid = java.util.UUID.randomUUID().toString()
                val cueStacksDir = scratch.resolve("cueStacks")
                Files.createDirectories(cueStacksDir)
                Files.writeString(
                    cueStacksDir.resolve("$stackUuid.json"),
                    """{"loop":false,"name":"From-other","palette":[],"uuid":"$stackUuid"}""" + "\n",
                )
                JGitClient.stageAll(repo)
                JGitClient.commit(repo, "RemoteInstall", "remote@example.com", "from-other-install")
                val pushResult = JGitClient.push(repo, "origin", "main", GitCredentials("", ""))
                assertEquals(PushStatus.OK, pushResult.status)
            }
        } finally {
            runCatching { scratch.toFile().deleteRecursively() }
        }

        // Original side: local HEAD == lastSyncedSha == SHA-A; remote == SHA-A + 1.
        // No local DB changes so snapshot is a no-op; classification is RemoteAhead → fast-forward.
        val pulled = runSync(projectId)
        assertEquals(SyncOutcome.FAST_FORWARDED, pulled.outcome)
        assertEquals(1, pulled.pulled)

        val stackNames = transaction(state.database) {
            val p = DaoProject.findById(projectId)!!
            p.cueStacks.map { it.name }.toSet()
        }
        assertTrue("From-other" in stackNames, "fast-forward should have populated DB; got $stackNames")
    }
}
