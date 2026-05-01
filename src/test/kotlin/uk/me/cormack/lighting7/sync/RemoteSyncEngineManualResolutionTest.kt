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
import uk.me.cormack.lighting7.models.DaoSyncSession
import uk.me.cormack.lighting7.models.DaoSyncSessions
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.dto.CueStackJson
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
 * Phase 6 conflict-resolution coverage:
 *  * MANUAL resolution writes the user-supplied JSON into the working tree and DB.
 *  * Crash recovery: an `APPLYING` session left from a prior JVM is demoted to `FAILED`
 *    on next [State] construction.
 *
 * Mirrors the two-install setup in [RemoteSyncEngineConflictsTest] — the same scenario
 * that produces an EDIT_EDIT conflict, but resolved via MANUAL rather than LOCAL/REMOTE.
 */
class RemoteSyncEngineManualResolutionTest {

    private lateinit var bareRepo: Path
    private lateinit var workingRootA: Path
    private lateinit var workingRootB: Path
    private lateinit var stateA: State
    private lateinit var stateB: State
    private lateinit var credsA: InMemoryCredentialStore
    private lateinit var credsB: InMemoryCredentialStore
    private lateinit var engineA: RemoteSyncEngine
    private lateinit var engineB: RemoteSyncEngine
    private lateinit var dbA: Path
    private lateinit var dbB: Path

    private val repoUrl: String get() = bareRepo.toUri().toString()

    @Before
    fun setUp() {
        bareRepo = Files.createTempDirectory("lighting7-manual-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()

        val tmpDir = Files.createTempDirectory("lighting7-manual-dbs-")
        dbA = tmpDir.resolve("a.db")
        dbB = tmpDir.resolve("b.db")

        workingRootA = Files.createTempDirectory("lighting7-manual-a-")
        stateA = State(testAppConfig(
            "database.path" to dbA.toString(),
            "sync.workingTreeRoot" to workingRootA.toString(),
        ))
        credsA = InMemoryCredentialStore()
        engineA = RemoteSyncEngine(stateA, AuthResolver(credsA, tokenStore = null, tokenProvider = null))

        workingRootB = Files.createTempDirectory("lighting7-manual-b-")
        stateB = State(testAppConfig(
            "database.path" to dbB.toString(),
            "sync.workingTreeRoot" to workingRootB.toString(),
        ))
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

    private fun configureSync(state: State, projectId: Int, creds: InMemoryCredentialStore) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?: DaoSyncConfig.new { this.project = project }
            cfg.repoUrl = repoUrl
            cfg.enabled = true
        }
        creds.set(repoUrl, "test-pat")
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
        runSync(stateB, engineB, projectBId)
        return projectBId
    }

