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
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncSession
import uk.me.cormack.lighting7.models.DaoSyncSessionConflict
import uk.me.cormack.lighting7.models.DaoSyncSessionConflicts
import uk.me.cormack.lighting7.models.DaoSyncState
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.MissingCredentialsException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cloud-sync remote engine. Three-way diffs the local DB and the remote tree at the
 * record level: disjoint edits auto-merge, same-record edits open a `sync_session` in
 * `CONFLICTS_PENDING` for the user to resolve via REST. After every terminal outcome
 * that advances local HEAD, [bootstrapSyncStateAtHead] rewrites per-record `sync_state`.
 *
 * One [Mutex] per project guards `runSync` / `applySession` / `abortSession` against
 * concurrent invocation; conflict resolution is multi-step and racing it would be a
 * footgun.
 */
class RemoteSyncEngine(
    private val state: State,
    private val authResolver: AuthResolver,
) {

    private val workingTree = SyncWorkingTree(state)
    private val snapshotEngine = SnapshotEngine(state)
    private val importer = ProjectImporter(state)

    private val projectLocks = ConcurrentHashMap<Int, Mutex>()

    /**
     * Pull credentials for [repoUrl] via [authResolver], translating its typed errors
     * into the [SyncException] codes the route layer maps to HTTP statuses.
     */
    private suspend fun resolveCredentials(repoUrl: String): GitCredentials {
        return try {
            authResolver.resolveFor(repoUrl)
        } catch (e: MissingCredentialsException) {
            throw SyncException(SyncErrorCode.MISSING_CREDENTIALS, e.message ?: "No GitHub credentials configured for this repository.")
        } catch (e: OAuthReauthRequiredException) {
            throw SyncException(SyncErrorCode.OAUTH_REAUTH_REQUIRED, e.message ?: "GitHub OAuth re-authentication required.")
        }
    }

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

    suspend fun applySession(
        projectId: Int,
        projectUuid: UUID,
        installUuid: UUID,
        installFriendlyName: String,
    ): SyncRunResult {
        val mutex = projectLocks.computeIfAbsent(projectId) { Mutex() }
        return mutex.withLock {
            doApply(projectId, projectUuid, installUuid, installFriendlyName)
        }
    }

    suspend fun abortSession(projectId: Int, projectUuid: UUID): AbortResult {
        val mutex = projectLocks.computeIfAbsent(projectId) { Mutex() }
        return mutex.withLock {
            doAbort(projectId, projectUuid)
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

            ConflictSession.findActive(projectId)?.let { existing ->
                throw SyncException(
                    SyncErrorCode.SESSION_PENDING,
                    "A conflict-resolution session (#${existing.id.value}) is already open for this project — apply or abort it before starting another sync.",
                )
            }

            url to cfg.branch
        }

        val credentials = resolveCredentials(repoUrl)

        snapshotEngine.snapshot(projectId, projectUuid, installUuid, installFriendlyName, message = null)

        val workingTreePath = workingTree.pathFor(projectUuid)
        val outcome = withContext(Dispatchers.IO) {
            val repo = JGitClient.open(workingTreePath)
                ?: throw SyncException(
                    SyncErrorCode.NO_REPO,
                    "Working tree at $workingTreePath has no git repo — take a snapshot first.",
                )
            repo.use {
                configureAndExchange(it, projectId, installUuid, installFriendlyName, repoUrl, branch, credentials)
            }
        }

        if (outcome.outcome != SyncOutcome.CONFLICTS_PENDING) {
            transaction(state.database) {
                val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                    ?: error("sync_config row vanished mid-sync for project $projectId")
                cfg.lastSyncedSha = outcome.headSha
                cfg.lastSyncedAtMs = System.currentTimeMillis()
            }
        }

        if (outcome.outcome == SyncOutcome.FAST_FORWARDED || outcome.outcome == SyncOutcome.MERGED) {
            reloadShowIfActive(projectId)
        }

        return outcome
    }

    private fun configureAndExchange(
        repo: Repository,
        projectId: Int,
        installUuid: UUID,
        installFriendlyName: String,
        repoUrl: String,
        branch: String,
        credentials: GitCredentials,
        attemptsRemaining: Int = MAX_PUSH_RETRIES,
    ): SyncRunResult {
        JGitClient.setRemote(repo, REMOTE_NAME, repoUrl)

        val remoteCommitId = try {
            JGitClient.fetch(repo, REMOTE_NAME, branch, credentials)
        } catch (e: GitAuthException) {
            throw SyncException(SyncErrorCode.AUTH_FAILED, "GitHub rejected the credentials — check the OAuth identity is connected (or that any PAT has `repo` scope and isn't expired). ${e.message}", e)
        }

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
        logger.info("Sync classify for repo={}: {} (attempts remaining {})", repoUrl, relation, attemptsRemaining)

        return when (relation) {
            HistoryRelation.Equal -> {
                val head = JGitClient.head(repo)?.sha ?: error("Equal classification with null HEAD")
                bootstrapSyncStateAtHead(projectId, repo, head)
                SyncRunResult(SyncOutcome.NO_OP, pushed = 0, pulled = 0, replaced = 0, headSha = head, message = "Already in sync.", sessionId = null, conflictCount = 0)
            }
            HistoryRelation.RemoteAbsent -> {
                val head = JGitClient.head(repo)?.sha
                    ?: error("Cannot push from an unborn repo (snapshot should have created at least one commit).")
                pushAheadOrRedispatch(
                    repo, projectId, installUuid, installFriendlyName,
                    repoUrl, branch, credentials,
                    headSha = head, ahead = 1, message = "Initial push to remote.",
                    attemptsRemaining = attemptsRemaining,
                )
            }
            is HistoryRelation.LocalAhead -> {
                val head = JGitClient.head(repo)?.sha ?: error("LocalAhead with null HEAD")
                pushAheadOrRedispatch(
                    repo, projectId, installUuid, installFriendlyName,
                    repoUrl, branch, credentials,
                    headSha = head, ahead = relation.ahead,
                    message = "Pushed ${relation.ahead} commit(s).",
                    attemptsRemaining = attemptsRemaining,
                )
            }
            is HistoryRelation.RemoteAhead -> {
                JGitClient.resetHard(repo, remoteRef)
                importer.replaceFromWorkingTree(projectId, repo.workTree.toPath())
                val head = JGitClient.head(repo)?.sha ?: error("RemoteAhead fast-forward produced no HEAD")
                bootstrapSyncStateAtHead(projectId, repo, head)
                SyncRunResult(SyncOutcome.FAST_FORWARDED, pushed = 0, pulled = relation.behind, replaced = 0, headSha = head, message = "Pulled ${relation.behind} commit(s) from remote.", sessionId = null, conflictCount = 0)
            }
            is HistoryRelation.Diverged -> {
                handleDiverged(
                    repo, projectId, installUuid, installFriendlyName, branch, credentials, relation,
                    attemptsRemaining = attemptsRemaining,
                )
            }
        }
    }

    /**
     * The `LocalAhead` / `RemoteAbsent` push path. On rejection, re-fetch and recurse into
     * [configureAndExchange] so the new history relation can be handled the same way as
     * the first attempt — the only difference is the decremented retry budget. This lets a
     * "we lost the push race; peer's commit means we're now Diverged" scenario fall
     * through to the full Diverged-with-retry handling, which the user-facing tests
     * exercise via concurrent runs.
     */
    private fun pushAheadOrRedispatch(
        repo: Repository,
        projectId: Int,
        installUuid: UUID,
        installFriendlyName: String,
        repoUrl: String,
        branch: String,
        credentials: GitCredentials,
        headSha: String,
        ahead: Int,
        message: String,
        attemptsRemaining: Int,
    ): SyncRunResult {
        val pushed = JGitClient.push(repo, REMOTE_NAME, branch, credentials, force = false)
        if (!pushIsRejected(pushed)) {
            ensurePushOk(pushed)
            bootstrapSyncStateAtHead(projectId, repo, headSha)
            return SyncRunResult(
                SyncOutcome.PUSHED, pushed = ahead, pulled = 0, replaced = 0,
                headSha = headSha, message = message, sessionId = null, conflictCount = 0,
            )
        }
        if (attemptsRemaining <= 0) {
            throw SyncException(
                SyncErrorCode.PUSH_REJECTED,
                "Remote rejected the push after $MAX_PUSH_RETRIES attempts: ${pushed.message ?: pushed.status}.",
            )
        }
        logger.info(
            "LocalAhead push rejected ({}); re-classifying with attempts remaining {}",
            pushed.message ?: pushed.status, attemptsRemaining - 1,
        )
        return configureAndExchange(
            repo, projectId, installUuid, installFriendlyName,
            repoUrl, branch, credentials,
            attemptsRemaining = attemptsRemaining - 1,
        )
    }

    private fun handleDiverged(
        repo: Repository,
        projectId: Int,
        installUuid: UUID,
        installFriendlyName: String,
        branch: String,
        credentials: GitCredentials,
        relation: HistoryRelation.Diverged,
        attemptsRemaining: Int,
    ): SyncRunResult {
        val remoteRef = remoteBranchRef(branch)
        val localSha = JGitClient.head(repo)?.sha ?: error("Diverged with null HEAD")
        val remoteSha = repo.resolve(remoteRef)?.name ?: error("Diverged with null remote ref")
        val baseSha = JGitClient.mergeBase(repo, localSha, remoteSha)

        val localSnapshots = RecordHasher.fromRef(repo, localSha)
        val remoteSnapshots = RecordHasher.fromRef(repo, remoteSha)
        val syncStateMeta = readSyncStateMeta(projectId)

        val outcomes = ThreeWayDiff.compute(
            localSnapshots.mapValues { it.value.toMeta() },
            remoteSnapshots.mapValues { it.value.toMeta() },
            syncStateMeta,
        )

        val conflictKeys = outcomes
            .filter { it.value is DiffOutcome.Conflict }
            .map { it.key }

        if (conflictKeys.isNotEmpty()) {
            return openConflictSessionFor(
                repo, projectId,
                outcomes, conflictKeys,
                localSnapshots, remoteSnapshots, baseSha,
                localSha, remoteSha,
            )
        }

        return autoMerge(
            repo, projectId, installUuid, installFriendlyName,
            branch, credentials,
            localSha, remoteSha, remoteRef,
            localSnapshots, remoteSnapshots, outcomes,
            relation.ahead, relation.behind,
            isFromConflictSession = false,
            attemptsRemaining = attemptsRemaining,
        )
    }

    /**
     * Read every project `sync_state` row into the `(hash, isDeleted)` map shape the
     * three-way diff consumes. One trip to the DB per diff invocation.
     */
    private fun readSyncStateMeta(projectId: Int): Map<RecordKey, SnapshotMeta> =
        transaction(state.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectId }
                .associate {
                    RecordKey(it.tableName, it.recordUuid) to
                        SnapshotMeta(it.lastSyncedHash, it.lastSyncedIsDeleted)
                }
        }

    /**
     * Build the merged working-tree state, commit it as a two-parent merge, and push.
     * Used both for the no-conflict Diverged path and the apply-session path. The caller
     * supplies pre-computed snapshots so we don't walk the trees twice.
     *
     * On `PUSH_REJECTED` (a peer pushed between our fetch and our push), retry up to
     * [attemptsRemaining] times: re-fetch, re-classify, and either re-merge against the
     * new remote tip or surface new conflicts. Note that `replaceFromWorkingTree` rewrites
     * the project's row set on every retry; for the small project sizes this engine
     * targets that's acceptable, but a future optimiser could budget here if it ever bites.
     */
    private fun autoMerge(
        repo: Repository,
        projectId: Int,
        installUuid: UUID,
        installFriendlyName: String,
        branch: String,
        credentials: GitCredentials,
        localSha: String,
        remoteSha: String,
        remoteRef: String,
        localSnapshots: Map<RecordKey, RecordSnapshot>,
        remoteSnapshots: Map<RecordKey, RecordSnapshot>,
        outcomes: Map<RecordKey, DiffOutcome>,
        ahead: Int,
        behind: Int,
        isFromConflictSession: Boolean,
        attemptsRemaining: Int,
    ): SyncRunResult {
        JGitClient.resetHard(repo, remoteRef)
        val workingTreePath = repo.workTree.toPath()

        // Overlay local-wins and manually-edited records onto the remote-tip working
        // tree. For each winning record, delete any path that exists only on the remote
        // side (so a tombstone overlay removes the live record file, and vice-versa) and
        // write the local snapshot's files. For MANUAL we look up the file paths from the
        // local snapshot — the user can only manually edit a record that already existed
        // locally.
        for ((key, outcome) in outcomes) {
            when (outcome) {
                is DiffOutcome.TakeLocal -> {
                    val localSnap = localSnapshots[key] ?: continue
                    overlayLocalOntoRemote(workingTreePath, localSnap, remoteSnapshots[key])
                }
                is DiffOutcome.TakeManual -> {
                    val localSnap = localSnapshots[key]
                        ?: error("MANUAL resolution for $key but no local snapshot to anchor it")
                    val paths = localSnap.files.keys
                    if (paths.size != 1) {
                        error(
                            "MANUAL resolution for $key spans ${paths.size} files; " +
                                "MANUAL only supports single-file records",
                        )
                    }
                    removeRemoteOnlyPaths(workingTreePath, localSnap, remoteSnapshots[key])
                    writeWorkingTreeFile(workingTreePath, paths.single(), outcome.content)
                }
                else -> Unit
            }
        }

        importer.replaceFromWorkingTree(projectId, workingTreePath)

        if (!JGitClient.stageAll(repo)) {
            // No tree-level diff vs remote — equivalent to a fast-forward.
            val head = JGitClient.head(repo)?.sha ?: error("Auto-merge no-op: null HEAD")
            bootstrapSyncStateAtHead(projectId, repo, head)
            return SyncRunResult(
                outcome = SyncOutcome.MERGED,
                pushed = 0, pulled = behind, replaced = 0,
                headSha = head,
                message = "Merged with remote (no local-only changes).",
                sessionId = null, conflictCount = 0,
            )
        }
        val shortInstall = installUuid.toString().take(8)
        val authorEmail = "$shortInstall@${SnapshotEngine.INSTALL_EMAIL_DOMAIN}"
        val mergeKind = if (isFromConflictSession) "Resolve" else "Merge"
        val summary = "$mergeKind ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}"
        val message = "$installFriendlyName: $summary [install:$shortInstall]"
        // After resetHard origin, HEAD is at remoteSha — that becomes parent #1
        // automatically. localSha is the extra parent that makes this a merge commit.
        val merged = JGitClient.commitWithParents(
            repo, installFriendlyName, authorEmail, message,
            extraParentSha = localSha,
        )

        val pushed = JGitClient.push(repo, REMOTE_NAME, branch, credentials, force = false)
        if (pushIsRejected(pushed)) {
            return retryAfterPushReject(
                repo, projectId, installUuid, installFriendlyName,
                branch, credentials,
                isFromConflictSession = isFromConflictSession,
                rejectionMessage = pushed.message ?: pushed.status.toString(),
                attemptsRemaining = attemptsRemaining,
            )
        }
        ensurePushOk(pushed)

        bootstrapSyncStateAtHead(projectId, repo, merged.sha)

        return SyncRunResult(
            outcome = SyncOutcome.MERGED,
            pushed = ahead, pulled = behind, replaced = 0,
            headSha = merged.sha,
            message = "Merged ${ahead + behind} commit(s) and pushed.",
            sessionId = null, conflictCount = 0,
        )
    }

    private fun pushIsRejected(result: PushResult): Boolean = when (result.status) {
        PushStatus.REJECTED_NONFASTFORWARD,
        PushStatus.REJECTED_REMOTE_CHANGED,
        PushStatus.REJECTED_OTHER -> true
        else -> false
    }

    /**
     * Push was rejected (a peer raced us between fetch and push). HEAD is at our merge
     * commit; the DB is at the merged state. Re-fetch and either re-merge against the
     * new remote tip (if no new conflicts emerge) or surface the new conflicts.
     */
    private fun retryAfterPushReject(
        repo: Repository,
        projectId: Int,
        installUuid: UUID,
        installFriendlyName: String,
        branch: String,
        credentials: GitCredentials,
        isFromConflictSession: Boolean,
        rejectionMessage: String,
        attemptsRemaining: Int,
    ): SyncRunResult {
        if (attemptsRemaining <= 0) {
            throw SyncException(
                SyncErrorCode.PUSH_REJECTED,
                "Remote rejected the push after $MAX_PUSH_RETRIES attempts: $rejectionMessage. " +
                    "Try again — the remote keeps moving under us.",
            )
        }

        logger.info(
            "Push rejected ({}); re-fetching and retrying (attempts remaining: {})",
            rejectionMessage, attemptsRemaining,
        )
        try {
            JGitClient.fetch(repo, REMOTE_NAME, branch, credentials)
        } catch (e: GitAuthException) {
            throw SyncException(SyncErrorCode.AUTH_FAILED, "GitHub rejected the credentials during retry: ${e.message}", e)
        }

        // After our last autoMerge ran, HEAD is at our merge commit. That commit captures
        // both the user's original work and the previous remote state — so for the retry's
        // diff input we walk our merge commit as the "local" side.
        val newLocalSha = JGitClient.head(repo)?.sha ?: error("Null HEAD on retry")
        val remoteRef = remoteBranchRef(branch)
        val newRelation = JGitClient.classify(repo, "HEAD", remoteRef)

        return when (newRelation) {
            // The peer's push made our merge a fast-forward target — done.
            HistoryRelation.Equal -> {
                bootstrapSyncStateAtHead(projectId, repo, newLocalSha)
                SyncRunResult(
                    outcome = SyncOutcome.MERGED, pushed = 0, pulled = 0, replaced = 0,
                    headSha = newLocalSha,
                    message = "Merge already on remote after retry.",
                    sessionId = null, conflictCount = 0,
                )
            }
            HistoryRelation.RemoteAbsent -> {
                error("Remote vanished between push attempts; this should not happen.")
            }
            // Our merge is now ahead — just push it.
            is HistoryRelation.LocalAhead -> {
                val pushed = JGitClient.push(repo, REMOTE_NAME, branch, credentials, force = false)
                if (pushIsRejected(pushed)) {
                    return retryAfterPushReject(
                        repo, projectId, installUuid, installFriendlyName,
                        branch, credentials, isFromConflictSession,
                        pushed.message ?: pushed.status.toString(), attemptsRemaining - 1,
                    )
                }
                ensurePushOk(pushed)
                bootstrapSyncStateAtHead(projectId, repo, newLocalSha)
                SyncRunResult(
                    outcome = SyncOutcome.MERGED, pushed = newRelation.ahead, pulled = 0, replaced = 0,
                    headSha = newLocalSha,
                    message = "Pushed merge after retry.",
                    sessionId = null, conflictCount = 0,
                )
            }
            // Remote moved ahead of us in a strict-superset way (peer fast-forwarded our
            // merge commit and then added more) — fast-forward our HEAD and re-import.
            is HistoryRelation.RemoteAhead -> {
                JGitClient.resetHard(repo, remoteRef)
                importer.replaceFromWorkingTree(projectId, repo.workTree.toPath())
                val head = JGitClient.head(repo)?.sha ?: error("Null HEAD after fast-forward retry")
                bootstrapSyncStateAtHead(projectId, repo, head)
                SyncRunResult(
                    outcome = SyncOutcome.FAST_FORWARDED,
                    pushed = 0, pulled = newRelation.behind, replaced = 0,
                    headSha = head,
                    message = "Fast-forwarded to peer's superset after retry.",
                    sessionId = null, conflictCount = 0,
                )
            }
            // The common case: a peer's commit lands during our push window. Re-do the
            // diff against the new remote tip, merging in any record we hadn't already
            // resolved. New conflicts get surfaced (or, in the apply-from-session path,
            // throw SESSION_STALE so the user re-syncs and re-resolves cleanly).
            is HistoryRelation.Diverged -> {
                val remoteSha = repo.resolve(remoteRef)?.name
                    ?: error("Diverged with null remote ref on retry")
                val baseSha = JGitClient.mergeBase(repo, newLocalSha, remoteSha)
                val localSnapshots = RecordHasher.fromRef(repo, newLocalSha)
                val remoteSnapshots = RecordHasher.fromRef(repo, remoteSha)
                val syncStateMeta = readSyncStateMeta(projectId)
                val outcomes = ThreeWayDiff.compute(
                    localSnapshots.mapValues { it.value.toMeta() },
                    remoteSnapshots.mapValues { it.value.toMeta() },
                    syncStateMeta,
                )
                val newConflictKeys = outcomes
                    .filter { it.value is DiffOutcome.Conflict }
                    .map { it.key }

                if (newConflictKeys.isNotEmpty()) {
                    if (isFromConflictSession) {
                        throw SyncException(
                            SyncErrorCode.SESSION_STALE,
                            "A peer pushed conflicting changes during apply (${newConflictKeys.size} new " +
                                "conflict(s)). Abort the session and re-run sync.",
                        )
                    }
                    return openConflictSessionFor(
                        repo, projectId,
                        outcomes, newConflictKeys,
                        localSnapshots, remoteSnapshots, baseSha,
                        newLocalSha, remoteSha,
                    )
                }

                autoMerge(
                    repo, projectId, installUuid, installFriendlyName,
                    branch, credentials,
                    newLocalSha, remoteSha, remoteRef,
                    localSnapshots, remoteSnapshots, outcomes,
                    newRelation.ahead, newRelation.behind,
                    isFromConflictSession = isFromConflictSession,
                    attemptsRemaining = attemptsRemaining - 1,
                )
            }
        }
    }

    /**
     * Hoist the conflict-session opening from [handleDiverged] so the push-retry path can
     * surface newly-discovered conflicts the same way the first-time-diverged path does.
     */
    private fun openConflictSessionFor(
        repo: Repository,
        projectId: Int,
        outcomes: Map<RecordKey, DiffOutcome>,
        conflictKeys: List<RecordKey>,
        localSnapshots: Map<RecordKey, RecordSnapshot>,
        remoteSnapshots: Map<RecordKey, RecordSnapshot>,
        baseSha: String?,
        localSha: String,
        remoteSha: String,
    ): SyncRunResult {
        val baseSnapshots = baseSha?.let { RecordHasher.fromRef(repo, it) } ?: emptyMap()
        val sessionId = transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: error("Project $projectId vanished mid-sync")
            val rows = conflictKeys.map { key ->
                val kind = (outcomes[key] as DiffOutcome.Conflict).kind.name
                ConflictRow(
                    tableName = key.tableName,
                    recordUuid = key.uuid,
                    conflictKind = kind,
                    localJson = localSnapshots[key]?.canonicalJsonForDisplay(),
                    remoteJson = remoteSnapshots[key]?.canonicalJsonForDisplay(),
                    baseJson = baseSnapshots[key]?.canonicalJsonForDisplay(),
                )
            }
            ConflictSession.open(project, localSha, remoteSha, baseSha, rows).id.value
        }
        state.emitCloudSyncEvent(
            uk.me.cormack.lighting7.plugins.CloudSyncConflictsPendingOutMessage(
                projectId = projectId,
                sessionId = sessionId,
                conflictCount = conflictKeys.size,
            ),
        )
        return SyncRunResult(
            outcome = SyncOutcome.CONFLICTS_PENDING,
            pushed = 0, pulled = 0, replaced = 0,
            headSha = localSha,
            message = "Found ${conflictKeys.size} conflict(s); resolve them in the UI to continue.",
            sessionId = sessionId,
            conflictCount = conflictKeys.size,
        )
    }

    /**
     * Overlay [localSnap]'s files onto the working tree, after deleting any path that
     * exists on the remote side but not in the local snapshot. This is what flips a
     * record-file ↔ tombstone-file pairing during merge — the kind of file changes
     * between sides, so just writing local without removing remote-only paths would
     * leave a stale file behind.
     */
    private fun overlayLocalOntoRemote(
        workingTreePath: Path,
        localSnap: RecordSnapshot,
        remoteSnap: RecordSnapshot?,
    ) {
        removeRemoteOnlyPaths(workingTreePath, localSnap, remoteSnap)
        for ((relPath, content) in localSnap.files) {
            writeWorkingTreeFile(workingTreePath, relPath, content)
        }
    }

    private fun removeRemoteOnlyPaths(
        workingTreePath: Path,
        localSnap: RecordSnapshot,
        remoteSnap: RecordSnapshot?,
    ) {
        val remotePaths = remoteSnap?.files?.keys ?: return
        for (path in remotePaths - localSnap.files.keys) {
            Files.deleteIfExists(workingTreePath.resolve(path))
        }
    }

    private suspend fun doApply(
        projectId: Int,
        projectUuid: UUID,
        installUuid: UUID,
        installFriendlyName: String,
    ): SyncRunResult {
        val view = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?: throw SyncException(SyncErrorCode.REPO_URL_MISSING, "Sync config has not been initialised for this project.")
            val session = ConflictSession.findActive(projectId)
                ?: throw SyncException(SyncErrorCode.SESSION_NOT_FOUND, "No active conflict session for this project.")
            if (session.state != SessionState.CONFLICTS_PENDING.name) {
                throw SyncException(SyncErrorCode.SESSION_NOT_FOUND, "Session is not in CONFLICTS_PENDING state.")
            }
            if (!ConflictSession.allResolved(session)) {
                throw SyncException(
                    SyncErrorCode.UNRESOLVED_CONFLICTS,
                    "Some conflicts still need a resolution before applying.",
                )
            }
            val resolutions = ConflictSession.listConflicts(session).associate { c ->
                val choice = c.resolution ?: error("allResolved guard violated")
                if (choice == ConflictResolution.MANUAL.name && c.manualValueJson == null) {
                    throw SyncException(
                        SyncErrorCode.UNRESOLVED_CONFLICTS,
                        "Conflict ${c.tableName}/${c.recordUuid} is marked MANUAL but has no replacement content.",
                    )
                }
                RecordKey(c.tableName, c.recordUuid) to ResolutionChoice(choice, c.manualValueJson)
            }
            session.state = SessionState.APPLYING.name
            ApplyView(
                sessionId = session.id.value,
                localSha = session.localSha ?: error("Session ${session.id.value} missing localSha"),
                remoteSha = session.remoteSha ?: error("Session ${session.id.value} missing remoteSha"),
                branch = cfg.branch,
                repoUrl = cfg.repoUrl?.takeIf { it.isNotBlank() }
                    ?: throw SyncException(SyncErrorCode.REPO_URL_MISSING, "Repository URL is not set."),
                resolutions = resolutions,
            )
        }

        val credentials = resolveCredentials(view.repoUrl)

        val workingTreePath = workingTree.pathFor(projectUuid)
        val result = withContext(Dispatchers.IO) {
            val repo = JGitClient.open(workingTreePath)
                ?: throw SyncException(SyncErrorCode.NO_REPO, "Working tree at $workingTreePath has no git repo.")
            repo.use { applyMergeFromSession(it, projectId, installUuid, installFriendlyName, view, credentials) }
        }

        transaction(state.database) {
            val session = DaoSyncSession.findById(view.sessionId) ?: return@transaction
            session.state = SessionState.DONE.name
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
            cfg?.let {
                it.lastSyncedSha = result.headSha
                it.lastSyncedAtMs = System.currentTimeMillis()
            }
        }

        reloadShowIfActive(projectId)
        return result
    }

    private fun applyMergeFromSession(
        repo: Repository,
        projectId: Int,
        installUuid: UUID,
        installFriendlyName: String,
        view: ApplyView,
        credentials: GitCredentials,
    ): SyncRunResult {
        // HEAD must still match the SHA the session was opened against. Anything else
        // means a snapshot or sync slipped in between resolve and apply.
        val currentHead = JGitClient.head(repo)?.sha
        if (currentHead != view.localSha) {
            transaction(state.database) {
                ConflictSession.findActive(projectId)?.let { it.state = SessionState.CONFLICTS_PENDING.name }
            }
            throw SyncException(
                SyncErrorCode.SESSION_STALE,
                "Local HEAD has moved since the session opened — abort the session and run sync again.",
            )
        }

        try {
            JGitClient.fetch(repo, REMOTE_NAME, view.branch, credentials)
        } catch (e: GitAuthException) {
            throw SyncException(SyncErrorCode.AUTH_FAILED, "GitHub rejected the credentials during apply: ${e.message}", e)
        }

        val localSnapshots = RecordHasher.fromRef(repo, view.localSha)
        val remoteSnapshots = RecordHasher.fromRef(repo, view.remoteSha)
        val syncStateMeta = readSyncStateMeta(projectId)
        val baseOutcomes = ThreeWayDiff.compute(
            localSnapshots.mapValues { it.value.toMeta() },
            remoteSnapshots.mapValues { it.value.toMeta() },
            syncStateMeta,
        )
        val mergedOutcomes = baseOutcomes.toMutableMap()
        for ((key, choice) in view.resolutions) {
            mergedOutcomes[key] = when (choice.resolution) {
                ConflictResolution.LOCAL.name -> DiffOutcome.TakeLocal
                ConflictResolution.REMOTE.name -> DiffOutcome.TakeRemote
                ConflictResolution.MANUAL.name -> DiffOutcome.TakeManual(
                    choice.manualValueJson
                        ?: error("MANUAL resolution for $key missing manualValueJson at apply time"),
                )
                else -> error("Unexpected resolution: ${choice.resolution}")
            }
        }

        return autoMerge(
            repo, projectId, installUuid, installFriendlyName,
            view.branch, credentials,
            view.localSha, view.remoteSha, remoteBranchRef(view.branch),
            localSnapshots, remoteSnapshots, mergedOutcomes,
            ahead = 0, behind = 0,
            isFromConflictSession = true,
            attemptsRemaining = MAX_PUSH_RETRIES,
        )
    }

    private suspend fun doAbort(projectId: Int, projectUuid: UUID): AbortResult {
        val sessionId = transaction(state.database) {
            val session = ConflictSession.findActive(projectId)
                ?: throw SyncException(SyncErrorCode.SESSION_NOT_FOUND, "No active conflict session.")
            DaoSyncSessionConflict.find { DaoSyncSessionConflicts.session eq session.id }
                .forEach { it.delete() }
            session.state = SessionState.ABORTED.name
            session.id.value
        }

        val workingTreePath = workingTree.pathFor(projectUuid)
        withContext(Dispatchers.IO) {
            JGitClient.open(workingTreePath)?.use { repo ->
                JGitClient.head(repo)?.let { JGitClient.resetHard(repo, it.sha) }
            }
        }
        return AbortResult(sessionId = sessionId)
    }

    /**
     * Walk the working tree at [sha] and replace this project's `sync_state` rows so each
     * entry exactly mirrors what's on disk. Called after every terminal outcome that
     * advances HEAD (NO_OP, PUSHED, FAST_FORWARDED, MERGED). Bootstraps the table on the
     * first Phase-5 sync after upgrading from Phase 4.
     */
    private fun bootstrapSyncStateAtHead(projectId: Int, repo: Repository, sha: String) {
        // Common case (NO_OP, repeat snapshot) — every existing row is already at this
        // SHA. Skip the delete-all + insert-all churn entirely.
        val alreadyAtSha = transaction(state.database) {
            val rows = DaoSyncState.find { DaoSyncStates.project eq projectId }
            val any = rows.firstOrNull()
            any != null && rows.all { it.lastSyncedSha == sha }
        }
        if (alreadyAtSha) return

        val snapshots = RecordHasher.fromRef(repo, sha)
        transaction(state.database) {
            DaoSyncState.find { DaoSyncStates.project eq projectId }.forEach { it.delete() }
            val project = DaoProject.findById(projectId) ?: return@transaction
            for ((key, snap) in snapshots) {
                DaoSyncState.new {
                    this.project = project
                    this.tableName = key.tableName
                    this.recordUuid = key.uuid
                    this.lastSyncedSha = sha
                    this.lastSyncedHash = snap.hash
                    this.lastSyncedIsDeleted = snap.isDeleted
                }
            }
        }
    }

    /** Write [content] to `relPath` under [workingTreePath], creating parent dirs. */
    private fun writeWorkingTreeFile(workingTreePath: Path, relPath: String, content: String) {
        val target = workingTreePath.resolve(relPath)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
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

    private suspend fun reloadShowIfActive(projectId: Int) {
        val isActive = try {
            state.projectManager.currentProject.id.value == projectId
        } catch (_: Exception) {
            false
        }
        if (!isActive) return
        try {
            state.projectManager.switchProject(projectId)
        } catch (e: Exception) {
            logger.warn("Failed to hot-reload show after pull for project {}: {}", projectId, e.message)
        }
    }

    /**
     * Internal struct passed from the DB-read step into the IO-bound apply step. Holds the
     * resolution choice plus, for MANUAL, the user-edited content. Carrying both here means
     * the IO step doesn't need to revisit the DB for manual payloads.
     */
    private data class ApplyView(
        val sessionId: Int,
        val localSha: String,
        val remoteSha: String,
        val branch: String,
        val repoUrl: String,
        val resolutions: Map<RecordKey, ResolutionChoice>,
    )

    private data class ResolutionChoice(val resolution: String, val manualValueJson: String?)

    companion object {
        const val REMOTE_NAME = "origin"
        /** Cap on automatic retries when push is rejected by the remote. */
        const val MAX_PUSH_RETRIES = 3
        private val logger = LoggerFactory.getLogger(RemoteSyncEngine::class.java)
    }
}

