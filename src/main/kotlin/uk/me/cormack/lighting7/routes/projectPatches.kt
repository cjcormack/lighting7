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
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val patches = transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC)
                .map { it.toDto() }
        }
        call.respond(patches)
    }

    // POST /{projectId}/patches - Create a single patch
    post<ProjectPatchesResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@post
        }

        if (project.mode != ProjectMode.DB_BASED) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Patches can only be created in DB_BASED mode"))
            return@post
        }

        val request = call.receive<CreatePatchRequest>()

        // Validate fixture type
        val typeInfo = FixtureTypeRegistry.allTypes.find { it.typeKey == request.fixtureTypeKey }
        if (typeInfo == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown fixture type: ${request.fixtureTypeKey}"))
            return@post
        }

        val channelCount = typeInfo.channelCount
        if (channelCount == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Fixture type ${request.fixtureTypeKey} has no channel count"))
            return@post
        }

        // Validate channels fit
        val lastChannel = request.startChannel + channelCount - 1
        if (lastChannel > 512) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                "Fixture extends to channel $lastChannel (max 512). Max start channel: ${512 - channelCount + 1}"
            ))
            return@post
        }

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
            return@post
        }

        if (state.isCurrentProject(project)) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
        }
        state.show.fixtures.patchListChanged()

        call.respond(HttpStatusCode.Created, patchDto!!)
    }

    // PUT /{projectId}/patches/{patchId} - Update a patch
    put<ProjectPatchResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        if (project.mode != ProjectMode.DB_BASED) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Patches can only be modified in DB_BASED mode"))
            return@put
        }

        val request = call.receive<UpdatePatchRequest>()
        val result = transaction(state.database) {
            val patch = DaoFixturePatch.findById(resource.patchId)
                ?: return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")

            if (patch.project.id != project.id) {
                return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")
            }

            request.displayName?.let { patch.displayName = it }
            request.key?.let { newKey ->
                // Check uniqueness
                val existing = DaoFixturePatch.find {
                    (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.key eq newKey)
                }.firstOrNull()
                if (existing != null && existing.id != patch.id) {
                    return@transaction Pair<FixturePatchDto?, String?>(null, "Key '$newKey' already exists")
                }
                patch.key = newKey
            }
            request.startChannel?.let { patch.startChannel = it }

            // Handle group changes
            request.removeFromGroupId?.let { groupId ->
                DaoFixtureGroupMember.find {
                    (DaoFixtureGroupMembers.fixturePatch eq patch.id) and
                        (DaoFixtureGroupMembers.group eq groupId)
                }.forEach { it.delete() }
            }
            request.addToGroup?.let { groupName ->
                val group = findOrCreateGroup(project, groupName)
                // Only add if not already a member
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
            return@put
        }

        if (state.isCurrentProject(project)) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
        }
        state.show.fixtures.patchListChanged()

        call.respond(patchDto!!)
    }

    // DELETE /{projectId}/patches/{patchId} - Delete a patch
    delete<ProjectPatchResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        if (project.mode != ProjectMode.DB_BASED) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Patches can only be deleted in DB_BASED mode"))
            return@delete
        }

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
            return@delete
        }

        if (state.isCurrentProject(project)) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
        }
        state.show.fixtures.patchListChanged()

        call.respond(HttpStatusCode.NoContent)
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
)

@Serializable
data class UpdatePatchRequest(
    val displayName: String? = null,
    val key: String? = null,
    val startChannel: Int? = null,
    val addToGroup: String? = null,
    val removeFromGroupId: Int? = null,
)

// Helpers
private fun DaoFixturePatch.toDto(): FixturePatchDto {
    val typeInfo = FixtureTypeRegistry.allTypes.find { it.typeKey == fixtureTypeKey }
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
    )
}

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
