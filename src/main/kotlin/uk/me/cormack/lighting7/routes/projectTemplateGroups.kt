package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoTemplateGroup
import uk.me.cormack.lighting7.models.DaoTemplateGroups
import uk.me.cormack.lighting7.state.State

private const val GROUP_NOT_FOUND = "Template group not found"

/**
 * Template groups: name, position, and — through the templates that point at them — membership.
 *
 * Deliberately thin. A group has no contents of its own (`DaoTemplateGroups`), so there is nothing
 * here but CRUD; membership and order are written by the *template* routes (`PUT /templates/{id}`
 * with a `groupId`, and `POST /templates/reorder` with the whole layout), because a template moving
 * is a fact about the template. The one thing a group route does to templates is on DELETE, where
 * the members are put back at top level in the group's place rather than deleted with it — a group
 * is a cluster, and dissolving a cluster loses nothing but the cluster.
 *
 * Every write broadcasts `templateListChanged`, the same signal a template create or rename sends:
 * to the client, the library changed shape, and the list it re-fetches carries both tables.
 */
internal fun Route.routeApiRestProjectTemplateGroups(state: State) {
    // GET /projects/{id}/template-groups
    get<ProjectTemplateGroupsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val groups = transaction(state.database) {
                DaoTemplateGroup.find { DaoTemplateGroups.project eq project.id }
                    .orderBy(DaoTemplateGroups.sortOrder to SortOrder.ASC, DaoTemplateGroups.name to SortOrder.ASC)
                    .map { it.toDto() }
            }
            call.respond(groups)
        }
    }

    // POST /projects/{id}/template-groups
    post<ProjectTemplateGroupsResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<TemplateGroupInput>()
            val name = request.name?.trim().orEmpty()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Group name must not be blank"))
                return@withCurrentProject
            }
            val created = transaction(state.database) {
                val clash = DaoTemplateGroup.find {
                    (DaoTemplateGroups.project eq project.id) and (DaoTemplateGroups.name eq name)
                }.firstOrNull()
                if (clash != null) return@transaction null
                DaoTemplateGroup.new {
                    this.project = project
                    this.name = name
                    // Appends to the top-level sequence the ungrouped templates share.
                    this.sortOrder = nextTopLevelSortOrder(project)
                }.toDto()
            }
            if (created == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A template group named '$name' already exists in this project"),
                )
                return@withCurrentProject
            }
            state.show.fixtures.templateListChanged()
            call.respond(HttpStatusCode.Created, created)
        }
    }

    // PUT /projects/{id}/template-groups/{groupId} — rename
    put<ProjectTemplateGroupResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<TemplateGroupInput>()
            val outcome = transaction(state.database) {
                val group = DaoTemplateGroup.findById(resource.groupId)
                    ?.takeIf { it.project.id == project.id }
                    ?: return@transaction GroupWriteOutcome.NotFound
                val newName = request.name?.trim()?.takeIf { it.isNotEmpty() }
                if (newName != null && newName != group.name) {
                    val clash = DaoTemplateGroup.find {
                        (DaoTemplateGroups.project eq project.id) and (DaoTemplateGroups.name eq newName)
                    }.firstOrNull()
                    if (clash != null) return@transaction GroupWriteOutcome.NameTaken(newName)
                    group.name = newName
                }
                GroupWriteOutcome.Written(group.toDto())
            }
            when (outcome) {
                is GroupWriteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(GROUP_NOT_FOUND))
                is GroupWriteOutcome.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A template group named '${outcome.name}' already exists in this project"),
                )
                is GroupWriteOutcome.Written -> {
                    state.show.fixtures.templateListChanged()
                    call.respond(outcome.dto)
                }
            }
        }
    }

    // DELETE /projects/{id}/template-groups/{groupId} — dissolve; members go back to top level
    delete<ProjectTemplateGroupResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val found = try {
                transaction(state.database) {
                    val group = DaoTemplateGroup.findById(resource.groupId)
                        ?.takeIf { it.project.id == project.id }
                        ?: return@transaction false
                    // Inline the members where the group sat, then renumber through the one
                    // implementation — so the members keep their relative order and everything else
                    // keeps its place. The layout is read *before* the row goes and applied *after*:
                    // `applyTemplateLayout` requires every group in the project to be named, and the
                    // one being dissolved must not be. Applying it is also what clears the members'
                    // `group_id` (SQLite enforces no cascade, and a template still pointing at a
                    // deleted group is exactly the dangling reference the importer has to warn about).
                    val layout = currentTemplateLayout(project).flatMap { entry ->
                        if (entry.groupId == group.id.value) {
                            entry.templateIds.map { TemplateLayoutEntry(templateId = it) }
                        } else {
                            listOf(entry)
                        }
                    }
                    group.delete()
                    val applied = applyTemplateLayout(project, layout)
                    // Dissolving one group re-applies the layout of *all* of them, so a group the
                    // write boundary would never have allowed — an archive whose `groupUuid`s the
                    // importer takes verbatim, or a hand-edited row — makes the layout refuse and
                    // has nothing to do with the group being dissolved. Reported rather than
                    // asserted: `check` threw out of the route, which 500s and leaves the operator
                    // with a delete that silently did nothing. Throwing rolls the delete back, so
                    // the refusal is still all-or-nothing.
                    if (applied !is TemplateLayoutOutcome.Ok) throw LayoutRefused(applied)
                    true
                }
            } catch (e: LayoutRefused) {
                val code = (e.outcome as? TemplateLayoutOutcome.MixedFamily)?.let { CODE_TEMPLATE_GROUP_FAMILY }
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.detail, code = code))
                return@withCurrentProject
            }
            if (!found) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(GROUP_NOT_FOUND))
                return@withCurrentProject
            }
            state.show.fixtures.templateListChanged()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Thrown out of the DELETE's transaction when re-applying the layout without the dissolved group
 * would break an invariant that has nothing to do with this delete — today only a *pre-existing*
 * mixed-family group. Throwing is what rolls the `group.delete()` back, so the refusal leaves the
 * library exactly as it was.
 */
