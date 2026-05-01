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
import uk.me.cormack.lighting7.models.DaoSyncState
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that `sync_state` accurately mirrors the working tree after every terminal
 * sync outcome. The bookkeeping is what makes Phase 5's three-way diff possible — without
 * an accurate `lastSyncedHash` per record we can't tell "I changed this" from "I happen
 * to look like remote".
 */
class SyncStateBookkeepingTest {

    private lateinit var workingRoot: Path
    private lateinit var bareRepo: Path
    private lateinit var state: State
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var engine: RemoteSyncEngine

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-sync-state-")
        bareRepo = Files.createTempDirectory("lighting7-sync-state-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()

        state = State(testAppConfig("sync.workingTreeRoot" to workingRoot.toString()))
        credentialStore = InMemoryCredentialStore()
        engine = RemoteSyncEngine(state, AuthResolver(credentialStore, tokenStore = null, tokenProvider = null))
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { workingRoot.toFile().deleteRecursively() }
        runCatching { bareRepo.toFile().deleteRecursively() }
    }

    private fun configureSync(projectId: Int) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?.also {
                    it.repoUrl = bareRepo.toUri().toString()
                    it.enabled = true
                }
                ?: DaoSyncConfig.new {
                    this.project = project
                    this.repoUrl = bareRepo.toUri().toString()
                    this.enabled = true
                }
        }
        credentialStore.set(bareRepo.toUri().toString(), "test-pat")
    }

    private fun runSync(projectId: Int): SyncRunResult {
        val triple = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        return runBlocking {
            engine.runSync(projectId, triple.first, triple.second, triple.third)
        }
    }

    private fun fetchSyncStates(projectId: Int): List<Pair<RecordKey, String>> = transaction(state.database) {
        DaoSyncState.find { DaoSyncStates.project eq projectId }
            .sortedWith(compareBy({ it.tableName }, { it.recordUuid }))
            .map { RecordKey(it.tableName, it.recordUuid) to it.lastSyncedHash }
    }

    @Test
    fun `PUSHED outcome populates sync_state with one row per record`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId)
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoCueStack.new { this.project = project; this.name = "Stack-1"; this.palette = emptyList() }
            DaoCueStack.new { this.project = project; this.name = "Stack-2"; this.palette = emptyList() }
        }

        val result = runSync(projectId)
        assertEquals(SyncOutcome.PUSHED, result.outcome)

        val states = fetchSyncStates(projectId)
        // 1 universeConfig (from seedMinimalProject) + 2 cueStacks
        assertEquals(3, states.size, "Expected one sync_state row per portable record; got: $states")

        val tables = states.map { it.first.tableName }.toSet()
        assertTrue("cueStacks" in tables)
        assertTrue("universeConfigs" in tables)
        // Every hash is 64 hex chars (SHA-256).
        for ((_, hash) in states) {
            assertEquals(64, hash.length, "expected hex SHA-256, got: $hash")
        }
    }

    @Test
    fun `subsequent NO_OP outcome leaves sync_state stable`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId)
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoCueStack.new { this.project = project; this.name = "Stack-1"; this.palette = emptyList() }
        }
        runSync(projectId)
        val before = fetchSyncStates(projectId)

        val second = runSync(projectId)
        assertEquals(SyncOutcome.NO_OP, second.outcome)
        val after = fetchSyncStates(projectId)
        assertEquals(before, after, "NO_OP must not change sync_state contents")
    }

    @Test
    fun `editing a record bumps its hash on next sync`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId)
        val stackUuid = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoCueStack.new { this.project = project; this.name = "before"; this.palette = emptyList() }.uuid
        }
        runSync(projectId)
        val before = fetchSyncStates(projectId).single { it.first.uuid == stackUuid }.second

        // Mutate the stack.
        transaction(state.database) {
            DaoCueStack.find { uk.me.cormack.lighting7.models.DaoCueStacks.uuid eq stackUuid }.first().name = "after"
        }
        val pushed = runSync(projectId)
        assertEquals(SyncOutcome.PUSHED, pushed.outcome)
        val after = fetchSyncStates(projectId).single { it.first.uuid == stackUuid }.second
        kotlin.test.assertNotEquals(before, after, "Editing the record must change its lastSyncedHash")
    }

    @Test
    fun `deleting a record converts its sync_state row to a tombstone on next sync`() {
        // Phase 7: deletion no longer drops the sync_state row — it flips
        // `lastSyncedIsDeleted = true` so the deletion propagates as a tombstone to peers
        // that haven't seen it yet. Without this, install B (which still has the record)
        // would resurrect it on its next push.
        val projectId = seedMinimalProject(state)
        configureSync(projectId)
        val stackUuid = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoCueStack.new { this.project = project; this.name = "deleteMe"; this.palette = emptyList() }.uuid
        }
        runSync(projectId)
        val initial = transaction(state.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectId }
                .first { it.recordUuid == stackUuid }
        }.let { it.lastSyncedIsDeleted to it.lastSyncedHash }
        assertEquals(false, initial.first, "Record should land as live before deletion")

        transaction(state.database) {
            DaoCueStack.find { uk.me.cormack.lighting7.models.DaoCueStacks.uuid eq stackUuid }.forEach { it.delete() }
        }
        val pushed = runSync(projectId)
        assertEquals(SyncOutcome.PUSHED, pushed.outcome)

        val afterRow = transaction(state.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectId }
                .firstOrNull { it.recordUuid == stackUuid }
        }
        assertTrue(afterRow != null, "Tombstone row must persist (not GC'd) so it propagates to peers")
        assertEquals(true, afterRow!!.lastSyncedIsDeleted, "sync_state must mark the record as deleted")
        kotlin.test.assertNotEquals(
            initial.second, afterRow.lastSyncedHash,
            "Tombstone hash must differ from the live-record hash",
        )
    }
}
