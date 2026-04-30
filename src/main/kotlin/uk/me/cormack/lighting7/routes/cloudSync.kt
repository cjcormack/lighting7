package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.plugins.CloudSyncDoneOutMessage
import uk.me.cormack.lighting7.plugins.CloudSyncFailedOutMessage
import uk.me.cormack.lighting7.plugins.CloudSyncStartedOutMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.CommitInfo
import uk.me.cormack.lighting7.sync.ConflictResolution
import uk.me.cormack.lighting7.sync.ConflictSession
import uk.me.cormack.lighting7.sync.JGitClient
import uk.me.cormack.lighting7.sync.RemoteSyncEngine
import uk.me.cormack.lighting7.sync.ResolutionEntry
import uk.me.cormack.lighting7.sync.SessionState
import uk.me.cormack.lighting7.sync.SnapshotEngine
import uk.me.cormack.lighting7.sync.SnapshotResponse
import uk.me.cormack.lighting7.sync.SyncErrorCode
import uk.me.cormack.lighting7.sync.SyncException
import uk.me.cormack.lighting7.sync.SyncOutcome
import uk.me.cormack.lighting7.sync.SyncRunResult
import uk.me.cormack.lighting7.sync.SyncWorkingTree
import uk.me.cormack.lighting7.sync.toHttpStatus
import java.util.UUID

/**
 * Cloud-sync REST endpoints.
 *
 * Phase 4 shipped configuration, snapshots, status, log, credentials, and a single-shot
 * `/sync/run` endpoint. Phase 5 layers on the conflict-session lifecycle:
 * `/sync/conflicts` (read), `/sync/resolve` (mark each conflict LOCAL/REMOTE),
 * `/sync/apply` (commit + push the resolved tree), `/sync/abort` (drop the session).
 *
 * JGit calls block; route handlers wrap them in `withContext(Dispatchers.IO)`.
 */