    private fun setUpSharedStackOnBothSides(): Triple<Int, Int, UUID> {
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val sharedUuid = transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            val stack = DaoCueStack.new {
                this.project = project
                this.name = "shared"
                this.palette = emptyList()
            }
            stack.uuid
        }
        runSync(stateA, engineA, projectIdA)
        val projectIdB = seedBFromRemote()
        return Triple(projectIdA, projectIdB, sharedUuid)
    }

    @Test
    fun `MANUAL resolution writes the user-supplied content`() {
        val (projectIdA, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()

        // Both sides edit the same stack — produces an EDIT_EDIT conflict on B's pull.
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-A"
        }
        runSync(stateA, engineA, projectIdA)
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-B"
        }

        val result = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, result.outcome)
        assertNotNull(result.sessionId)

        // Build a MANUAL replacement payload — same shape as the canonical JSON that
        // would have been produced for the cue stack, but with a third name nobody
        // touched yet.
        val manualPayload = canonicalEncode(
            CueStackJson.serializer(),
            CueStackJson(
                uuid = sharedStackUuid.toString(),
                name = "manually-merged",
            ),
        )

        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            ConflictSession.resolve(
                session,
                listOf(
                    ResolutionEntry(
                        tableName = "cueStacks",
                        recordUuid = sharedStackUuid,
                        resolution = ConflictResolution.MANUAL.name,
                        manualValueJson = manualPayload,
                    ),
                ),
            )
            // Persisted manual content survives the round-trip.
            val conflict = ConflictSession.listConflicts(session).single()
            assertEquals(ConflictResolution.MANUAL.name, conflict.resolution)
            assertEquals(manualPayload, conflict.manualValueJson)
        }

        applySync(stateB, engineB, projectIdB)

        val finalNameOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name
        }
        assertEquals("manually-merged", finalNameOnB, "MANUAL resolution must overwrite both A's and B's values")

        // Other installs see the manually-merged value too once they pull.
        val pulledByA = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.FAST_FORWARDED, pulledByA.outcome)
        val finalNameOnA = transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name
        }
        assertEquals("manually-merged", finalNameOnA)
    }

    @Test
    fun `switching MANUAL to REMOTE clears the saved manual content`() {
        val (projectIdA, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-A"
        }
        runSync(stateA, engineA, projectIdA)
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-B"
        }
        runSync(stateB, engineB, projectIdB)

        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            ConflictSession.resolve(session, listOf(
                ResolutionEntry("cueStacks", sharedStackUuid, ConflictResolution.MANUAL.name, "{ \"draft\": true }"),
            ))
            val withDraft = ConflictSession.listConflicts(session).single()
            assertEquals("{ \"draft\": true }", withDraft.manualValueJson, "draft should land")

            // Switching to REMOTE drops the draft so a stale payload can't be applied.
            ConflictSession.resolve(session, listOf(
                ResolutionEntry("cueStacks", sharedStackUuid, ConflictResolution.REMOTE.name),
            ))
            val cleared = ConflictSession.listConflicts(session).single()
            assertEquals(ConflictResolution.REMOTE.name, cleared.resolution)
            assertNull(cleared.manualValueJson)
        }
    }

    @Test
    fun `MANUAL resolution on EDIT_DELETE keeps the record alive with custom content`() {
        // Phase 7: A deletes the shared stack; B edits it. EDIT_DELETE on B (local edited,
        // remote deleted). MANUAL is allowed for EDIT_DELETE — the user can hand-edit the
        // record they want to keep.
        val (projectIdA, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.forEach { it.delete() }
        }
        runSync(stateA, engineA, projectIdA)
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "edited-by-B"
        }
        val result = runSync(stateB, engineB, projectIdB)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, result.outcome)

        // Verify the conflict was classified as EDIT_DELETE (local edited, remote tombstone).
        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            val c = ConflictSession.listConflicts(session).single()
            assertEquals(ConflictKind.EDIT_DELETE.name, c.conflictKind)
        }

        val manualPayload = canonicalEncode(
            CueStackJson.serializer(),
            CueStackJson(
                uuid = sharedStackUuid.toString(),
                name = "manually-rescued",
            ),
        )
        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            ConflictSession.resolve(
                session,
                listOf(
                    ResolutionEntry(
                        tableName = "cueStacks",
                        recordUuid = sharedStackUuid,
                        resolution = ConflictResolution.MANUAL.name,
                        manualValueJson = manualPayload,
                    ),
                ),
            )
        }
        applySync(stateB, engineB, projectIdB)

        val nameOnB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.firstOrNull()?.name
        }
        assertEquals(
            "manually-rescued", nameOnB,
            "MANUAL on EDIT_DELETE must restore the record with the user's content (not the deletion)",
        )
    }

    @Test
    fun `apply rejects MANUAL with no content even after allResolved passes`() {
        // allResolved only checks `resolution != null`. If a row is somehow MANUAL with
        // no manualValueJson — e.g. a hand-crafted DB write — apply must refuse rather
        // than throw an internal `error()`. The route layer normally prevents this, but
        // the engine is the second line of defence.
        val (projectIdA, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-A"
        }
        runSync(stateA, engineA, projectIdA)
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-B"
        }
        runSync(stateB, engineB, projectIdB)

        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            val c = ConflictSession.listConflicts(session).single()
            // Bypass the helper to simulate a corrupted row.
            c.resolution = ConflictResolution.MANUAL.name
            c.manualValueJson = null
        }

        try {
            applySync(stateB, engineB, projectIdB)
            fail("expected UNRESOLVED_CONFLICTS")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.UNRESOLVED_CONFLICTS, e.code)
        }
    }

    @Test
    fun `crash recovery demotes APPLYING sessions to FAILED on next State construction`() {
        val (_, projectIdB, sharedStackUuid) = setUpSharedStackOnBothSides()
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-A"
        }
        runSync(stateA, engineA, transaction(stateA.database) { DaoProject.all().first().id.value })
        transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq sharedStackUuid }.first().name = "by-B"
        }
        runSync(stateB, engineB, projectIdB)

        // Simulate a JVM crash mid-apply: forcibly mark the session APPLYING and
        // then re-construct State (which mimics a process restart pointing at the
        // same SQLite file).
        transaction(stateB.database) {
            val session = ConflictSession.findActive(projectIdB)!!
            session.state = SessionState.APPLYING.name
        }

        stateB.shutdown()
        stateB = State(testAppConfig(
            "database.path" to dbB.toString(),
            "sync.workingTreeRoot" to workingRootB.toString(),
        ))

        // After re-construction, the recovery hook should have demoted the session.
        transaction(stateB.database) {
            val sessions = DaoSyncSession.find { DaoSyncSessions.project eq projectIdB }.toList()
            val applying = sessions.filter { it.state == SessionState.APPLYING.name }
            assertTrue(applying.isEmpty(), "no APPLYING sessions should remain after recovery")
            val failed = sessions.filter { it.state == SessionState.FAILED.name }
            assertEquals(1, failed.size, "the prior APPLYING session should be marked FAILED")
            val msg = failed.single().errorMessage
            assertNotNull(msg)
            assertTrue("interrupted" in msg, "errorMessage should explain why: '$msg'")
        }

        // FAILED is in SessionState.ACTIVE so the discard banner can surface it via
        // findActive — the session remains visible until the user aborts it.
        transaction(stateB.database) {
            val active = ConflictSession.findActive(projectIdB)
            assertNotNull(active)
            assertEquals(SessionState.FAILED.name, active.state)
        }
    }
}
