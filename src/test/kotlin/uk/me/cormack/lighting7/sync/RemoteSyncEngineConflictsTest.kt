package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.sql.transactions.transaction
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
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 5 conflict-session lifecycle integration tests.
 *
 * Each scenario uses a single shared file:// bare repo and *two* lighting7 installs to
 * simulate true multi-master use. Install A pushes first; Install B then sees the
 * remote ahead of its own changes and either auto-merges (disjoint records) or
 * surfaces a conflict (same record on both sides).
 *
 * The two installs share the test JVM's static class loader, so we keep separate `State`
 * instances pointing at separate SQLite files via [IntegrationTestDb.reset].
 */
class RemoteSyncEngineConflictsTest {

    private lateinit var bareRepo: Path
    private lateinit var workingRootA: Path
    private lateinit var workingRootB: Path

    private lateinit var stateA: State
    private lateinit var stateB: State
    private lateinit var credsA: InMemoryCredentialStore
    private lateinit var credsB: InMemoryCredentialStore
    private lateinit var engineA: RemoteSyncEngine
    private lateinit var engineB: RemoteSyncEngine

    private val repoUrl: String get() = bareRepo.toUri().toString()

    private lateinit var dbA: Path
    private lateinit var dbB: Path

    @Before
    fun setUp() {
        bareRepo = Files.createTempDirectory("lighting7-conflicts-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()

        // Two installs need two distinct SQLite files. The shared IntegrationTestDb
        // singleton rotates one global path and deletes the previous file on each
        // `reset()`, so we can't use it for both — opening State B after resetting would
        // pull the rug out from under State A's connection (`SQLITE_READONLY_DBMOVED`).
        // Allocate fresh paths and override `database.path` per-install.
        val tmpDir = Files.createTempDirectory("lighting7-conflicts-dbs-")
        dbA = tmpDir.resolve("a.db")
        dbB = tmpDir.resolve("b.db")

        workingRootA = Files.createTempDirectory("lighting7-conflicts-a-")
        stateA = State(
            testAppConfig(
                "database.path" to dbA.toString(),
                "sync.workingTreeRoot" to workingRootA.toString(),
            ),
        )
        credsA = InMemoryCredentialStore()
        engineA = RemoteSyncEngine(stateA, AuthResolver(credsA, tokenStore = null, tokenProvider = null))

        workingRootB = Files.createTempDirectory("lighting7-conflicts-b-")
        stateB = State(
            testAppConfig(
                "database.path" to dbB.toString(),
                "sync.workingTreeRoot" to workingRootB.toString(),
            ),
        )
        credsB = InMemoryCredentialStore()
        engineB = RemoteSyncEngine(stateB, AuthResolver(credsB, tokenStore = null, tokenProvider = null))
    }

    @After
    fun tearDown() {
        runCatching { stateA.shutdown() }
        runCatching { stateB.shutdown() }
        runCatching { workingRootA.toFile().deleteRecursively() }
        runCatching { workingRootB.toFile().deleteRecursively() }
        runCatching { bareRepo.toFile().deleteRecursively() }
    }

    private fun configureSync(state: State, projectId: Int, creds: InMemoryCredentialStore, pat: String = "test-pat") {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?: DaoSyncConfig.new { this.project = project }
            cfg.repoUrl = repoUrl
            cfg.enabled = true
        }
        creds.set(repoUrl, pat)
    }

    private fun runSync(state: State, engine: RemoteSyncEngine, projectId: Int): SyncRunResult {
        val triple = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        return runBlocking {
            engine.runSync(projectId, triple.first, triple.second, triple.third)
        }
    }

    private fun applySync(state: State, engine: RemoteSyncEngine, projectId: Int): SyncRunResult {
        val triple = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        return runBlocking {
            engine.applySession(projectId, triple.first, triple.second, triple.third)
        }
    }

    /**
     * Bootstrap install B from A's pushed state: insert an empty project row with A's
     * UUID, then run a sync. The engine sees a "diverged" history (B's first snapshot
     * vs A's pushed commits have no common ancestor) but every record is remote-only
     * (B's empty project has nothing to clash with), so the diff classifies them all as
     * `TakeRemote` and auto-merges. End state on B: same tree as A, project DB
     * populated.
     */
    private fun seedBFromRemote(): Int {
        val projectAUuid = transaction(stateA.database) { DaoProject.all().first().uuid }
        val projectBId = transaction(stateB.database) {
            val project = DaoProject.new {
                name = "B-placeholder"
                description = ""
                isCurrent = true
                uuid = projectAUuid
            }
            project.id.value
        }
        configureSync(stateB, projectBId, credsB)
        val result = runSync(stateB, engineB, projectBId)
        // FAST_FORWARDED would be the ideal classification but B's bootstrap snapshot
        // diverges from A's history (different root commits), so we hit the auto-merge
        // path with all-`TakeRemote` outcomes and report MERGED. Either is "B is now in
        // sync with A".
        assertTrue(
            result.outcome == SyncOutcome.MERGED || result.outcome == SyncOutcome.FAST_FORWARDED,
            "B should pull A's state on first sync; got ${result.outcome}",
        )
        return projectBId
    }

    @Test
    fun `disjoint edits across two installs auto-merge`() {
        // A: create + push initial state with one cue stack.
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            DaoCueStack.new { this.project = project; this.name = "shared"; this.palette = emptyList() }
        }
        runSync(stateA, engineA, projectIdA)

        // B: pull A's initial state.
        val projectIdB = seedBFromRemote()