internal fun Route.routeApiRestProjectCloudSync(state: State) {
    val workingTree = SyncWorkingTree(state)
    val snapshotEngine = SnapshotEngine(state)
    val remoteSyncEngine = RemoteSyncEngine(state, state.authResolver)

    get<ProjectSyncConfigResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val (config, repoUrl) = transaction(state.database) {
                val cfg = ensureSyncConfig(project)
                cfg.toBareDto() to cfg.repoUrl
            }
            call.respond(config.copy(tokenPresent = resolveTokenPresent(state, repoUrl)))
        }
    }

    put<ProjectSyncConfigResource> { resource ->
        val request = call.receive<UpdateSyncConfigRequest>()
        if (request.branch != null && request.branch.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("branch must not be blank"))
            return@put
        }
        withProject(state, resource.parent.projectId) { project ->
            val (config, repoUrl) = transaction(state.database) {
                val cfg = ensureSyncConfig(project)
                request.repoUrl?.let { cfg.repoUrl = it.ifBlank { null } }
                request.branch?.let { cfg.branch = it }
                request.enabled?.let { cfg.enabled = it }
                cfg.toBareDto() to cfg.repoUrl
            }
            call.respond(config.copy(tokenPresent = resolveTokenPresent(state, repoUrl)))
        }
    }

    get<ProjectSyncStatusResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val path = workingTree.pathFor(project.uuid)
            val response = withContext(Dispatchers.IO) {
                JGitClient.open(path)?.use { repo ->
                    SyncStatusResponse(
                        workingTreePath = path.toString(),
                        hasRepo = true,
                        head = JGitClient.head(repo),
                        dirty = JGitClient.isWorkingTreeDirty(repo),
                    )
                } ?: SyncStatusResponse(
                    workingTreePath = path.toString(),
                    hasRepo = false,
                    head = null,
                    dirty = false,
                )
            }
            call.respond(response)
        }
    }

    post<ProjectSyncSnapshotResource> { resource ->
        val request = call.receiveNullable<TakeSnapshotRequest>() ?: TakeSnapshotRequest()
        withProject(state, resource.parent.projectId) { project ->
            // Refuse to take a snapshot while a conflict session is open — the snapshot
            // would race the canonical-JSON the resolution UI is showing.
            val activeSessionId = transaction(state.database) {
                ConflictSession.findActive(project.id.value)?.id?.value
            }
            if (activeSessionId != null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    SyncErrorResponse(
                        "Cannot take a snapshot while conflict session #$activeSessionId is open. " +
                            "Apply or abort it first.",
                        SyncErrorCode.SESSION_PENDING.name,
                    ),
                )
                return@withProject
            }
            val install = transaction(state.database) {
                DaoInstall.all().firstOrNull()?.let { it.uuid to it.friendlyName }
                    ?: error("Install row missing — `ensureInstallRow` should have created it on startup.")
            }
            val response = snapshotEngine.snapshot(
                projectId = project.id.value,
                projectUuid = project.uuid,
                installUuid = install.first,
                installFriendlyName = install.second,
                message = request.message,
            )
            call.respond(response)
        }
    }

    get<ProjectSyncLogResource> { resource ->
        val limit = (resource.limit ?: DEFAULT_LOG_LIMIT).coerceIn(1, MAX_LOG_LIMIT)
        withProject(state, resource.parent.projectId) { project ->
            val path = workingTree.pathFor(project.uuid)
            val commits = withContext(Dispatchers.IO) {
                JGitClient.open(path)?.use { repo -> JGitClient.log(repo, limit) }
                    ?: emptyList()
            }
            call.respond(commits)
        }
    }

    put<ProjectSyncCredentialsResource> { resource ->
        val request = call.receive<SetCredentialsRequest>()
        if (request.pat.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("PAT must not be blank"))
            return@put
        }
        withProject(state, resource.parent.projectId) { project ->
            val repoUrl = transaction(state.database) { ensureSyncConfig(project).repoUrl }
            if (repoUrl.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Repository URL must be set before storing a PAT"))
                return@withProject
            }
            withContext(Dispatchers.IO) { state.credentialStore.set(repoUrl, request.pat) }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    delete<ProjectSyncCredentialsResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val repoUrl = transaction(state.database) { ensureSyncConfig(project).repoUrl }
            if (!repoUrl.isNullOrBlank()) {
                withContext(Dispatchers.IO) { state.credentialStore.delete(repoUrl) }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    post<ProjectSyncRunResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val install = transaction(state.database) {
                DaoInstall.all().firstOrNull()?.let { it.uuid to it.friendlyName }
                    ?: error("Install row missing — `ensureInstallRow` should have created it on startup.")
            }
            state.emitCloudSyncEvent(CloudSyncStartedOutMessage(project.id.value))
            val result = try {
                remoteSyncEngine.runSync(
                    projectId = project.id.value,
                    projectUuid = project.uuid,
                    installUuid = install.first,
                    installFriendlyName = install.second,
                )
            } catch (e: SyncException) {
                state.emitCloudSyncEvent(
                    CloudSyncFailedOutMessage(
                        projectId = project.id.value,
                        errorCode = e.code.name,
                        message = e.message ?: e.code.name,
                    ),
                )
                call.respond(e.code.toHttpStatus(), SyncErrorResponse(e.message ?: e.code.name, e.code.name))
                return@withProject
            }
            // A CONFLICTS_PENDING run emits its own bespoke message from the engine;
            // don't double-fire `cloudSyncDone` for that case.
            if (result.outcome != SyncOutcome.CONFLICTS_PENDING) {
                state.emitCloudSyncEvent(
                    CloudSyncDoneOutMessage(
                        projectId = project.id.value,
                        outcome = result.outcome.name,
                        headSha = result.headSha,
                        pushed = result.pushed,
                        pulled = result.pulled,
                        replaced = result.replaced,
                        message = result.message,
                    ),
                )
            }
            call.respond(result)
        }
    }

    // ─── Phase 5 conflict-session endpoints ────────────────────────────

    get<ProjectSyncConflictsResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val response = transaction(state.database) {
                val session = ConflictSession.findActive(project.id.value)
                    ?: return@transaction ConflictsResponse(activeSession = false)
                val conflicts = ConflictSession.listConflicts(session).map { c ->
                    ConflictDto(
                        tableName = c.tableName,
                        recordUuid = c.recordUuid.toString(),
                        conflictKind = c.conflictKind,
                        resolution = c.resolution,
                        localJson = c.localJson,
                        remoteJson = c.remoteJson,
                        baseJson = c.baseJson,
                    )
                }
                ConflictsResponse(
                    activeSession = true,
                    sessionId = session.id.value,
                    state = session.state,
                    localSha = session.localSha,
                    remoteSha = session.remoteSha,
                    baseSha = session.baseSha,
                    errorMessage = session.errorMessage,
                    conflicts = conflicts,
                )
            }
            call.respond(response)
        }
    }

    post<ProjectSyncResolveResource> { resource ->
        val request = call.receive<ResolveRequest>()
        // Validate body before opening a transaction. Bad-choice and bad-UUID are
        // body-shape errors, not session-state errors, so they don't go through
        // SyncException's status mapping.
        val parsed = mutableListOf<ResolutionEntry>()
        for (entry in request.resolutions) {
            val choice = entry.resolution
            if (choice != null && choice != ConflictResolution.LOCAL.name && choice != ConflictResolution.REMOTE.name) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Resolution must be 'LOCAL' or 'REMOTE' (or null to clear)."))
                return@post
            }
            val uuid = runCatching { UUID.fromString(entry.recordUuid) }.getOrNull() ?: continue
            parsed.add(ResolutionEntry(entry.tableName, uuid, choice))
        }
        withProject(state, resource.parent.projectId) { project ->
            try {
                transaction(state.database) {
                    val session = ConflictSession.findActive(project.id.value)
                        ?: throw SyncException(SyncErrorCode.SESSION_NOT_FOUND, "No active conflict session.")
                    if (session.state != SessionState.CONFLICTS_PENDING.name) {
                        throw SyncException(SyncErrorCode.SESSION_NOT_FOUND, "Session is not in CONFLICTS_PENDING state.")
                    }
                    ConflictSession.resolve(session, parsed)
                }
                call.respond(HttpStatusCode.NoContent)
            } catch (e: SyncException) {
                call.respond(e.code.toHttpStatus(), SyncErrorResponse(e.message ?: e.code.name, e.code.name))
            }
        }
    }

    post<ProjectSyncApplyResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val install = transaction(state.database) {
                DaoInstall.all().firstOrNull()?.let { it.uuid to it.friendlyName }
                    ?: error("Install row missing — `ensureInstallRow` should have created it on startup.")
            }
            state.emitCloudSyncEvent(CloudSyncStartedOutMessage(project.id.value))
            val result = try {
                remoteSyncEngine.applySession(
                    projectId = project.id.value,
                    projectUuid = project.uuid,
                    installUuid = install.first,
                    installFriendlyName = install.second,
                )
            } catch (e: SyncException) {
                state.emitCloudSyncEvent(
                    CloudSyncFailedOutMessage(
                        projectId = project.id.value,
                        errorCode = e.code.name,
                        message = e.message ?: e.code.name,
                    ),
                )
                call.respond(e.code.toHttpStatus(), SyncErrorResponse(e.message ?: e.code.name, e.code.name))
                return@withProject
            }
            state.emitCloudSyncEvent(
                CloudSyncDoneOutMessage(
                    projectId = project.id.value,
                    outcome = result.outcome.name,
                    headSha = result.headSha,
                    pushed = result.pushed,
                    pulled = result.pulled,
                    replaced = result.replaced,
                    message = result.message,
                ),
            )
            call.respond(result)
        }
    }

    post<ProjectSyncAbortResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val result = try {
                remoteSyncEngine.abortSession(project.id.value, project.uuid)
            } catch (e: SyncException) {
                call.respond(e.code.toHttpStatus(), SyncErrorResponse(e.message ?: e.code.name, e.code.name))
                return@withProject
            }
            call.respond(result)
        }
    }
}

