package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectRiggings(state: State) {
    get<ProjectRiggingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val riggings = transaction(state.database) {
                DaoRigging.find { DaoRiggings.project eq project.id }
                    .orderBy(
                        DaoRiggings.sortOrder to SortOrder.ASC,
                        DaoRiggings.name to SortOrder.ASC,
                    )
                    .map { it.toDto() }
            }
            call.respond(riggings)
        }
    }

    get<ProjectRiggingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val rigging = DaoRigging.findById(resource.riggingId) ?: return@transaction null
                if (rigging.project.id != project.id) return@transaction null
                rigging.toDto()
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rigging not found"))
            } else {
                call.respond(dto)
            }
        }
    }

    post<ProjectRiggingsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreateRiggingRequest>()
            val poseError = validateRiggingPose(
                positionX = request.positionX,
                positionY = request.positionY,
                positionZ = request.positionZ,
                yawDeg = request.yawDeg,
                pitchDeg = request.pitchDeg,
                rollDeg = request.rollDeg,
            )
            if (poseError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(poseError))
                return@withProject
            }
            val trimmedName = request.name.trim()
            if (trimmedName.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Rigging name must not be blank"))
                return@withProject
            }

            val result = transaction(state.database) {
                val existing = DaoRigging.find {
                    (DaoRiggings.project eq project.id) and (DaoRiggings.name eq trimmedName)
                }.firstOrNull()
                if (existing != null) {
                    return@transaction Pair<RiggingDto?, String?>(null, "Rigging '$trimmedName' already exists")
                }
                val maxSortOrder = DaoRigging.find { DaoRiggings.project eq project.id }
                    .maxOfOrNull { it.sortOrder } ?: -1
                val rigging = DaoRigging.new {
                    this.project = project
                    this.name = trimmedName
                    this.kind = request.kind?.trim()?.takeIf { it.isNotEmpty() }
                    this.positionX = request.positionX
                    this.positionY = request.positionY
                    this.positionZ = request.positionZ
                    this.yawDeg = request.yawDeg
                    this.pitchDeg = request.pitchDeg
                    this.rollDeg = request.rollDeg
                    this.sortOrder = maxSortOrder + 1
                }
                Pair<RiggingDto?, String?>(rigging.toDto(), null)
            }
            val (dto, error) = result
            if (error != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(error))
                return@withProject
            }
            call.respond(HttpStatusCode.Created, dto!!)
        }
    }

    put<ProjectRiggingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            val poseError = validateRiggingPose(
                positionX = body["positionX"].nullableDouble(),
                positionY = body["positionY"].nullableDouble(),
                positionZ = body["positionZ"].nullableDouble(),
                yawDeg = body["yawDeg"].nullableDouble(),
                pitchDeg = body["pitchDeg"].nullableDouble(),
                rollDeg = body["rollDeg"].nullableDouble(),
            )
            if (poseError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(poseError))
                return@withProject
            }

            val result = transaction(state.database) {
                val rigging = DaoRigging.findById(resource.riggingId)
                    ?: return@transaction Pair<RiggingDto?, String?>(null, "Rigging not found")
                if (rigging.project.id != project.id) {
                    return@transaction Pair<RiggingDto?, String?>(null, "Rigging not found")
                }

                body["name"].nullableString()?.let { newName ->
                    val trimmed = newName.trim()
                    if (trimmed.isEmpty()) {
                        return@transaction Pair<RiggingDto?, String?>(null, "Rigging name must not be blank")
                    }
                    val collision = DaoRigging.find {
                        (DaoRiggings.project eq project.id) and (DaoRiggings.name eq trimmed)
                    }.firstOrNull()
                    if (collision != null && collision.id != rigging.id) {
                        return@transaction Pair<RiggingDto?, String?>(null, "Rigging '$trimmed' already exists")
                    }
                    rigging.name = trimmed
                }
                if ("kind" in body) {
                    rigging.kind = body["kind"].nullableString()?.trim()?.takeIf { it.isNotEmpty() }
                }
                if ("positionX" in body) rigging.positionX = body["positionX"].nullableDouble()
                if ("positionY" in body) rigging.positionY = body["positionY"].nullableDouble()
                if ("positionZ" in body) rigging.positionZ = body["positionZ"].nullableDouble()
                if ("yawDeg" in body) rigging.yawDeg = body["yawDeg"].nullableDouble()
                if ("pitchDeg" in body) rigging.pitchDeg = body["pitchDeg"].nullableDouble()
                if ("rollDeg" in body) rigging.rollDeg = body["rollDeg"].nullableDouble()
                body["sortOrder"].nullableInt()?.let { rigging.sortOrder = it }

                Pair<RiggingDto?, String?>(rigging.toDto(), null)
            }
            val (dto, error) = result
            if (error != null) {
                val code = if (error == "Rigging not found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
                call.respond(code, ErrorResponse(error))
                return@withProject
            }
            call.respond(dto!!)
        }
    }

    delete<ProjectRiggingResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val rigging = DaoRigging.findById(resource.riggingId) ?: return@transaction false
                if (rigging.project.id != project.id) return@transaction false
                // Detach any patches before deleting (FK is ON DELETE SET NULL conceptually,
                // but Exposed's optReference doesn't enforce that — clear explicitly).
                DaoFixturePatch.find { DaoFixturePatches.rigging eq rigging.id }
                    .forEach { it.rigging = null }
                rigging.delete()
                true
            }
            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Rigging not found"))
                return@withProject
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Resource("/{projectId}/riggings")
data class ProjectRiggingsResource(val projectId: String)

@Resource("/{riggingId}")
data class ProjectRiggingResource(val parent: ProjectRiggingsResource, val riggingId: Int)

@Serializable
data class RiggingDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val kind: String? = null,
    val positionX: Double? = null,
    val positionY: Double? = null,
    val positionZ: Double? = null,
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val sortOrder: Int,
)

@Serializable
data class CreateRiggingRequest(
    val name: String,
    val kind: String? = null,
    val positionX: Double? = null,
    val positionY: Double? = null,
    val positionZ: Double? = null,
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
)

private fun DaoRigging.toDto() = RiggingDto(
    id = id.value,
    uuid = uuid.toString(),
    name = name,
    kind = kind,
    positionX = positionX,
    positionY = positionY,
    positionZ = positionZ,
    yawDeg = yawDeg,
    pitchDeg = pitchDeg,
    rollDeg = rollDeg,
    sortOrder = sortOrder,
)

/**
 * Range-check rigging pose fields. Position bounds match fixture stage coordinates
 * (±500 m); yaw allows full ±360°; pitch and roll allow ±180°.
 */
internal fun validateRiggingPose(
    positionX: Double?,
    positionY: Double?,
    positionZ: Double?,
    yawDeg: Double?,
    pitchDeg: Double?,
    rollDeg: Double?,
): String? {
    checkStageCoord("positionX", positionX)?.let { return it }
    checkStageCoord("positionY", positionY)?.let { return it }
    checkStageCoord("positionZ", positionZ)?.let { return it }
    checkAngle("yawDeg", yawDeg, -360.0, 360.0)?.let { return it }
    checkAngle("pitchDeg", pitchDeg, -180.0, 180.0)?.let { return it }
    checkAngle("rollDeg", rollDeg, -180.0, 180.0)?.let { return it }
    return null
}
