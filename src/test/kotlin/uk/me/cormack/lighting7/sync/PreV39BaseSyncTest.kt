package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncState
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PreV38BaseSyncTest] one version on: a desk that last synced at v38, whose `sync_state` holds a
 * surface binding's hash in the words v39 moved (terminology plan session 6), still takes a peer's
 * edit to that binding rather than conflicting.
 */
class PreV39BaseSyncTest {

    private lateinit var state: State
    private lateinit var root: Path
    private lateinit var bareRepo: Path
    private lateinit var credentialStore: InMemoryCredentialStore

    private val frozen: Path = Path.of("src/test/resources/sync/legacy-v38")
    private val repoUrl: String get() = bareRepo.toUri().toString()
    private val groupFader = UUID.fromString("c37b48a2-64d2-4cf4-9a60-10b53021b325")
    private val warmAmber = UUID.fromString("cf6a2703-659c-4b14-b6d6-8cd83cd5f5b4")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        root = Files.createTempDirectory("lighting7-pre-v39-")
        bareRepo = root.resolve("remote.git")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()
        state = State(testAppConfig("sync.workingTreeRoot" to root.resolve("trees").toString()))
        credentialStore = InMemoryCredentialStore().also { it.set(repoUrl, "test-pat") }
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { root.toFile().deleteRecursively() }
    }

    @Test
    fun `a desk upgraded from v38 takes a peer's edit to a binding it never touched`() {
        val peer = root.resolve("peer")
        pushFromPeer(peer, init = true) { tree -> frozen.toFile().copyRecursively(tree.toFile()) }
        val projectId = runBlocking {
            CloudSyncImporter(state, auth()).import(repoUrl = repoUrl, branch = "main", projectName = null).projectId
        }
        // What the desk recorded when it synced this commit at v38: the hash of each file as it was.
        transaction(state.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectId }.forEach { row ->
                val file = (listOf(row.tableName) + LegacyNames.oldFolders(row.tableName))
                    .map { frozen.resolve(it).resolve("${row.recordUuid}.json") }
                    .firstOrNull(Files::exists) ?: return@forEach
                row.lastSyncedHash = RecordHasher.sha256Hex(Files.readString(file))
            }
        }

        pushFromPeer(peer, init = false) { tree ->
            val binding = tree.resolve("controlSurfaceBindings/$groupFader.json")
            Files.writeString(binding, Files.readString(binding).replace("\"sortOrder\": 30", "\"sortOrder\": 32"))
        }
        transaction(state.database) { DaoLook.find { DaoLooks.uuid eq warmAmber }.single().name = "Warm Amber, ours" }

        val result = sync(projectId)

        assertEquals(SyncOutcome.MERGED, result.outcome, result.message)
        assertNull(result.sessionId, "no conflict session")
        transaction(state.database) {
            val binding = DaoControlSurfaceBinding.find { DaoControlSurfaceBindings.uuid eq groupFader }.single()
            assertEquals(32, binding.sortOrder)
            assertEquals("groupAttribute", binding.targetType)
            assertEquals("Warm Amber, ours", DaoLook.find { DaoLooks.uuid eq warmAmber }.single().name)
        }
    }

    private fun auth() = AuthResolver(credentialStore, tokenStore = null, tokenProvider = null)

    private fun sync(projectId: Int): SyncRunResult {
        val (projectUuid, installUuid, installName) = transaction(state.database) {
            val install = DaoInstall.all().first()
            Triple(DaoProject[projectId].uuid, install.uuid, install.friendlyName)
        }
        return runBlocking { RemoteSyncEngine(state, auth()).runSync(projectId, projectUuid, installUuid, installName) }
    }

    /** An older desk: [edit] its tree, commit and push, starting the remote's history when [init]. */
    private fun pushFromPeer(peer: Path, init: Boolean, edit: (Path) -> Unit) {
        if (init) Files.createDirectories(peer)
        JGitClient.init(peer).use { repo ->
            if (init) {
                JGitClient.setRemote(repo, "origin", repoUrl)
            } else {
                JGitClient.fetch(repo, "origin", "main", GitCredentials("", ""))
                JGitClient.resetHard(repo, repo.resolve("refs/remotes/origin/main")!!.name)
            }
            edit(peer)
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "OlderDesk", "older@example.com", "from a v38 desk")
            assertEquals(PushStatus.OK, JGitClient.push(repo, "origin", "main", GitCredentials("", "")).status)
        }
    }
}
