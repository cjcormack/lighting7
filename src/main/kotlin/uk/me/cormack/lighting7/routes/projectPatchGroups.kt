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
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.VenueRigGuard

internal fun Route.routeApiRestProjectPatchGroups(state: State) {
    // GET /{projectId}/patch-groups - List groups
    get<ProjectPatchGroupsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val groups = transaction(state.database) {
                val rig = RigScope.of(project)
                rig.groups()
                    .orderBy(DaoFixtureGroups.name to SortOrder.ASC)
                    .map { it.toSummaryDto(rig) }
            }
            call.respond(groups)
        }
    }

    // GET /{projectId}/patch-groups/{groupId} - Group detail with ordered members
    get<ProjectPatchGroupResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val group = transaction(state.database) {
                val rig = RigScope.of(project)
                rig.groups(DaoFixtureGroups.id eq resource.groupId).firstOrNull()?.toDetailDto(rig)
            }

            if (group == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                return@withProject
            }
            call.respond(group)
        }
    }

    // PUT /{projectId}/patch-groups/{groupId} - Update group (rename, reorder members, colour)
    put<ProjectPatchGroupResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            // Read as an object so an explicit `colour: null` (clear) is told from an absent one, and the
            // colour checked by its own parser before the rest of the body is decoded.
            val body = call.receive<JsonObject>()
            val colourSent = FixtureColours.FIELD in body
            val colour = if (colourSent) {
                FixtureColours.parse(body[FixtureColours.FIELD]).getOrElse {
                    call.respond(HttpStatusCode.BadRequest, colourError(it.message))
                    return@withProject
                }
            } else null
            val request = try {
                DefaultJson.decodeFromJsonElement(UpdatePatchGroupRequest.serializer(), JsonObject(body - FixtureColours.FIELD))
            } catch (e: SerializationException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid group update"))
                return@withProject
            }
            val colourOnly = ShowColours.isRecolour(body.keys)
            val result = transaction(state.database) {
                val rig = RigScope.of(project)
                val found = DaoFixtureGroup.findById(resource.groupId)
                // A venue group in a show takes the show's recolour (tables-colours plan D10), and nothing else.
                if (found != null && colourOnly && rig.ownerOf(found.project.id) == RigOwner.VENUE &&
                    !rig.isHidden(HideKind.GROUP, found.uuid)
                ) {
                    ShowColours.put(project.id, found.uuid, colour)
                    return@transaction Pair<PatchGroupDetailDto?, String?>(found.toDetailDto(rig), null)
                }
                val group = VenueRigGuard.ownForWrite(
                    rig, found, { it.project.id }, { "Group '${it.name}'" },
                ) ?: return@transaction Pair<PatchGroupDetailDto?, String?>(null, "Group not found")

                // Rename
                request.name?.let { newName ->
                    val existing = rig.groups(DaoFixtureGroups.name eq newName).firstOrNull()
                    if (existing != null && existing.id != group.id) {
                        VenueRigGuard.refuseIfVenue(rig, existing.project.id, "Group name '$newName'")
                        return@transaction Pair<PatchGroupDetailDto?, String?>(null, "Group name '$newName' already exists")
                    }
                    group.name = newName
                }

                // Reorder members (patch ids in the order wanted; a member not named keeps its place)
                request.memberOrder?.let { reorderGroupMembers(group, it) }

                if (colourSent) group.colour = colour

                Pair<PatchGroupDetailDto?, String?>(group.toDetailDto(rig), null)
            }

            val (groupDto, error) = result
            if (error != null) {
                val code = if (error == "Group not found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
                call.respond(code, ErrorResponse(error))
                return@withProject
            }

            // Reload fixtures if project is current (group order affects runtime). A colour is never built
            // from, but the live group list carries it, so a colour-only edit announces that list instead.
            if (state.isCurrentProject(project)) {
                if (colourOnly) {
                    state.show.fixtures.announceFixturesChanged()
                } else {
                    DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
                }
            }
            state.show.fixtures.patchListChanged()
            // A busk rig tile embeds its group's colour.
            if (colourSent) state.show.fixtures.buskRigChanged()

            call.respond(groupDto!!)
        }
    }

    // DELETE /{projectId}/patch-groups/{groupId} - Delete group (removes members from group)
    delete<ProjectPatchGroupResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val group = VenueRigGuard.ownForWrite(
                    RigScope.of(project), DaoFixtureGroup.findById(resource.groupId), { it.project.id }, { "Group '${it.name}'" },
                ) ?: return@transaction null
                deleteFixtureGroupRows(group)
            }

            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                return@withProject
            }
            if (deleted > 0) state.show.fixtures.buskRigChanged()

            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Delete [group] and the rows that hang off it, inside the caller's transaction: its busk rig tiles
 * — a tile is an enrichment, never a guard, and the FK has no cascade (`DaoBuskRigTiles`) — and its
 * memberships, so the fixtures stay patched, just unlinked from the group. Answers how many rig
 * tiles went, so the caller knows whether to fire `buskRigChanged`. Shared by the REST delete and
 * the `delete_groups` MCP tool, which must leave the same rows behind.
 */
