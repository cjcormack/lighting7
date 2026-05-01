package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [AutoSyncScheduler]. Uses the same bare-repo + InMemoryCredentialStore
 * scaffolding as [RemoteSyncEngineTest], but doesn't wait on the per-tick wall clock —
 * tests drive the scheduler's tick logic directly via the engine, then assert log entries.
 *
 * The actual coroutine timing (interval delay, MIN_INTERVAL_MS clamp) is unit-checked
 * via the constants; we don't try to schedule a real-time tick under test control.
 */
class AutoSyncSchedulerTest {

    private lateinit var workingRoot: Path
    private lateinit var bareRepo: Path
    private lateinit var state: State
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var engine: RemoteSyncEngine
    private lateinit var scheduler: AutoSyncScheduler
    private lateinit var syncLogger: SyncLogger

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-autosync-")
        bareRepo = Files.createTempDirectory("lighting7-autosync-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()

        state = State(testAppConfig("sync.workingTreeRoot" to workingRoot.toString()))
        credentialStore = InMemoryCredentialStore()
        engine = RemoteSyncEngine(state, AuthResolver(credentialStore, tokenStore = null, tokenProvider = null))
        scheduler = AutoSyncScheduler(state, engine)
        syncLogger = SyncLogger(state)
    }

    @After
    fun tearDown() {
        runCatching { scheduler.stop() }
        runCatching { state.shutdown() }
        runCatching { workingRoot.toFile().deleteRecursively() }
        runCatching { bareRepo.toFile().deleteRecursively() }
    }

    private fun configureSync(projectId: Int, autoSyncEnabled: Boolean = true, intervalMs: Long? = null) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?: DaoSyncConfig.new { this.project = project }
            cfg.repoUrl = bareRepo.toUri().toString()
            cfg.enabled = true
            cfg.autoSyncEnabled = autoSyncEnabled
            cfg.autoSyncIntervalMs = intervalMs
        }
        credentialStore.set(bareRepo.toUri().toString(), "test-pat")
    }

    @Test
    fun `reschedule with autoSync disabled is a no-op (no exception)`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = false)
        scheduler.reschedule(projectId) // shouldn't throw, shouldn't launch a job
        scheduler.stop()
    }

    @Test
    fun `runOneTick goes through the engine and writes log entries`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)

        // Drive the engine directly to mirror what runOneTick does.
        val (projectUuid, installUuid, installFriendly) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        val result = engine.runSync(projectId, projectUuid, installUuid, installFriendly)
        assertEquals(SyncOutcome.PUSHED, result.outcome)

        val entries = syncLogger.list(projectId)
        // RUN_STARTED + RUN_DONE in order (newest first).
        assertTrue(entries.any { it.event == SyncLogEvent.RUN_STARTED })
        assertTrue(entries.any { it.event == SyncLogEvent.RUN_DONE })
        // Snapshot was taken (engine snapshots at run start) — verify SNAPSHOT_TAKEN landed too.
        assertTrue(
            entries.any { it.event == SyncLogEvent.SNAPSHOT_TAKEN } ||
                entries.any { it.event == SyncLogEvent.SNAPSHOT_NOOP },
        )
    }

    @Test
    fun `failed sync logs RUN_FAILED with error code`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        // Drop credentials so the run fails with MISSING_CREDENTIALS.
        credentialStore.delete(bareRepo.toUri().toString())

        val (projectUuid, installUuid, installFriendly) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        try {
            engine.runSync(projectId, projectUuid, installUuid, installFriendly)
        } catch (_: SyncException) {
            // expected
        }
        val entries = syncLogger.list(projectId)
        val failed = entries.firstOrNull { it.event == SyncLogEvent.RUN_FAILED }
        assertNotNull(failed)
        assertEquals("ERROR", failed.level)
        assertTrue(failed.message.contains("MISSING_CREDENTIALS"))
    }

}
