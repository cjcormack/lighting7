package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the snapshot pipeline. These exercise the full chain
 * (DB → working tree → ProjectExporter → JGit add/commit) so a regression in any
 * single step trips a test here.
 */
class SnapshotEngineTest {

    private lateinit var workingRoot: Path
    private lateinit var state: State
    private lateinit var engine: SnapshotEngine
    private lateinit var workingTree: SyncWorkingTree

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-snapshot-")
        state = State(testAppConfig("sync.workingTreeRoot" to workingRoot.toString()))
        engine = SnapshotEngine(state)
        workingTree = SyncWorkingTree(state)
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { workingRoot.toFile().deleteRecursively() }
    }

    @Test
    fun `first snapshot commits the exported tree`() {
        val projectId = seedMinimalProject(state)

        val result = runBlocking { takeSnapshot(projectId, null) }
        assertFalse(result.noChanges, "first snapshot must commit")
        val commit = result.commit
        assertNotNull(commit)

        val (friendly, shortUuid) = transaction(state.database) {
            val install = DaoInstall.all().first()
            install.friendlyName to install.uuid.toString().take(8)
        }
        assertEquals(friendly, commit!!.authorName)
        assertTrue(
            commit.authorEmail.endsWith("@lighting7.local"),
            "author email should be {shortUuid}@lighting7.local, got ${commit.authorEmail}",
        )
        assertTrue(
            commit.message.startsWith("$friendly: ") &&
                commit.message.endsWith("[install:$shortUuid]"),
            "commit message should be `<friendly>: <summary> [install:<short>]`, got ${commit.message}",
        )

        val path = Path.of(result.workingTreePath)
        assertTrue(Files.isDirectory(path.resolve(".git")))
        assertTrue(Files.exists(path.resolve("project.json")))
        assertTrue(Files.exists(path.resolve("formatVersion.json")))

        JGitClient.open(path)!!.use { repo ->
            assertEquals(1, JGitClient.log(repo).size)
            assertFalse(JGitClient.isWorkingTreeDirty(repo))
        }
    }

    @Test
    fun `second snapshot with no DB change is a noop`() {
        val projectId = seedMinimalProject(state)
        runBlocking { takeSnapshot(projectId, null) }

        val result = runBlocking { takeSnapshot(projectId, "redundant") }
        assertTrue(result.noChanges, "no DB change → no commit")
        assertNull(result.commit)

        val path = workingTree.pathFor(transaction(state.database) {
            DaoProject.findById(projectId)!!.uuid
        })
        JGitClient.open(path)!!.use { repo ->
            assertEquals(1, JGitClient.log(repo).size)
        }
    }

    @Test
    fun `mutation produces a second commit with the user message`() {
        val projectId = seedMinimalProject(state)
        runBlocking { takeSnapshot(projectId, null) }
        newStack(projectId, "Stack 1")

        val result = runBlocking { takeSnapshot(projectId, "added stack") }
        assertFalse(result.noChanges)
        assertTrue(
            result.commit!!.message.contains(": added stack ["),
            "user-supplied summary should appear verbatim in the commit message",
        )

        JGitClient.open(Path.of(result.workingTreePath))!!.use { repo ->
            assertEquals(2, JGitClient.log(repo).size)
        }
    }

    @Test
    fun `deletion shows up as a removed file in the next commit`() {
        val projectId = seedMinimalProject(state)
        val stackId = newStack(projectId, "Doomed")
        runBlocking { takeSnapshot(projectId, "with stack") }

        transaction(state.database) {
            DaoCueStack.findById(stackId)!!.delete()
        }
        val result = runBlocking { takeSnapshot(projectId, "without stack") }
        assertFalse(result.noChanges)

        val cueStacksDir = Path.of(result.workingTreePath).resolve("cueStacks")
        if (Files.exists(cueStacksDir)) {
            val remaining = Files.list(cueStacksDir).use { it.count() }
            assertEquals(0, remaining, "cueStacks/ should be empty after deletion + snapshot")
        }
    }

    @Test
    fun `snapshot fails cleanly on unknown project`() {
        // Real project + install identity, but pass a project id that isn't in the DB —
        // ProjectExporter (called inside the engine) is what enforces existence.
        val install = transaction(state.database) {
            val i = DaoInstall.all().first()
            i.uuid to i.friendlyName
        }
        val ex = runCatching {
            runBlocking {
                engine.snapshot(
                    projectId = 99999,
                    projectUuid = UUID.randomUUID(),
                    installUuid = install.first,
                    installFriendlyName = install.second,
                    message = null,
                )
            }
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message?.contains("Project not found") == true)
    }

    @Test
    fun `null message produces a Snapshot {timestamp} summary`() {
        val projectId = seedMinimalProject(state)
        val result = runBlocking { takeSnapshot(projectId, null) }
        assertTrue(
            result.commit!!.message.contains(": Snapshot "),
            "null message should fall back to `Snapshot <ts>`",
        )
    }

    @Test
    fun `working tree path reflects configured root`() {
        val projectId = seedMinimalProject(state)
        val result = runBlocking { takeSnapshot(projectId, null) }
        val projectUuid = transaction(state.database) { DaoProject.findById(projectId)!!.uuid }
        assertEquals(
            workingRoot.resolve(projectUuid.toString()).resolve("repo").toString(),
            result.workingTreePath,
        )
    }

    @Test
    fun `installs json unions a peer registry across snapshots`() {
        val projectId = seedMinimalProject(state)
        runBlocking { takeSnapshot(projectId, null) }

        val path = workingTree.pathFor(transaction(state.database) {
            DaoProject.findById(projectId)!!.uuid
        })

        // Inject a peer entry into installs.json the way a fetch+merge from another rig
        // would. The installs.json file is checked-in, so we need to mimic the same shape
        // (live registry + valid canonical JSON) and commit it.
        val peerUuid = "ffffeeee-dddd-cccc-bbbb-aaaa99998888"
        val peerName = "Touring Laptop"
        val installsFile = path.resolve("installs.json")
        val merged = uk.me.cormack.lighting7.sync.dto.InstallsJson(
            installs = canonicalDecode(
                uk.me.cormack.lighting7.sync.dto.InstallsJson.serializer(),
                Files.readString(installsFile),
            ).installs + (peerUuid to peerName),
        )
        Files.writeString(installsFile, canonicalEncode(uk.me.cormack.lighting7.sync.dto.InstallsJson.serializer(), merged))

        // Snapshot again — the wipe step should preserve the peer entry through the
        // exporter's union path, so the next installs.json still carries it. The
        // peer-injected installs.json by itself constitutes a tree-level diff so the
        // snapshot will commit even without DB changes.
        runBlocking { takeSnapshot(projectId, "after peer write") }

        val finalRegistry = canonicalDecode(
            uk.me.cormack.lighting7.sync.dto.InstallsJson.serializer(),
            Files.readString(installsFile),
        ).installs
        assertTrue(finalRegistry.containsKey(peerUuid), "peer install entry must survive wipe-then-export")
        assertEquals(peerName, finalRegistry[peerUuid])
        // Local install also still present.
        val localInstall = transaction(state.database) {
            DaoInstall.all().first().uuid.toString()
        }
        assertTrue(finalRegistry.containsKey(localInstall), "local install must remain in registry")
    }

    /**
     * Resolve project + install identity in a transaction and call the engine —
     * the same shape the REST handler uses, so tests exercise the realistic path.
     */
    private suspend fun takeSnapshot(projectId: Int, message: String?): SnapshotResponse {
        val (projectUuid, installUuid, installName) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        return engine.snapshot(
            projectId = projectId,
            projectUuid = projectUuid,
            installUuid = installUuid,
            installFriendlyName = installName,
            message = message,
        )
    }

    private fun newStack(projectId: Int, name: String): Int = transaction(state.database) {
        val project = DaoProject.findById(projectId)!!
        DaoCueStack.new {
            this.project = project
            this.name = name
            this.palette = emptyList()
        }.id.value
    }
}
