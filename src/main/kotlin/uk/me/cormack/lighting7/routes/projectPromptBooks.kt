package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.RecordHasher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

// ─── Script PDF storage ─────────────────────────────────────────────────
//
// PDFs are stored content-addressed on the backend's disk, outside the DB and
// outside git sync (which is JSON-only by design). The hash is the identity;
// re-importing identical bytes re-attaches cleanly, and the store is immutable
// so serve responses can be cached forever.

private val SCRIPT_HASH_REGEX = Regex("^[0-9a-f]{64}$")

/** Upload cap: far above any real script PDF, small enough that a mistake can't OOM the JVM. */
private const val MAX_SCRIPT_BYTES = 100 * 1024 * 1024

internal fun promptScriptPath(state: State, projectUuid: String, hash: String): Path =
    state.promptScriptStoreRoot.resolve(projectUuid).resolve("$hash.pdf")

/**
 * Outcome of a transaction block that can fail with a client error. Lets handlers
 * validate against DB state (page counts, uniqueness) inside the transaction and
 * respond with the right status outside it — one mechanism for every endpoint here.
 */
private sealed interface RouteResult<out T> {
    data class Ok<T>(val value: T) : RouteResult<T>
    data class Error(val status: HttpStatusCode, val message: String) : RouteResult<Nothing>
}

private suspend inline fun <reified T : Any> RoutingContext.respondRouteResult(result: RouteResult<T>) {
    when (result) {
        is RouteResult.Ok -> call.respond(result.value)
        is RouteResult.Error -> call.respond(result.status, ErrorResponse(result.message))
    }
}

