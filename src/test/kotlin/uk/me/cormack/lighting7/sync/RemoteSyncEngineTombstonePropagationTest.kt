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
import uk.me.cormack.lighting7.models.DaoSyncState
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7's resurrection-bug regression test. Three installs sync against the same bare
 * repo:
 *  * **A** creates a record, syncs, deletes it, syncs (pushes the tombstone).
 *  * **C** has never seen the record. C pulls; without carry-forward, C's `sync_state`
 *    might lose the tombstone, and on C's next snapshot+push the record could resurrect
 *    on A's next pull.
 *
 * The test asserts that C absorbs the tombstone (live row deleted, `sync_state` row marked
 * `isDeleted=true`) and that C's next push doesn't drop the deletion. A's pull after C
 * confirms the record stays gone — no resurrection.
 */
class RemoteSyncEngineTombstonePropagationTest {

    private lateinit var bareRepo: Path
    private lateinit var workingRootA: Path
    private lateinit var workingRootC: Path
    private lateinit var stateA: State
    private lateinit var stateC: State
    private lateinit var credsA: InMemoryCredentialStore
    private lateinit var credsC: InMemoryCredentialStore
    private lateinit var engineA: RemoteSyncEngine
    private lateinit var engineC: RemoteSyncEngine

    private val repoUrl: String get() = bareRepo.toUri().toString()

    @Before
    fun setUp() {
        bareRepo = Files.createTempDirectory("lighting7-tombstone-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()

        val tmpDir = Files.createTempDirectory("lighting7-tombstone-dbs-")
        workingRootA = Files.createTempDirectory("lighting7-tombstone-a-")
        workingRootC = Files.createTempDirectory("lighting7-tombstone-c-")

        stateA = State(
            testAppConfig(
                "database.path" to tmpDir.resolve("a.db").toString(),
                "sync.workingTreeRoot" to workingRootA.toString(),
            ),
        )
        credsA = InMemoryCredentialStore()
        engineA = RemoteSyncEngine(stateA, AuthResolver(credsA, tokenStore = null, tokenProvider = null))

        stateC = State(
            testAppConfig(
                "database.path" to tmpDir.resolve("c.db").toString(),
                "sync.workingTreeRoot" to workingRootC.toString(),
            ),
        )
        credsC = InMemoryCredentialStore()
        engineC = RemoteSyncEngine(stateC, AuthResolver(credsC, tokenStore = null, tokenProvider = null))
    }

    @After
    fun tearDown() {
        runCatching { stateA.shutdown() }
        runCatching { stateC.shutdown() }
        runCatching { workingRootA.toFile().deleteRecursively() }
        runCatching { workingRootC.toFile().deleteRecursively() }
        runCatching { bareRepo.toFile().deleteRecursively() }
    }

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
        val triple = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        return runBlocking { engine.runSync(projectId, triple.first, triple.second, triple.third) }
    }