private const val DEFAULT_LOG_LIMIT = 50
private const val MAX_LOG_LIMIT = 500

@Resource("/{projectId}/sync")
data class ProjectSyncResource(val projectId: String)

@Resource("/config")
data class ProjectSyncConfigResource(val parent: ProjectSyncResource)

@Resource("/status")
data class ProjectSyncStatusResource(val parent: ProjectSyncResource)

@Resource("/snapshot")
data class ProjectSyncSnapshotResource(val parent: ProjectSyncResource)

@Resource("/log")
data class ProjectSyncLogResource(val parent: ProjectSyncResource, val limit: Int? = null)

@Resource("/credentials")
data class ProjectSyncCredentialsResource(val parent: ProjectSyncResource)

@Resource("/run")
data class ProjectSyncRunResource(val parent: ProjectSyncResource)

@Resource("/conflicts")
data class ProjectSyncConflictsResource(val parent: ProjectSyncResource)

@Resource("/resolve")
data class ProjectSyncResolveResource(val parent: ProjectSyncResource)

@Resource("/apply")
data class ProjectSyncApplyResource(val parent: ProjectSyncResource)

@Resource("/abort")
data class ProjectSyncAbortResource(val parent: ProjectSyncResource)

@Serializable
data class SyncConfigDto(
    val branch: String,
    val repoUrl: String?,
    val enabled: Boolean,
    val autoSyncEnabled: Boolean,
    val autoSyncIntervalMs: Long?,
    val lastSyncedSha: String?,
    val lastSyncedAtMs: Long?,
    /**
     * True if a PAT for the configured `repoUrl` is stored in the credential store. The
     * actual token is never returned to the client; this flag is just so the UI can
     * show "✓ token stored" without round-tripping the secret.
     */
    val tokenPresent: Boolean,
)

