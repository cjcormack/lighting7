package uk.me.cormack.lighting7.sync

import kotlinx.serialization.Serializable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
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
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.PushRefusals
import uk.me.cormack.lighting7.telemetry.Tracing

/**
 * Thin wrapper around JGit so callers (snapshot engine, REST routes, tests) don't
 * import `org.eclipse.jgit.*` directly.
 *
 * All JGit calls block I/O; callers must dispatch onto `Dispatchers.IO`. Every
 * `Repository` returned by [init]/[open] is a closeable native resource and must
 * be wrapped in `.use { ... }` at the call site.
 */
object JGitClient {
    /** A fetch, push or clone as an `http.client` span (Sentry plan D6), named without the URL's credentials. */
    private fun <T> network(verb: String, url: String?, block: () -> T): T =
        Tracing.span("http.client", "git $verb ${withoutCredentials(url) ?: "remote"}") { block() }

    private fun withoutCredentials(url: String?): String? =
        url?.let { runCatching { URIish(it).setUser(null).setPass(null).toString() }.getOrNull() }

    private fun remoteUrl(repo: Repository, remote: String): String? = repo.config.getString("remote", remote, "url")


    /**
     * How long a fetch or push may wait on the remote without a byte before JGit gives up with a
     * [GitTransportException]. Without it a hung remote holds the project's sync lock indefinitely.
     */
    const val TRANSPORT_TIMEOUT_SECONDS = 30

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
    fun stageAll(repo: Repository): Boolean = stageAllPaths(repo).isNotEmpty()

    /**
     * [stageAll], answering the paths staged against HEAD — added, changed or removed —
     * from the same post-stage Status walk. On an unborn repo every path is "added".
     */
    fun stageAllPaths(repo: Repository): Set<String> {
        Git(repo).use { git ->
            val pre = git.status().call()
            git.add().addFilepattern(".").call()
            if (pre.missing.isNotEmpty()) {
                val rm = git.rm()
                pre.missing.forEach { rm.addFilepattern(it) }
                rm.call()
            }
            val post = git.status().call()
            return post.added + post.changed + post.removed
        }
    }

    /**
     * Put [path] back to its HEAD content in both the index and the working tree — the
     * single-path `git checkout HEAD -- path`. Used to drop a staged change the caller has
     * decided is not worth a commit. A path HEAD does not carry is unstaged and deleted,
     * so the working tree is left clean either way.
     */
    fun restoreFromHead(repo: Repository, path: String) {
        Git(repo).use { git ->
            git.reset().setRef("HEAD").addPath(path).call()
            if (readBlob(repo, "HEAD", path) == null) {
                Files.deleteIfExists(repo.workTree.toPath().resolve(path))
            } else {
                git.checkout().setStartPoint("HEAD").addPath(path).call()
            }
        }
    }

    /** Returns true if `git status` would report any change vs HEAD. */
    fun isWorkingTreeDirty(repo: Repository): Boolean {
        Git(repo).use { git ->
            val status = git.status().call()
            return status.hasUncommittedChanges() || status.untracked.isNotEmpty()
        }
    }

