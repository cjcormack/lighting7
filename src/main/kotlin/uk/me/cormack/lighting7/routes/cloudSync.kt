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
import uk.me.cormack.lighting7.sync.ConflictKind
import uk.me.cormack.lighting7.sync.ConflictResolution
import uk.me.cormack.lighting7.sync.ConflictSession
import uk.me.cormack.lighting7.sync.JGitClient
import uk.me.cormack.lighting7.sync.RecordHasher
import uk.me.cormack.lighting7.sync.RecordKey
import uk.me.cormack.lighting7.sync.ResolutionEntry
import uk.me.cormack.lighting7.sync.SessionState
import uk.me.cormack.lighting7.sync.SnapshotEngine
import uk.me.cormack.lighting7.sync.SnapshotResponse
import uk.me.cormack.lighting7.sync.SyncErrorCode
import uk.me.cormack.lighting7.sync.SyncException
import uk.me.cormack.lighting7.sync.SyncOutcome
import uk.me.cormack.lighting7.sync.SyncRunResult
import uk.me.cormack.lighting7.sync.AutoSyncScheduler
import uk.me.cormack.lighting7.sync.SyncLogger
import uk.me.cormack.lighting7.sync.SyncWorkingTree
import uk.me.cormack.lighting7.sync.canonicalDecode
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.toHttpStatus
import uk.me.cormack.lighting7.sync.withAttribution
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
    val remoteSyncEngine = state.remoteSyncEngine
    val syncLogger = state.syncLogger

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
        if (request.autoSyncIntervalMs != null &&
            request.autoSyncIntervalMs < AutoSyncScheduler.MIN_INTERVAL_MS
        ) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    "autoSyncIntervalMs must be at least " +
                        "${AutoSyncScheduler.MIN_INTERVAL_MS}ms.",
                ),
            )
            return@put
        }
        withProject(state, resource.parent.projectId) { project ->
            val (config, repoUrl) = transaction(state.database) {
                val cfg = ensureSyncConfig(project)
                request.repoUrl?.let { cfg.repoUrl = it.ifBlank { null } }
                request.branch?.let { cfg.branch = it }
                request.enabled?.let { cfg.enabled = it }
                request.autoSyncEnabled?.let { cfg.autoSyncEnabled = it }
                if (request.autoSyncIntervalMs != null) {
                    cfg.autoSyncIntervalMs = request.autoSyncIntervalMs
                }
                cfg.toBareDto() to cfg.repoUrl
            }
            state.autoSyncScheduler.reschedule(project.id.value)
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
            val commits: List<CommitInfo> = withContext(Dispatchers.IO) {
                JGitClient.open(path)?.use { repo ->
                    val installs = JGitClient.readBlob(repo, "HEAD", "installs.json")
                        ?.let {
                            runCatching {
                                canonicalDecode(InstallsJson.serializer(), it)
                            }.getOrNull()
                        }
                    JGitClient.log(repo, limit, before = resource.before)
                        .map { it.withAttribution(installs?.installs.orEmpty()) }
                } ?: emptyList()
            }
            call.respond(commits)
        }
    }

    get<ProjectSyncActivityResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val limit = (resource.limit ?: SyncLogger.DEFAULT_LIST_LIMIT)
                .coerceIn(1, SyncLogger.MAX_LIST_LIMIT)
            val entries = withContext(Dispatchers.IO) {
                syncLogger.list(project.id.value, limit, resource.beforeId)
            }
            call.respond(entries)
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
                respondSyncError(e)
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
                        manualValueJson = c.manualValueJson,
                        manualEditAllowed = isManualEditAllowed(c.tableName, c.conflictKind),
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
        // Body-shape pass: bad-choice and bad-UUID are body errors and skip SyncException's
        // status mapping. The conflict-kind gate for MANUAL needs the DB and runs below.
        val parsed = mutableListOf<ResolutionEntry>()
        for (entry in request.resolutions) {
            val choice = entry.resolution
            if (choice != null &&
                choice != ConflictResolution.LOCAL.name &&
                choice != ConflictResolution.REMOTE.name &&
                choice != ConflictResolution.MANUAL.name
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Resolution must be 'LOCAL', 'REMOTE', or 'MANUAL' (or null to clear)."),
                )
                return@post
            }
            if (choice == ConflictResolution.MANUAL.name && entry.manualValueJson == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MANUAL resolution requires manualValueJson."),
                )
                return@post
            }
            val uuid = runCatching { UUID.fromString(entry.recordUuid) }.getOrNull() ?: continue
            parsed.add(ResolutionEntry(entry.tableName, uuid, choice, entry.manualValueJson))
        }
        withProject(state, resource.parent.projectId) { project ->
            // Track the first MANUAL gate violation so we can respond with a precise body
            // error after the transaction unwinds.
            var manualGateError: String? = null
            try {
                transaction(state.database) {
                    val session = ConflictSession.findActive(project.id.value)
                        ?: throw SyncException(SyncErrorCode.SESSION_NOT_FOUND, "No active conflict session.")
                    if (session.state != SessionState.CONFLICTS_PENDING.name) {
                        throw SyncException(SyncErrorCode.SESSION_NOT_FOUND, "Session is not in CONFLICTS_PENDING state.")
                    }

                    // Look up each MANUAL entry's conflict row to verify the kind allows
                    // MANUAL (multi-file gate + DELETE_EDIT gate). DELETE_EDIT means the
                    // local side is a tombstone — there's no record to "edit."
                    val sessionConflicts = ConflictSession.listConflicts(session)
                        .associateBy { RecordKey(it.tableName, it.recordUuid) }
                    for (entry in parsed) {
                        if (entry.resolution != ConflictResolution.MANUAL.name) continue
                        val key = RecordKey(entry.tableName, entry.recordUuid)
                        val conflict = sessionConflicts[key] ?: continue
                        if (!isManualEditAllowed(entry.tableName, conflict.conflictKind)) {
                            manualGateError = manualGateMessage(entry.tableName, conflict.conflictKind)
                            return@transaction
                        }
                    }

                    ConflictSession.resolve(session, parsed)
                }
                if (manualGateError != null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(manualGateError!!))
                    return@withProject
                }
                call.respond(HttpStatusCode.NoContent)
            } catch (e: SyncException) {
                respondSyncError(e)
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
                respondSyncError(e)
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
                respondSyncError(e)
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
data class ProjectSyncLogResource(
    val parent: ProjectSyncResource,
    val limit: Int? = null,
    /** Pagination cursor: full SHA of the commit to walk backwards from (exclusive). */
    val before: String? = null,
)

@Resource("/activity")
data class ProjectSyncActivityResource(
    val parent: ProjectSyncResource,
    val limit: Int? = null,
    /** Pagination cursor: id of the oldest entry from the previous page. */
    val beforeId: Int? = null,
)

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

/**
 * Send a [SyncException] as a stable-coded JSON error response. Centralises the four
 * call sites in this file plus the import handler in `cloudSyncTopLevel.kt`.
 */
internal suspend fun io.ktor.server.routing.RoutingContext.respondSyncError(e: SyncException) {
    call.respond(e.code.toHttpStatus(), SyncErrorResponse(e.message ?: e.code.name, e.code.name))
}

@Serializable
data class UpdateSyncConfigRequest(
    val repoUrl: String? = null,
    val branch: String? = null,
    val enabled: Boolean? = null,
    val autoSyncEnabled: Boolean? = null,
    /** Minimum [AutoSyncScheduler.MIN_INTERVAL_MS]. */
    val autoSyncIntervalMs: Long? = null,
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
    /** `LOCAL` / `REMOTE` / `MANUAL` once the user has chosen, otherwise null. */
    val resolution: String?,
    /** Phase 6 three-pane diff source: side-by-side renderings of mine / theirs / common ancestor. */
    val localJson: String?,
    val remoteJson: String?,
    val baseJson: String?,
    /** Saved MANUAL replacement payload, if any — null when the user hasn't chosen MANUAL. */
    val manualValueJson: String? = null,
    /** False for multi-file records (e.g. scripts) — Phase 6 only allows MANUAL on single-file records. */
    val manualEditAllowed: Boolean = true,
)

@Serializable
data class ResolveRequest(val resolutions: List<ResolveEntry>)

@Serializable
data class ResolveEntry(
    val tableName: String,
    val recordUuid: String,
    /** `LOCAL` / `REMOTE` / `MANUAL` / null (clear). */
    val resolution: String?,
    /** Required when `resolution = MANUAL`; ignored otherwise. */
    val manualValueJson: String? = null,
)

/**
 * Whether MANUAL editing is meaningful for this `(tableName, conflictKind)` pair.
 *
 *  * Multi-file records (currently scripts) can't round-trip through the single-textarea
 *    MANUAL editor — defers to [RecordHasher.isMultiFileTable].
 *  * `DELETE_EDIT` means the local side is a tombstone, so there's no record to
 *    hand-edit. Pick LOCAL (keep deleted) or REMOTE (accept the remote edit) instead.
 *    `EDIT_DELETE` (local edited, remote deleted) is fine — local-side is a live record.
 */
private fun isManualEditAllowed(tableName: String, conflictKind: String): Boolean {
    if (RecordHasher.isMultiFileTable(tableName)) return false
    if (conflictKind == ConflictKind.DELETE_EDIT.name) return false
    return true
}

private fun manualGateMessage(tableName: String, conflictKind: String): String = when {
    RecordHasher.isMultiFileTable(tableName) ->
        "MANUAL editing is not yet supported for $tableName records (multi-file layout). " +
            "Choose LOCAL or REMOTE instead."
    conflictKind == ConflictKind.DELETE_EDIT.name ->
        "MANUAL editing isn't available on a DELETE_EDIT conflict — the local side is a " +
            "deletion, so there's nothing to hand-edit. Choose LOCAL (keep deleted) or " +
            "REMOTE (accept the remote edit)."
    else -> "MANUAL is not allowed for this conflict."
}

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
internal fun DaoSyncConfig.toBareDto() = SyncConfigDto(
    branch = branch,
    repoUrl = repoUrl,
    enabled = enabled,
    autoSyncEnabled = autoSyncEnabled,
    autoSyncIntervalMs = autoSyncIntervalMs,
    lastSyncedSha = lastSyncedSha,
    lastSyncedAtMs = lastSyncedAtMs,
    tokenPresent = false,
)
