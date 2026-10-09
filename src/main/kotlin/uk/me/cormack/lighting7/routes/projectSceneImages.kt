package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.request.*
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.SCENE_IMAGE_HASH
import uk.me.cormack.lighting7.state.SceneImageException
import uk.me.cormack.lighting7.state.SceneImageStore
import uk.me.cormack.lighting7.state.SceneImageVariant
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.Overrides

/**
 * The images painted on scene cloths over REST (scrim plan §3.4), under the stage-element routes'
 * gate — any signed-in desk account, either role — and, like them, stored data only, so ungated by
 * the current project. Every one of these is reachable from the internet on the public listener,
 * which is why the upload reads at most [SceneImageStore.MAX_UPLOAD_BYTES] + 1 bytes and refuses an
 * image claiming more than [SceneImageStore.MAX_SIDE_PX] a side from its header, before decoding it.
 *
 * - `POST scene-images` takes the raw bytes (`Content-Type: image/png | image/jpeg`) and answers
 *   what they are. Idempotent by hash: the same bytes answer the same hash and are stored once.
 * - `GET scene-images` lists every image this machine holds for the project — the element sheet's
 *   thumbnails, its aspect hint and its "missing on this machine" all read it.
 * - `GET scene-images/{hash}` serves the original, `?variant=display | detail | mask` a derived copy;
 *   all immutable, since a hash names its bytes forever.
 * - `PUT stage-elements/{eid}/display-detail {full}` is the per-machine *Full detail* switch (D12).
 *
 * An image is referenced by an element write, which fires `stageElementListChanged` already, so
 * nothing here needs a socket frame — except the switch, which changes the element's DTO.
 */
internal fun Route.routeApiRestProjectSceneImages(state: State) {
    post<ProjectSceneImagesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val declared = call.request.contentLength()
            if (declared != null && declared > SceneImageStore.MAX_UPLOAD_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, tooLarge())
                return@withProject
            }
            // Bounded read: never buffer more than the cap and one sentinel byte.
            val bytes = call.receiveChannel().readBuffer(SceneImageStore.MAX_UPLOAD_BYTES + 1L).readByteArray()
            if (bytes.size > SceneImageStore.MAX_UPLOAD_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, tooLarge())
                return@withProject
            }
            if (bytes.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Empty upload", code = SceneImageStore.CODE_INVALID))
                return@withProject
            }
            val info = try {
                state.sceneImages.store(project.uuid.toString(), bytes, call.request.headers[HttpHeaders.ContentType])
            } catch (e: SceneImageException) {
                call.respond(
                    if (e.tooLarge) HttpStatusCode.PayloadTooLarge else HttpStatusCode.BadRequest,
                    ErrorResponse(e.message ?: "Not a scene image", code = e.code),
                )
                return@withProject
            }
            call.respond(HttpStatusCode.OK, info)
        }
    }

    get<ProjectSceneImagesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            call.respond(state.sceneImages.list(project.uuid.toString()))
        }
    }

    get<ProjectSceneImageResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val hash = resource.hash.lowercase()
            val variant = resource.variant?.let {
                SceneImageVariant.ofWire(it) ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("variant must be one of ${SceneImageVariant.entries.joinToString { v -> v.wire }}", code = SceneImageStore.CODE_INVALID),
                    )
                    return@withProject
                }
            }
            val uuid = project.uuid.toString()
            val path = try {
                when {
                    !SCENE_IMAGE_HASH.matches(hash) -> null
                    variant == null -> state.sceneImages.original(uuid, hash)?.first
                    else -> state.sceneImages.variant(uuid, hash, variant)
                }
            } catch (e: SceneImageException) {
                // A stored original no copy can be made of — one that predates the store's checks,
                // or was hand-placed. Said as the image it is, not as the desk failing.
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorResponse("Scene image $hash is stored but cannot be read: ${e.message}", code = SceneImageStore.CODE_INVALID),
                )
                return@withProject
            } catch (e: java.io.IOException) {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorResponse("Scene image $hash is stored but cannot be read: ${e.message}", code = SceneImageStore.CODE_INVALID),
                )
                return@withProject
            }
            if (path == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("No scene image $hash on this machine", code = SceneImageStore.CODE_UNKNOWN),
                )
                return@withProject
            }
            // Content-addressed: the bytes for a hash, and every copy made from them, never change.
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondFile(path.toFile())
        }
    }

    put<ProjectStageElementDisplayDetailResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<DisplayDetailRequest>()
            val dto = transaction(state.database) {
                val element = DaoStageElement.findById(resource.elementId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction null
                Overrides.setString(
                    project.id.value, STAGE_ELEMENTS_OVERRIDE_TABLE, element.uuid, FIELD_DISPLAY_DETAIL,
                    if (body.full) DISPLAY_DETAIL_FULL else null,
                )
                element.toDetailDto(project)
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(STAGE_ELEMENT_NOT_FOUND))
                return@withProject
            }
            state.show.fixtures.stageElementListChanged()
            call.respond(dto)
        }
    }
}

private fun tooLarge() = ErrorResponse(
    "A scene image is at most ${SceneImageStore.MAX_UPLOAD_BYTES / (1024 * 1024)} MB",
    code = SceneImageStore.CODE_INVALID,
)

@Resource("/{projectId}/scene-images")
data class ProjectSceneImagesResource(val projectId: String)

@Resource("/{hash}")
data class ProjectSceneImageResource(
    val parent: ProjectSceneImagesResource,
    val hash: String,
    val variant: String? = null,
)

@Resource("/{elementId}/display-detail")
data class ProjectStageElementDisplayDetailResource(val parent: ProjectStageElementsResource, val elementId: Int)

@Serializable
data class DisplayDetailRequest(val full: Boolean)
