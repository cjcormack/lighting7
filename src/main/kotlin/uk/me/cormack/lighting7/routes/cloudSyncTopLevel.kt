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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncLinkedRepos
import uk.me.cormack.lighting7.plugins.CloudSyncProjectImportedOutMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.CloudSyncImporter
import uk.me.cormack.lighting7.sync.GitAuthException
import uk.me.cormack.lighting7.sync.ImportError
import uk.me.cormack.lighting7.sync.SyncErrorCode
import uk.me.cormack.lighting7.sync.SyncException

/**
 * Install-scoped cloud-sync REST endpoints — the per-project routes live in `cloudSync.kt`
 * under `/project/{projectId}/sync/`. These two are siblings of `/project` because they
 * either span all projects (`/cloud-sync/configs`) or pre-date a project's existence
 * (`/cloud-sync/import`).
 */
internal fun Route.routeApiRestCloudSync(state: State) {

    /**
     * Batch fetch of every project's sync configuration. The hub used to fire one
     * `GET /project/{id}/sync/config` per row; this collapses the whole hub render into
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
                .orderBy(DaoSyncLinkedRepos.lastLinkedAtMs, SortOrder.DESC)
                .groupBy({ it[DaoSyncLinkedRepos.project].value }) {
                    LinkedRepoDto(it[DaoSyncLinkedRepos.repoUrl], it[DaoSyncLinkedRepos.lastLinkedAtMs])
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
        // The file-backed credential store would re-read + decode its JSON file once per
        // key under the naive `rows.map { contains(it) }` shape; `containsAll` collapses
        // that to a single read. The keychain backend's default override falls back to
        // sequential `contains` calls — fine, the JNA path is single-flight anyway.
        val resolved = withContext(Dispatchers.IO) {
            val urls = rows.mapNotNull { it.repoUrl?.takeIf(String::isNotBlank) }.toSet()
            val present = if (urls.isNotEmpty()) state.credentialStore.containsAll(urls) else emptySet()
            rows.associate { entry ->
                val tokenPresent = entry.repoUrl?.let { it in present } ?: false
                entry.projectId.toString() to entry.dto.copy(tokenPresent = tokenPresent)
            }
        }
        call.respond(resolved)
    }

    /**
     * Import a remote repository as a brand-new local project. The flow:
     *
     *  1. Resolve auth via [State.authResolver] — same install-wide OAuth-or-PAT path as
     *     `/sync/run`, so once the user has connected GitHub once (visible from the hub)
     *     this works without per-import setup.
     *  2. JGit-clone into a pending sibling directory under `syncWorkingTreeRoot`.
     *  3. Hand the clone to [ProjectImporter.import] which validates `formatVersion.json`
     *     + `project.json` and creates DB rows — refusing with 409 on UUID or name
     *     collision, 422 on unsupported format, 400 on a missing/malformed archive.
     *  4. Move the pending tree to the canonical `<root>/{projectUuid}/repo/` location.
     *  5. Persist `sync_configs` so the next "Sync now" works without further setup.
     *
     * Sync-state bootstrap is left to the next sync run — its `Equal` history branch
     * already calls [bootstrapSyncStateAtHead], which keeps that helper private.
     */
    post<CloudSyncImportResource> {
        val request = call.receive<ImportFromRemoteRequest>()
        if (request.repoUrl.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("repoUrl must not be blank"))
            return@post
        }
        try {
            val result = CloudSyncImporter(state, state.authResolver).import(
                repoUrl = request.repoUrl.trim(),
                branch = request.branch,
                projectName = request.projectName,
            )
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
                ),
            )
        } catch (e: SyncException) {
            respondSyncError(e)
        } catch (e: GitAuthException) {
            respondSyncError(SyncException(
                SyncErrorCode.AUTH_FAILED,
                "GitHub rejected the credentials during clone: ${e.message}",
                e,
            ))
        } catch (e: ImportError) {
            call.respond(e.status, ErrorResponse(e.message ?: "Import failed"))
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
)
