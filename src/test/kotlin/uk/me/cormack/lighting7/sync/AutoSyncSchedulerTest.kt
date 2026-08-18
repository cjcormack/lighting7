package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncSession
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

    /** Resolve what `runOneTick` passes to the engine, so tests can mirror a real tick. */
    private fun runArgs(projectId: Int): Triple<java.util.UUID, java.util.UUID, String> =
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }

    @Test
    fun `a manual run writes the full set of log entries`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)

        // Drive the engine directly to mirror what runOneTick does — with the MANUAL
        // default, i.e. what pressing "Sync now" produces.
        val (projectUuid, installUuid, installFriendly) = runArgs(projectId)
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

        val (projectUuid, installUuid, installFriendly) = runArgs(projectId)
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

    // ─── Quiet auto ticks ───────────────────────────────────────────────
    //
    // At the 60s floor an auto tick used to write four rows a minute against a capped
    // per-project log, so a day-old failure or a manual run had always scrolled out of
    // existence. A tick that changed nothing now writes nothing.

    @Test
    fun `an auto tick that changes nothing writes no log entries`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        val (projectUuid, installUuid, installFriendly) = runArgs(projectId)

        // First tick has the initial export to push, so it does have news.
        engine.runSync(projectId, projectUuid, installUuid, installFriendly, SyncTrigger.AUTO)
        val afterFirst = syncLogger.list(projectId, limit = SyncLogger.MAX_LIST_LIMIT).size

        val second = engine.runSync(projectId, projectUuid, installUuid, installFriendly, SyncTrigger.AUTO)
        assertEquals(SyncOutcome.NO_OP, second.outcome, "nothing changed between the two ticks")
        assertEquals(
            afterFirst,
            syncLogger.list(projectId, limit = SyncLogger.MAX_LIST_LIMIT).size,
            "an idle auto tick must be completely silent",
        )
    }

    @Test
    fun `an auto tick that pushes still logs the snapshot and the outcome`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        val (projectUuid, installUuid, installFriendly) = runArgs(projectId)

        engine.runSync(projectId, projectUuid, installUuid, installFriendly, SyncTrigger.AUTO)

        val entries = syncLogger.list(projectId, limit = SyncLogger.MAX_LIST_LIMIT)
        assertTrue(entries.any { it.event == SyncLogEvent.SNAPSHOT_TAKEN }, "a commit is history")
        assertTrue(entries.any { it.event == SyncLogEvent.RUN_DONE }, "a push is worth a row")
        // RUN_STARTED is the one row that never survives an auto tick: the terminal row
        // already says everything, and this one doubled the log's growth rate.
        assertTrue(entries.none { it.event == SyncLogEvent.RUN_STARTED })
    }

    @Test
    fun `a failed auto tick still logs RUN_FAILED`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        credentialStore.delete(bareRepo.toUri().toString())
        val (projectUuid, installUuid, installFriendly) = runArgs(projectId)

        try {
            engine.runSync(projectId, projectUuid, installUuid, installFriendly, SyncTrigger.AUTO)
        } catch (_: SyncException) {
            // expected
        }
        // Quiet mode suppresses "nothing happened", never "this is broken".
        assertTrue(syncLogger.list(projectId).any { it.event == SyncLogEvent.RUN_FAILED })
    }

    // ─── Tick classification ────────────────────────────────────────────
    //
    // The loop's *timing* still isn't test-driven, but its classification is: two real bugs
    // (a false "recovered" for a deleted project, and a cancellation logged as an unexpected
    // error) lived here precisely because nothing exercised it.

    @Test
    fun `a tick for a project with auto-sync disabled is idle, not a recovery`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = false)

        // Idle rather than Ran: nothing ran, so the loop must not announce a recovery — which
        // it did when both cases shared one result.
        assertEquals(AutoSyncScheduler.TickResult.Idle, scheduler.runOneTick(projectId))
        assertTrue(syncLogger.list(projectId).none { it.event == SyncLogEvent.AUTO_SYNC_RECOVERED })
    }

    @Test
    fun `a tick for a project that no longer exists is idle`() = runBlocking {
        // 4 billion projects from now. Stands in for "deleted under us", which is the real
        // way this arises — project deletion doesn't reschedule the loop.
        assertEquals(AutoSyncScheduler.TickResult.Idle, scheduler.runOneTick(Int.MAX_VALUE))
    }

    @Test
    fun `a tick that cannot authenticate is blocked`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        credentialStore.delete(bareRepo.toUri().toString())

        val result = scheduler.runOneTick(projectId)
        assertTrue(result is AutoSyncScheduler.TickResult.Blocked, "expected Blocked, got $result")
        assertTrue(result.reason.contains("MISSING_CREDENTIALS"), "reason was: ${result.reason}")
        // The engine's row is the record; the scheduler adds none of its own for this case.
        assertTrue(syncLogger.list(projectId).any { it.event == SyncLogEvent.RUN_FAILED })
    }

    @Test
    fun `recovery is announced even though reconnecting relaunches the loop`() = runBlocking {
        // Reconnecting GitHub calls reschedule, so the tick that recovers is the first one a
        // brand-new coroutine runs. When the "was blocked" memory lived in the loop, that tick
        // had no idea it was recovering and the feed was left ending on the failures — the exact
        // thing AUTO_SYNC_RECOVERED exists to prevent, missing from the commonest recovery.
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        credentialStore.delete(bareRepo.toUri().toString())
        assertTrue(scheduler.runOneTick(projectId) is AutoSyncScheduler.TickResult.Blocked)

        // Credentials restored, loop relaunched from scratch: no in-coroutine state survives.
        credentialStore.set(bareRepo.toUri().toString(), "test-pat")
        scheduler.reschedule(projectId)
        assertEquals(AutoSyncScheduler.TickResult.Ran, scheduler.runOneTick(projectId))

        val recovered = syncLogger.list(projectId).firstOrNull {
            it.event == SyncLogEvent.AUTO_SYNC_RECOVERED
        }
        assertNotNull(recovered, "a recovery after a blocked spell must leave a visible row")
        assertTrue(recovered.message.contains("1 blocked attempt"), "was: ${recovered.message}")
    }

    @Test
    fun `a recovery is announced once, not on every later tick`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        credentialStore.delete(bareRepo.toUri().toString())
        scheduler.runOneTick(projectId)
        credentialStore.set(bareRepo.toUri().toString(), "test-pat")

        scheduler.runOneTick(projectId)
        scheduler.runOneTick(projectId)

        assertEquals(
            1,
            syncLogger.list(projectId).count { it.event == SyncLogEvent.AUTO_SYNC_RECOVERED },
            "the memory must be consumed by the first success",
        )
    }

    @Test
    fun `a tick blocked by a conflict session carries its own log event`() = runBlocking {
        val projectId = seedMinimalProject(state)
        configureSync(projectId, autoSyncEnabled = true, intervalMs = 60_000)
        val (projectUuid, installUuid, installFriendly) = runArgs(projectId)
        engine.runSync(projectId, projectUuid, installUuid, installFriendly)
        // Inserted directly: manufacturing a genuine conflict needs two diverging repos, and
        // what's under test here is only the scheduler's reaction to an active session.
        transaction(state.database) {
            DaoSyncSession.new {
                this.project = DaoProject.findById(projectId)!!
                this.startedAtMs = System.currentTimeMillis()
                this.state = SessionState.CONFLICTS_PENDING.name
            }
        }

        val result = scheduler.runOneTick(projectId)
        assertTrue(result is AutoSyncScheduler.TickResult.Blocked, "expected Blocked, got $result")
        // Unlike a failed run, nothing else records this, so the result carries the event for
        // the loop to write once on the transition.
        assertEquals(SyncLogEvent.AUTO_SYNC_SKIPPED, result.logEvent)
    }

    // ─── Backoff curve ──────────────────────────────────────────────────
    //
    // A dead credential used to be retried 1,440 times a day per project, forever.

    @Test
    fun `a healthy tick keeps the configured interval`() {
        assertEquals(60_000L, AutoSyncScheduler.nextDelayMs(60_000L, 0))
        assertEquals(60_000L, AutoSyncScheduler.nextDelayMs(60_000L, -1))
    }

    @Test
    fun `consecutive failures double the wait up to the ceiling`() {
        val base = AutoSyncScheduler.MIN_INTERVAL_MS
        val expected = listOf(
            1 to 120_000L,      // 2m
            2 to 240_000L,      // 4m
            3 to 480_000L,      // 8m
            4 to 960_000L,      // 16m
            5 to 1_920_000L,    // 32m
            6 to AutoSyncScheduler.BACKOFF_CEILING_MS,
            7 to AutoSyncScheduler.BACKOFF_CEILING_MS,
        )
        for ((blocked, wait) in expected) {
            assertEquals(wait, AutoSyncScheduler.nextDelayMs(base, blocked), "after $blocked failure(s)")
        }
    }

    @Test
    fun `a long outage stays at the ceiling rather than overflowing`() {
        // A desk left broken over a holiday reaches a high exponent; 1L shl 64 wraps.
        for (blocked in listOf(30, 62, 63, 64, 1_000)) {
            assertEquals(
                AutoSyncScheduler.BACKOFF_CEILING_MS,
                AutoSyncScheduler.nextDelayMs(AutoSyncScheduler.MIN_INTERVAL_MS, blocked),
                "after $blocked failure(s)",
            )
        }
    }

    @Test
    fun `backoff never shortens an interval longer than the ceiling`() {
        val sixHourly = 6 * 60 * 60_000L
        assertEquals(sixHourly, AutoSyncScheduler.nextDelayMs(sixHourly, 1))
        assertEquals(sixHourly, AutoSyncScheduler.nextDelayMs(sixHourly, 12))
        assertTrue(sixHourly > AutoSyncScheduler.BACKOFF_CEILING_MS, "premise of this test")
    }

}
