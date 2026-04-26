package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.resources.delete
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectPatches(state: State) {
    // GET /{projectId}/patches - List all patches for a project
    get<ProjectPatchesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val patches = transaction(state.database) {
                DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                    .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC)
                    .map { it.toDto() }
            }
            call.respond(patches)
        }
    }

    // GET /{projectId}/patches/{patchId} - Get a single patch
    get<ProjectPatchResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId) ?: return@transaction null
                if (patch.project.id != project.id) return@transaction null
                patch.toDto()
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
            } else {
                call.respond(dto)
            }
        }
    }

    // POST /{projectId}/patches - Create a single patch
    post<ProjectPatchesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreatePatchRequest>()

            // Validate fixture type
            val typeInfo = FixtureTypeRegistry.allTypes.find { it.typeKey == request.fixtureTypeKey }
            if (typeInfo == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown fixture type: ${request.fixtureTypeKey}"))
                return@withProject
            }

            val channelCount = typeInfo.channelCount
            if (channelCount == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Fixture type ${request.fixtureTypeKey} has no channel count"))
                return@withProject
            }

            // Validate channels fit
            val lastChannel = request.startChannel + channelCount - 1
            if (lastChannel > 512) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    "Fixture extends to channel $lastChannel (max 512). Max start channel: ${512 - channelCount + 1}"
                ))
                return@withProject
            }

            val stageError = validateStageMetadata(
                stageX = request.stageX,
                stageY = request.stageY,
                beamAngleDeg = request.beamAngleDeg,
            )
            if (stageError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(stageError))
                return@withProject
            }
            val normalisedRiggingPosition = normaliseRiggingPosition(request.riggingPosition)
            val normalisedGelCode = normaliseGelCode(request.gelCode)

            val result = transaction(state.database) {
                // Find or create universe config
                val universeConfig = DaoUniverseConfig.find {
                    (DaoUniverseConfigs.project eq project.id) and
                        (DaoUniverseConfigs.subnet eq 0) and
                        (DaoUniverseConfigs.universe eq request.universe)
                }.firstOrNull() ?: DaoUniverseConfig.new {
                    this.project = project
                    this.subnet = 0
                    this.universe = request.universe
                    this.controllerType = "ARTNET"
                    this.address = request.address
                }

                // Check for channel overlap
                val overlap = DaoFixturePatch.find {
                    DaoFixturePatches.universeConfig eq universeConfig.id
                }.firstOrNull { existing ->
                    val existingType = FixtureTypeRegistry.allTypes.find { it.typeKey == existing.fixtureTypeKey }
                    val existingChannelCount = existingType?.channelCount ?: 1
                    val existingEnd = existing.startChannel + existingChannelCount - 1
                    request.startChannel <= existingEnd && lastChannel >= existing.startChannel
                }
                if (overlap != null) {
                    return@transaction Pair<FixturePatchDto?, String?>(
                        null, "Channel overlap with fixture '${overlap.displayName}' (${overlap.key})"
                    )
                }

                // Check key uniqueness
                val existingKey = DaoFixturePatch.find {
                    (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.key eq request.key)
                }.firstOrNull()
                if (existingKey != null) {
                    return@transaction Pair<FixturePatchDto?, String?>(null, "Duplicate key: ${request.key}")
                }

                val maxSortOrder = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                    .maxOfOrNull { it.sortOrder } ?: -1

                val patch = DaoFixturePatch.new {
                    this.project = project
                    this.universeConfig = universeConfig
                    this.fixtureTypeKey = request.fixtureTypeKey
                    this.key = request.key
                    this.displayName = request.name
                    this.startChannel = request.startChannel
                    this.sortOrder = maxSortOrder + 1
                    this.stageX = request.stageX
                    this.stageY = request.stageY
                    this.riggingPosition = normalisedRiggingPosition
                    this.beamAngleDeg = request.beamAngleDeg
                    this.gelCode = normalisedGelCode
                }

                // Assign to group if specified
                request.groupName?.takeIf { it.isNotBlank() }?.let { groupName ->
                    val group = findOrCreateGroup(project, groupName)
                    assignPatchToGroup(patch, group)
                }

                Pair<FixturePatchDto?, String?>(patch.toDto(), null)
            }

            val (patchDto, error) = result
            if (error != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(error))
                return@withProject
            }

            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.Created, patchDto!!)
        }
    }

    // PUT /{projectId}/patches/{patchId} — partial update.
    // Keys absent from the JSON body are left unchanged; for the nullable
    // stage-metadata fields, an explicit JSON null clears the value.
    put<ProjectPatchResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()

            // Range-check stage metadata before opening the transaction so a bad
            // request fails with a 400 instead of rolling back a write.
            val stageError = validateStageMetadata(
                stageX = body["stageX"].nullableDouble(),
                stageY = body["stageY"].nullableDouble(),
                beamAngleDeg = body["beamAngleDeg"].nullableInt(),
            )
            if (stageError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(stageError))
                return@withProject
            }

            val result = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId)
                    ?: return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")

                if (patch.project.id != project.id) {
                    return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")
                }

                body["displayName"].nullableString()?.let { patch.displayName = it }
                body["key"].nullableString()?.let { newKey ->
                    val existing = DaoFixturePatch.find {
                        (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.key eq newKey)
                    }.firstOrNull()
                    if (existing != null && existing.id != patch.id) {
                        return@transaction Pair<FixturePatchDto?, String?>(null, "Key '$newKey' already exists")
                    }
                    patch.key = newKey
                }
                body["startChannel"].nullableInt()?.let { patch.startChannel = it }

                if ("stageX" in body) patch.stageX = body["stageX"].nullableDouble()
                if ("stageY" in body) patch.stageY = body["stageY"].nullableDouble()
                if ("beamAngleDeg" in body) patch.beamAngleDeg = body["beamAngleDeg"].nullableInt()
                if ("riggingPosition" in body) {
                    patch.riggingPosition = normaliseRiggingPosition(body["riggingPosition"].nullableString())
                }
                if ("gelCode" in body) {
                    patch.gelCode = normaliseGelCode(body["gelCode"].nullableString())
                }

                body["removeFromGroupId"].nullableInt()?.let { groupId ->
                    DaoFixtureGroupMember.find {
                        (DaoFixtureGroupMembers.fixturePatch eq patch.id) and
                            (DaoFixtureGroupMembers.group eq groupId)
                    }.forEach { it.delete() }
                }
                body["addToGroup"].nullableString()?.let { groupName ->
                    val group = findOrCreateGroup(project, groupName)
                    val alreadyMember = DaoFixtureGroupMember.find {
                        (DaoFixtureGroupMembers.fixturePatch eq patch.id) and
                            (DaoFixtureGroupMembers.group eq group.id)
                    }.firstOrNull()
                    if (alreadyMember == null) {
                        val maxOrder = group.members.maxOfOrNull { it.sortOrder } ?: -1
                        DaoFixtureGroupMember.new {
                            this.group = group
                            this.fixturePatch = patch
                            this.sortOrder = maxOrder + 1
                        }
                    }
                }

                Pair<FixturePatchDto?, String?>(patch.toDto(), null)
            }

            val (patchDto, error) = result
            if (error != null) {
                val code = if (error == "Patch not found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
                call.respond(code, ErrorResponse(error))
                return@withProject
            }

            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
            }
            state.show.fixtures.patchListChanged()

            call.respond(patchDto!!)
        }
    }

    // DELETE /{projectId}/patches/{patchId} - Delete a patch
    delete<ProjectPatchResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId) ?: return@transaction false
                if (patch.project.id != project.id) return@transaction false

                // Remove from any groups first
                DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
                    .forEach { it.delete() }

                patch.delete()
                true
            }

            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
                return@withProject
            }

            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// Resources
