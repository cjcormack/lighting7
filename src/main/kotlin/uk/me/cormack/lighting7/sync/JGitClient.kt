package uk.me.cormack.lighting7.sync

import kotlinx.serialization.Serializable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
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

    /**
     * Create a commit whose parents are HEAD plus [extraParentSha]. Used by the
     * cloud-sync auto-merge path — the resulting commit needs the remote tip (HEAD
     * after `resetHard`) and the original local snapshot tip as both parents so
     * `git log --graph` reflects the merge.
     *
     * Implemented by writing `MERGE_HEAD` and then running a normal `CommitCommand` —
     * the command picks `MERGE_HEAD` up automatically and adds it as a second parent
     * (and clears it on success). Mirrors `git merge`'s wire behaviour without any of
     * its tree-merge logic; we've already produced the merged tree on disk.
     */
    fun commitWithParents(
        repo: Repository,
        authorName: String,
        authorEmail: String,
        message: String,
        extraParentSha: String,
    ): CommitInfo {
        val extraId = repo.resolve(extraParentSha)
            ?: error("Cannot resolve parent ref $extraParentSha for merge commit")
        repo.writeMergeHeads(listOf(extraId))
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

    // ─── Remote operations (cloud-sync phase 4) ────────────────────────────

    /**
     * Idempotently configure a single remote on the repo. If [name] already exists with a
     * different URL, it's updated in place; the previously stored URL is overwritten so a
     * user who corrects a typo in the repo URL doesn't have to manually edit `.git/config`.
     */
    fun setRemote(repo: Repository, name: String, url: String) {
        Git(repo).use { git ->
            val existing = repo.config.getString("remote", name, "url")
            if (existing == url) return
            val cmd = if (existing == null) {
                git.remoteAdd().setName(name).setUri(URIish(url))
            } else {
                git.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(url))
            }
            cmd.call()
        }
    }

    /**
     * Fetch [branch] from [remote] using [credentials]. Returns the fetched commit's id (or
     * null if the remote branch doesn't exist yet — first push will create it). Throws
     * [GitAuthException] on a 401/403 so callers can map to a clear UI error rather than
     * showing a generic stack trace.
     */
    fun fetch(
        repo: Repository,
        remote: String,
        branch: String,
        credentials: GitCredentials,
    ): ObjectId? {
        Git(repo).use { git ->
            try {
                val result = git.fetch()
                    .setRemote(remote)
                    .setRefSpecs(RefSpec("+refs/heads/$branch:refs/remotes/$remote/$branch"))
                    .setCredentialsProvider(credentials.toProvider())
                    .call()
                val advertised = result.advertisedRefs.firstOrNull { it.name == "refs/heads/$branch" }
                return advertised?.objectId
            } catch (e: TransportException) {
                // First-push case: the remote exists but doesn't yet have our branch.
                // JGit treats this as a transport error; we treat it as "ref absent" so the
                // caller proceeds to push and create the branch.
                val msg = e.message ?: ""
                if ("does not have" in msg && "refs/heads/$branch" in msg) {
                    return null
                }
                throw e.toAuthOrRethrow()
            }
        }
    }

    /**
     * Push [branch] to [remote]. If [force] is true, performs a force update — the caller
     * must have decided this is the right call (phase 4 does so on diverged history). Returns
     * a [PushResult] describing the outcome of the single ref update we sent.
     */
    fun push(
        repo: Repository,
        remote: String,
        branch: String,
        credentials: GitCredentials,
        force: Boolean = false,
    ): PushResult {
        Git(repo).use { git ->
            try {
                val refSpec = if (force) "+refs/heads/$branch:refs/heads/$branch"
                              else "refs/heads/$branch:refs/heads/$branch"
                val results = git.push()
                    .setRemote(remote)
                    .setRefSpecs(RefSpec(refSpec))
                    .setCredentialsProvider(credentials.toProvider())
                    .setForce(force)
                    .call()
                val update = results.firstOrNull()?.remoteUpdates?.firstOrNull()
                    ?: return PushResult(status = PushStatus.UP_TO_DATE, message = null)
                return when (update.status) {
                    RemoteRefUpdate.Status.OK,
                    RemoteRefUpdate.Status.UP_TO_DATE -> PushResult(PushStatus.OK, update.message)
                    RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                        PushResult(PushStatus.REJECTED_NONFASTFORWARD, update.message)
                    RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                        PushResult(PushStatus.REJECTED_REMOTE_CHANGED, update.message)
                    RemoteRefUpdate.Status.REJECTED_NODELETE,
                    RemoteRefUpdate.Status.REJECTED_OTHER_REASON,
                    RemoteRefUpdate.Status.NON_EXISTING,
                    RemoteRefUpdate.Status.AWAITING_REPORT,
                    RemoteRefUpdate.Status.NOT_ATTEMPTED ->
                        PushResult(PushStatus.REJECTED_OTHER, update.message ?: update.status.toString())
                    null -> PushResult(PushStatus.REJECTED_OTHER, "no status")
                }
            } catch (e: TransportException) {
                throw e.toAuthOrRethrow()
            }
        }
    }

    /**
     * Hard-reset the working tree to [ref]. Used to fast-forward a local branch to its
     * upstream — JGit's `MergeCommand` won't move HEAD if the branch is checked out and the
     * working tree differs, so a hard reset is the simpler, less-magical primitive for the
     * "remote is ancestor of local" case.
     */
    fun resetHard(repo: Repository, ref: String) {
        Git(repo).use { git ->
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(ref)
                .call()
        }
    }

    /**
     * Compare two commit-ish refs and return their relationship.
     *
     *  * [HistoryRelation.RemoteAbsent] if [remoteRef] doesn't resolve.
     *  * [HistoryRelation.Equal] if both sides point at the same commit.
     *  * [HistoryRelation.LocalAhead] if remote is a strict ancestor of local (regular push).
     *  * [HistoryRelation.RemoteAhead] if local is a strict ancestor of remote (fast-forward).
     *  * [HistoryRelation.Diverged] otherwise — both have commits the other doesn't.
     *
     * The `aheadCount` / `behindCount` counts are absolute commit counts on each side beyond
     * the merge base, which the UI uses to show "force-pushed N commits".
     */
    fun classify(repo: Repository, localRef: String, remoteRef: String): HistoryRelation {
        val localId = repo.resolve(localRef) ?: return HistoryRelation.RemoteAbsent
        val remoteId = repo.resolve(remoteRef) ?: return HistoryRelation.RemoteAbsent
        if (localId == remoteId) return HistoryRelation.Equal

        val localAhead = countAhead(repo, localId, remoteId)
        val remoteAhead = countAhead(repo, remoteId, localId)
        return when {
            localAhead > 0 && remoteAhead == 0 -> HistoryRelation.LocalAhead(localAhead)
            localAhead == 0 && remoteAhead > 0 -> HistoryRelation.RemoteAhead(remoteAhead)
            else -> HistoryRelation.Diverged(localAhead, remoteAhead)
        }
    }

    /** Read [path] from [ref] as a UTF-8 string, or null if either doesn't exist. */
    fun readBlob(repo: Repository, ref: String, path: String): String? {
        val commitId = repo.resolve(ref) ?: return null
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(commitId)
            TreeWalk.forPath(repo, path, commit.tree)?.use { tree ->
                val loader = repo.open(tree.getObjectId(0))
                val out = ByteArrayOutputStream()
                loader.copyTo(out)
                return out.toString(Charsets.UTF_8)
            }
        }
        return null
    }

    /**
     * Walk every blob reachable from [ref] and return `(repo-relative path → UTF-8 contents)`.
     * Returns an empty map if [ref] is unborn / can't be resolved. Used by
     * [uk.me.cormack.lighting7.sync.RecordHasher] to snapshot a whole commit's tree
     * without checking it out into the working tree.
     */
    fun walkTree(repo: Repository, ref: String): Map<String, String> {
        val commitId = repo.resolve(ref) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(commitId)
            TreeWalk(repo).use { tree ->
                tree.addTree(commit.tree)
                tree.isRecursive = true
                while (tree.next()) {
                    val loader = repo.open(tree.getObjectId(0))
                    val bytes = ByteArrayOutputStream().also { loader.copyTo(it) }
                    out[tree.pathString] = bytes.toString(Charsets.UTF_8)
                }
            }
        }
        return out
    }

    /** Find the merge-base between [a] and [b] (the common ancestor commit), or null if none. */
    fun mergeBase(repo: Repository, a: String, b: String): String? {
        val aId = repo.resolve(a) ?: return null
        val bId = repo.resolve(b) ?: return null
        RevWalk(repo).use { walk ->
            walk.revFilter = org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE
            walk.markStart(walk.parseCommit(aId))
            walk.markStart(walk.parseCommit(bId))
            return walk.next()?.name
        }
    }

    private fun countAhead(repo: Repository, head: ObjectId, base: ObjectId): Int {
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(head))
            walk.markUninteresting(walk.parseCommit(base))
            return walk.count()
        }
    }

    private fun TransportException.toAuthOrRethrow(): RuntimeException {
        val msg = message ?: ""
        // JGit doesn't expose a structured error type for auth failures — sniff the message.
        // GitHub's typical response is "not authorized" / "Authentication is required" / 401.
        val lower = msg.lowercase()
        val isAuth = "not authorized" in lower
            || "authentication" in lower
            || "401" in msg
            || "403" in msg
        return if (isAuth) GitAuthException(msg, this) else RuntimeException(msg, this)
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

/**
 * Authentication for JGit transport. GitHub Personal Access Tokens are sent as the
 * password with the literal username `x-access-token` (this is GitHub's documented
 * placeholder for token-based HTTPS auth).
 */
data class GitCredentials(val username: String, val secret: String) {
    fun toProvider(): UsernamePasswordCredentialsProvider =
        UsernamePasswordCredentialsProvider(username, secret)

    companion object {
        /** Build credentials for a GitHub Personal Access Token. */
        fun forGitHubPat(pat: String): GitCredentials = GitCredentials("x-access-token", pat)
    }
}

/**
 * Result of a [JGitClient.push] call. [PushStatus.OK] covers both first-create and
 * fast-forward updates; [PushStatus.UP_TO_DATE] means the remote already had everything
 * we tried to send (a no-op push). The non-fast-forward statuses can only happen on a
 * regular (non-force) push.
 */
data class PushResult(val status: PushStatus, val message: String?)

enum class PushStatus { OK, UP_TO_DATE, REJECTED_NONFASTFORWARD, REJECTED_REMOTE_CHANGED, REJECTED_OTHER }

/**
 * Result of [JGitClient.classify] — the relationship between two refs from the local
 * repo's perspective. The `count` fields are commit counts beyond the merge base, used
 * by the UI to surface "force-pushed N commits" warnings on diverged history.
 */
sealed class HistoryRelation {
    object Equal : HistoryRelation()
    object RemoteAbsent : HistoryRelation()
    data class LocalAhead(val ahead: Int) : HistoryRelation()
    data class RemoteAhead(val behind: Int) : HistoryRelation()
    data class Diverged(val ahead: Int, val behind: Int) : HistoryRelation()
}

/**
 * Thrown by [JGitClient.fetch] / [JGitClient.push] when the remote rejected the
 * connection on auth grounds. Callers (the REST layer) translate this to a 401 with
 * `AUTH_FAILED` so the UI can prompt the user to re-enter the PAT.
 */
class GitAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
