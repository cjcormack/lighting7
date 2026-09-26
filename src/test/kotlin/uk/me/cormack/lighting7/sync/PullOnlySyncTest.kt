package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two installs on one bare remote — A a normal desk, B a second install — for the two
 * rules that keep an install that only *reads* a show from writing to its repo:
 *
 *  * a snapshot whose only change is `installs.json` is not a commit, so a new install
 *    that edits nothing has nothing to push, whatever `sync.push` says;
 *  * `sync.push = false` makes an install pull-only: it still pulls and merges, and its
 *    own commits stay in its working tree.
 *
 * Harness shape follows [RemoteSyncEngineConflictsTest]: separate SQLite files per install,
 * since [uk.me.cormack.lighting7.testsupport.IntegrationTestDb] rotates one global path.
 */
class PullOnlySyncTest {

    private lateinit var bareRepo: Path
    private lateinit var tmpDir: Path
    private lateinit var stateA: State
    private lateinit var credsA: InMemoryCredentialStore
    private lateinit var engineA: RemoteSyncEngine
    private var stateB: State? = null

    private val repoUrl: String get() = bareRepo.toUri().toString()

    @Before
    fun setUp() {
        bareRepo = Files.createTempDirectory("lighting7-pullonly-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()
        tmpDir = Files.createTempDirectory("lighting7-pullonly-")

        stateA = newState("a", push = true)
        credsA = InMemoryCredentialStore()
        engineA = RemoteSyncEngine(stateA, AuthResolver(credsA, tokenStore = null, tokenProvider = null))
    }

    @After
    fun tearDown() {
        runCatching { stateA.shutdown() }
        runCatching { stateB?.shutdown() }
        runCatching { tmpDir.toFile().deleteRecursively() }
        runCatching { bareRepo.toFile().deleteRecursively() }
    }

    private fun newState(name: String, push: Boolean): State = State(
        testAppConfig(
            "database.path" to tmpDir.resolve("$name.db").toString(),
            "sync.workingTreeRoot" to tmpDir.resolve("$name-sync").toString(),
            "sync.push" to push.toString(),
        ),
    )

    private fun configureSync(state: State, projectId: Int, creds: InMemoryCredentialStore) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?: DaoSyncConfig.new { this.project = project }
            cfg.repoUrl = repoUrl
        }
        creds.set(repoUrl, "test-pat")
    }

    private fun runSync(state: State, engine: RemoteSyncEngine, projectId: Int): SyncRunResult {
        val (projectUuid, installUuid, installName) = transaction(state.database) {
            val install = DaoInstall.all().first()
            Triple(DaoProject.findById(projectId)!!.uuid, install.uuid, install.friendlyName)
        }
        return runBlocking { engine.runSync(projectId, projectUuid, installUuid, installName) }
    }

    private fun addStack(state: State, projectId: Int, name: String) {
        transaction(state.database) {
            DaoCueStack.new { project = DaoProject.findById(projectId)!!; this.name = name }
        }
    }

    private fun renameStack(state: State, projectId: Int, from: String, to: String) {
        transaction(state.database) {
            DaoCueStack.find { DaoCueStacks.project eq projectId }.single { it.name == from }.name = to
        }
    }

    private fun stackNames(state: State, projectId: Int): Set<String> = transaction(state.database) {
        DaoCueStack.find { DaoCueStacks.project eq projectId }.map { it.name }.toSet()
    }

    private fun remoteHead(): String = Git.open(bareRepo.toFile()).use { it.repository.resolve("refs/heads/main").name }

    private fun remoteInstalls(): Set<String> = Git.open(bareRepo.toFile()).use { git ->
        val json = JGitClient.readBlob(git.repository, "refs/heads/main", SnapshotEngine.INSTALLS_FILE)!!
        canonicalDecode(InstallsJson.serializer(), json).installs.keys
    }

    private fun installUuid(state: State): String = transaction(state.database) { DaoInstall.all().first().uuid.toString() }

    /** A pushes a project with one stack; returns its id. */
    private fun seedA(): Int {
        val projectId = seedMinimalProject(stateA)
        configureSync(stateA, projectId, credsA)
        addStack(stateA, projectId, "shared")
        assertEquals(SyncOutcome.PUSHED, runSync(stateA, engineA, projectId).outcome)
        return projectId
    }

    /** Stand up B with A's project uuid and pull A's state into it. */
    private fun seedB(push: Boolean, projectIdA: Int): Triple<State, RemoteSyncEngine, Int> {
        val state = newState("b", push).also { stateB = it }
        val creds = InMemoryCredentialStore()
        val engine = RemoteSyncEngine(state, AuthResolver(creds, tokenStore = null, tokenProvider = null))
        val uuidA = transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }
        val projectId = transaction(state.database) {
            DaoProject.new { name = "B-placeholder"; description = ""; isCurrent = true; uuid = uuidA }.id.value
        }
        configureSync(state, projectId, creds)
        runSync(state, engine, projectId)
        assertEquals(setOf("shared"), stackNames(state, projectId))
        return Triple(state, engine, projectId)
    }

    @Test
    fun `a new install that edits nothing pushes nothing, and registers with its first edit`() {
        val projectIdA = seedA()
        val (stateB, engineB, projectIdB) = seedB(push = true, projectIdA)
        val afterPull = remoteHead()

        // Before the registry rule this snapshot committed installs.json alone and pushed it.
        val idle = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.NO_OP, idle.outcome)
        assertEquals(afterPull, remoteHead())
        assertFalse(installUuid(stateB) in remoteInstalls(), "an idle install must not register")

        addStack(stateB, projectIdB, "from-B")
        assertEquals(SyncOutcome.PUSHED, runSync(stateB, engineB, projectIdB).outcome)
        assertTrue(installUuid(stateB) in remoteInstalls(), "B registers with its first real commit")
    }

    @Test
    fun `a pull-only install keeps its edits local and still pulls`() {
        val projectIdA = seedA()
        val (stateB, engineB, projectIdB) = seedB(push = false, projectIdA)
        val seeded = remoteHead()

        // Local edits with the remote unchanged: LocalAhead, and the push is skipped.
        addStack(stateB, projectIdB, "from-B")
        renameStack(stateB, projectIdB, "shared", "shared (B)")
        val ahead = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.NO_OP, ahead.outcome)
        assertTrue(ahead.message.startsWith(RemoteSyncEngine.PUSH_DISABLED_PREFIX), ahead.message)
        assertEquals(seeded, remoteHead(), "nothing reaches the remote")

        // A moves on. B merges A's change locally and still pushes nothing.
        addStack(stateA, projectIdA, "from-A")
        runSync(stateA, engineA, projectIdA)
        val fromA = remoteHead()
        val merged = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.MERGED, merged.outcome)
        assertEquals(0, merged.pushed)
        assertEquals(fromA, merged.headSha, "lastSyncedSha names the remote tip, not the local merge")
        assertEquals(fromA, remoteHead())
        assertEquals(setOf("shared (B)", "from-A", "from-B"), stackNames(stateB, projectIdB))

        // A second remote change. B's rename must survive the next merge too. That rests on
        // the bookkeeping above: the base is A's tip, where the stack is still "shared", so
        // the rename reads as B's local edit. Had the base been B's own merge commit, the
        // rename would read as unchanged locally and the remote's "shared" would win.
        addStack(stateA, projectIdA, "from-A-2")
        runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.MERGED, runSync(stateB, engineB, projectIdB).outcome)
        assertEquals(setOf("shared (B)", "from-A", "from-A-2", "from-B"), stackNames(stateB, projectIdB))

        assertEquals(setOf("shared", "from-A", "from-A-2"), stackNames(stateA, projectIdA))
        assertFalse(installUuid(stateB) in remoteInstalls())
    }
}