internal fun Route.routeApiRestProjectPromptBooks(state: State) {
    // GET /{projectId}/cue-locations - Per-cue reading positions from the project's
    // prompt book, each reduced to {cueId, page, y}. Project-scoped and book-agnostic —
    // it resolves the project's single prompt book. Empty when the project has no book.
    // The human phrasing ("top of p. 9") is derived on the frontend from these coordinates.
    get<ProjectCueLocationsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val locations = transaction(state.database) {
                val book = project.promptBook
                    ?: return@transaction emptyList<CueLocationDto>()
                book.anchors.mapNotNull { anchor ->
                    val earliest = anchor.region.minWithOrNull(compareBy({ it.page }, { it.y }))
                        ?: return@mapNotNull null
                    CueLocationDto(cueId = anchor.cueId, page = earliest.page, y = earliest.y)
                }
            }
            call.respond(locations)
        }
    }

    // GET /{projectId}/prompt-book - The project's prompt book with anchors + annotations
    get<ProjectPromptBookResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val isCurrentProject = state.isCurrentProject(project)
            val details = transaction(state.database) {
                project.promptBook?.toDetails(isCurrentProject)
            }

            if (details != null) {
                call.respond(details)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Prompt book not found"))
            }
        }
    }

    // PUT /{projectId}/prompt-book - Create or replace the project's prompt book.
    // Idempotent: updates the existing book's script in place (anchors kept — the
    // re-anchor flow), or creates the one book if the show doesn't have one yet.
    put<ProjectPromptBookResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot modify prompt books in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val input = call.receive<NewPromptBook>()
            val error = validatePromptBookInput(input)
            if (error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@withCurrentProject
            }

            val (details, created) = transaction(state.database) {
                val existing = project.promptBook
                if (existing != null) {
                    existing.scriptHash = input.scriptHash
                    existing.scriptFileName = input.scriptFileName
                    existing.pageCount = input.pageCount
                    existing.toDetails(isCurrentProject = true) to false
                } else {
                    DaoPromptBook.new {
                        this.project = project
                        scriptHash = input.scriptHash
                        scriptFileName = input.scriptFileName
                        pageCount = input.pageCount
                    }.toDetails(isCurrentProject = true) to true
                }
            }

            state.show.fixtures.promptBookChanged()
            call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, details)
        }
    }

    // DELETE /{projectId}/prompt-book
    delete<ProjectPromptBookResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot delete prompt books in project '${p.name}' - only the current project can be modified" },
        ) { project ->
            val found = transaction(state.database) {
                val book = project.promptBook ?: return@transaction false
                DaoPromptBookAnchors.deleteWhere { with(SqlExpressionBuilder) { DaoPromptBookAnchors.promptBook eq book.id } }
                DaoPromptBookAnnotations.deleteWhere { with(SqlExpressionBuilder) { DaoPromptBookAnnotations.promptBook eq book.id } }
                book.delete()
                true
            }

            if (found) {
                state.show.fixtures.promptBookChanged()
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Prompt book not found"))
            }
        }
    }

    // PUT /{projectId}/prompt-book/anchors/{cueId} - Upsert the cue's anchor
    put<PromptBookAnchorResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            "Cannot modify prompt book anchors - not current project",
        ) { project ->
            val input = call.receive<UpsertAnchorRequest>()

            val result: RouteResult<PromptBookAnchorDto> = transaction(state.database) {
                val book = project.promptBook
                    ?: return@transaction RouteResult.Error(HttpStatusCode.NotFound, "Prompt book not found")
                checkPromptBookRegion(input.region, book.pageCount)?.let {
                    return@transaction RouteResult.Error(HttpStatusCode.BadRequest, it)
                }
                val cue = DaoCue.findById(resource.cueId)
                if (cue == null || cue.project.id != project.id) {
                    return@transaction RouteResult.Error(HttpStatusCode.NotFound, "Cue not found")
                }

                val existing = DaoPromptBookAnchor.find {
                    (DaoPromptBookAnchors.promptBook eq book.id) and (DaoPromptBookAnchors.cue eq cue.id)
                }.firstOrNull()

                val anchor = if (existing != null) {
                    existing.region = input.region
                    existing.label = input.label
                    existing
                } else {
                    DaoPromptBookAnchor.new {
                        promptBook = book
                        this.cue = cue
                        region = input.region
                        label = input.label
                    }
                }
                RouteResult.Ok(anchor.toDto())
            }

            if (result is RouteResult.Ok) state.show.fixtures.promptBookChanged()
            respondRouteResult(result)
        }
    }

    // DELETE /{projectId}/prompt-book/anchors/{cueId}
    delete<PromptBookAnchorResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            "Cannot modify prompt book anchors - not current project",
        ) { project ->
            val found = transaction(state.database) {
                val book = project.promptBook ?: return@transaction false
                val anchor = DaoPromptBookAnchor.find {
                    (DaoPromptBookAnchors.promptBook eq book.id) and
                        (DaoPromptBookAnchors.cue eq resource.cueId)
                }.firstOrNull() ?: return@transaction false
                anchor.delete()
                true
            }

            if (found) {
                state.show.fixtures.promptBookChanged()
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Anchor not found"))
            }
        }
    }

    // POST /{projectId}/prompt-book/annotations - Create annotation
    post<PromptBookAnnotationsResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            "Cannot modify prompt book annotations - not current project",
        ) { project ->
            val input = call.receive<AnnotationRequest>()

            val result: RouteResult<PromptBookAnnotationDto> = transaction(state.database) {
                val book = project.promptBook
                    ?: return@transaction RouteResult.Error(HttpStatusCode.NotFound, "Prompt book not found")
                validateAnnotationInput(input, book.pageCount)?.let {
                    return@transaction RouteResult.Error(HttpStatusCode.BadRequest, it)
                }
                RouteResult.Ok(
                    DaoPromptBookAnnotation.new {
                        promptBook = book
                        kind = input.kind
                        region = input.region
                        text = input.text
                        color = input.color
                        tone = input.tone
                    }.toDto()
                )
            }

            if (result is RouteResult.Ok) {
                state.show.fixtures.promptBookChanged()
                call.respond(HttpStatusCode.Created, result.value)
            } else {
                respondRouteResult(result)
            }
        }
    }

    // PUT /{projectId}/prompt-book/annotations/{annotationId} - Update annotation
    put<PromptBookAnnotationResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.parent.projectId,
            "Cannot modify prompt book annotations - not current project",
        ) { project ->
            val input = call.receive<AnnotationRequest>()

            val result: RouteResult<PromptBookAnnotationDto> = transaction(state.database) {
                val book = project.promptBook
                    ?: return@transaction RouteResult.Error(HttpStatusCode.NotFound, "Prompt book not found")
                validateAnnotationInput(input, book.pageCount)?.let {
                    return@transaction RouteResult.Error(HttpStatusCode.BadRequest, it)
                }
                val annotation = DaoPromptBookAnnotation.findById(resource.annotationId)
                    ?.takeIf { it.promptBook.id == book.id }
                    ?: return@transaction RouteResult.Error(HttpStatusCode.NotFound, "Annotation not found")

                annotation.kind = input.kind
                annotation.region = input.region
                annotation.text = input.text
                annotation.color = input.color
                annotation.tone = input.tone
                RouteResult.Ok(annotation.toDto())
            }

            if (result is RouteResult.Ok) state.show.fixtures.promptBookChanged()
            respondRouteResult(result)
        }
    }

    // DELETE /{projectId}/prompt-book/annotations/{annotationId}
    delete<PromptBookAnnotationResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.parent.projectId,
            "Cannot modify prompt book annotations - not current project",
        ) { project ->
            val found = transaction(state.database) {
                val book = project.promptBook ?: return@transaction false
                val annotation = DaoPromptBookAnnotation.findById(resource.annotationId)
                    ?.takeIf { it.promptBook.id == book.id }
                    ?: return@transaction false
                annotation.delete()
                true
            }

            if (found) {
                state.show.fixtures.promptBookChanged()
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Annotation not found"))
            }
        }
    }

    // POST /{projectId}/prompt-book/scripts - Upload a script PDF (raw bytes)
    post<PromptBookScriptsResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            "Cannot upload prompt book scripts - not current project",
        ) { project ->
            // Bounded read: never buffer more than the cap + 1 sentinel byte.
            val bytes = call.receiveChannel().readRemaining(MAX_SCRIPT_BYTES + 1L).readByteArray()
            if (bytes.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Empty upload"))
                return@withCurrentProject
            }
            if (bytes.size > MAX_SCRIPT_BYTES) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse("Script PDF exceeds the ${MAX_SCRIPT_BYTES / (1024 * 1024)}MB limit"),
                )
                return@withCurrentProject
            }
            // Magic-byte check, not extension: identity is content, and users upload
            // renamed/exported files. "%PDF-" is the required PDF header.
            if (bytes.size < 5 || !bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Not a PDF file"))
                return@withCurrentProject
            }

            val hash = RecordHasher.sha256Hex(bytes)
            val path = promptScriptPath(state, project.uuid.toString(), hash)
            if (!path.exists()) {
                Files.createDirectories(path.parent)
                // Write via temp + atomic move so a crash mid-write can't leave a
                // truncated file answering to a valid content hash.
                val tmp = Files.createTempFile(path.parent, ".upload-", ".tmp")
                try {
                    Files.write(tmp, bytes)
                    Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }

            call.respond(HttpStatusCode.Created, ScriptUploadResponse(hash, bytes.size.toLong()))
        }
    }

    // GET /{projectId}/prompt-book/scripts/{hash} - Serve a script PDF
    get<PromptBookScriptResource> { resource ->
        withProject(state, resource.parent.parent.projectId) { project ->
            if (!SCRIPT_HASH_REGEX.matches(resource.hash)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid script hash"))
                return@withProject
            }
            val path = promptScriptPath(state, project.uuid.toString(), resource.hash)
            if (!path.exists()) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Script not found"))
                return@withProject
            }
            // Content-addressed: the bytes for a hash can never change.
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.respondFile(path.toFile())
        }
    }
}

