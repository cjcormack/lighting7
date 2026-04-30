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
import uk.me.cormack.lighting7.sync.JGitClient
import uk.me.cormack.lighting7.sync.RemoteSyncEngine
import uk.me.cormack.lighting7.sync.SnapshotEngine
import uk.me.cormack.lighting7.sync.SnapshotResponse
import uk.me.cormack.lighting7.sync.SyncException
import uk.me.cormack.lighting7.sync.SyncRunResult
import uk.me.cormack.lighting7.sync.SyncWorkingTree
import uk.me.cormack.lighting7.sync.toHttpStatus

/**
 * Cloud-sync REST endpoints. All endpoints are local-only — no remote, no PAT, no
 * conflict resolution yet (see `docs/sync-engineering.md`). JGit calls block, so
 * route handlers wrap them in `withContext(Dispatchers.IO)`.
 */
internal fun Route.routeApiRestProjectCloudSync(state: State) {
    val workingTree = SyncWorkingTree(state)
    val snapshotEngine = SnapshotEngine(state)
    val remoteSyncEngine = RemoteSyncEngine(state, state.credentialStore)

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
        // receiveNullable returns null on an empty body without swallowing real
        // deserialisation errors the way `runCatching { receive() }` would.
        val request = call.receiveNullable<TakeSnapshotRequest>() ?: TakeSnapshotRequest()
        withProject(state, resource.parent.projectId) { project ->
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
