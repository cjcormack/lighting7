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

    // ─── Phase 7 tombstone scenarios ──────────────────────────────

    @Test
    fun `tombstone propagates A to B (no conflict)`() {
        val (projectIdA, projectIdB, sharedUuid) = setUpSharedStackOnBothSides()

        // A deletes the shared stack and pushes the tombstone.
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.forEach { it.delete() }
        }
        val pushA = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.PUSHED, pushA.outcome)

        // B has no local edits to the shared stack, so the tombstone fast-forwards.
        val resultB = runSync(stateB, engineB, projectIdB)
        assertTrue(
            resultB.outcome == SyncOutcome.FAST_FORWARDED || resultB.outcome == SyncOutcome.MERGED,
            "Expected B to absorb A's tombstone cleanly; got ${resultB.outcome}",
        )

        val stillThere = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.toList()
        }
        assertTrue(stillThere.isEmpty(), "Tombstone must remove B's local row")
    }

    @Test
    fun `same-record EDIT_DELETE conflict, resolve LOCAL keeps the record`() {
        // A deletes; B edits — surface as EDIT_DELETE on B. (`local edited, remote deleted`)
        val (_, projectIdB, sharedUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.forEach { it.delete() }
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.first().name = "edited-by-B"
        }

        val result = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, result.outcome)
        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            val c = ConflictSession.listConflicts(session).single()
            assertEquals(ConflictKind.EDIT_DELETE.name, c.conflictKind)
            ConflictSession.resolve(
                session,
                listOf(ResolutionEntry("cueStacks", sharedUuid, ConflictResolution.LOCAL.name)),
            )
        }
        applySync(stateB, engineB, projectIdB)

        val nameOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.firstOrNull()?.name
        }
        assertEquals("edited-by-B", nameOnB, "LOCAL on EDIT_DELETE must keep the record alive on B")

        // A pulls and gets the record back (resurrected by B's resolution).
        val resultA = runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        assertEquals(SyncOutcome.FAST_FORWARDED, resultA.outcome)
        val nameOnA = transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.firstOrNull()?.name
        }
        assertEquals("edited-by-B", nameOnA)
    }

    @Test
    fun `same-record EDIT_DELETE conflict, resolve REMOTE accepts the deletion`() {
        val (_, projectIdB, sharedUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.forEach { it.delete() }
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.first().name = "edited-by-B"
        }
        runSync(stateB, engineB, projectIdB)

        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            ConflictSession.resolve(
                session,
                listOf(ResolutionEntry("cueStacks", sharedUuid, ConflictResolution.REMOTE.name)),
            )
        }
        applySync(stateB, engineB, projectIdB)

        val rowOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.toList()
        }
        assertTrue(rowOnB.isEmpty(), "REMOTE on EDIT_DELETE must accept A's deletion")
    }

    @Test
    fun `same-record DELETE_EDIT conflict, resolve LOCAL keeps the deletion`() {
        // B deletes; A edits — surface as DELETE_EDIT on B. (`local deleted, remote edited`)
        val (_, projectIdB, sharedUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.first().name = "edited-by-A"
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.forEach { it.delete() }
        }

        val result = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, result.outcome)
        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            val c = ConflictSession.listConflicts(session).single()
            assertEquals(ConflictKind.DELETE_EDIT.name, c.conflictKind)
            ConflictSession.resolve(
                session,
                listOf(ResolutionEntry("cueStacks", sharedUuid, ConflictResolution.LOCAL.name)),
            )
        }
        applySync(stateB, engineB, projectIdB)

        val rowOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.toList()
        }
        assertTrue(rowOnB.isEmpty(), "LOCAL on DELETE_EDIT must keep the deletion")
    }

    @Test
    fun `same-record DELETE_EDIT conflict, resolve REMOTE restores the edit`() {
        val (_, projectIdB, sharedUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.first().name = "edited-by-A"
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.forEach { it.delete() }
        }
        runSync(stateB, engineB, projectIdB)

        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            ConflictSession.resolve(
                session,
                listOf(ResolutionEntry("cueStacks", sharedUuid, ConflictResolution.REMOTE.name)),
            )
        }
        applySync(stateB, engineB, projectIdB)

        val nameOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.firstOrNull()?.name
        }
        assertEquals("edited-by-A", nameOnB, "REMOTE on DELETE_EDIT must restore A's edit")
    }

    @Test
    fun `concurrent identical deletes auto-merge with no conflict`() {
        val (projectIdA, projectIdB, sharedUuid) = setUpSharedStackOnBothSides()

        // Both sides delete the same stack between syncs.
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.forEach { it.delete() }
        }
        runSync(stateA, engineA, projectIdA)
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.forEach { it.delete() }
        }
        val resultB = runSync(stateB, engineB, projectIdB)
        // Both arrived at the same tombstone — no conflict, auto-merge.
        assertTrue(
            resultB.outcome == SyncOutcome.MERGED || resultB.outcome == SyncOutcome.FAST_FORWARDED,
            "Concurrent identical deletes must not conflict; got ${resultB.outcome}",
        )
        val rowOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedUuid }.toList()
        }
        assertTrue(rowOnB.isEmpty())
    }

    @Test
    fun `delete with no intervening sync still produces a tombstone on next snapshot`() {
        // Sequence: create + sync, delete, snapshot/sync. The deletion happens entirely on
        // one install — the test asserts that the snapshot pipeline picks up the deletion
        // from `sync_state` even though the wipe-then-export step nuked the record path.
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val stackUuid = transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            DaoCueStack.new { this.project = project; this.name = "ephemeral"; this.palette = emptyList() }.uuid
        }
        runSync(stateA, engineA, projectIdA)

        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq stackUuid }.forEach { it.delete() }
        }
        val push = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.PUSHED, push.outcome)

        // Verify the working tree has a tombstone file for the deleted UUID.
        val tombstonePath = workingRootA
            .resolve(transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }.toString())
            .resolve("repo")
            .resolve("tombstones").resolve("cueStacks").resolve("$stackUuid.json")
        assertTrue(Files.exists(tombstonePath), "Snapshot must write a tombstone file at $tombstonePath")
    }
}