    @Test
    fun `tombstone propagates A to C and survives C's next push (no resurrection)`() {
        // ─── Phase 1: A creates a record, syncs ──────────────────────────────
        val projectIdA = uk.me.cormack.lighting7.testsupport.seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val recordUuid = transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            DaoCueStack.new { this.project = project; this.name = "to-be-deleted"; this.palette = emptyList() }.uuid
        }
        runSync(stateA, engineA, projectIdA)
        val projectUuid = transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }

        // ─── Phase 2: A deletes + syncs (pushes tombstone) ───────────────────
        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq recordUuid }.forEach { it.delete() }
        }
        val deletePush = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.PUSHED, deletePush.outcome)

        // ─── Phase 3: C bootstraps with A's UUID, pulls everything ───────────
        val projectIdC = transaction(stateC.database) {
            DaoProject.new {
                name = "C-placeholder"
                description = ""
                isCurrent = true
                uuid = projectUuid
            }.id.value
        }
        configureSync(stateC, projectIdC, credsC)
        val pullToC = runSync(stateC, engineC, projectIdC)
        assertTrue(
            pullToC.outcome == SyncOutcome.MERGED || pullToC.outcome == SyncOutcome.FAST_FORWARDED,
            "C should pull A's history clean; got ${pullToC.outcome}",
        )

        // C must NOT have the live record.
        val cHasRecord = transaction(stateC.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq recordUuid }.toList()
        }
        assertTrue(cHasRecord.isEmpty(), "C must have absorbed the tombstone — record should not be present")

        // C's `sync_state` must carry the tombstone with `isDeleted = true` so the next
        // snapshot writes it back out.
        val cTombstoneRow = transaction(stateC.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectIdC }
                .firstOrNull { it.recordUuid == recordUuid }
        }
        assertTrue(cTombstoneRow != null, "C's sync_state must have a row for the tombstoned record")
        assertEquals(true, cTombstoneRow!!.lastSyncedIsDeleted, "Tombstone must carry isDeleted=true")

        // ─── Phase 4: C makes an unrelated edit, pushes ──────────────────────
        // Without carry-forward, the snapshot would drop the tombstone here and C's push
        // would resurrect the record for any peer that later pulls.
        transaction(stateC.database) {
            val project = DaoProject.findById(projectIdC)!!
            DaoCueStack.new {
                this.project = project; this.name = "unrelated-on-C"; this.palette = emptyList()
            }
        }
        val cPush = runSync(stateC, engineC, projectIdC)
        assertTrue(
            cPush.outcome == SyncOutcome.PUSHED || cPush.outcome == SyncOutcome.MERGED,
            "C's push must succeed; got ${cPush.outcome}",
        )

        // The tombstone file must still be present in C's working tree post-push.
        val tombstonePath = workingRootC
            .resolve(projectUuid.toString())
            .resolve("repo")
            .resolve("tombstones").resolve("cueStacks").resolve("$recordUuid.json")
        assertTrue(
            Files.exists(tombstonePath),
            "C's snapshot must carry the tombstone forward; expected file at $tombstonePath",
        )

        // ─── Phase 5: A pulls C's push, must NOT see the resurrected record ──
        val aPull = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.FAST_FORWARDED, aPull.outcome)
        val aHasRecord = transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq recordUuid }.toList()
        }
        assertTrue(
            aHasRecord.isEmpty(),
            "A must not see the deleted record resurrected — that would be the Phase 7 bug.",
        )
    }

    @Test
    fun `tombstone never resurrected even after multiple pull-push cycles on C`() {
        // Stress the carry-forward: C does several pull-push round-trips after absorbing
        // the tombstone. Each cycle re-derives tombstones, re-snapshots, re-pushes — the
        // tombstone must persist in `sync_state` and on disk throughout.
        val projectIdA = uk.me.cormack.lighting7.testsupport.seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val recordUuid = transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            DaoCueStack.new { this.project = project; this.name = "doomed"; this.palette = emptyList() }.uuid
        }
        runSync(stateA, engineA, projectIdA)
        val projectUuid = transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }

        transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq recordUuid }.forEach { it.delete() }
        }
        runSync(stateA, engineA, projectIdA)

        val projectIdC = transaction(stateC.database) {
            DaoProject.new {
                name = "C-placeholder"; description = ""; isCurrent = true; uuid = projectUuid
            }.id.value
        }
        configureSync(stateC, projectIdC, credsC)
        runSync(stateC, engineC, projectIdC)

        // Three cycles of "C makes an unrelated edit + sync."
        repeat(3) { i ->
            transaction(stateC.database) {
                val project = DaoProject.findById(projectIdC)!!
                DaoCueStack.new {
                    this.project = project; this.name = "cycle-$i"; this.palette = emptyList()
                }
            }
            runSync(stateC, engineC, projectIdC)

            val row = transaction(stateC.database) {
                DaoSyncState.find { DaoSyncStates.project eq projectIdC }
                    .firstOrNull { it.recordUuid == recordUuid }
            }
            assertTrue(row != null, "Cycle $i: tombstone row vanished from sync_state")
            assertEquals(true, row!!.lastSyncedIsDeleted, "Cycle $i: tombstone lost its isDeleted flag")
        }

        // A pulls everything; should still not have the record.
        runSync(stateA, engineA, projectIdA)
        val aHasRecord = transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.uuid eq recordUuid }.toList()
        }
        assertTrue(aHasRecord.isEmpty(), "Tombstone must persist across multiple cycles")
    }
}
