package uk.me.cormack.lighting7.sync

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.eclipse.jgit.lib.Repository
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloud-sync phase 4 — remote push/pull pipeline.
 *
 * Wraps [SnapshotEngine] (commit local changes) and [JGitClient] (talk to GitHub) and
 * orchestrates the snapshot → fetch → reconcile → push flow described in
 * `docs/plans/cloud-sync.md` phase 4. Phase 4 has no three-way merge: history can be
 * fast-forwarded, regularly pushed, force-pushed (on divergence), or left alone — that's
 * it. Phase 5 will replace the force-push branch with a real conflict-resolution UX.
 *
 * One [Mutex] per project guards against two `Sync now` clicks racing each other; a sync
 * involves a wipe-then-export and remote network calls, so interleaving them would be a
 * footgun.
 */
class RemoteSyncEngine(
    private val state: State,
    private val credentialStore: CredentialStore,
) {

    private val workingTree = SyncWorkingTree(state)
    private val snapshotEngine = SnapshotEngine(state)
    private val importer = ProjectImporter(state)

    /** One mutex per project; lazy so we don't allocate for projects that never sync. */
    private val projectLocks = ConcurrentHashMap<Int, Mutex>()

    /**
     * Run the full sync pipeline for [projectId]. Throws [SyncException] on any
     * user-visible failure (missing PAT, auth failed, format too new, etc); REST callers
     * map these to 4xx responses. Successful runs return a structured outcome the UI can
     * render directly.
     *
     * The two-arg `installUuid`/`installFriendlyName` are passed through to
     * [SnapshotEngine] for commit attribution; the caller resolves them inside its own
     * transaction (mirrors the existing snapshot route's pattern).
     */
    suspend fun runSync(
        projectId: Int,
        projectUuid: UUID,
        installUuid: UUID,
        installFriendlyName: String,
    ): SyncRunResult {
        val mutex = projectLocks.computeIfAbsent(projectId) { Mutex() }
        return mutex.withLock {
            doRun(projectId, projectUuid, installUuid, installFriendlyName)
        }
    }

    private suspend fun doRun(
        projectId: Int,
        projectUuid: UUID,
        installUuid: UUID,
        installFriendlyName: String,
    ): SyncRunResult {
        val (repoUrl, branch) = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?: throw SyncException(SyncErrorCode.REPO_URL_MISSING, "Sync config has not been initialised for this project.")
            if (!cfg.enabled) {
                throw SyncException(SyncErrorCode.SYNC_DISABLED, "Cloud sync is disabled for this project — enable it in the sync configuration first.")
            }
            val url = cfg.repoUrl?.takeIf { it.isNotBlank() }
                ?: throw SyncException(SyncErrorCode.REPO_URL_MISSING, "Repository URL is not set.")
            url to cfg.branch
        }

        val pat = credentialStore.get(repoUrl)
            ?: throw SyncException(SyncErrorCode.MISSING_PAT, "No GitHub Personal Access Token stored for this repository — set one in the sync configuration.")
        val credentials = GitCredentials.forGitHubPat(pat)

        snapshotEngine.snapshot(projectId, projectUuid, installUuid, installFriendlyName, message = null)

        val workingTreePath = workingTree.pathFor(projectUuid)
        val outcome = withContext(Dispatchers.IO) {
            val repo = JGitClient.open(workingTreePath)
                ?: throw SyncException(
                    SyncErrorCode.NO_REPO,
                    "Working tree at $workingTreePath has no git repo — take a snapshot first.",
                )
            repo.use { configureAndExchange(it, projectId, repoUrl, branch, credentials) }
        }

        transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?: error("sync_config row vanished mid-sync for project $projectId")
            cfg.lastSyncedSha = outcome.headSha
            cfg.lastSyncedAtMs = System.currentTimeMillis()
        }

        // Show reload after the sync_config commit: a transient show-init failure mustn't
        // leave the sync metadata unwritten.
        if (outcome.outcome == SyncOutcome.FAST_FORWARDED) {
            reloadShowIfActive(projectId)
        }

        return outcome
    }

    private fun configureAndExchange(
        repo: Repository,
        projectId: Int,
        repoUrl: String,
        branch: String,
        credentials: GitCredentials,
    ): SyncRunResult {
        JGitClient.setRemote(repo, REMOTE_NAME, repoUrl)

        val remoteCommitId = try {
            JGitClient.fetch(repo, REMOTE_NAME, branch, credentials)
        } catch (e: GitAuthException) {
            throw SyncException(SyncErrorCode.AUTH_FAILED, "GitHub rejected the PAT — check it has `repo` scope and isn't expired. ${e.message}", e)
        }

        // Format check has to happen before any working-tree mutation: a too-new repo can't
        // be allowed to taint the tree the importer will read.
        val remoteRef = remoteBranchRef(branch)
        if (remoteCommitId != null) {
            JGitClient.readBlob(repo, remoteRef, "formatVersion.json")?.let { json ->
                val remoteFormat = canonicalDecode(FormatVersionJson.serializer(), json)
                if (remoteFormat.formatVersion > SUPPORTED_FORMAT_VERSION) {
                    throw SyncException(
                        SyncErrorCode.FORMAT_TOO_NEW,
                        "Repo format v${remoteFormat.formatVersion} is newer than this install supports (v$SUPPORTED_FORMAT_VERSION). Upgrade lighting7 before syncing.",
                    )
                }
            }
        }

        val relation = JGitClient.classify(repo, "HEAD", remoteRef)
        logger.info("Sync classify for repo={}: {}", repoUrl, relation)

        return when (relation) {
            HistoryRelation.Equal -> {
                val head = JGitClient.head(repo)?.sha ?: error("Equal classification with null HEAD")
                SyncRunResult(SyncOutcome.NO_OP, pushed = 0, pulled = 0, replaced = 0, headSha = head, message = "Already in sync.")
            }
            HistoryRelation.RemoteAbsent -> {
                val head = JGitClient.head(repo)?.sha
                    ?: error("Cannot push from an unborn repo (snapshot should have created at least one commit).")
                val pushed = JGitClient.push(repo, REMOTE_NAME, branch, credentials, force = false)
                ensurePushOk(pushed)
                SyncRunResult(SyncOutcome.PUSHED, pushed = 1, pulled = 0, replaced = 0, headSha = head, message = "Initial push to remote.")
            }
            is HistoryRelation.LocalAhead -> {
                val head = JGitClient.head(repo)?.sha ?: error("LocalAhead with null HEAD")
                val pushed = JGitClient.push(repo, REMOTE_NAME, branch, credentials, force = false)
                ensurePushOk(pushed)
                SyncRunResult(SyncOutcome.PUSHED, pushed = relation.ahead, pulled = 0, replaced = 0, headSha = head, message = "Pushed ${relation.ahead} commit(s).")
            }
            is HistoryRelation.RemoteAhead -> {
                JGitClient.resetHard(repo, remoteRef)
                importer.replaceFromWorkingTree(projectId, repo.workTree.toPath())
                val head = JGitClient.head(repo)?.sha ?: error("RemoteAhead fast-forward produced no HEAD")
                SyncRunResult(SyncOutcome.FAST_FORWARDED, pushed = 0, pulled = relation.behind, replaced = 0, headSha = head, message = "Pulled ${relation.behind} commit(s) from remote.")
            }
            is HistoryRelation.Diverged -> {
                logger.warn(
                    "Diverged history (local ahead by {}, remote ahead by {}); force-pushing per phase 4 policy. {} remote commit(s) will be discarded.",
                    relation.ahead, relation.behind, relation.behind,
                )
                val head = JGitClient.head(repo)?.sha ?: error("Diverged with null HEAD")
                val pushed = JGitClient.push(repo, REMOTE_NAME, branch, credentials, force = true)
                ensurePushOk(pushed)
                SyncRunResult(
                    outcome = SyncOutcome.FORCE_PUSHED,
                    pushed = relation.ahead,
                    pulled = 0,
                    replaced = relation.behind,
                    headSha = head,
                    message = "Force-pushed ${relation.ahead} commit(s); ${relation.behind} remote commit(s) replaced.",
                )
            }
        }
    }

    private fun remoteBranchRef(branch: String): String = "refs/remotes/$REMOTE_NAME/$branch"

    private fun ensurePushOk(result: PushResult) {
        when (result.status) {
            PushStatus.OK, PushStatus.UP_TO_DATE -> Unit
            PushStatus.REJECTED_NONFASTFORWARD,
            PushStatus.REJECTED_REMOTE_CHANGED,
            PushStatus.REJECTED_OTHER ->
                throw SyncException(
                    SyncErrorCode.PUSH_REJECTED,
                    "Remote rejected the push: ${result.message ?: result.status}. " +
                        "This usually means another install pushed a commit between fetch and push — try again.",
                )
        }
    }

    /**
     * If the just-pulled project happens to be the active one, hot-reload the show so the
     * operator sees the fresh DB state immediately. Done outside the sync transaction —
     * the show lifecycle is its own concern and a transient failure shouldn't undo the
     * `lastSyncedSha` write.
     */
    private suspend fun reloadShowIfActive(projectId: Int) {
        val isActive = try {
            state.projectManager.currentProject.id.value == projectId
        } catch (_: Exception) {
            false
        }
        if (!isActive) return
        // switchProject(currentId) tears down the existing show and rebuilds from DB.
        try {
            state.projectManager.switchProject(projectId)
        } catch (e: Exception) {
            logger.warn("Failed to hot-reload show after pull for project {}: {}", projectId, e.message)
        }
    }

    companion object {
        const val REMOTE_NAME = "origin"
        private val logger = LoggerFactory.getLogger(RemoteSyncEngine::class.java)
    }
}

