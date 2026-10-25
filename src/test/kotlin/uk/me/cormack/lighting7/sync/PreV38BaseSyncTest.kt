package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncState
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPalettes
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
 * A desk that last synced at v37 and then upgraded: its `sync_state` holds each record's hash in the
 * old words, which its own export no longer says. Its first merge must still see a record it never
 * touched as unchanged here, so a peer's edit to it lands rather than conflicting.
 */
class PreV38BaseSyncTest {

    private lateinit var state: State
    private lateinit var root: Path
    private lateinit var bareRepo: Path
    private lateinit var credentialStore: InMemoryCredentialStore

    private val frozen: Path = Path.of("src/test/resources/sync/legacy-v37")
    private val repoUrl: String get() = bareRepo.toUri().toString()
    private val amberKey = UUID.fromString("8c68a983-63d2-4ed7-88c3-601639edf9fc")
    private val warmAmber = UUID.fromString("6459bfd2-a46b-4322-8b57-bc0252c1dc63")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        root = Files.createTempDirectory("lighting7-pre-v38-")
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
    fun `a desk upgraded from v37 takes a peer's edit to a record it never touched`() {
        val peer = root.resolve("peer")
        pushFromPeer(peer, init = true) { tree -> frozen.toFile().copyRecursively(tree.toFile()) }
        val projectId = runBlocking {
            CloudSyncImporter(state, auth()).import(repoUrl = repoUrl, branch = "main", projectName = null).projectId
        }
        // What the desk recorded when it synced this commit at v37: the hash of each file as it was.
        transaction(state.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectId }.forEach { row ->
                val file = (listOf(row.tableName) + LegacyNames.oldFolders(row.tableName))
                    .map { frozen.resolve(it).resolve("${row.recordUuid}.json") }
                    .firstOrNull(Files::exists) ?: return@forEach
                row.lastSyncedHash = RecordHasher.sha256Hex(Files.readString(file))
            }
        }

        pushFromPeer(peer, init = false) { tree ->
            val palette = tree.resolve("templates/$amberKey.json")
            Files.writeString(palette, Files.readString(palette).replace("\"name\": \"amber-key\"", "\"name\": \"amber-key, peer's\""))
        }
        transaction(state.database) { DaoLook.find { DaoLooks.uuid eq warmAmber }.single().name = "Warm Amber, ours" }

        val result = sync(projectId)

        assertEquals(SyncOutcome.MERGED, result.outcome, result.message)
        assertNull(result.sessionId, "no conflict session")
        transaction(state.database) {
            assertEquals("amber-key, peer's", DaoPalette.find { DaoPalettes.uuid eq amberKey }.single().name)
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
            JGitClient.commit(repo, "OlderDesk", "older@example.com", "from a v37 desk")
            assertEquals(PushStatus.OK, JGitClient.push(repo, "origin", "main", GitCredentials("", "")).status)
        }
    }
}
