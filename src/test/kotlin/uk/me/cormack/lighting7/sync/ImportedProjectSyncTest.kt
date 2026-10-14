package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A project added from its remote repo ([CloudSyncImporter]) syncs like one that has synced
 * before: its `sync_state` is the cloned tree, so the first sync that diverges takes a peer's
 * edit to a record this desk never touched, rather than calling it a conflict, and a deletion
 * made here ships with its tombstone.
 *
 * Desk A builds the project and pushes; desk B imports it. B's local edit to a *different*
 * record is what makes the histories diverge — a desk that changed nothing at all would
 * fast-forward and never reach the three-way diff.
 */
class ImportedProjectSyncTest {

    private lateinit var root: Path
    private lateinit var bareRepo: Path
    private val states = mutableListOf<State>()

    private val repoUrl: String get() = bareRepo.toUri().toString()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lighting7-imported-sync-")
        bareRepo = root.resolve("remote.git")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()
    }

    @After
    fun tearDown() {
        states.forEach { runCatching { it.shutdown() } }
        runCatching { root.toFile().deleteRecursively() }
    }

    private class Desk(val state: State, val engine: RemoteSyncEngine, val creds: InMemoryCredentialStore)

    private fun desk(name: String, push: Boolean = true): Desk {
        val state = State(
            testAppConfig(
                "database.path" to root.resolve("$name.db").toString(),
                "sync.workingTreeRoot" to root.resolve("$name-trees").toString(),
                "sync.push" to push.toString(),
            ),
        ).also { states += it }
        val creds = InMemoryCredentialStore().also { it.set(repoUrl, "test-pat") }
        return Desk(state, RemoteSyncEngine(state, AuthResolver(creds, tokenStore = null, tokenProvider = null)), creds)
    }

    private fun Desk.sync(projectId: Int): SyncRunResult {
        val (projectUuid, installUuid, installName) = transaction(state.database) {
            val install = DaoInstall.all().first()
            Triple(DaoProject[projectId].uuid, install.uuid, install.friendlyName)
        }
        return runBlocking { engine.runSync(projectId, projectUuid, installUuid, installName) }
    }

    private fun Desk.import(): Int = runBlocking {
        CloudSyncImporter(state, AuthResolver(creds, tokenStore = null, tokenProvider = null))
            .import(repoUrl = repoUrl, branch = "main", projectName = null)
            .projectId
    }

    private fun Desk.stackNames(projectId: Int): Map<UUID, String> = transaction(state.database) {
        DaoCueStack.find { DaoCueStacks.project eq projectId }.associate { it.uuid to it.name }
    }

    private fun Desk.rename(stackUuid: UUID, name: String) = transaction(state.database) {
        DaoCueStack.find { DaoCueStacks.uuid eq stackUuid }.single().name = name
    }

    private fun Desk.syncStateRows(projectId: Int): Long = transaction(state.database) {
        DaoSyncState.find { DaoSyncStates.project eq projectId }.count()
    }

    /** Desk A's project, two cue stacks, pushed. Answers its id and the two stacks' uuids. */
    private fun Desk.publish(): Triple<Int, UUID, UUID> {
        val projectId = seedMinimalProject(state)
        val url = repoUrl // inside DaoSyncConfig.new, a bare `repoUrl` is the row's own
        val (edited, other) = transaction(state.database) {
            val project = DaoProject[projectId]
            DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?.also { it.repoUrl = url }
                ?: DaoSyncConfig.new { this.project = project; this.repoUrl = url }
            DaoCueStack.new { this.project = project; name = "edited-by-A" }.uuid to
                DaoCueStack.new { this.project = project; name = "edited-by-B" }.uuid
        }
        assertEquals(SyncOutcome.PUSHED, sync(projectId).outcome)
        return Triple(projectId, edited, other)
    }

    /** B diverges on [other]; A then edits [edited] and pushes; B's sync must merge, not conflict. */
    private fun assertPeerEditMerges(a: Desk, projectA: Int, b: Desk, projectB: Int, edited: UUID, other: UUID) {
        b.rename(other, "B's name")
        a.rename(edited, "A's name")
        assertEquals(SyncOutcome.PUSHED, a.sync(projectA).outcome)

        val result = b.sync(projectB)

        assertEquals(SyncOutcome.MERGED, result.outcome, "B never touched A's stack: ${result.message}")
        assertNull(result.sessionId, "no conflict session")
        val names = b.stackNames(projectB)
        assertEquals("A's name", names[edited])
        assertEquals("B's name", names[other])
    }

    @Test
    fun `an imported desk takes a peer's edit to a record it never touched`() {
        val a = desk("a")
        val (projectA, edited, other) = a.publish()
        val b = desk("b")
        val projectB = b.import()

        assertTrue(b.syncStateRows(projectB) > 0, "the import seeds sync_state from the cloned tree")
        assertPeerEditMerges(a, projectA, b, projectB, edited, other)
    }

    @Test
    fun `a pull-only imported desk takes a peer's edit after keeping a local commit`() {
        val a = desk("a")
        val (projectA, edited, other) = a.publish()
        val b = desk("b", push = false)
        val projectB = b.import()

        // A local commit first: pull-only keeps it and records nothing new as agreed.
        b.rename(other, "B's first name")
        assertEquals(SyncOutcome.NO_OP, b.sync(projectB).outcome)

        assertPeerEditMerges(a, projectA, b, projectB, edited, other)
    }

    @Test
    fun `a desk imported without sync_state is seeded from its last-synced commit`() {
        val a = desk("a")
        val (projectA, edited, other) = a.publish()
        val b = desk("b")
        val projectB = b.import()
        // What an import made before the importer seeded the rows left behind.
        transaction(b.state.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectB }.forEach { it.delete() }
        }

        assertPeerEditMerges(a, projectA, b, projectB, edited, other)
    }

    @Test
    fun `a deletion on an imported desk reaches the peer`() = assertDeletionReachesPeer(clearSyncState = false)

    @Test
    fun `a deletion on a desk imported without sync_state reaches the peer`() = assertDeletionReachesPeer(clearSyncState = true)

    /**
     * B imports, deletes a stack and pushes; A then syncs with a commit of its own, so it reaches
     * the three-way diff — where, without B's tombstone, the stack is merely absent from the remote
     * and A would keep it and push it back. [clearSyncState] takes the rows the import seeded away
     * first, as an import made before the fix left them, so the snapshot's repair has to find them.
     */
    private fun assertDeletionReachesPeer(clearSyncState: Boolean) {
        val a = desk("a")
        val (projectA, edited, other) = a.publish()
        val b = desk("b")
        val projectB = b.import()
        assertTrue(b.syncStateRows(projectB) > 0, "the import seeds sync_state from the cloned tree")
        transaction(b.state.database) {
            if (clearSyncState) DaoSyncState.find { DaoSyncStates.project eq projectB }.forEach { it.delete() }
            DaoCueStack.find { DaoCueStacks.uuid eq other }.single().delete()
        }

        assertEquals(SyncOutcome.PUSHED, b.sync(projectB).outcome)
        a.rename(edited, "A's name")
        assertEquals(SyncOutcome.MERGED, a.sync(projectA).outcome)

        assertEquals(setOf(edited), a.stackNames(projectA).keys, "the tombstone removed it on A")
        assertEquals(SyncOutcome.FAST_FORWARDED, b.sync(projectB).outcome)
        assertEquals(mapOf(edited to "A's name"), b.stackNames(projectB), "and B does not get it back")
    }
}
