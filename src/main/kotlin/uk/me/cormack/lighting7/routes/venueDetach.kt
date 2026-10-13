package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.ReplaceSeatingOutcome
import uk.me.cormack.lighting7.venue.SeatingPresets

/**
 * A show leaving its venue, and a show's stalls (venue plan §3.5, session 7):
 *
 * - `POST /projects/{id}/detach` `{dryRun?}` — *Detach* (D9): the venue's rig at the show's pin, as the
 *   show sees it, written into the show's own rows — overrides applied, hidden records left out, links
 *   re-pointed, the venue's machine overrides copied, the pin cleared — in one transaction
 *   (`venue/VenueDetach.kt`). `dryRun` answers the same run rolled back. 409 `NOT_A_VENUE_SHOW` for a
 *   project that is not a show on a venue, `VENUE_MISSING` for one whose pinned version this desk
 *   lacks. A live show reloads, which drops its hold: a standalone show is never behind.
 * - `GET /projects/{id}/seating/presets` — the Preset seating a venue offers at its installed version
 *   (*Create*'s stalls preset), or a show's pinned version (*Replace from preset*), and a show's own
 *   seating as it stands. 409 `NOT_A_VENUE` for a standalone show.
 * - `POST /projects/{id}/seating/from-preset` `{elementUuid, force?}` — *Replace from preset* (D6): the
 *   show's seating replaced by a fresh copy of that preset. 400 `SEATING_PRESET_UNKNOWN`; 409
 *   `STAGE_ELEMENT_IN_USE` naming the show's seat views whose seat the copy lacks, which `force` leaves
 *   without one.
 *
 * **Both roles** for Detach and Replace: each changes only the show — its own rows and its pin — which
 * is a stored show write an operator makes (as Update, a hide and a facet override are); the venue and
 * its versions are not touched. A venue (`VENUE_ADMIN_ONLY` for an operator) and a version
 * (`VENUE_VERSION_READ_ONLY`) are refused before these run (`projectWriteGuard.kt`). REST only.
 */
private val logger = LoggerFactory.getLogger("VenueDetachRoutes")

internal fun Route.routeApiRestVenueDetach(state: State) {
    post<ProjectDetachResource> { resource ->
        withProject(state, resource.id.toString()) { project ->
            val request = call.receive<DetachRequest>()
            val projectId = project.id.value
            val result = state.venueDetach.detach(projectId, request.dryRun)
            if (!result.dryRun) {
                val current = transaction(state.database) { state.projectManager.currentProject.id.value }
                // The show's rig is its own rows now: the live show reloads onto them, which judges its
                // hold afresh (none — a standalone show is never behind). Committed by now, so a reload
                // that fails is logged, not answered as a failed detach.
                if (current == projectId) {
                    runCatching { state.projectManager.switchProject(projectId, reload = true) }
                        .onFailure { logger.error("Project {} was detached but did not reload: {}", projectId, it.message, it) }
                }
                state.show.fixtures.projectDetailsChanged(projectId)
                state.show.fixtures.projectDetailsChanged(result.venueId)
                state.show.fixtures.patchListChanged()
                state.show.fixtures.stageElementListChanged()
                state.show.fixtures.stageViewpointListChanged()
                state.show.fixtures.buskRigChanged()
                state.show.fixtures.cueListChanged()
            }
            call.respond(result)
        }
    }

    get<ProjectSeatingPresetsResource> { resource ->
        withProject(state, resource.id.toString()) { project ->
            call.respond(transaction(state.database) { SeatingPresets.list(project) })
        }
    }

    post<ProjectSeatingFromPresetResource> { resource ->
        withProject(state, resource.id.toString()) { project ->
            val request = call.receive<ReplaceSeatingRequest>()
            val outcome = transaction(state.database) { SeatingPresets.replace(state, project, request.elementUuid, request.force) }
            when (outcome) {
                is ReplaceSeatingOutcome.InUse -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        "Seat views sit where the preset has no seat: ${outcome.views.joinToString { "'$it'" }}. " +
                            "Send force: true to go ahead and leave them without a seat.",
                        code = CODE_STAGE_ELEMENT_IN_USE,
                    ),
                )
                is ReplaceSeatingOutcome.Replaced -> {
                    state.show.fixtures.stageElementListChanged()
                    if (outcome.result.viewsReseated + outcome.result.viewsWithoutSeat.size > 0) {
                        state.show.fixtures.stageViewpointListChanged()
                    }
                    if (outcome.result.sceneryMoved + outcome.result.scenerySwept > 0) sceneryOwnersChanged(state)
                    call.respond(outcome.result)
                }
            }
        }
    }
}

@Serializable
data class DetachRequest(val dryRun: Boolean = false)

@Serializable
data class ReplaceSeatingRequest(
    /** The preset's uuid, as `GET …/seating/presets` lists it. */
    val elementUuid: String,
    /** Go ahead when seat views would be left without a seat. */
    val force: Boolean = false,
)

@Resource("/{id}/detach")
data class ProjectDetachResource(val id: Int)

@Resource("/{id}/seating/presets")
data class ProjectSeatingPresetsResource(val id: Int)

@Resource("/{id}/seating/from-preset")
data class ProjectSeatingFromPresetResource(val id: Int)
