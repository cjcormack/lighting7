package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post as routingPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.appDataDir
import uk.me.cormack.lighting7.sync.ImportError
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class ProjectExportRequest(
    val path: String? = null,
)

@Serializable
data class ProjectExportResponse(
    val path: String,
    val fileCount: Int,
)

@Serializable
data class ProjectImportRequest(
    val path: String,
    val nameOverride: String? = null,
)

@Serializable
data class ProjectImportResponse(
    val projectId: Int,
    val projectUuid: String,
    val name: String,
)

@Resource("/{id}/export")
data class ProjectExportResource(val id: Int)

internal fun Route.routeApiRestProjectExport(state: State) {
    val exporter = ProjectExporter(state)
    val importer = ProjectImporter(state)

    post<ProjectExportResource> { resource ->
        val request = call.receive<ProjectExportRequest>()
        val project = transaction(state.database) {
            DaoProject.findById(resource.id)
        }
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        val target = resolveExportPath(request.path, project)
        val result = try {
            // Export does file IO inside an Exposed transaction; off-loading to Dispatchers.IO
            // keeps the Ktor worker free for other requests during a large project export.
            withContext(Dispatchers.IO) { exporter.export(resource.id, target) }
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Project not found"))
            return@post
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Export failed: ${e.message}"))
            return@post
        }

        call.respond(
            ProjectExportResponse(
                path = result.path.toAbsolutePath().toString(),
                fileCount = result.fileCount,
            )
        )
    }

    routingPost("/import") {
        val request = call.receive<ProjectImportRequest>()
        val source = Paths.get(request.path)

        try {
            val result = withContext(Dispatchers.IO) {
                importer.import(source, request.nameOverride)
            }
            call.respond(
                HttpStatusCode.Created,
                ProjectImportResponse(
                    projectId = result.projectId,
                    projectUuid = result.projectUuid,
                    name = result.name,
                )
            )
        } catch (e: ImportError) {
            call.respond(e.status, ErrorResponse(e.message ?: "Import failed"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Import failed: ${e.message}"))
        }
    }
}

/**
 * Resolves the export target. A null/blank request path falls back to a per-project folder
 * under `appDataDir()/exports/{projectUuid}`. Caller-supplied paths are honoured verbatim.
 */
private fun resolveExportPath(requestPath: String?, project: DaoProject): Path {
    val explicit = requestPath?.takeIf { it.isNotBlank() }
    if (explicit != null) return Paths.get(explicit)
    return appDataDir().resolve("exports").resolve(project.uuid.toString())
}
