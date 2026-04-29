package uk.me.cormack.lighting7.sync

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smoke tests for the JGit wrapper. We don't test JGit itself — just that our
 * wrapper produces the shape downstream code expects (a single linear branch on
 * `main`, commits with the supplied identity, log walks back from HEAD).
 */
class JGitClientTest {

    private lateinit var repoDir: Path

    @Before
    fun setUp() {
        repoDir = Files.createTempDirectory("lighting7-jgit-test-")
    }

    @After
    fun tearDown() {
        runCatching { repoDir.toFile().deleteRecursively() }
    }

    @Test
    fun `init creates an unborn repo on main`() {
        JGitClient.init(repoDir).use { repo ->
            assertTrue(Files.isDirectory(repoDir.resolve(".git")), "expected .git/ directory")
            // HEAD points at refs/heads/main, but the ref doesn't exist yet — the
            // snapshot engine relies on `head()` returning null on an unborn repo.
            assertNull(JGitClient.head(repo))
            assertEquals("refs/heads/main", repo.fullBranch)
        }
    }

    @Test
    fun `open returns null for a directory with no git dir`() {
        // repoDir exists but has no .git/
        assertNull(JGitClient.open(repoDir))
    }

    @Test
    fun `commit roundtrips author identity and message`() {
        JGitClient.init(repoDir).use { repo ->
            Files.writeString(repoDir.resolve("hello.txt"), "world\n")
            assertTrue(JGitClient.stageAll(repo))

            val commit = JGitClient.commit(
                repo,
                authorName = "Test User",
                authorEmail = "test@lighting7.local",
                message = "Initial commit",
            )

            assertEquals("Test User", commit.authorName)
            assertEquals("test@lighting7.local", commit.authorEmail)
            assertEquals("Initial commit", commit.message)

            val head = JGitClient.head(repo)
            assertNotNull(head)
            assertEquals(commit.sha, head!!.sha)

            assertFalse(JGitClient.isWorkingTreeDirty(repo))
        }
    }

    @Test
    fun `log walks commits in reverse chronological order`() {
        JGitClient.init(repoDir).use { repo ->
            Files.writeString(repoDir.resolve("a.txt"), "1\n")
            JGitClient.stageAll(repo)
            val first = JGitClient.commit(repo, "T", "t@local", "first")

            Files.writeString(repoDir.resolve("b.txt"), "2\n")
            JGitClient.stageAll(repo)
            val second = JGitClient.commit(repo, "T", "t@local", "second")

            val log = JGitClient.log(repo, limit = 10)
            assertEquals(2, log.size)
            assertEquals(second.sha, log[0].sha)
            assertEquals(first.sha, log[1].sha)
            assertEquals("first", log[1].message)
            assertEquals("second", log[0].message)
            assertEquals(7, log[0].shortSha.length)
        }
    }

    @Test
    fun `stageAll handles deletions of tracked files`() {
        JGitClient.init(repoDir).use { repo ->
            val file = repoDir.resolve("doomed.txt")
            Files.writeString(file, "rip\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "T", "t@local", "create")

            Files.deleteIfExists(file)
            assertTrue(
                JGitClient.stageAll(repo),
                "deleting a tracked file should stage the removal",
            )

            JGitClient.commit(repo, "T", "t@local", "delete")
            assertFalse(JGitClient.isWorkingTreeDirty(repo))
            assertEquals(2, JGitClient.log(repo).size)
        }
    }

    @Test
    fun `stageAll returns false when nothing changed`() {
        JGitClient.init(repoDir).use { repo ->
            Files.writeString(repoDir.resolve("a.txt"), "1\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "T", "t@local", "first")

            // Re-staging without any working-tree change should report no staged content.
            assertFalse(JGitClient.stageAll(repo))
        }
    }
}
