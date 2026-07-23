package uk.me.cormack.lighting7.sync

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
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
import kotlin.test.fail

/**
 * Coverage for [CloudSyncImporter] — clones a remote repo as a fresh local project.
 *
 * Strategy: build a "remote" by exporting + committing a seeded project in **state A**,
 * then tear that down (`IntegrationTestDb.reset()`) and run the importer in a fresh
 * **state B** against the bare repo on disk. file:// remotes don't actually exercise
 * credentials in JGit, but we still seed an [InMemoryCredentialStore] PAT so the
 * happy-path doesn't short-circuit on `MISSING_CREDENTIALS`.
 *
 * `ProjectImporter`-level concerns (UUID collision, name collision, format-too-new) have
 * direct coverage in [ProjectRoundTripTest]; this file focuses on what's new in the
 * cloud-sync layer: the clone step, sync_config persistence, and the pending-dir cleanup
 * on failure.
 */
class CloudSyncImporterTest {

    private lateinit var workingTreeRoot: Path
    private lateinit var perTestRoot: Path
    private lateinit var state: State
    private lateinit var credentialStore: InMemoryCredentialStore

    @Before
    fun setUp() {
        perTestRoot = Files.createTempDirectory("lighting7-import-test-")
        workingTreeRoot = perTestRoot.resolve("working-tree-root")
        IntegrationTestDb.reset()
        state = State(testAppConfig("sync.workingTreeRoot" to workingTreeRoot.toString()))
        credentialStore = InMemoryCredentialStore()
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { perTestRoot.toFile().deleteRecursively() }
    }