@Serializable
data class SetCredentialsRequest(val pat: String)

/** Error response carrying a stable [code] so the frontend can branch on cause. */
@Serializable
data class SyncErrorResponse(val error: String, val code: String)

@Serializable
data class UpdateSyncConfigRequest(
    val repoUrl: String? = null,
    val branch: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class SyncStatusResponse(
    val workingTreePath: String,
    val hasRepo: Boolean,
    val head: CommitInfo?,
    val dirty: Boolean,
)

@Serializable
data class TakeSnapshotRequest(
    val message: String? = null,
)

/**
 * Response shape for `GET /sync/conflicts`. When `activeSession=false`, the other fields
 * are absent — the UI uses this to render an empty conflict panel (or hide it entirely).
 */
@Serializable
data class ConflictsResponse(
    val activeSession: Boolean,
    val sessionId: Int? = null,
    val state: String? = null,
    val localSha: String? = null,
    val remoteSha: String? = null,
    val baseSha: String? = null,
    val errorMessage: String? = null,
    val conflicts: List<ConflictDto> = emptyList(),
)

@Serializable
data class ConflictDto(
    val tableName: String,
    val recordUuid: String,
    val conflictKind: String,
    /** `LOCAL` / `REMOTE` once the user has chosen, otherwise null. */
    val resolution: String?,
    /** Phase 6 will use these for the three-pane diff; Phase 5 leaves them unrendered. */
    val localJson: String?,
    val remoteJson: String?,
    val baseJson: String?,
)

@Serializable
data class ResolveRequest(val resolutions: List<ResolveEntry>)

@Serializable
data class ResolveEntry(
    val tableName: String,
    val recordUuid: String,
    /** `LOCAL` / `REMOTE` / null (clear). */
    val resolution: String?,
)

/**
 * Lazily create the per-project `sync_configs` row on first read. Mirrors the
 * machine-overrides pattern — callers always get a row back, so the UI can
 * render the form without a 404 song-and-dance on the first visit.
 */
private fun ensureSyncConfig(project: DaoProject): DaoSyncConfig {
    return DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }
        .firstOrNull()
        ?: DaoSyncConfig.new {
            this.project = project
        }
}

/**
 * Look up [repoUrl] in the credential store on the IO dispatcher. The keychain backend
 * is a JNA round-trip; we use [CredentialStore.contains] rather than `get` so the PAT
 * itself never leaves the store just to compute a UI flag.
 */
private suspend fun resolveTokenPresent(state: State, repoUrl: String?): Boolean {
    val url = repoUrl?.takeIf { it.isNotBlank() } ?: return false
    return withContext(Dispatchers.IO) { state.credentialStore.contains(url) }
}

/**
 * Build a [SyncConfigDto] from the DAO without consulting the credential store. Callers
 * post-process the DTO with the real `tokenPresent` value because the credential lookup
 * is blocking I/O that we don't want to do inside a DB transaction.
 */
private fun DaoSyncConfig.toBareDto() = SyncConfigDto(
    branch = branch,
    repoUrl = repoUrl,
    enabled = enabled,
    autoSyncEnabled = autoSyncEnabled,
    autoSyncIntervalMs = autoSyncIntervalMs,
    lastSyncedSha = lastSyncedSha,
    lastSyncedAtMs = lastSyncedAtMs,
    tokenPresent = false,
)
