package uk.me.cormack.lighting7.sync

import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the working-tree lifecycle: idempotent init, metadata-file preservation
 * during clean, and end-to-end deletion semantics when JGit picks up the wipe.
 */
class SyncWorkingTreeTest {

    private lateinit var workingRoot: Path
    private lateinit var state: State
    private lateinit var workingTree: SyncWorkingTree

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-sync-root-")
        state = State(testAppConfig("sync.workingTreeRoot" to workingRoot.toString()))
        workingTree = SyncWorkingTree(state)
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { workingRoot.toFile().deleteRecursively() }
    }

    @Test
    fun `pathFor uses configured root and project uuid`() {
        val uuid = UUID.randomUUID()
        val path = workingTree.pathFor(uuid)
        assertEquals(workingRoot.resolve(uuid.toString()).resolve("repo"), path)
    }

    @Test
    fun `ensureInitialised is idempotent and writes metadata files`() {
        val uuid = UUID.randomUUID()
        val path = workingTree.pathFor(uuid)

        workingTree.ensureInitialised(path).close()
        assertTrue(Files.isDirectory(path.resolve(".git")))
        assertTrue(Files.exists(path.resolve(".gitignore")))
        assertTrue(Files.exists(path.resolve(".gitattributes")))
        assertTrue(
            Files.readString(path.resolve(".gitattributes")).contains("eol=lf"),
            "gitattributes should normalise line endings",
        )

        // Calling again on an existing repo must not blow away history.
        Files.writeString(path.resolve("hello.txt"), "world\n")
        JGitClient.open(path)!!.use { repo ->
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "T", "t@local", "first")
        }

        workingTree.ensureInitialised(path).use { repo ->
            assertEquals(1, JGitClient.log(repo).size)
        }
    }

    @Test
    fun `cleanTrackedFiles preserves git, gitignore, gitattributes`() {
        val uuid = UUID.randomUUID()
        val path = workingTree.pathFor(uuid)
        workingTree.ensureInitialised(path).close()

        // Plant some content that should be wiped.
        Files.createDirectories(path.resolve("cues"))
        Files.writeString(path.resolve("cues/a.json"), "{}")
        Files.writeString(path.resolve("project.json"), "{}")
        Files.writeString(path.resolve(".gitignore"), "custom\n")

        workingTree.cleanTrackedFiles(path)

        assertFalse(Files.exists(path.resolve("project.json")))
        assertFalse(Files.exists(path.resolve("cues/a.json")))
        assertFalse(Files.exists(path.resolve("cues")))
        assertTrue(Files.exists(path.resolve(".gitignore")))
        assertTrue(Files.exists(path.resolve(".gitattributes")))
        assertTrue(Files.isDirectory(path.resolve(".git")))
    }

    @Test
    fun `clean then re-add surfaces deletion in git status`() {
        val uuid = UUID.randomUUID()
        val path = workingTree.pathFor(uuid)
        workingTree.ensureInitialised(path).close()

        // Establish baseline tree with one tracked file.
        Files.writeString(path.resolve("project.json"), "{}\n")
        JGitClient.open(path)!!.use { repo ->
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "T", "t@local", "baseline")
        }

        workingTree.cleanTrackedFiles(path)
        // Re-export simulated by writing a different file (project.json is gone).
        Files.writeString(path.resolve("other.json"), "{}\n")

        JGitClient.open(path)!!.use { repo ->
            assertTrue(
                JGitClient.stageAll(repo),
                "wipe + new file should stage both deletion and addition",
            )
            JGitClient.commit(repo, "T", "t@local", "wipe")
            assertEquals(2, JGitClient.log(repo).size)
        }
    }
}