@Resource("/{projectId}/patches")
data class ProjectPatchesResource(val projectId: String)

@Resource("/{patchId}")
data class ProjectPatchResource(val parent: ProjectPatchesResource, val patchId: Int)

// DTOs
@Serializable
data class FixturePatchDto(
    val id: Int,
    val key: String,
    val displayName: String,
    val fixtureTypeKey: String,
    val startChannel: Int,
    val channelCount: Int?,
    val manufacturer: String?,
    val model: String?,
    val modeName: String?,
    val universe: Int,
    val subnet: Int,
    val sortOrder: Int,
    val groups: List<FixturePatchGroupRef>,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val riggingPosition: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
)

@Serializable
data class FixturePatchGroupRef(
    val id: Int,
    val name: String,
)

@Serializable
data class CreatePatchRequest(
    val universe: Int,
    val fixtureTypeKey: String,
    val key: String,
    val name: String,
    val startChannel: Int,
    val address: String? = null,
    val groupName: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val riggingPosition: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
)

// Helpers
private fun DaoFixturePatch.toDto(): FixturePatchDto {
    val typeInfo = FixtureTypeRegistry.typeInfoForKey(fixtureTypeKey)
    val groupRefs = DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq this@toDto.id }
        .map { FixturePatchGroupRef(id = it.group.id.value, name = it.group.name) }
    return FixturePatchDto(
        id = id.value,
        key = key,
        displayName = displayName,
        fixtureTypeKey = fixtureTypeKey,
        startChannel = startChannel,
        channelCount = typeInfo?.channelCount,
        manufacturer = typeInfo?.manufacturer,
        model = typeInfo?.model,
        modeName = typeInfo?.modeName,
        universe = universeConfig.universe,
        subnet = universeConfig.subnet,
        sortOrder = sortOrder,
        groups = groupRefs,
        stageX = stageX,
        stageY = stageY,
        riggingPosition = riggingPosition,
        beamAngleDeg = beamAngleDeg,
        gelCode = gelCode,
    )
}