        // A adds CueStack "from-A".
        transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            DaoCueStack.new { this.project = project; this.name = "from-A"; this.palette = emptyList() }
        }
        runSync(stateA, engineA, projectIdA)

        // B adds CueStack "from-B" before pulling A's new state. Disjoint records → auto-merge.
        transaction(stateB.database) {
            val project = DaoProject.findById(projectIdB)!!
            DaoCueStack.new { this.project = project; this.name = "from-B"; this.palette = emptyList() }
        }
        val resultB = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.MERGED, resultB.outcome, "Disjoint edits must auto-merge")
        assertNull(resultB.sessionId, "Auto-merge must not open a session")

        // B's DB should now contain shared, from-A, and from-B.
        val namesB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.project eq projectIdB }.map { it.name }.toSet()
        }
        assertTrue("shared" in namesB)
        assertTrue("from-A" in namesB)
        assertTrue("from-B" in namesB)

        // A pulls B's merged push and gets all three.
        val resultA = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.FAST_FORWARDED, resultA.outcome)
        val namesA = transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.project eq projectIdA }.map { it.name }.toSet()
        }
        assertEquals(namesB, namesA)
    }

    @Test
    fun `same-record edits surface as conflict, resolve LOCAL keeps local value`() {
        val (projectIdA, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()

        // Both sides edit the same stack to different names.
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "renamed-by-A"
        }
        runSync(stateA, engineA, projectIdA)
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "renamed-by-B"
        }

        // B's runSync should produce a CONFLICTS_PENDING result.
        val result = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, result.outcome)
        val sessionId = result.sessionId
        assertNotNull(sessionId)
        assertEquals(1, result.conflictCount)

        // Inspect the persisted conflict and resolve it as LOCAL (keep B's value).
        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB) ?: error("expected session")
            val conflicts = ConflictSession.listConflicts(session)
            assertEquals(1, conflicts.size)
            val c = conflicts.first()
            assertEquals("cueStacks", c.tableName)
            assertEquals(sharedStackUuid, c.recordUuid)
            assertEquals(ConflictKind.EDIT_EDIT.name, c.conflictKind)
            ConflictSession.resolve(session, listOf(ResolutionEntry("cueStacks", sharedStackUuid, ConflictResolution.LOCAL.name)))
        }

        val applied = applySync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.MERGED, applied.outcome)

        val finalNameOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name
        }
        assertEquals("renamed-by-B", finalNameOnB, "LOCAL resolution should retain B's value")

        // A pulls and ends up with B's value too (since B pushed it as the merged tip).
        val pulledByA = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.FAST_FORWARDED, pulledByA.outcome)
        val finalNameOnA = transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name
        }
        assertEquals("renamed-by-B", finalNameOnA)
    }

    @Test
    fun `same-record edits surface as conflict, resolve REMOTE takes remote value`() {
        val (projectIdA, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()

        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "renamed-by-A"
        }
        runSync(stateA, engineA, projectIdA)
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "renamed-by-B"
        }

        val result = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, result.outcome)
        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            ConflictSession.resolve(session, listOf(ResolutionEntry("cueStacks", sharedStackUuid, ConflictResolution.REMOTE.name)))
        }
        applySync(stateB, engineB, projectIdB)

        val finalNameOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name
        }
        assertEquals("renamed-by-A", finalNameOnB, "REMOTE resolution should take A's value")
    }

    @Test
    fun `runSync refuses to start while a session is open`() {
        val (_, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()
        // Trigger the conflict on B.
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-A"
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-B"
        }
        val first = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, first.outcome)

        // Second runSync while a session is open must fail with SESSION_PENDING.
        try {
            runSync(stateB, engineB, projectIdB)
            fail("expected SyncException SESSION_PENDING")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.SESSION_PENDING, e.code)
        }
    }

    @Test
    fun `abort clears the session`() {
        val (_, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-A"
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-B"
        }
        runSync(stateB, engineB, projectIdB)

        val projectUuidB = transaction(stateB.database) { DaoProject.findById(projectIdB)!!.uuid }
        val abortResult = runBlocking { engineB.abortSession(projectIdB, projectUuidB) }
        assertTrue(abortResult.sessionId > 0)

        // No active session left.
        transaction(stateB.database) {
            assertNull(ConflictSession.findActive(projectIdB))
        }

        // After abort, B's local DB still has its pre-resolution edit (we never applied).
        val nameOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name
        }
        assertEquals("by-B", nameOnB)
    }

    @Test
    fun `apply with unresolved conflicts returns UNRESOLVED_CONFLICTS`() {
        val (_, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-A"
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-B"
        }
        runSync(stateB, engineB, projectIdB)

        try {
            applySync(stateB, engineB, projectIdB)
            fail("expected UNRESOLVED_CONFLICTS")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.UNRESOLVED_CONFLICTS, e.code)
        }
    }

    /**
     * Set up two installs (A, B) with the same project + a shared cue stack, both synced
     * to the same remote tip. Returns `(projectIdA, projectIdB, sharedStackUuid)`.
     */
    private fun setUpSharedStackOnBothSides(): Triple<Int, Int, UUID> {
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val sharedUuid = transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            val stack = DaoCueStack.new { this.project = project; this.name = "shared"; this.palette = emptyList() }
            stack.uuid
        }
        runSync(stateA, engineA, projectIdA)
        val projectIdB = seedBFromRemote()
        return Triple(projectIdA, projectIdB, sharedUuid)
    }
}
