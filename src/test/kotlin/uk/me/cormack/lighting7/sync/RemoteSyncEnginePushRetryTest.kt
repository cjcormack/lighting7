package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7 push-retry tests.
 *
 * **Coverage limitation:** the engine's `configureAndExchange` fetches and pushes inside
 * one synchronous IO block. To trigger `PUSH_REJECTED` deterministically we'd need a peer
 * to mutate the bare repo *between* our fetch and our push — there's no test seam to
 * insert that, short of refactoring `JGitClient` into an injectable interface. Until that
 * refactor lands (deferred), the retry path is exercised opportunistically here via
 * concurrent `runSync` calls from two engines against a shared bare. The tests assert
 * eventual consistency rather than verifying the retry counter directly.
 */
class RemoteSyncEnginePushRetryTest {

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

    @Before
    fun setUp() {
        bareRepo = Files.createTempDirectory("lighting7-push-retry-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()
        val tmpDir = Files.createTempDirectory("lighting7-push-retry-dbs-")
        workingRootA = Files.createTempDirectory("lighting7-push-retry-a-")
        workingRootB = Files.createTempDirectory("lighting7-push-retry-b-")

        stateA = State(testAppConfig(
            "database.path" to tmpDir.resolve("a.db").toString(),
            "sync.workingTreeRoot" to workingRootA.toString(),
        ))
        credsA = InMemoryCredentialStore()
        engineA = RemoteSyncEngine(stateA, AuthResolver(credsA, tokenStore = null, tokenProvider = null))

        stateB = State(testAppConfig(
            "database.path" to tmpDir.resolve("b.db").toString(),
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

    @Test
    fun `MAX_PUSH_RETRIES is 3 (matches plan doc)`() {
        assertEquals(3, RemoteSyncEngine.MAX_PUSH_RETRIES)
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
    fun `concurrent runSync from two installs both succeed (retry kicks in opportunistically)`() = runBlocking {
        // A pushes initial state; B bootstraps; both make disjoint edits and call runSync
        // concurrently. One will win the push race; the other will see Diverged on its
        // fetch (already-merged scenario, not a real retry) — but if the timing happens to
        // line up so the second's push lands while the bare has moved, the retry in
        // `pushWithRetry` / `retryAfterPushReject` is exercised. Either way the engine must
        // not surface PUSH_REJECTED to the caller for these clean edits.
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        runSync(stateA, engineA, projectIdA)

        val projectAUuid = transaction(stateA.database) { DaoProject.all().first().uuid }
        val projectIdB = transaction(stateB.database) {
            DaoProject.new {
                name = "B-placeholder"; description = ""; isCurrent = true; uuid = projectAUuid
            }.id.value
        }
        configureSync(stateB, projectIdB, credsB)
        runSync(stateB, engineB, projectIdB)

        // Each side adds a unique cue stack — disjoint records, so any race resolves
        // cleanly via auto-merge.
        transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            DaoCueStack.new { this.project = project; this.name = "from-A-${UUID.randomUUID()}"; this.palette = emptyList() }
        }
        transaction(stateB.database) {
            val project = DaoProject.findById(projectIdB)!!
            DaoCueStack.new { this.project = project; this.name = "from-B-${UUID.randomUUID()}"; this.palette = emptyList() }
        }

        val (resultA, resultB) = listOf(
            async { runSync(stateA, engineA, projectIdA) },
            async { runSync(stateB, engineB, projectIdB) },
        ).awaitAll()

        // Both must reach a terminal state — no PUSH_REJECTED bubbling up because either
        // there's no race (the second engine's fetch sees the first's push and runs the
        // Diverged-no-conflict path), or the race triggered the in-engine retry which
        // resolved it. CONFLICTS_PENDING is also acceptable — concurrent inserts in
        // different engines might happen to land on the same key if a generated UUID
        // coincided, though astronomically unlikely with random UUIDs.
        for (r in listOf(resultA, resultB)) {
            assertTrue(
                r.outcome in listOf(
                    SyncOutcome.PUSHED, SyncOutcome.MERGED,
                    SyncOutcome.FAST_FORWARDED, SyncOutcome.NO_OP, SyncOutcome.CONFLICTS_PENDING,
                ),
                "Concurrent runSync produced unexpected outcome: ${r.outcome} (${r.message})",
            )
        }

        // After the race, run one more sync on each side to converge.
        runSync(stateA, engineA, projectIdA)
        runSync(stateB, engineB, projectIdB)
        runSync(stateA, engineA, projectIdA)

        val namesA = transaction(stateA.database) {
            DaoCueStack.find { DaoCueStacks.project eq projectIdA }.map { it.name }.toSet()
        }
        val namesB = transaction(stateB.database) {
            DaoCueStack.find { DaoCueStacks.project eq projectIdB }.map { it.name }.toSet()
        }
        assertEquals(namesA, namesB, "Both installs must converge after the race")
    }
}
