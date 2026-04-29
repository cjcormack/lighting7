package uk.me.cormack.lighting7.sync

import kotlinx.serialization.Serializable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Thin wrapper around JGit so callers (snapshot engine, REST routes, tests) don't
 * import `org.eclipse.jgit.*` directly.
 *
 * All JGit calls block I/O; callers must dispatch onto `Dispatchers.IO`. Every
 * `Repository` returned by [init]/[open] is a closeable native resource and must
 * be wrapped in `.use { ... }` at the call site.
 */
object JGitClient {

    /** Initial branch for new repos. JGit defaults to `master`; we want `main`. */
    const val DEFAULT_BRANCH = "main"

    /** Initialise a new git repository at [path]. Creates the directory if needed. */
    fun init(path: Path): Repository {
        Files.createDirectories(path)
        return Git.init()
            .setDirectory(path.toFile())
            .setInitialBranch(DEFAULT_BRANCH)
            .call()
            .repository
    }

    /**
     * Open an existing repository at [path], or `null` if the directory exists but
     * has no `.git/`. Throws if [path] does not exist.
     */
    fun open(path: Path): Repository? {
        if (!Files.exists(path)) return null
        val gitDir = path.resolve(".git")
        if (!Files.isDirectory(gitDir)) return null
        return FileRepositoryBuilder()
            .setGitDir(gitDir.toFile())
            .build()
    }

    /**
     * Stage every change in the working tree and report whether anything ended up
     * in the index. Combines `git add -A` and the post-stage emptiness check into
     * a single Status walk: JGit's `AddCommand` covers new + modified files,
     * deletions of tracked files require an explicit `RmCommand`, and we want to
     * short-circuit a no-op snapshot without a third Status.
     *
     * Returns `true` if the next commit would have non-empty content.
     */
    fun stageAll(repo: Repository): Boolean {
        Git(repo).use { git ->
            val pre = git.status().call()
            git.add().addFilepattern(".").call()
            if (pre.missing.isNotEmpty()) {
                val rm = git.rm()
                pre.missing.forEach { rm.addFilepattern(it) }
                rm.call()
            }
            val post = git.status().call()
            return post.added.isNotEmpty()
                || post.changed.isNotEmpty()
                || post.removed.isNotEmpty()
        }
    }

    /** Returns true if `git status` would report any change vs HEAD. */
    fun isWorkingTreeDirty(repo: Repository): Boolean {
        Git(repo).use { git ->
            val status = git.status().call()
            return status.hasUncommittedChanges() || status.untracked.isNotEmpty()
        }
    }

    /** Create a commit with the given author identity. */
    fun commit(
        repo: Repository,
        authorName: String,
        authorEmail: String,
        message: String,
    ): CommitInfo {
        Git(repo).use { git ->
            return git.commit()
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .setMessage(message)
                .call()
                .toCommitInfo()
        }
    }

    /** HEAD's commit info, or null on an unborn repo (before the first commit). */
    fun head(repo: Repository): CommitInfo? {
        val headRef = repo.exactRef("HEAD") ?: return null
        val target = headRef.target ?: return null
        val objectId = target.objectId ?: return null
        return repo.parseCommit(objectId).toCommitInfo()
    }

    /** Walks recent commits from HEAD, mapping to the public DTO. */
    fun log(repo: Repository, limit: Int = 50): List<CommitInfo> {
        if (head(repo) == null) return emptyList()
        Git(repo).use { git ->
            return git.log()
                .setMaxCount(limit)
                .call()
                .map { it.toCommitInfo() }
        }
    }

    private fun RevCommit.toCommitInfo(): CommitInfo {
        val full = name
        return CommitInfo(
            sha = full,
            shortSha = full.take(7),
            authorName = authorIdent.name,
            authorEmail = authorIdent.emailAddress,
            // RevCommit.commitTime is seconds-since-epoch; multiply for canonical millis.
            whenMs = commitTime.toLong() * 1000L,
            message = fullMessage.trim(),
        )
    }
}

/**
 * Serialisable view of a git commit returned by REST endpoints. `whenMs` is the
 * author timestamp in epoch millis (UTC). `shortSha` is the conventional 7-char
 * abbreviation for UI display.
 */
@Serializable
data class CommitInfo(
    val sha: String,
    val shortSha: String,
    val authorName: String,
    val authorEmail: String,
    val whenMs: Long,
    val message: String,
)