    companion object {
        // The "remote" bare repo is identical across tests — building it once shaves ~3×
        // JGit init+clone off the suite's wall-clock. Setup uses its own ephemeral State
        // (then shuts it down) so the per-test DB starts empty.
        private lateinit var sharedRoot: Path
        private lateinit var bareRepo: Path

        @BeforeClass
        @JvmStatic
        fun buildSharedSourceRepo() {
            sharedRoot = Files.createTempDirectory("lighting7-import-test-shared-")
            bareRepo = sharedRoot.resolve("source-bare.git")

            IntegrationTestDb.reset()
            val sourceState = State(testAppConfig(
                "sync.workingTreeRoot" to sharedRoot.resolve("source-working-tree").toString(),
            ))
            try {
                val projectId = seedMinimalProject(sourceState, projectName = "ImportSource")
                val sourceCheckout = sharedRoot.resolve("source-checkout")
                ProjectExporter(sourceState).export(projectId, sourceCheckout)
                Git.init().setDirectory(sourceCheckout.toFile()).setInitialBranch("main").call()
                    .use { git ->
                        git.add().addFilepattern(".").call()
                        git.commit()
                            .setAuthor("Test", "test@example.com")
                            .setCommitter("Test", "test@example.com")
                            .setMessage("seed")
                            .setSign(false)
                            .call()
                    }
                Git.cloneRepository()
                    .setURI(sourceCheckout.toUri().toString())
                    .setDirectory(bareRepo.toFile())
                    .setBare(true)
                    .call()
                    .close()
            } finally {
                sourceState.shutdown()
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanSharedSourceRepo() {
            runCatching { sharedRoot.toFile().deleteRecursively() }
        }
    }

    private fun importer(): CloudSyncImporter =
        CloudSyncImporter(state, AuthResolver(credentialStore, tokenStore = null, tokenProvider = null))

    private fun bareUrl(): String = bareRepo.toUri().toString()

    @Test
    fun `happy path imports a remote project and persists sync_config`() = runBlocking {
        credentialStore.set(bareUrl(), "test-pat")

        val result = importer().import(repoUrl = bareUrl(), branch = "main", projectName = "Imported")

        assertEquals("Imported", result.name)

        // Project + sync_config rows landed. A remote project is synced by definition
        // (repo attached) with auto-sync on by default.
        val (cfgRepoUrl, cfgBranch, cfgAutoSync, cfgLastSha) = transaction(state.database) {
            val project = DaoProject.findById(result.projectId)!!
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.first()
            listOf(cfg.repoUrl, cfg.branch, cfg.autoSyncEnabled, cfg.lastSyncedSha)
        }
        assertEquals(bareUrl(), cfgRepoUrl)
        assertEquals("main", cfgBranch)
        assertEquals(true, cfgAutoSync)
        assertNotNull(cfgLastSha)
        assertTrue((cfgLastSha as String).length == 40)

        // Working tree is at the canonical `<root>/{projectUuid}/repo/` location.
        val workingTreePath = workingTreeRoot.resolve(result.projectUuid).resolve("repo")
        assertTrue(Files.isDirectory(workingTreePath), "working tree should be at canonical location")
        assertTrue(Files.exists(workingTreePath.resolve(".git")), "working tree should be a git repo")

        // Pending parent was cleaned up after the move.
        val leftovers = Files.list(workingTreeRoot).use { stream ->
            stream.filter { it.fileName.toString().startsWith("_import-") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "pending dir should be cleaned up; found: $leftovers")
    }

    @Test
    fun `missing credentials short-circuits with MISSING_CREDENTIALS`() = runBlocking {
        // No PAT, no OAuth identity — AuthResolver throws MissingCredentialsException, which
        // CloudSyncImporter remaps to a SyncException with the right code.
        try {
            importer().import(repoUrl = bareUrl(), branch = "main", projectName = "Imported")
            fail("expected SyncException")
        } catch (e: SyncException) {
            assertEquals(SyncErrorCode.MISSING_CREDENTIALS, e.code)
        }
        // No partial state on disk or in DB.
        assertTrue(
            !Files.exists(workingTreeRoot) || Files.list(workingTreeRoot).use { it.toList() }.isEmpty(),
            "no working trees should be created when credentials are missing",
        )
        transaction(state.database) {
            assertEquals(0, DaoProject.all().count(), "no project should be created")
        }
    }

    @Test
    fun `second import of the same repo collides on UUID`() = runBlocking {
        credentialStore.set(bareUrl(), "test-pat")
        importer().import(repoUrl = bareUrl(), branch = "main", projectName = "Imported")

        // Second import with a different name — UUID collides because it's the same source.
        try {
            importer().import(repoUrl = bareUrl(), branch = "main", projectName = "Other")
            fail("expected ImportError")
        } catch (e: ImportError) {
            assertEquals(HttpStatusCode.Conflict, e.status)
        }

        // The first import's data must still be intact.
        transaction(state.database) {
            assertEquals(1, DaoProject.all().count(), "second import should not have created a project")
        }
        // Pending-dir cleanup ran even though the import threw post-clone.
        val leftovers = Files.list(workingTreeRoot).use { stream ->
            stream.filter { it.fileName.toString().startsWith("_import-") }.toList()
        }
        assertTrue(leftovers.isEmpty(), "pending dir should be cleaned up after a collision; found: $leftovers")
    }

    @Test
    fun `clone of a non-existent branch fails and leaves no working tree behind`() = runBlocking {
        credentialStore.set(bareUrl(), "test-pat")

        try {
            importer().import(repoUrl = bareUrl(), branch = "nonexistent", projectName = "X")
            fail("expected exception for missing branch")
        } catch (e: Exception) {
            // JGit's clone throws TransportException for a missing branch; we just want to
            // confirm cleanup runs and no project landed in the DB. Whether the exception
            // surfaces as GitAuthException or a plain RuntimeException is JGit's call.
        }

        transaction(state.database) {
            assertEquals(0, DaoProject.all().count(), "failed clone should leave DB empty")
        }
        if (Files.exists(workingTreeRoot)) {
            val leftovers = Files.list(workingTreeRoot).use { it.toList() }
            assertTrue(leftovers.isEmpty(), "no pending or canonical trees should remain; found: $leftovers")
        }
    }
}