internal fun deleteFixtureGroupRows(group: DaoFixtureGroup): Int {
    val sweptTiles = deleteBuskRigTilesReferencing(groupId = group.id.value)
    group.members.forEach { it.delete() }
    group.delete()
    return sweptTiles
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
    /** Whose the group is in this rig (venue plan D12): a show's may hold venue patches, a venue's never a show's. */
    val owner: RigOwner = RigOwner.SHOW,
    /** The group's colour as this rig draws it (tables-colours plan D7, D10); null is none. Never its members'. */
    val colour: String? = null,
    /** On a venue group in a show: whether [colour] is the show's recolour or the venue's own. Null otherwise. */
    val colourOwner: ColourOwner? = null,
)

@Serializable
data class PatchGroupDetailDto(
    val id: Int,
    val name: String,
    val members: List<PatchGroupMemberDto>,
    val owner: RigOwner = RigOwner.SHOW,
    val colour: String? = null,
    val colourOwner: ColourOwner? = null,
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
    /**
     * Patch ids in the order wanted. The members named take the places the members named held, so
     * a member left out — a venue patch the show hides — keeps its own ([reorderGroupMembers]).
     */
    val memberOrder: List<Int>? = null,
    /**
     * One of `FixtureColours.KEYS`; null clears it, absent leaves it (400 `COLOUR_UNKNOWN` otherwise). On a
     * venue group in a show, a body naming only `colour` is the show's recolour; null takes the venue's back.
     */
    val colour: String? = null,
)

// Helpers
private fun DaoFixtureGroup.toSummaryDto(rig: RigScope) = PatchGroupDto(
    id = id.value,
    name = name,
    memberCount = rig.members(this).count().toInt(),
    owner = rig.ownerOf(project.id) ?: RigOwner.SHOW,
    colour = rig.colourOf(this),
    colourOwner = rig.colourOwnerOf(project.id, uuid),
)

private fun DaoFixtureGroup.toDetailDto(rig: RigScope) = PatchGroupDetailDto(
    id = id.value,
    name = name,
    owner = rig.ownerOf(project.id) ?: RigOwner.SHOW,
    colour = rig.colourOf(this),
    colourOwner = rig.colourOwnerOf(project.id, uuid),
    members = rig.members(this)
        .sortedBy { it.sortOrder }
        .map { PatchGroupMemberDto(
            patchId = it.fixturePatch.id.value,
            fixtureKey = it.fixturePatch.key,
            fixtureName = it.fixturePatch.displayName,
            fixtureTypeKey = it.fixturePatch.fixtureTypeKey,
            sortOrder = it.sortOrder,
        ) },
)
