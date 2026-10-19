package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.ShowHides
import java.util.UUID

/**
 * A show's hides of its venue's records (venue plan §3.5, session 3) — one of the changes a show makes
 * to a venue row, beside a patch's facet overrides (session 5, `routes/patchFacets.kt`) and a patch's or
 * group's recolour (`models/showColours.kt`).
 * `venue/ShowHides.kt` holds the rules.
 *
 * - `PUT /projects/{id}/patches/{pid}/hidden` `{hidden}` — hide a venue patch in this show, or bring
 *   it back. `pid` is the patch's id in the pinned version, as the Patch list and
 *   `GET …/patches/hidden` give it.
 * - `GET /projects/{id}/patches/hidden` — the venue patches this show hides, as Patch list rows
 *   (`owner: VENUE`), so the Patch list can offer them back.
 * - `GET /projects/{id}/hides` — every hide, of all six kinds, named from the pinned version.
 * - `PUT /projects/{id}/hides/{kind}/{uuid}` `{hidden}` — hide or show any of the six kinds by the
 *   record's uuid: `PATCH | RIGGING | ELEMENT | REGION | GROUP | VIEWPOINT`. The patch route above is
 *   this for a patch, by id.
 *
 * Stored data, ungated by the current project, as every rig route is; a hidden patch or group
 * changes what the live rig loads, so a live show reloads. Every refusal is a `VenueException`.
 */
internal fun Route.routeApiRestShowHides(state: State) {
    get<ProjectHiddenPatchesResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val rows = transaction(state.database) {
                val rig = RigScope.of(project)
                val venue = rig.venue ?: return@transaction emptyList()
                val uuids = venue.hides[HideKind.PATCH].orEmpty().toList()
                if (uuids.isEmpty()) return@transaction emptyList()
                DaoFixturePatch.find { (DaoFixturePatches.project eq venue.snapshotId) and (DaoFixturePatches.uuid inList uuids) }
                    .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC)
                    .map { it.toDto(rig = rig) }
            }
            call.respond(rows)
        }
    }

    put<ProjectPatchHiddenResource> { resource ->
        withProject(state, resource.parent.parent.projectId) { project ->
            val request = call.receive<SetHiddenRequest>()
            val changed = transaction(state.database) {
                ShowHides.setHiddenById(project, HideKind.PATCH, resource.parent.patchId, request.hidden)
            }
            if (changed) announceHideChange(state, project.id.value, HideKind.PATCH)
            call.respond(SetHiddenResponse(hidden = request.hidden, changed = changed))
        }
    }

    get<ProjectHidesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            call.respond(transaction(state.database) { ShowHides.list(project) })
        }
    }

    put<ProjectHideResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val kind = HideKind.entries.firstOrNull { it.name.equals(resource.kind, ignoreCase = true) }
            val uuid = runCatching { UUID.fromString(resource.uuid) }.getOrNull()
            if (kind == null || uuid == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "A hide names a kind (${HideKind.entries.joinToString(" | ")}) and a record uuid",
                        "VENUE_HIDE_INVALID",
                    ),
                )
                return@withProject
            }
            val request = call.receive<SetHiddenRequest>()
            val changed = transaction(state.database) { ShowHides.setHiddenByUuid(project, kind, uuid, request.hidden) }
            if (changed) announceHideChange(state, project.id.value, kind)
            call.respond(SetHiddenResponse(hidden = request.hidden, changed = changed))
        }
    }
}

/** What a hide changed, said the way that record's own writes say it; the live rig reloads for a fixture or group. */
private fun announceHideChange(state: State, projectId: Int, kind: HideKind) {
    val fixtures = state.show.fixtures
    when (kind) {
        HideKind.PATCH, HideKind.GROUP -> {
            if (state.projectManager.currentProject.id.value == projectId) {
                DbFixtureLoader.loadFixtures(projectId, fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
            }
            fixtures.patchListChanged()
        }
        HideKind.RIGGING -> {
            fixtures.riggingListChanged()
            fixtures.patchListChanged()
        }
        HideKind.ELEMENT -> fixtures.stageElementListChanged()
        HideKind.REGION -> fixtures.stageRegionListChanged()
        HideKind.VIEWPOINT -> fixtures.stageViewpointListChanged()
    }
}

@Serializable
data class SetHiddenRequest(val hidden: Boolean)

@Serializable
data class SetHiddenResponse(val hidden: Boolean, val changed: Boolean)

@Resource("/hidden")
data class ProjectHiddenPatchesResource(val parent: ProjectPatchesResource)

@Resource("/hidden")
data class ProjectPatchHiddenResource(val parent: ProjectPatchResource)

@Resource("/{projectId}/hides")
data class ProjectHidesResource(val projectId: String)

@Resource("/{kind}/{uuid}")
data class ProjectHideResource(val parent: ProjectHidesResource, val kind: String, val uuid: String)
