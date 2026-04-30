package uk.me.cormack.lighting7.sync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [JGitClient]'s remote-aware operations against a local bare repo. We don't
 * exercise auth here (no real GitHub) — JGit's HTTP transport accepts a no-op credentials
 * provider for `file://` URLs. Auth-failure paths are covered by the higher-level
 * [RemoteSyncEngineTest] with a deliberately-bad PAT against an unauthenticated remote.
 */
class JGitRemoteTest {

    private lateinit var bareRepoDir: Path
    private lateinit var localRepoA: Path
    private lateinit var localRepoB: Path
    private val noAuth = GitCredentials("", "")

    @Before
    fun setUp() {
        bareRepoDir = Files.createTempDirectory("lighting7-bare-")
        localRepoA = Files.createTempDirectory("lighting7-localA-")
        localRepoB = Files.createTempDirectory("lighting7-localB-")
        Git.init().setBare(true).setDirectory(bareRepoDir.toFile()).setInitialBranch("main").call().close()
    }

    @After
    fun tearDown() {
        listOf(bareRepoDir, localRepoA, localRepoB).forEach {
            runCatching { it.toFile().deleteRecursively() }
        }
    }

    private fun bareUrl(): String = bareRepoDir.toUri().toString()

    private fun seed(repoDir: Path, fileName: String, content: String, message: String): String {
        return JGitClient.init(repoDir).use { repo ->
            Files.writeString(repoDir.resolve(fileName), content)
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Tester", "t@example.com", message).sha
        }
    }

