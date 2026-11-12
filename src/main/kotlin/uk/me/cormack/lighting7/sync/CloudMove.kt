package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.eclipse.jgit.lib.Repository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncLinkedRepo
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.plugins.CloudSyncDoneOutMessage
import uk.me.cormack.lighting7.plugins.CloudSyncFailedOutMessage
import uk.me.cormack.lighting7.plugins.CloudSyncStartedOutMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver

/**
 * *Move GitHub-synced projects to the cloud*: each project syncing with a repo that is not the cloud's
 * moves, history and all, onto its cloud repo, one at a time under its sync's locks.
 *
 * A sync with the old remote comes first; one that cannot reach it moves the working tree as it is.
 * The branch (onto the cloud's `main`) and a venue's every version tag are pushed, never forced, so
 * every sha is the old remote's. A cloud repo whose history this desk's does not contain is not pushed
 * to: the project re-points and its next sync merges. Only once the cloud holds what was pushed does
 * the project re-point, followed by a verifying sync; a project that fails before that is left as it was.
 */
class CloudMove(
    private val state: State,
    private val engine: RemoteSyncEngine = state.remoteSyncEngine,
    private val authResolver: AuthResolver = state.authResolver,
    private val cloudRepos: CloudRepos = state.cloudRepos,
) {

    private val failures = SyncFailures(authResolver)

    /** Every project syncing with a repo that is not the cloud's, venues first, each with the cloud's repo for it if any. */
    suspend fun plan(): CloudMovePlan {
        val candidates = candidates()
        if (candidates.isEmpty()) return CloudMovePlan(emptyList(), state.syncPushEnabled)
        val onCloud = cloudRepos.list()
        return CloudMovePlan(
            candidates.map { c ->
                val existing = onCloud.firstOrNull { it.projectUuid == c.uuid }
                CloudMovePlanRow(
                    projectId = c.projectId, name = c.name, kind = c.kind.name, repoUrl = c.repoUrl,
                    cloudRepoName = existing?.name, cloudHasRepo = existing != null,
                )
            },
            state.syncPushEnabled,
        )
    }

    /** Move [projectIds], venues first, one at a time. */
    suspend fun run(projectIds: List<Int>): CloudMoveResult {
        if (!state.syncPushEnabled) {
            throw SyncException(
                SyncErrorCode.CLOUD_SYNC_PULL_ONLY,
                "This desk only pulls (sync.push = false in local.conf), so it cannot move a project to the cloud: move it from a desk that pushes.",
            )
        }
        val wanted = projectIds.toSet()
        val candidates = candidates().filter { it.projectId in wanted }
        val unknown = (wanted - candidates.map { it.projectId }.toSet()).sorted().map(::notACandidate)
        if (candidates.isNotEmpty()) cloudRepos.listForCreate()
        return CloudMoveResult(candidates.map { c -> heldBack(c) ?: moveOne(c) } + unknown)
    }

    private fun notACandidate(id: Int): CloudMoveRow {
        val (name, url) = transaction(state.database) {
            val project = DaoProject.findById(id)
            val url = project?.let { p -> DaoSyncConfig.find { DaoSyncConfigs.project eq p.id }.firstOrNull()?.repoUrl }
            (project?.name ?: "#$id") to url?.takeIf { it.isNotBlank() }
        }
        val message = if (url != null && authResolver.isCloudRepo(url)) {
            "This project is already on the cloud, so there is nothing to move."
        } else {
            "This project does not sync with a GitHub repository, so there is nothing to move."
        }
        return CloudMoveRow(id, name, CloudMoveStatus.FAILED, code = SyncErrorCode.MOVE_NOT_ON_GITHUB.name, message = message)
    }

    /** A show whose venue on this desk still syncs with a repo that is not the cloud's stays where it is. */
    private fun heldBack(c: Candidate): CloudMoveRow? {
        if (c.kind != ProjectKind.SHOW) return null
        val venue = transaction(state.database) {
            val venueUuid = DaoProject[c.projectId].venueUuid ?: return@transaction null
            val venue = DaoProject.find { (DaoProjects.uuid eq venueUuid) and (DaoProjects.kind eq ProjectKind.VENUE) }.firstOrNull()
                ?: return@transaction null
            val url = DaoSyncConfig.find { DaoSyncConfigs.project eq venue.id }.firstOrNull()?.repoUrl?.takeIf { it.isNotBlank() }
            venue.name.takeIf { url != null && !authResolver.isCloudRepo(url) }
        } ?: return null
        val message = "Its venue $venue still syncs with GitHub: move the venue first, then this show."
        state.syncLogger.error(c.projectId, SyncLogEvent.MOVE_FAILED, "${SyncErrorCode.VENUE_NOT_MOVED.name}: $message")
        return CloudMoveRow(c.projectId, c.name, CloudMoveStatus.FAILED, code = SyncErrorCode.VENUE_NOT_MOVED.name, message = message)
    }

    private data class Candidate(val projectId: Int, val uuid: String, val name: String, val kind: ProjectKind, val repoUrl: String)

    private fun candidates(): List<Candidate> = transaction(state.database) {
        DaoSyncConfig.all().mapNotNull { cfg ->
            val url = cfg.repoUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (authResolver.isCloudRepo(url)) return@mapNotNull null
            val project = cfg.project
            if (project.kind == ProjectKind.VENUE_VERSION) return@mapNotNull null
            Candidate(project.id.value, project.uuid.toString(), project.name, project.kind, url)
        }.sortedWith(compareBy({ it.kind != ProjectKind.VENUE }, { it.name.lowercase() }, { it.projectId }))
    }

    private suspend fun moveOne(c: Candidate): CloudMoveRow {
        val row = try {
            engine.withSyncLocks(c.projectId) { sync -> moveLocked(c, sync) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? SyncException ?: failures.cloudRefusalOf(e)
                ?: SyncException(SyncErrorCode.PUSH_REJECTED, "The move failed: ${e.message ?: e::class.simpleName}", e)
            if (failure !== e) log.warn("Moving project {} to the cloud failed", c.projectId, e)
            state.syncLogger.error(c.projectId, SyncLogEvent.MOVE_FAILED, "${failure.code.name}: ${failure.message ?: failure.code.name}")
            CloudMoveRow(c.projectId, c.name, CloudMoveStatus.FAILED, code = failure.code.name, message = failure.message)
        }
        if (row.status != CloudMoveStatus.FAILED) state.autoSyncScheduler.reschedule(c.projectId)
        return row
    }

    private suspend fun moveLocked(c: Candidate, sync: suspend () -> SyncRunResult): CloudMoveRow {
        val first: SyncRunResult? = try {
            sync().also { result ->
                if (result.outcome == SyncOutcome.CONFLICTS_PENDING) {
                    throw SyncException(
                        SyncErrorCode.SESSION_PENDING,
                        "Syncing with GitHub first opened a conflict session: resolve it on the Sync tab, then move the project.",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val unreachable = e is GitTransportException || e is GitAuthException || (e is SyncException && e.code in UNREACHABLE)
            if (!unreachable) throw e
            log.info("Project {}: GitHub could not be synced first ({}); moving from the working tree", c.projectId, e.message)
            state.syncLogger.warn(
                c.projectId, SyncLogEvent.MOVE_FROM_WORKING_TREE,
                "GitHub could not be synced first (${e.message ?: e::class.simpleName}): moving what this desk holds.",
            )
            null
        }

        val created = cloudRepos.create(java.util.UUID.fromString(c.uuid), c.kind, c.name)
        val repo = created.repo
        val credentials = failures.credentialsFor(repo.url)
        val remoteHead = first?.headSha?.takeIf { it.isNotEmpty() }
        val push = withContext(Dispatchers.IO) { pushHistory(c, repo.url, credentials, remoteHead) }

        withContext(Dispatchers.IO) {
            transaction(state.database) {
                val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq c.projectId }.first()
                if (cfg.repoUrl != c.repoUrl) {
                    throw SyncException(SyncErrorCode.REPO_URL_IMMUTABLE, "The project's repository changed while it moved: nothing was re-pointed.")
                }
                DaoSyncLinkedRepo.remember(DaoProject[c.projectId], c.repoUrl)
                cfg.repoUrl = repo.url
                cfg.repoName = repo.name
                cfg.branch = JGitClient.DEFAULT_BRANCH
                workingTree(c).use { JGitClient.renameHeadBranch(it, JGitClient.DEFAULT_BRANCH) }
            }
        }
        val status = if (push.diverged) CloudMoveStatus.MOVED_DIVERGED else CloudMoveStatus.MOVED
        state.syncLogger.info(
            c.projectId, SyncLogEvent.MOVED_TO_CLOUD,
            buildString {
                append("Moved to the cloud repo ${repo.name}")
                append(if (push.diverged) ": its history differs from this desk's, so the next sync merges the two." else ".")
                if (first == null) append(" Moved from this desk's working tree: GitHub could not be synced first.")
            },
        )

        val (verify, verifyError) = verify(c.projectId, sync)
        return CloudMoveRow(
            projectId = c.projectId, name = c.name, status = status,
            repoUrl = repo.url, repoName = repo.name, existed = created.existed,
            fromWorkingTree = first == null, headSha = push.headSha, tags = push.tags,
            verify = verify, verifyError = verifyError,
        )
    }

    /** The first sync against the cloud, announced on the cloud-sync stream as any sync is. */
    private suspend fun verify(projectId: Int, sync: suspend () -> SyncRunResult): Pair<SyncRunResult?, SyncErrorResponse?> {
        state.emitCloudSyncEvent(CloudSyncStartedOutMessage(projectId))
        return try {
            val result = sync()
            if (result.outcome != SyncOutcome.CONFLICTS_PENDING) {
                state.emitCloudSyncEvent(
                    CloudSyncDoneOutMessage(
                        projectId = projectId, outcome = result.outcome.name, headSha = result.headSha,
                        pushed = result.pushed, pulled = result.pulled, replaced = result.replaced, message = result.message,
                    ),
                )
            }
            result to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? SyncException ?: failures.cloudRefusalOf(e)
            val error = SyncErrorResponse(failure?.message ?: e.message ?: "sync failed", failure?.code?.name ?: SyncErrorCode.PUSH_REJECTED.name)
            state.emitCloudSyncEvent(CloudSyncFailedOutMessage(projectId, error.code, error.error))
            state.syncLogger.error(projectId, SyncLogEvent.RUN_FAILED, "${error.code}: ${error.error}")
            null to error
        }
    }

    private fun workingTree(c: Candidate): Repository =
        JGitClient.open(SyncWorkingTree(state).pathFor(java.util.UUID.fromString(c.uuid)))
            ?: throw SyncException(SyncErrorCode.NO_REPO, "This desk holds no history for the project to move: sync it with GitHub first.")

    private data class Pushed(val headSha: String, val diverged: Boolean, val tags: Int)

    private fun pushHistory(c: Candidate, url: String, credentials: GitCredentials, remoteHead: String?): Pushed =
        workingTree(c).use {
            val head = JGitClient.head(it)?.sha
                ?: throw SyncException(SyncErrorCode.NO_REPO, "This desk holds no history for the project to move: sync it with GitHub first.")
            if (remoteHead != null && remoteHead != head) {
                throw SyncException(
                    SyncErrorCode.MOVE_HISTORY_MISMATCH,
                    "This desk's history (${head.take(7)}) is not GitHub's (${remoteHead.take(7)}) after syncing it: nothing was moved.",
                )
            }
            try {
                pushIn(it, c, url, credentials, head)
            } finally {
                JGitClient.deleteRefsUnder(it, CLOUD_PREFIX)
            }
        }

    /** A tag this desk holds: the ref it is pushed from and the commit it names. */
    private data class DeskTag(val ref: String, val commit: String)

    /** Every tag the desk holds: its own, and the old remote's as its last fetch read them. */
    private fun deskTags(repo: Repository): Map<String, DeskTag> {
        val remotePrefix = "refs/remotes/${RemoteSyncEngine.REMOTE_NAME}/tags/"
        val remote = JGitClient.listRemoteTags(repo, RemoteSyncEngine.REMOTE_NAME).mapValues { (name, commit) -> DeskTag("$remotePrefix$name", commit) }
        val local = JGitClient.listTags(repo).mapValues { (name, commit) -> DeskTag("refs/tags/$name", commit) }
        return remote + local
    }

    private fun pushIn(repo: Repository, c: Candidate, url: String, credentials: GitCredentials, head: String): Pushed {
        val main = JGitClient.DEFAULT_BRANCH
        val target = "refs/heads/$main"
        val source = repo.fullBranch?.takeIf { it.startsWith("refs/heads/") }
            ?: throw SyncException(SyncErrorCode.NO_REPO, "This desk's working tree for the project is on no branch: sync it with GitHub first.")
        val cloudRef = "${CLOUD_PREFIX}heads/$main"
        JGitClient.fetchUrlInto(repo, url, main, CLOUD_PREFIX, credentials)
        val relation = JGitClient.classify(repo, "HEAD", cloudRef)
        val tags = if (c.kind == ProjectKind.VENUE) deskTags(repo) else emptyMap()
        val cloudTags = JGitClient.refsAt(repo, "${CLOUD_PREFIX}tags/")
        val tagsDiffer = tags.any { (name, tag) -> cloudTags[name]?.let { it != tag.commit } == true }

        var diverged = relation is HistoryRelation.Diverged || tagsDiffer
        val pushBranch = !diverged && (relation is HistoryRelation.RemoteAbsent || relation is HistoryRelation.LocalAhead)
        val pushTags = if (diverged) emptyList() else tags.keys.filter { it !in cloudTags }.sorted()
        val specs = buildList {
            if (pushBranch) add("$source:$target")
            pushTags.forEach { add("${tags.getValue(it).ref}:refs/tags/$it") }
        }
        val answered = JGitClient.pushToUrl(repo, url, specs, credentials)
        val branchResult = answered[target]
        if (branchResult?.status == PushStatus.REJECTED_NONFASTFORWARD || branchResult?.status == PushStatus.REJECTED_REMOTE_CHANGED) {
            // Another desk pushed between the fetch and the push: its history is the cloud's now.
            diverged = true
        } else {
            answered.values.firstOrNull { it.status == PushStatus.REJECTED_OTHER || it.status == PushStatus.REJECTED_NONFASTFORWARD }?.let {
                throw SyncException(SyncErrorCode.PUSH_REJECTED, "The cloud refused the move: ${it.message ?: it.status}.")
            }
        }

        if (!diverged && relation !is HistoryRelation.RemoteAhead) {
            JGitClient.fetchUrlInto(repo, url, main, CLOUD_PREFIX, credentials)
            val onCloud = repo.resolve(cloudRef)?.name
            if (onCloud != head) {
                throw SyncException(
                    SyncErrorCode.MOVE_HISTORY_MISMATCH,
                    "The cloud's history (${onCloud?.take(7) ?: "none"}) is not this desk's (${head.take(7)}) after the push: the project stays on GitHub.",
                )
            }
            val tagsNow = JGitClient.refsAt(repo, "${CLOUD_PREFIX}tags/")
            tags.filter { (name, tag) -> tagsNow[name] != tag.commit }.keys.firstOrNull()?.let { name ->
                throw SyncException(
                    SyncErrorCode.MOVE_HISTORY_MISMATCH,
                    "The cloud lacks this venue's $name after the push: the venue stays on GitHub.",
                )
            }
        }
        return Pushed(head, diverged, pushTags.size)
    }

    private companion object {
        /** Where the cloud repo's refs are read into while a project moves; deleted after. */
        const val CLOUD_PREFIX = "refs/lighting7-move/"

        /** An old remote that cannot be reached, or refuses this desk: the move goes on from the working tree. */
        @Suppress("DEPRECATION")
        val UNREACHABLE = setOf(
            SyncErrorCode.MISSING_CREDENTIALS, SyncErrorCode.MISSING_PAT,
            SyncErrorCode.OAUTH_REAUTH_REQUIRED, SyncErrorCode.AUTH_FAILED,
        )

        val log = LoggerFactory.getLogger(CloudMove::class.java)
    }
}

/** `GET /cloud-sync/move`: what would move, and whether this desk pushes, without which nothing can. */
@Serializable
data class CloudMovePlan(val projects: List<CloudMovePlanRow>, val pushEnabled: Boolean = true)

@Serializable
data class CloudMovePlanRow(
    val projectId: Int,
    val name: String,
    /** `SHOW` or `VENUE`; venues move first. */
    val kind: String,
    /** The GitHub repository it syncs with now. */
    val repoUrl: String,
    /** Whether the cloud already has a repo for its uuid: another desk moved it. */
    val cloudHasRepo: Boolean,
    val cloudRepoName: String? = null,
)

@Serializable
enum class CloudMoveStatus { MOVED, MOVED_DIVERGED, FAILED }

/** `POST /cloud-sync/move`'s answer, a row per project asked for. */
@Serializable
data class CloudMoveResult(val projects: List<CloudMoveRow>)

@Serializable
data class CloudMoveRow(
    val projectId: Int,
    val name: String,
    val status: CloudMoveStatus,
    /** A failure's [SyncErrorCode]. */
    val code: String? = null,
    val message: String? = null,
    val repoUrl: String? = null,
    val repoName: String? = null,
    /** Whether the cloud had the repo already. */
    val existed: Boolean = false,
    /** Whether GitHub could not be synced first, so the working tree was moved as it was. */
    val fromWorkingTree: Boolean = false,
    val headSha: String? = null,
    /** A venue's version tags pushed. */
    val tags: Int = 0,
    /** The verifying sync after the re-point; null when it failed, with [verifyError] saying why. */
    val verify: SyncRunResult? = null,
    val verifyError: SyncErrorResponse? = null,
)