private fun validatePromptBookInput(input: NewPromptBook): String? {
    if (!SCRIPT_HASH_REGEX.matches(input.scriptHash)) return "scriptHash must be 64 lowercase hex characters (SHA-256)"
    if (input.pageCount < 1) return "pageCount must be at least 1"
    return null
}

private fun validateAnnotationInput(input: AnnotationRequest, pageCount: Int): String? {
    if (PromptBookAnnotationKind.entries.none { it.name == input.kind }) {
        return "kind must be one of ${PromptBookAnnotationKind.entries.joinToString { it.name }}"
    }
    if (input.tone != null && PromptBookNoteTone.entries.none { it.name == input.tone }) {
        return "tone must be one of ${PromptBookNoteTone.entries.joinToString { it.name }}"
    }
    return checkPromptBookRegion(input.region, pageCount)
}

private fun DaoPromptBook.toDetails(isCurrentProject: Boolean) = PromptBookDetails(
    scriptHash = scriptHash,
    scriptFileName = scriptFileName,
    pageCount = pageCount,
    anchors = anchors.map { it.toDto() },
    annotations = annotations.map { it.toDto() },
    canEdit = isCurrentProject,
)

private fun DaoPromptBookAnchor.toDto() = PromptBookAnchorDto(
    cueId = cueId,
    region = region,
    label = label,
)