    @Test
    fun `setRemote is idempotent`() {
        JGitClient.init(localRepoA).use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.setRemote(repo, "origin", bareUrl())
            assertEquals(bareUrl(), repo.config.getString("remote", "origin", "url"))
        }
    }

    @Test
    fun `setRemote rewrites a previously stored URL`() {
        JGitClient.init(localRepoA).use { repo ->
            JGitClient.setRemote(repo, "origin", "https://example.com/old.git")
            JGitClient.setRemote(repo, "origin", bareUrl())
            assertEquals(bareUrl(), repo.config.getString("remote", "origin", "url"))
        }
    }

    @Test
    fun `classify reports RemoteAbsent for a fresh remote with no branch`() {
        seed(localRepoA, "a.txt", "1\n", "first")
        JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.fetch(repo, "origin", "main", noAuth) // no remote ref to fetch
            val rel = JGitClient.classify(repo, "HEAD", "refs/remotes/origin/main")
            assertEquals(HistoryRelation.RemoteAbsent, rel)
        }
    }

    @Test
    fun `push then fetch then classify produces Equal`() {
        seed(localRepoA, "a.txt", "1\n", "first")
        JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            val pushed = JGitClient.push(repo, "origin", "main", noAuth)
            assertEquals(PushStatus.OK, pushed.status)
            JGitClient.fetch(repo, "origin", "main", noAuth)
            assertEquals(HistoryRelation.Equal, JGitClient.classify(repo, "HEAD", "refs/remotes/origin/main"))
        }
    }

    @Test
    fun `LocalAhead when local has commits remote doesn't`() {
        seed(localRepoA, "a.txt", "1\n", "first")
        JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.push(repo, "origin", "main", noAuth)
            JGitClient.fetch(repo, "origin", "main", noAuth)

            // Add a second commit locally only.
            Files.writeString(localRepoA.resolve("b.txt"), "2\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Tester", "t@example.com", "second")

            val rel = JGitClient.classify(repo, "HEAD", "refs/remotes/origin/main")
            assertTrue(rel is HistoryRelation.LocalAhead)
            assertEquals(1, (rel as HistoryRelation.LocalAhead).ahead)
        }
    }

    @Test
    fun `RemoteAhead when remote has commits local doesn't`() {
        // Set up a remote with two commits via repoA, then make repoB clone-equivalent at
        // commit one and fetch to discover the second.
        seed(localRepoA, "a.txt", "1\n", "first")
        val firstSha = JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.push(repo, "origin", "main", noAuth)
            JGitClient.head(repo)!!.sha
        }

        // Bootstrap repoB at the same content as repoA (one commit) and push it; then
        // repoA pushes a second commit. Now repoB fetches and should be RemoteAhead.
        seed(localRepoB, "a.txt", "1\n", "first")
        // repoB hasn't fetched origin yet; classify with no remote ref returns RemoteAbsent.
        // We make repoB's local HEAD point at an unrelated history with the same tree —
        // that's fine because classify cares about commit SHAs, not tree content.

        // Add a second commit on repoA and push it to origin.
        JGitClient.open(localRepoA)!!.use { repo ->
            Files.writeString(localRepoA.resolve("b.txt"), "2\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Tester", "t@example.com", "second")
            JGitClient.push(repo, "origin", "main", noAuth)
        }

        // Now reset repoB's HEAD to match firstSha so its history is a strict ancestor of origin/main.
        // We do this by force-pushing repoB back to origin's first commit equivalent — but easier:
        // delete repoB's git dir and re-init from the bare repo's first commit by fetch+resetHard.
        runCatching { localRepoB.toFile().deleteRecursively() }
        Files.createDirectories(localRepoB)
        // Clone-by-fetch: init empty repo, set origin, fetch all refs, then resetHard to firstSha.
        JGitClient.init(localRepoB).use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.fetch(repo, "origin", "main", noAuth)
            // Make the local main branch point at firstSha so origin/main is genuinely ahead.
            org.eclipse.jgit.api.Git(repo).branchCreate()
                .setName("main")
                .setStartPoint(firstSha)
                .setForce(true)
                .call()
            JGitClient.resetHard(repo, firstSha)

            val rel = JGitClient.classify(repo, "HEAD", "refs/remotes/origin/main")
            assertTrue(rel is HistoryRelation.RemoteAhead, "expected RemoteAhead, got $rel")
            assertEquals(1, (rel as HistoryRelation.RemoteAhead).behind)
        }
    }

    @Test
    fun `Diverged when both sides have unique commits`() {
        // repoA pushes commit1; repoB clones; both diverge.
        seed(localRepoA, "a.txt", "1\n", "first")
        JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.push(repo, "origin", "main", noAuth)
        }

        // repoB starts as a clone-equivalent of origin/main.
        JGitClient.init(localRepoB).use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.fetch(repo, "origin", "main", noAuth)
            val originSha = repo.resolve("refs/remotes/origin/main")!!.name
            org.eclipse.jgit.api.Git(repo).branchCreate()
                .setName("main")
                .setStartPoint(originSha)
                .setForce(true)
                .call()
            JGitClient.resetHard(repo, originSha)
            // Diverge: commit on B.
            Files.writeString(localRepoB.resolve("b.txt"), "B\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "BeeBee", "b@example.com", "from-b")
            JGitClient.push(repo, "origin", "main", noAuth)
        }

        // Now repoA commits separately and fetches.
        JGitClient.open(localRepoA)!!.use { repo ->
            Files.writeString(localRepoA.resolve("c.txt"), "A\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Aye", "a@example.com", "from-a")
            JGitClient.fetch(repo, "origin", "main", noAuth)

            val rel = JGitClient.classify(repo, "HEAD", "refs/remotes/origin/main")
            assertTrue(rel is HistoryRelation.Diverged, "expected Diverged, got $rel")
            val d = rel as HistoryRelation.Diverged
            assertEquals(1, d.ahead)
            assertEquals(1, d.behind)

            // Force-push from A wins.
            val pushed = JGitClient.push(repo, "origin", "main", noAuth, force = true)
            assertEquals(PushStatus.OK, pushed.status)
        }
    }

    @Test
    fun `non-force push to a diverged ref is rejected`() {
        seed(localRepoA, "a.txt", "1\n", "first")
        JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.push(repo, "origin", "main", noAuth)
        }
        // repoB pushes a different history.
        JGitClient.init(localRepoB).use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.fetch(repo, "origin", "main", noAuth)
            val originSha = repo.resolve("refs/remotes/origin/main")!!.name
            org.eclipse.jgit.api.Git(repo).branchCreate()
                .setName("main")
                .setStartPoint(originSha)
                .setForce(true)
                .call()
            JGitClient.resetHard(repo, originSha)
            Files.writeString(localRepoB.resolve("b.txt"), "B\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "BeeBee", "b@example.com", "from-b")
            JGitClient.push(repo, "origin", "main", noAuth, force = true)
        }
        // repoA tries to push without force; remote has moved.
        JGitClient.open(localRepoA)!!.use { repo ->
            Files.writeString(localRepoA.resolve("c.txt"), "A\n")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Aye", "a@example.com", "from-a")
            val pushed = JGitClient.push(repo, "origin", "main", noAuth, force = false)
            assertTrue(
                pushed.status == PushStatus.REJECTED_NONFASTFORWARD || pushed.status == PushStatus.REJECTED_REMOTE_CHANGED,
                "expected non-fast-forward rejection, got ${pushed.status}",
            )
        }
    }

    @Test
    fun `readBlob returns file content from a remote ref`() {
        seed(localRepoA, "formatVersion.json", """{"formatVersion":1,"minReader":1}""" + "\n", "first")
        JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.push(repo, "origin", "main", noAuth)
        }

        JGitClient.init(localRepoB).use { repo ->
            JGitClient.setRemote(repo, "origin", bareUrl())
            JGitClient.fetch(repo, "origin", "main", noAuth)
            val content = JGitClient.readBlob(repo, "refs/remotes/origin/main", "formatVersion.json")
            assertNotNull(content)
            assertTrue(content!!.contains("formatVersion"), "expected formatVersion JSON, got: $content")
        }
    }

    @Test
    fun `readBlob returns null for missing path`() {
        seed(localRepoA, "a.txt", "1\n", "first")
        JGitClient.open(localRepoA)!!.use { repo ->
            assertNull(JGitClient.readBlob(repo, "HEAD", "no-such-file.txt"))
        }
    }

    @Test
    fun `resetHard moves working tree to ref`() {
        seed(localRepoA, "a.txt", "1\n", "first")
        val firstSha = JGitClient.open(localRepoA)!!.use { JGitClient.head(it)!!.sha }
        Files.writeString(localRepoA.resolve("b.txt"), "2\n")
        JGitClient.open(localRepoA)!!.use { repo ->
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "T", "t@e", "second")
            JGitClient.resetHard(repo, firstSha)
            assertEquals(firstSha, JGitClient.head(repo)!!.sha)
            assertTrue(!Files.exists(localRepoA.resolve("b.txt")), "second-commit file should be gone after reset")
        }
    }
}

@Suppress("unused")
private fun Repository.dummy() = Unit
