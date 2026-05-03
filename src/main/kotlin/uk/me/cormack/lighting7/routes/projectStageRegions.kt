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
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectStageRegions(state: State) {
    get<ProjectStageRegionsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val regions = transaction(state.database) {
                DaoStageRegion.find { DaoStageRegions.project eq project.id }
                    .orderBy(
                        DaoStageRegions.sortOrder to SortOrder.ASC,
                        DaoStageRegions.name to SortOrder.ASC,
                    )
                    .map { it.toDto() }
            }
            call.respond(regions)
        }
    }

    get<ProjectStageRegionResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val region = DaoStageRegion.findById(resource.regionId) ?: return@transaction null
                if (region.project.id != project.id) return@transaction null
                region.toDto()
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Stage region not found"))
            } else {
                call.respond(dto)
            }
        }
    }

    post<ProjectStageRegionsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreateStageRegionRequest>()
            val regionError = validateStageRegion(
                centerX = request.centerX,
                centerY = request.centerY,
                centerZ = request.centerZ,
                widthM = request.widthM,
                depthM = request.depthM,
                heightM = request.heightM,
                yawDeg = request.yawDeg,
            )
            if (regionError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(regionError))
                return@withProject
            }
            val trimmedName = request.name.trim()
            if (trimmedName.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Stage region name must not be blank"))
                return@withProject
            }

            val result = transaction(state.database) {
                val existing = DaoStageRegion.find {
                    (DaoStageRegions.project eq project.id) and (DaoStageRegions.name eq trimmedName)
                }.firstOrNull()
                if (existing != null) {
                    return@transaction Pair<StageRegionDto?, String?>(null, "Stage region '$trimmedName' already exists")
                }
                val maxSortOrder = DaoStageRegion.find { DaoStageRegions.project eq project.id }
                    .maxOfOrNull { it.sortOrder } ?: -1
                val region = DaoStageRegion.new {
                    this.project = project
                    this.name = trimmedName
                    this.centerX = request.centerX
                    this.centerY = request.centerY
                    this.centerZ = request.centerZ
                    this.widthM = request.widthM
                    this.depthM = request.depthM
                    this.heightM = request.heightM
                    this.yawDeg = request.yawDeg
                    this.sortOrder = maxSortOrder + 1
                }
                Pair<StageRegionDto?, String?>(region.toDto(), null)
            }
            val (dto, error) = result
            if (error != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(error))
                return@withProject
            }
            state.show.fixtures.stageRegionListChanged()
            call.respond(HttpStatusCode.Created, dto!!)
        }
    }

    put<ProjectStageRegionResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            val regionError = validateStageRegion(
                centerX = body["centerX"].nullableDouble(),
                centerY = body["centerY"].nullableDouble(),
                centerZ = body["centerZ"].nullableDouble(),
                widthM = body["widthM"].nullableDouble(),
                depthM = body["depthM"].nullableDouble(),
                heightM = body["heightM"].nullableDouble(),
                yawDeg = body["yawDeg"].nullableDouble(),
            )
            if (regionError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(regionError))
                return@withProject
            }

            val result = transaction(state.database) {
                val region = DaoStageRegion.findById(resource.regionId)
                    ?: return@transaction Pair<StageRegionDto?, String?>(null, "Stage region not found")
                if (region.project.id != project.id) {
                    return@transaction Pair<StageRegionDto?, String?>(null, "Stage region not found")
                }

                body["name"].nullableString()?.let { newName ->
                    val trimmed = newName.trim()
                    if (trimmed.isEmpty()) {
                        return@transaction Pair<StageRegionDto?, String?>(null, "Stage region name must not be blank")
                    }
                    val collision = DaoStageRegion.find {
                        (DaoStageRegions.project eq project.id) and (DaoStageRegions.name eq trimmed)
                    }.firstOrNull()
                    if (collision != null && collision.id != region.id) {
                        return@transaction Pair<StageRegionDto?, String?>(null, "Stage region '$trimmed' already exists")
                    }
                    region.name = trimmed
                }
                if ("centerX" in body) region.centerX = body["centerX"].nullableDouble()
                if ("centerY" in body) region.centerY = body["centerY"].nullableDouble()
                if ("centerZ" in body) region.centerZ = body["centerZ"].nullableDouble()
                if ("widthM" in body) region.widthM = body["widthM"].nullableDouble()
                if ("depthM" in body) region.depthM = body["depthM"].nullableDouble()
                if ("heightM" in body) region.heightM = body["heightM"].nullableDouble()
                if ("yawDeg" in body) region.yawDeg = body["yawDeg"].nullableDouble()
                body["sortOrder"].nullableInt()?.let { region.sortOrder = it }

                Pair<StageRegionDto?, String?>(region.toDto(), null)
            }
            val (dto, error) = result
            if (error != null) {
                val code = if (error == "Stage region not found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
                call.respond(code, ErrorResponse(error))
                return@withProject
            }
            state.show.fixtures.stageRegionListChanged()
            call.respond(dto!!)
        }
    }

    delete<ProjectStageRegionResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val region = DaoStageRegion.findById(resource.regionId) ?: return@transaction false
                if (region.project.id != project.id) return@transaction false
                region.delete()
                true
            }
            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Stage region not found"))
                return@withProject
            }
            state.show.fixtures.stageRegionListChanged()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

@Resource("/{projectId}/stageRegions")
data class ProjectStageRegionsResource(val projectId: String)

@Resource("/{regionId}")
data class ProjectStageRegionResource(val parent: ProjectStageRegionsResource, val regionId: Int)

@Serializable
data class StageRegionDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val centerZ: Double? = null,
    val widthM: Double? = null,
    val depthM: Double? = null,
    val heightM: Double? = null,
    val yawDeg: Double? = null,
    val sortOrder: Int,
)

@Serializable
data class CreateStageRegionRequest(
    val name: String,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val centerZ: Double? = null,
    val widthM: Double? = null,
    val depthM: Double? = null,
    val heightM: Double? = null,
    val yawDeg: Double? = null,
)

private fun DaoStageRegion.toDto() = StageRegionDto(
    id = id.value,
    uuid = uuid.toString(),
    name = name,
    centerX = centerX,
    centerY = centerY,
    centerZ = centerZ,
    widthM = widthM,
    depthM = depthM,
    heightM = heightM,
    yawDeg = yawDeg,
    sortOrder = sortOrder,
)

internal fun validateStageRegion(
    centerX: Double?,
    centerY: Double?,
    centerZ: Double?,
    widthM: Double?,
    depthM: Double?,
    heightM: Double?,
    yawDeg: Double?,
): String? {
    fun checkExtent(name: String, v: Double?): String? {
        if (v == null) return null
        if (!v.isFinite()) return "$name must be a finite number"
        if (v < 0.0 || v > 500.0) return "$name must be between 0 and 500 metres"
        return null
    }
    checkStageCoord("centerX", centerX)?.let { return it }
    checkStageCoord("centerY", centerY)?.let { return it }
    checkStageCoord("centerZ", centerZ)?.let { return it }
    checkExtent("widthM", widthM)?.let { return it }
    checkExtent("depthM", depthM)?.let { return it }
    checkExtent("heightM", heightM)?.let { return it }
    checkAngle("yawDeg", yawDeg, -360.0, 360.0)?.let { return it }
    return null
}