/** Outcome of a successful sync run. The UI uses this to pick which toast/badge to show. */
enum class SyncOutcome { NO_OP, PUSHED, FAST_FORWARDED, FORCE_PUSHED }

@Serializable
data class SyncRunResult(
    val outcome: SyncOutcome,
    val pushed: Int,
    val pulled: Int,
    val replaced: Int,
    val headSha: String,
    val message: String,
)

/**
 * User-visible sync errors, with stable codes the UI can branch on. Mapped to HTTP
 * statuses inside the route layer (see [toHttpStatus]).
 */
enum class SyncErrorCode {
    REPO_URL_MISSING, SYNC_DISABLED, MISSING_PAT, AUTH_FAILED,
    FORMAT_TOO_NEW, PUSH_REJECTED, NO_REPO,
}

class SyncException(val code: SyncErrorCode, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

fun SyncErrorCode.toHttpStatus(): HttpStatusCode = when (this) {
    SyncErrorCode.REPO_URL_MISSING, SyncErrorCode.SYNC_DISABLED, SyncErrorCode.NO_REPO ->
        HttpStatusCode.BadRequest
    SyncErrorCode.MISSING_PAT, SyncErrorCode.AUTH_FAILED -> HttpStatusCode.Unauthorized
    SyncErrorCode.FORMAT_TOO_NEW -> HttpStatusCode.UnprocessableEntity
    SyncErrorCode.PUSH_REJECTED -> HttpStatusCode.Conflict
}