/**
 * Canonical-JSON view of a record snapshot for the conflict-resolution UI, suitable for
 * storing as a single TEXT column on `sync_session_conflict`. Returns `null` for
 * tombstones — the UI then renders a "(deleted)" placeholder instead of the marker file
 * body, matching the frontend `localJson: string | null` typing for the deletion conflict
 * kinds. Live-record files are joined in sorted-path order with a literal `\n`, matching
 * the order [RecordHasher] uses for hashing.
 */
private fun RecordSnapshot.canonicalJsonForDisplay(): String? {
    if (isDeleted) return null
    return files.entries.sortedBy { it.key }.joinToString("\n") { it.value }
}

/** Outcome of a successful sync run. The UI uses this to pick which toast/badge to show. */
enum class SyncOutcome { NO_OP, PUSHED, FAST_FORWARDED, MERGED, CONFLICTS_PENDING }

@Serializable
data class SyncRunResult(
    val outcome: SyncOutcome,
    val pushed: Int,
    val pulled: Int,
    /** Number of remote commits dropped (always 0 in Phase 5; retained for API stability). */
    val replaced: Int,
    val headSha: String,
    val message: String,
    /** Set when `outcome == CONFLICTS_PENDING`; null otherwise. */
    val sessionId: Int? = null,
    /** Number of conflicts persisted; 0 unless `outcome == CONFLICTS_PENDING`. */
    val conflictCount: Int = 0,
)

