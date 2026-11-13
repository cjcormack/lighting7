package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncLinkedRepos
import uk.me.cormack.lighting7.plugins.CloudSyncProjectImportedOutMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.CloudMove
import uk.me.cormack.lighting7.sync.CloudRepoRow
import uk.me.cormack.lighting7.sync.CloudSyncImporter
import uk.me.cormack.lighting7.sync.CueNumberClash
import uk.me.cormack.lighting7.sync.ImportError
import uk.me.cormack.lighting7.sync.SyncException
import uk.me.cormack.lighting7.models.toIsoUtc

/**
 * Install-scoped cloud-sync REST endpoints — the per-project routes live in `cloudSync.kt`
 * under `/projects/{projectId}/sync/`. These are siblings of `/projects` because they
 * either span all projects (`/cloud-sync/configs`, `/cloud-sync/repos`) or pre-date a
 * project's existence (`/cloud-sync/import`).
 */
internal fun Route.routeApiRestCloudSync(state: State) {

    /**
     * Batch fetch of every project's sync configuration. The hub used to fire one
     * `GET /projects/{id}/sync/config` per row; this collapses the whole hub render into
     * a single round-trip. The result is sparse — projects without a `sync_configs` row
     * are absent from the map (rather than auto-created) so simply visiting the hub
     * doesn't materialise empty config rows.
     */
    get<CloudSyncConfigsResource> {
        val rows = transaction(state.database) {
            // Pre-load every project's remembered-repo set in a single query, grouped by
            // project (most-recently-linked first), so per-config `toBareDto` doesn't fire
            // an N+1 across the batch.
            val linkedByProject = DaoSyncLinkedRepos
                .selectAll()
                .orderBy(DaoSyncLinkedRepos.lastLinkedAt, SortOrder.DESC)
                .groupBy({ it[DaoSyncLinkedRepos.project].value }) {
                    LinkedRepoDto(it[DaoSyncLinkedRepos.repoUrl], it[DaoSyncLinkedRepos.lastLinkedAt].toIsoUtc())
                }
            DaoSyncConfig.all().map { cfg ->
                val projectId = cfg.project.id.value
                BatchEntry(
                    projectId = projectId,
                    dto = cfg.toBareDto(linkedByProject[projectId] ?: emptyList()),
                    repoUrl = cfg.repoUrl,
                )
            }
        }
        call.respond(rows.associate { entry -> entry.projectId.toString() to decorateSyncConfig(state, entry.dto, entry.repoUrl) })
    }

    /**
     * The org's cloud repos (cloud-p2 plan D6), each with whether a project here already syncs it and
     * its kind, which *Import from the cloud* and onboarding's venues-first order read.
     */
    get<CloudSyncReposResource> {
        try {
            call.respond(CloudReposResponse(state.cloudRepos.list()))
        } catch (e: SyncException) {
            respondSyncError(e)
        }
    }

    /**
     * *Move GitHub-synced projects to the cloud*: the plan, and the run, a row per project. Refused on
     * the public listener, as adoption is: a move hands a project's whole history to the cloud.
     */
    get<CloudSyncMoveResource> {
        if (call.isRemote) return@get refuseRemoteMove()
        try {
            call.respond(CloudMove(state).plan())
        } catch (e: SyncException) {
            respondSyncError(e)
        }
    }

    post<CloudSyncMoveResource> {
        if (call.isRemote) return@post refuseRemoteMove()
        val request = call.receive<CloudMoveRequest>()
        if (request.projectIds.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("projectIds must name at least one project"))
            return@post
        }
        try {
            call.respond(CloudMove(state).run(request.projectIds.distinct()))
        } catch (e: SyncException) {
            respondSyncError(e)
        }
    }

    /**
     * Import a remote repository as a brand-new local project. The flow:
     *
     *  1. Resolve auth via [State.authResolver], as `/sync/run` does: a cloud repo's token.
     *  2. JGit-clone into a pending sibling directory under `syncWorkingTreeRoot`.
     *  3. Hand the clone to [ProjectImporter.import] which validates `formatVersion.json`
     *     + `project.json` and creates DB rows — refusing with 409 on UUID or name
     *     collision, 422 on unsupported format, 400 on a missing/malformed archive.
     *  4. Move the pending tree to the canonical `<root>/{projectUuid}/repo/` location.
     *  5. Persist `sync_configs` so the next "Sync now" works without further setup, and
     *     seed `sync_state` at the clone's HEAD — the tree the DB now holds is the last one
     *     this install and the remote agree on, which the first diverged sync diffs against.
     */
    post<CloudSyncImportResource> {
        val request = call.receive<ImportFromRemoteRequest>()
        if (request.repoUrl.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("repoUrl must not be blank"))
            return@post
        }
        try {
            val repoUrl = request.repoUrl.trim()
            val result = CloudSyncImporter(state, state.authResolver).import(
                repoUrl = repoUrl,
                branch = request.branch,
                projectName = request.projectName,
            )
            state.cloudRepos.refreshName(repoUrl)
            state.emitCloudSyncEvent(
                CloudSyncProjectImportedOutMessage(
                    projectId = result.projectId,
                    projectUuid = result.projectUuid,
                    name = result.name,
                ),
            )
            call.respond(
                HttpStatusCode.Created,
                ImportFromRemoteResponse(
                    projectId = result.projectId,
                    projectUuid = result.projectUuid,
                    name = result.name,
                    numberClashes = result.numberClashes,
                ),
            )
        } catch (e: SyncException) {
            respondSyncError(e)
        } catch (e: ImportError) {
            call.respond(e.status, ErrorResponse(e.message ?: "Import failed", e.code))
        } catch (e: Exception) {
            logger.error("Cloud-sync import from ${request.repoUrl} failed", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Import failed: ${e.message}"),
            )
        }
    }
}

private val logger = LoggerFactory.getLogger("cloud-sync.toplevel")

/** Carries the per-row pieces from the DB transaction so credential lookups can run outside it. */
private data class BatchEntry(
    val projectId: Int,
    val dto: SyncConfigDto,
    val repoUrl: String?,
)

@Resource("/cloud-sync/configs")
class CloudSyncConfigsResource

@Resource("/cloud-sync/import")
class CloudSyncImportResource

@Resource("/cloud-sync/repos")
class CloudSyncReposResource

@Resource("/cloud-sync/move")
class CloudSyncMoveResource

@Serializable
data class CloudMoveRequest(val projectIds: List<Int>)

/** The REST code a move asked for over remote access answers. */
const val CLOUD_MOVE_LOCAL_ONLY = "CLOUD_MOVE_LOCAL_ONLY"

private suspend fun io.ktor.server.routing.RoutingContext.refuseRemoteMove() {
    call.respond(
        HttpStatusCode.Forbidden,
        ErrorResponse("Projects can only be moved to the cloud from the desk itself, never over remote access.", CLOUD_MOVE_LOCAL_ONLY),
    )
}

@Serializable
data class CloudReposResponse(val repos: List<CloudRepoRow>)

@Serializable
data class ImportFromRemoteRequest(
    val repoUrl: String,
    val branch: String? = null,
    /** Optional override; when null/blank the imported `project.json`'s name is used. */
    val projectName: String? = null,
)

@Serializable
data class ImportFromRemoteResponse(
    val projectId: Int,
    val projectUuid: String,
    val name: String,
    /** Explicit cue numbers the repo held twice in one stack, and how each was resolved (sync v32). */
    val numberClashes: List<CueNumberClash> = emptyList(),
)