/**
 * Range-check the numeric stage-metadata fields. Returns the first error message,
 * or null if every field is acceptable. Trim/uppercase normalisation for the
 * string fields lives in [normaliseRiggingPosition] / [normaliseGelCode].
 */
private fun validateStageMetadata(
    stageX: Double?,
    stageY: Double?,
    beamAngleDeg: Int?,
): String? {
    if (stageX != null && (stageX < 0.0 || stageX > 100.0)) {
        return "stageX must be between 0.0 and 100.0"
    }
    if (stageY != null && (stageY < 0.0 || stageY > 100.0)) {
        return "stageY must be between 0.0 and 100.0"
    }
    if (beamAngleDeg != null && (beamAngleDeg < 2 || beamAngleDeg > 120)) {
        return "beamAngleDeg must be between 2 and 120"
    }
    return null
}

private fun normaliseRiggingPosition(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    return trimmed.uppercase().take(50)
}

private fun normaliseGelCode(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    return trimmed.take(20)
}

// Tri-state JSON-element extractors for partial updates: a missing key and an
// explicit JSON null both yield Kotlin null; callers that need to tell those
// apart can guard with `"key" in body` first.
private fun JsonElement?.nullableString(): String? =
    if (this == null || this is JsonNull) null else jsonPrimitive.content

private fun JsonElement?.nullableInt(): Int? =
    if (this == null || this is JsonNull) null else jsonPrimitive.int

private fun JsonElement?.nullableDouble(): Double? =
    if (this == null || this is JsonNull) null else jsonPrimitive.double

/**
 * Find or create a fixture group by name within a project.
 */
private fun findOrCreateGroup(project: DaoProject, groupName: String): DaoFixtureGroup {
    return DaoFixtureGroup.find {
        (DaoFixtureGroups.project eq project.id) and (DaoFixtureGroups.name eq groupName)
    }.firstOrNull() ?: DaoFixtureGroup.new {
        this.project = project
        this.name = groupName
    }
}

/**
 * Add a patch to a group, removing from any previous group first.
 */
private fun assignPatchToGroup(patch: DaoFixturePatch, group: DaoFixtureGroup) {
    // Remove from current group(s)
    DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
        .forEach { it.delete() }

    val maxOrder = group.members.maxOfOrNull { it.sortOrder } ?: -1
    DaoFixtureGroupMember.new {
        this.group = group
        this.fixturePatch = patch
        this.sortOrder = maxOrder + 1
    }
}

/**
 * Remove a patch from all groups.
 */
private fun removePatchFromGroups(patch: DaoFixturePatch) {
    DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
        .forEach { it.delete() }
}