    /**
     * Create a commit with the given author identity.
     *
     * Never signed: these are the engine's commits, authored as the install rather than as a
     * person, and JGit honours the machine user's `~/.gitconfig`. A desk whose owner signs
     * their own commits (`commit.gpgsign = true`) would otherwise fail every snapshot — with
     * SSH signing JGit has no signer at all ("No signer for ssh signatures") — or prompt for
     * a key passphrase mid-show. [commitWithParents] makes the same call.
     */
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
                .setSign(false)
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
                .setSign(false)
                .call()
                .toCommitInfo()
        }
    }

    /** [ref]'s commit info, or null when it resolves to nothing. */
    fun commitInfo(repo: Repository, ref: String): CommitInfo? {
        val id = repo.resolve(ref) ?: return null
        return repo.parseCommit(id).toCommitInfo()
    }

    /** HEAD's commit info, or null on an unborn repo (before the first commit). */
    fun head(repo: Repository): CommitInfo? {
        val headRef = repo.exactRef("HEAD") ?: return null
        val target = headRef.target ?: return null
        val objectId = target.objectId ?: return null
        return repo.parseCommit(objectId).toCommitInfo()
    }

    /**
     * Walks recent commits from HEAD (or *strictly older than* [before] when supplied),
     * mapping to the public DTO. [before] is an exclusive cursor: when set, the walk
     * walks **all parents** of the cursor commit, so a merge between the previous page
     * and this one doesn't lose its second-parent ancestors. Paging by passing the
     * smallest SHA from the previous page yields the next page without overlap.
     */
    fun log(repo: Repository, limit: Int = 50, before: String? = null): List<CommitInfo> {
        if (head(repo) == null) return emptyList()
        Git(repo).use { git ->
            val log = git.log().setMaxCount(limit)
            if (before != null) {
                val cursor = repo.resolve(before) ?: return emptyList()
                val parents = RevWalk(repo).use { walk ->
                    val commit = walk.parseCommit(cursor)
                    if (commit.parentCount == 0) return emptyList()
                    (0 until commit.parentCount).map { commit.getParent(it).id }
                }
                parents.forEach { log.add(it) }
            }
            return log.call().map { it.toCommitInfo() }
        }
    }

    // ─── Remote operations (cloud-sync phase 4) ────────────────────────────

    /**
     * Clone [repoUrl] (single-branch [branch]) into [path] using [credentials]. Used by the
     * import-from-remote path to materialise an existing peer's repo into a fresh local
     * working tree before [ProjectImporter] reads it. The returned [Repository] is a
     * closeable native resource — wrap in `.use { ... }`.
     *
     * Translates JGit's auth-failure transport exceptions to [GitAuthException] in the
     * same shape as [fetch]/[push] so the route layer can map them to a 401 with a stable
     * `AUTH_FAILED` code.
     */
    fun clone(
        repoUrl: String,
        path: Path,
        branch: String,
        credentials: GitCredentials,
    ): Repository {
        Files.createDirectories(path)
        try {
            return network("clone", repoUrl) {
                Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(path.toFile())
                    .setBranch(branch)
                    .setBranchesToClone(listOf("refs/heads/$branch"))
                    .setCredentialsProvider(credentials.toProvider())
                    .call()
            }.repository
        } catch (e: TransportException) {
            throw e.toAuthOrRethrow(repoUrl)
        } catch (e: InvalidRemoteException) {
            throw e.unreachableRemote(repoUrl)
        }
    }

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
                val result = network("fetch", remoteUrl(repo, remote)) {
                    git.fetch()
                        .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                        .setRemote(remote)
                        .setRefSpecs(RefSpec("+refs/heads/$branch:refs/remotes/$remote/$branch"))
                        .setCredentialsProvider(credentials.toProvider())
                        .call()
                }
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
                throw e.toAuthOrRethrow(remoteUrl(repo, remote))
            } catch (e: InvalidRemoteException) {
                throw e.unreachableRemote(remoteUrl(repo, remote))
            }
        }
    }

    /**
     * Push [branch] to [remote]. If [force] is true, performs a force update — the caller
     * must have decided this is the right call (phase 4 does so on diverged history). Returns
     * a [PushResult] describing the outcome of the single ref update we sent; a cloud refusal that
     * no retry can get past throws [GitPushRefusedException] instead (cloud-p2 plan D4).
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
                val results = network("push", remoteUrl(repo, remote)) {
                    git.push()
                        .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                        .setRemote(remote)
                        .setRefSpecs(RefSpec(refSpec))
                        .setCredentialsProvider(credentials.toProvider())
                        .setForce(force)
                        .call()
                }
                val update = results.firstOrNull()?.remoteUpdates?.firstOrNull()
                    ?: return PushResult(status = PushStatus.UP_TO_DATE, message = null)
                return update.toPushResult().also(::throwIfRefusedForGood)
            } catch (e: TransportException) {
                throw e.toAuthOrRethrow(remoteUrl(repo, remote))
            } catch (e: InvalidRemoteException) {
                throw e.unreachableRemote(remoteUrl(repo, remote))
            }
        }
    }

    // ─── Tags (venue repos, venue plan §3.4) ─────────────────────────────────

    /**
     * Tag HEAD as [name] with [message] (an annotated tag, so it carries who and when). Refuses a
     * name that is already a local tag rather than moving it: a published version's tag never moves.
     */
    fun tag(repo: Repository, name: String, message: String, taggerName: String, taggerEmail: String) {
        Git(repo).use { git ->
            git.tag()
                .setName(name)
                .setMessage(message)
                .setAnnotated(true)
                .setTagger(org.eclipse.jgit.lib.PersonIdent(taggerName, taggerEmail))
                .setSigned(false)
                .setForceUpdate(false)
                .call()
        }
    }

    /** Delete the local tag [name], if any — undoing a [tag] whose push was refused. */
    fun deleteTag(repo: Repository, name: String) {
        Git(repo).use { git -> git.tagDelete().setTags(name).call() }
    }

    /** This repo's local tags, name → the commit each names (an annotated tag peeled). */
    fun listTags(repo: Repository): Map<String, String> = refsUnder(repo, "refs/tags/")

    /** The remote's tags as of the last [fetchTags], name → commit, kept apart from the local ones. */
    fun listRemoteTags(repo: Repository, remote: String): Map<String, String> =
        refsUnder(repo, remoteTagPrefix(remote))

    private fun remoteTagPrefix(remote: String) = "refs/remotes/$remote/tags/"

    private fun refsUnder(repo: Repository, prefix: String): Map<String, String> =
        repo.refDatabase.getRefsByPrefix(prefix).associate { ref ->
            val peeled = repo.refDatabase.peel(ref)
            ref.name.removePrefix(prefix) to (peeled.peeledObjectId ?: ref.objectId).name
        }

    /**
     * Fetch [remote]'s tags into `refs/remotes/{remote}/tags/` — beside this repo's own tags, never
     * over them, so a desk can tell which versions the remote has ([listRemoteTags]). A remote with no
     * commits yet answers nothing. The remote's own deletions are mirrored (`setRemoveDeletedRefs`), so
     * the list is what the remote holds now.
     */
    fun fetchTags(repo: Repository, remote: String, credentials: GitCredentials) {
        Git(repo).use { git ->
            try {
                network("fetch", remoteUrl(repo, remote)) {
                    git.fetch()
                        .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                        .setRemote(remote)
                        .setRefSpecs(RefSpec("+refs/tags/*:${remoteTagPrefix(remote)}*"))
                        .setTagOpt(org.eclipse.jgit.transport.TagOpt.NO_TAGS)
                        .setRemoveDeletedRefs(true)
                        .setCredentialsProvider(credentials.toProvider())
                        .call()
                }
            } catch (e: TransportException) {
                throw e.toAuthOrRethrow(remoteUrl(repo, remote))
            } catch (e: InvalidRemoteException) {
                throw e.unreachableRemote(remoteUrl(repo, remote))
            }
        }
    }

    /**
     * Push [branch] and the tag [tag] together. A tag the remote already holds is refused rather than
     * moved, and so is a branch update that is not a fast-forward. Answers each ref's outcome, by its
     * remote name (`refs/heads/{branch}`, `refs/tags/{tag}`), or throws [GitPushRefusedException] as
     * [push] does.
     */
    fun pushWithTag(
        repo: Repository,
        remote: String,
        branch: String,
        tag: String,
        credentials: GitCredentials,
    ): Map<String, PushResult> {
        Git(repo).use { git ->
            try {
                val results = network("push", remoteUrl(repo, remote)) {
                    git.push()
                        .setTimeout(TRANSPORT_TIMEOUT_SECONDS)
                        .setRemote(remote)
                        .setRefSpecs(
                            RefSpec("refs/heads/$branch:refs/heads/$branch"),
                            RefSpec("refs/tags/$tag:refs/tags/$tag"),
                        )
                        .setCredentialsProvider(credentials.toProvider())
                        .call()
                }
                val answered = results.flatMap { it.remoteUpdates }.associate { it.remoteName to it.toPushResult() }
                answered.values.forEach(::throwIfRefusedForGood)
                return answered
            } catch (e: TransportException) {
                throw e.toAuthOrRethrow(remoteUrl(repo, remote))
            } catch (e: InvalidRemoteException) {
                throw e.unreachableRemote(remoteUrl(repo, remote))
            }
        }
    }

    /**
     * Write every file of [ref]'s tree into [target], byte for byte — binaries included, unlike
     * [walkTree] — without touching the working tree. How a venue repo's tag is read back as an
     * archive to import (venue plan §3.4).
     */
    fun extractTree(repo: Repository, ref: String, target: Path) {
        val commitId = repo.resolve(ref) ?: error("Cannot resolve $ref")
        Files.createDirectories(target)
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(commitId)
            TreeWalk(repo).use { tree ->
                tree.addTree(commit.tree)
                tree.isRecursive = true
                while (tree.next()) {
                    val out = target.resolve(tree.pathString).normalize()
                    // A tree is the remote's to write; a path that climbs out of [target] is not a file of it.
                    require(out.startsWith(target.normalize())) { "Refusing tree path ${tree.pathString}" }
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out).use { repo.open(tree.getObjectId(0)).copyTo(it) }
                }
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

    /** Whether [ref]'s tree holds [path] — a file, or a folder (git keeps no empty ones). */
    fun hasPath(repo: Repository, ref: String, path: String): Boolean {
        val commitId = repo.resolve(ref) ?: return false
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(commitId)
            TreeWalk.forPath(repo, path, commit.tree)?.use { return true }
        }
        return false
    }

    /**
     * Walk every blob reachable from [ref] and return `(repo-relative path → UTF-8 contents)`.
     * Returns an empty map if [ref] is unborn / can't be resolved. Used by
     * [uk.me.cormack.lighting7.sync.RecordHasher] to snapshot a whole commit's tree
     * without checking it out into the working tree.
     *
     * The `promptScripts/` and `sceneImages/` subtrees are skipped: those are binary,
     * content-addressed PDF and image blobs (not records) and decoding them as UTF-8 here would
     * be lossy — plus at up to 100 MB and 25 MB each, materialising them into this String map on
     * every diff would be a real memory cost. They travel as raw bytes via
     * [PromptScriptRepoSync] and [SceneImageRepoSync] instead.
     */
    fun walkTree(repo: Repository, ref: String): Map<String, String> {
        val commitId = repo.resolve(ref) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        val skipPrefixes = RecordHasher.BINARY_DIRS.map { "$it/" }
        RevWalk(repo).use { walk ->
            val commit = walk.parseCommit(commitId)
            TreeWalk(repo).use { tree ->
                tree.addTree(commit.tree)
                tree.isRecursive = true
                while (tree.next()) {
                    if (skipPrefixes.any { tree.pathString.startsWith(it) }) continue
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

    private fun TransportException.toAuthOrRethrow(url: String?): RuntimeException {
        val msg = message ?: ""
        if (isAuthFailure(msg, url)) return GitAuthException(msg, this)
        return GitTransportException(msg, this, httpStatusOf(this), withoutCredentials(url))
    }

    /**
     * Whether JGit's [message] for a transport failure says the remote refused the credentials. JGit has
     * no structured type for it, so the words are read — GitHub's "not authorized", "Authentication is
     * required", a 401 or 403 — with [url] and every other URL taken out first, since a repo's path may
     * hold the digits.
     */
    internal fun isAuthFailure(message: String, url: String?): Boolean {
        val words = (url?.let { message.replace(it, " ") } ?: message).replace(URL_IN_MESSAGE, " ")
        val lower = words.lowercase()
        return "not authorized" in lower || "authentication" in lower || AUTH_STATUS.containsMatchIn(words)
    }

    private val URL_IN_MESSAGE = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")
    private val AUTH_STATUS = Regex("""\b40[13]\b""")

    /** A remote that answered `info/refs` with 404 or 403, which JGit's commands report as an invalid remote. */
    private fun InvalidRemoteException.unreachableRemote(url: String?): GitTransportException =
        GitTransportException(generateSequence(cause) { it.cause }.lastOrNull()?.message ?: message ?: "invalid remote", this, httpStatusOf(this), withoutCredentials(url))

    /** 404 or 403 from JGit's words for them answering `info/refs` (`{url} not found`, `{service} not permitted on '{url}'`), else null. */
    private fun httpStatusOf(e: Throwable): Int? {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { " not permitted on " in (it.message ?: "") } -> 403
            chain.any { it is NoRemoteRepositoryException } -> 404
            else -> null
        }
    }

    /** The codes a cloud push refusal carries that no retry gets past; the rest are answered as statuses. */
    private val REFUSED_FOR_GOOD = setOf(
        ErrorCode.SYNC_QUOTA_EXCEEDED,
        ErrorCode.SYNC_STORAGE_UNAVAILABLE,
        ErrorCode.SYNC_FORMAT_INVALID,
        ErrorCode.SYNC_REF_PROTECTED,
    )

    private fun throwIfRefusedForGood(result: PushResult) {
        val code = result.cloudCode ?: return
        if (code in REFUSED_FOR_GOOD) throw GitPushRefusedException(code, result.message ?: code)
    }

    /** A ref's outcome, reading a cloud refusal's code from its reason; `SYNC_NON_FAST_FORWARD` is JGit's non-fast-forward. */
    private fun RemoteRefUpdate.toPushResult(): PushResult {
        val cloudCode = PushRefusals.codeOf(message)
        val status = when {
            cloudCode == ErrorCode.SYNC_NON_FAST_FORWARD -> PushStatus.REJECTED_NONFASTFORWARD
            else -> when (this.status) {
                RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> PushStatus.OK
                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> PushStatus.REJECTED_NONFASTFORWARD
                RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> PushStatus.REJECTED_REMOTE_CHANGED
                else -> PushStatus.REJECTED_OTHER
            }
        }
        val words = message ?: if (status == PushStatus.REJECTED_OTHER) (this.status?.toString() ?: "no status") else null
        return PushResult(status, words, cloudCode)
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
            restoredFrom = restoredFromTrailer(fullMessage),
        )
    }
}

/**
 * Serialisable view of a git commit returned by REST endpoints. `whenMs` is the
 * author timestamp in epoch millis (UTC). `shortSha` is the conventional 7-char
 * abbreviation for UI display.
 *
 * [installShortUuid] / [installFriendlyName] carry the parsed attribution from the
 * commit message's `[install:{shortUuid}]` marker — populated by route handlers via
 * [withAttribution]; null on commits not produced by the snapshot/merge engines.
 */
@Serializable
data class CommitInfo(
    val sha: String,
    val shortSha: String,
    val authorName: String,
    val authorEmail: String,
    val whenMs: Long,
    val message: String,
    val installShortUuid: String? = null,
    val installFriendlyName: String? = null,
    /** The full sha a restore commit brought back, from its `Restored-From:` trailer; null on any other commit. */
    val restoredFrom: String? = null,
)

/** The trailer [SnapshotRestore] writes on a restore's commit, after a blank line. */
const val RESTORED_FROM_TRAILER = "Restored-From:"

private val RESTORED_FROM_REGEX = Regex("""^${Regex.escape(RESTORED_FROM_TRAILER)} ([0-9a-f]{40})$""")

/** The sha a `Restored-From:` trailer names, read only from a line of its own. */
fun restoredFromTrailer(message: String): String? =
    message.lineSequence().firstNotNullOfOrNull { RESTORED_FROM_REGEX.matchEntire(it.trim())?.groupValues?.get(1) }

private val INSTALL_MARKER_REGEX = Regex("""\[install:([0-9a-f]{8})]""")

/**
 * Parse the `[install:{shortUuid}]` marker out of [CommitInfo.message] and pair it with
 * a friendly name from [installs] (falling back to null when the install hasn't
 * registered itself in `installs.json`). Stable for non-engine commits — they keep
 * `installShortUuid = null`.
 */
fun CommitInfo.withAttribution(installs: Map<String, String>): CommitInfo {
    val match = INSTALL_MARKER_REGEX.find(message) ?: return this
    val short = match.groupValues[1]
    val friendly = installs.entries
        .firstOrNull { it.key.startsWith(short) }
        ?.value
    return copy(installShortUuid = short, installFriendlyName = friendly)
}

/**
 * Authentication for JGit transport. GitHub Personal Access Tokens **and** OAuth
 * user-to-server tokens are both sent as the password with the literal username
 * `x-access-token` (GitHub's documented placeholder for token-based HTTPS auth).
 */
data class GitCredentials(val username: String, val secret: String) {
    fun toProvider(): UsernamePasswordCredentialsProvider =
        UsernamePasswordCredentialsProvider(username, secret)

    companion object {
        /**
         * Build credentials for a GitHub token (PAT or OAuth user-to-server token).
         * GitHub treats both identically over HTTPS — the username is a literal
         * placeholder. */
        fun forGitHubToken(token: String): GitCredentials = GitCredentials("x-access-token", token)

        /** Deprecated alias kept for one release while callers migrate. */
        @Deprecated("Use forGitHubToken — works for both PAT and OAuth tokens.", ReplaceWith("forGitHubToken(pat)"))
        fun forGitHubPat(pat: String): GitCredentials = forGitHubToken(pat)
    }
}

/**
 * Result of a [JGitClient.push] call. [PushStatus.OK] covers both first-create and
 * fast-forward updates; [PushStatus.UP_TO_DATE] means the remote already had everything
 * we tried to send (a no-op push). The non-fast-forward statuses can only happen on a
 * regular (non-force) push.
 */
data class PushResult(
    val status: PushStatus,
    val message: String?,
    /** The cloud's refusal code, read from the ref's reason (`CODE: words`); null for any other remote. */
    val cloudCode: String? = null,
)

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

/**
 * A fetch, push or clone that failed on the way to the remote for any reason but its credentials: a
 * refused connection, a timeout, a host that does not resolve. A [RuntimeException], as it was before
 * it had a name, so every existing catch still holds. [status] is 404 or 403 when the remote answered
 * one, [url] the remote's, without credentials.
 */
class GitTransportException(
    message: String,
    cause: Throwable? = null,
    val status: Int? = null,
    val url: String? = null,
) : RuntimeException(message, cause)

/**
 * A push the cloud refused with [code] (`SYNC_QUOTA_EXCEEDED` and the rest of
 * [JGitClient]'s refusals no retry gets past), in its own words (cloud-p2 plan D4).
 */
class GitPushRefusedException(val code: String, message: String) : RuntimeException(message)
