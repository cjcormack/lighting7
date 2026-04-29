package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
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
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.CommitInfo
import uk.me.cormack.lighting7.sync.JGitClient
import uk.me.cormack.lighting7.sync.SnapshotEngine
import uk.me.cormack.lighting7.sync.SnapshotResponse
import uk.me.cormack.lighting7.sync.SyncWorkingTree

/**
 * Cloud-sync REST endpoints. All endpoints are local-only — no remote, no PAT, no
 * conflict resolution yet (see `docs/sync-engineering.md`). JGit calls block, so
 * route handlers wrap them in `withContext(Dispatchers.IO)`.
 */
internal fun Route.routeApiRestProjectCloudSync(state: State) {
    val workingTree = SyncWorkingTree(state)
    val snapshotEngine = SnapshotEngine(state)

    get<ProjectSyncConfigResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                ensureSyncConfig(project).toDto()
            }
            call.respond(dto)
        }
    }

    put<ProjectSyncConfigResource> { resource ->
        val request = call.receive<UpdateSyncConfigRequest>()
        if (request.branch != null && request.branch.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("branch must not be blank"))
            return@put
        }
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val cfg = ensureSyncConfig(project)
                request.repoUrl?.let { cfg.repoUrl = it.ifBlank { null } }
                request.branch?.let { cfg.branch = it }
                request.enabled?.let { cfg.enabled = it }
                cfg.toDto()
            }
            call.respond(dto)
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

@Serializable
data class SyncConfigDto(
    val branch: String,
    val repoUrl: String?,
    val enabled: Boolean,
    val autoSyncEnabled: Boolean,
    val autoSyncIntervalMs: Long?,
    val lastSyncedSha: String?,
    val lastSyncedAtMs: Long?,
)

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

private fun DaoSyncConfig.toDto() = SyncConfigDto(
    branch = branch,
    repoUrl = repoUrl,
    enabled = enabled,
    autoSyncEnabled = autoSyncEnabled,
    autoSyncIntervalMs = autoSyncIntervalMs,
    lastSyncedSha = lastSyncedSha,
    lastSyncedAtMs = lastSyncedAtMs,
)