private class LayoutRefused(val outcome: TemplateLayoutOutcome) : RuntimeException(detailOf(outcome)) {
    val detail: String = detailOf(outcome)
}

private fun detailOf(outcome: TemplateLayoutOutcome): String = when (outcome) {
    is TemplateLayoutOutcome.MixedFamily -> outcome.message
    is TemplateLayoutOutcome.Invalid -> outcome.message
    TemplateLayoutOutcome.Ok -> "no refusal"
}

private sealed interface GroupWriteOutcome {
    data object NotFound : GroupWriteOutcome
    data class NameTaken(val name: String) : GroupWriteOutcome
    data class Written(val dto: TemplateGroupDto) : GroupWriteOutcome
}

// ─── Resources ──────────────────────────────────────────────────────────

@Resource("/{projectId}/template-groups")
internal data class ProjectTemplateGroupsResource(val projectId: String)

@Resource("/{groupId}")
internal data class ProjectTemplateGroupResource(val parent: ProjectTemplateGroupsResource, val groupId: Int)

// ─── DTOs ───────────────────────────────────────────────────────────────

/**
 * A group as the library lists it. Membership is **not** here — it is `TemplateDto.groupId` on
 * each template, so the client composes the tree from the two flat lists (`lib/templateLayout.ts`)
 * and a template joining or leaving never invalidates a group document.
 */
@Serializable
internal data class TemplateGroupDto(
    val id: Int,
    val uuid: String,
    val name: String,
    /** Position in the top-level sequence, shared with ungrouped templates. */
    val sortOrder: Int,
    /**
     * The one family this group is in, **derived** from its members and never stored — null for
     * an empty group, which has no busk column and shows only under *All* on `/templates`.
     */
    val family: String? = null,
)

@Serializable
internal data class TemplateGroupInput(val name: String? = null)

/** Must be called inside a transaction. */
internal fun DaoTemplateGroup.toDto(): TemplateGroupDto = TemplateGroupDto(
    id = id.value,
    uuid = uuid.toString(),
    name = name,
    sortOrder = sortOrder,
    family = familyOf()?.name,
)