@Serializable
data class AbortResult(val sessionId: Int)

/**
 * User-visible sync errors, with stable codes the UI can branch on. Mapped to HTTP
 * statuses inside the route layer (see [toHttpStatus]).
 */
enum class SyncErrorCode {
    REPO_URL_MISSING, SYNC_DISABLED,
    /** No GitHub credentials of any kind (OAuth or PAT) configured for this repo. */
    MISSING_CREDENTIALS,
    /**
     * Legacy alias for [MISSING_CREDENTIALS]; retained for one release so existing UIs
     * that branch on this code keep working. New code should branch on
     * [MISSING_CREDENTIALS] / [OAUTH_REAUTH_REQUIRED].
     */
    @Deprecated("Use MISSING_CREDENTIALS or OAUTH_REAUTH_REQUIRED.")
    MISSING_PAT,
    /** OAuth identity present but the refresh token is rejected — user must re-connect. */
    OAUTH_REAUTH_REQUIRED,
    AUTH_FAILED,
    FORMAT_TOO_NEW, PUSH_REJECTED, NO_REPO,
    SESSION_PENDING, SESSION_NOT_FOUND, SESSION_STALE, UNRESOLVED_CONFLICTS,
}

class SyncException(val code: SyncErrorCode, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

@Suppress("DEPRECATION")
fun SyncErrorCode.toHttpStatus(): HttpStatusCode = when (this) {
    SyncErrorCode.REPO_URL_MISSING, SyncErrorCode.SYNC_DISABLED, SyncErrorCode.NO_REPO ->
        HttpStatusCode.BadRequest
    SyncErrorCode.MISSING_CREDENTIALS, SyncErrorCode.MISSING_PAT,
    SyncErrorCode.OAUTH_REAUTH_REQUIRED, SyncErrorCode.AUTH_FAILED ->
        HttpStatusCode.Unauthorized
    SyncErrorCode.FORMAT_TOO_NEW -> HttpStatusCode.UnprocessableEntity
    SyncErrorCode.UNRESOLVED_CONFLICTS -> HttpStatusCode.UnprocessableEntity
    SyncErrorCode.PUSH_REJECTED, SyncErrorCode.SESSION_PENDING, SyncErrorCode.SESSION_STALE ->
        HttpStatusCode.Conflict
    SyncErrorCode.SESSION_NOT_FOUND -> HttpStatusCode.NotFound
}