private fun DaoPromptBookAnnotation.toDto() = PromptBookAnnotationDto(
    id = id.value,
    kind = kind,
    region = region,
    text = text,
    color = color,
    tone = tone,
)

// ─── Resources ──────────────────────────────────────────────────────────

@Resource("/{projectId}/prompt-book")
data class ProjectPromptBookResource(val projectId: String)

@Resource("/{projectId}/cue-locations")
data class ProjectCueLocationsResource(val projectId: String)

@Resource("/anchors/{cueId}")
data class PromptBookAnchorResource(val parent: ProjectPromptBookResource, val cueId: Int)

@Resource("/annotations")
data class PromptBookAnnotationsResource(val parent: ProjectPromptBookResource)

@Resource("/{annotationId}")
data class PromptBookAnnotationResource(val parent: PromptBookAnnotationsResource, val annotationId: Int)

@Resource("/scripts")
data class PromptBookScriptsResource(val parent: ProjectPromptBookResource)

@Resource("/{hash}")
data class PromptBookScriptResource(val parent: PromptBookScriptsResource, val hash: String)

// ─── DTOs ──────────────────────────────────────────────────────────────

@Serializable
data class NewPromptBook(
    val scriptHash: String,
    val pageCount: Int,
    val scriptFileName: String? = null,
)

@Serializable
data class PromptBookDetails(
    val scriptHash: String,
    val scriptFileName: String?,
    val pageCount: Int,
    val anchors: List<PromptBookAnchorDto>,
    val annotations: List<PromptBookAnnotationDto>,
    val canEdit: Boolean,
)

@Serializable
data class PromptBookAnchorDto(
    val cueId: Int,
    val region: List<PromptBookRectDto>,
    val label: String? = null,
)

/**
 * A cue's reading position in the project's prompt book, reduced from its anchor
 * region to the earliest rect (topmost on the lowest page). Consumed by the Run view;
 * the frontend formats these coordinates into "top of p. 9"-style labels.
 */
@Serializable
data class CueLocationDto(
    val cueId: Int,
    val page: Int,
    val y: Double,
)

@Serializable
data class PromptBookAnnotationDto(
    val id: Int,
    val kind: String,
    val region: List<PromptBookRectDto>,
    val text: String? = null,
    val color: String? = null,
    val tone: String? = null,
)

@Serializable
data class UpsertAnchorRequest(
    val region: List<PromptBookRectDto>,
    val label: String? = null,
)

@Serializable
data class AnnotationRequest(
    val kind: String,
    val region: List<PromptBookRectDto>,
    val text: String? = null,
    val color: String? = null,
    val tone: String? = null,
)

@Serializable
data class ScriptUploadResponse(
    val scriptHash: String,
    val sizeBytes: Long,
)
