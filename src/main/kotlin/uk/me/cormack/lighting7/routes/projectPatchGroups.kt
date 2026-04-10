package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectPatchGroups(state: State) {
    // GET /{projectId}/patch-groups - List groups
    get<ProjectPatchGroupsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val groups = transaction(state.database) {
            DaoFixtureGroup.find { DaoFixtureGroups.project eq project.id }
                .orderBy(DaoFixtureGroups.name to SortOrder.ASC)
                .map { it.toSummaryDto() }
        }
        call.respond(groups)
    }

    // GET /{projectId}/patch-groups/{groupId} - Group detail with ordered members
    get<ProjectPatchGroupResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val group = transaction(state.database) {
            val group = DaoFixtureGroup.findById(resource.groupId) ?: return@transaction null
            if (group.project.id != project.id) return@transaction null
            group.toDetailDto()
        }

        if (group == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
            return@get
        }
        call.respond(group)
    }

    // PUT /{projectId}/patch-groups/{groupId} - Update group (rename, reorder members)
    put<ProjectPatchGroupResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        val request = call.receive<UpdatePatchGroupRequest>()
        val result = transaction(state.database) {
            val group = DaoFixtureGroup.findById(resource.groupId)
                ?: return@transaction Pair<PatchGroupDetailDto?, String?>(null, "Group not found")
            if (group.project.id != project.id) {
                return@transaction Pair<PatchGroupDetailDto?, String?>(null, "Group not found")
            }

            // Rename
            request.name?.let { newName ->
                val existing = DaoFixtureGroup.find {
                    (DaoFixtureGroups.project eq project.id) and (DaoFixtureGroups.name eq newName)
                }.firstOrNull()
                if (existing != null && existing.id != group.id) {
                    return@transaction Pair<PatchGroupDetailDto?, String?>(null, "Group name '$newName' already exists")
                }
                group.name = newName
            }

            // Reorder members (list of patch IDs in desired order)
            request.memberOrder?.let { orderedPatchIds ->
                val members = group.members.associateBy { it.fixturePatch.id.value }
                orderedPatchIds.forEachIndexed { index, patchId ->
                    members[patchId]?.let { it.sortOrder = index }
                }
            }

            Pair<PatchGroupDetailDto?, String?>(group.toDetailDto(), null)
        }

        val (groupDto, error) = result
        if (error != null) {
            val code = if (error == "Group not found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
            call.respond(code, ErrorResponse(error))
            return@put
        }

        // Reload fixtures if project is current (group order affects runtime)
        if (state.isCurrentProject(project)) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
        }
        state.show.fixtures.patchListChanged()

        call.respond(groupDto!!)
    }

    // DELETE /{projectId}/patch-groups/{groupId} - Delete group (removes members from group)
    delete<ProjectPatchGroupResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        val deleted = transaction(state.database) {
            val group = DaoFixtureGroup.findById(resource.groupId) ?: return@transaction false
            if (group.project.id != project.id) return@transaction false
            // Remove all memberships (fixtures stay, just unlinked from group)
            group.members.forEach { it.delete() }
            group.delete()
            true
        }

        if (!deleted) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
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
@Resource("/{projectId}/patch-groups")
data class ProjectPatchGroupsResource(val projectId: String)

@Resource("/{groupId}")
data class ProjectPatchGroupResource(val parent: ProjectPatchGroupsResource, val groupId: Int)

// DTOs
@Serializable
data class PatchGroupDto(
    val id: Int,
    val name: String,
    val memberCount: Int,
)

@Serializable
data class PatchGroupDetailDto(
    val id: Int,
    val name: String,
    val members: List<PatchGroupMemberDto>,
)

@Serializable
data class PatchGroupMemberDto(
    val patchId: Int,
    val fixtureKey: String,
    val fixtureName: String,
    val fixtureTypeKey: String,
    val sortOrder: Int,
)

@Serializable
data class UpdatePatchGroupRequest(
    val name: String? = null,
    val memberOrder: List<Int>? = null, // list of patch IDs in desired order
)

// Helpers
private fun DaoFixtureGroup.toSummaryDto() = PatchGroupDto(
    id = id.value,
    name = name,
    memberCount = members.count().toInt(),
)

private fun DaoFixtureGroup.toDetailDto() = PatchGroupDetailDto(
    id = id.value,
    name = name,
    members = members
        .sortedBy { it.sortOrder }
        .map { PatchGroupMemberDto(
            patchId = it.fixturePatch.id.value,
            fixtureKey = it.fixturePatch.key,
            fixtureName = it.fixturePatch.displayName,
            fixtureTypeKey = it.fixturePatch.fixtureTypeKey,
            sortOrder = it.sortOrder,
        ) },
)
