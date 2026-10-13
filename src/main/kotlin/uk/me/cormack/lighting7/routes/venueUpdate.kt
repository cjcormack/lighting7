package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.mcp.requireEffectsAccess
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.VenueRunAgainstRequest
import uk.me.cormack.lighting7.venue.VenueUpdateRequest

/**
 * A show that is behind its venue (venue plan §3.5, session 6):
 *
 * - `GET /projects/{id}/venue/update?to=n` — the **review**: what changed in the hall from the show's
 *   version to `n` (the installed one when absent), and what that does to the show — carried over,
 *   kept under drift, dropped, the clashes that need a decision (P8), the blockers, and every key that
 *   will point at nothing. Nothing is written: it is the update rolled back (`venue/VenueUpdate.kt`).
 * - `POST /projects/{id}/venue/update` `{to?, decisions, dryRun?}` — the **update**, in one
 *   transaction: the pin moves, every link is re-pointed through `VenueLinks`, links to removed
 *   records go. 409 `VENUE_UPDATE_UNDECIDED` while a decision is open, `VENUE_UPDATE_BLOCKED` on a
 *   clash that cannot be kept, `VENUE_UP_TO_DATE` when `to` is not newer; 400 `VENUE_UPDATE_INVALID`
 *   for an answer that is not one. A live show reloads, which lifts its hold.
 * - `POST /projects/{id}/venue/run-against` `{on}` — lift the hold for this session (`on`), or put it
 *   back. The current project only; 409 `VENUE_NOT_BEHIND` when there is no hold to lift. Answers the
 *   hold as `venue.hold` carries it. Lifting it from the public listener is refused unless an admin
 *   allows remote arming (`requireEffectsAccess`, 403 `REMOTE_EFFECTS_DISABLED`): it puts an
 *   out-of-date patch on the rig, which is arming's weight.
 *
 * **Both roles.** An update is a stored write to the show — its pin and its own links — like hiding a
 * venue fixture or overriding a facet, which operators make; editing and publishing the venue stays
 * an admin's. *Run against* is a runtime gesture, like arming: whoever is at the desk tonight is who
 * decides to run the old patch. A venue (`VENUE_ADMIN_ONLY` for an operator's write) and a version
 * (`VENUE_VERSION_READ_ONLY`) are refused before this runs (`projectWriteGuard.kt`); either is
 * `NOT_A_VENUE_SHOW` here. REST only, so the socket gains no operation.
 */
private val logger = LoggerFactory.getLogger("VenueUpdateRoutes")

internal fun Route.routeApiRestVenueUpdate(state: State) {
    get<ProjectVenueUpdateResource> { resource ->
        withProject(state, resource.id.toString()) { project ->
            call.respond(state.venueUpdate.review(project.id.value, resource.to))
        }
    }

    post<ProjectVenueUpdateResource> { resource ->
        withProject(state, resource.id.toString()) { project ->
            val request = call.receive<VenueUpdateRequest>()
            val projectId = project.id.value
            val result = state.venueUpdate.update(projectId, request.to ?: resource.to, request.decisions, request.dryRun)
            if (!result.dryRun) {
                val current = transaction(state.database) { state.projectManager.currentProject.id.value }
                // Every link moved to the new version's rows: the live show reloads onto them, which
                // judges its hold afresh (lifted, now it is on the version it was behind). The update
                // is committed by now, so a reload that fails is logged, not answered as a failed update.
                if (current == projectId) {
                    runCatching { state.projectManager.switchProject(projectId, reload = true) }
                        .onFailure { logger.error("Project {} was updated but did not reload: {}", projectId, it.message, it) }
                }
                state.show.fixtures.projectDetailsChanged(projectId)
                state.show.fixtures.patchListChanged()
                state.show.fixtures.cueListChanged()
                state.show.fixtures.buskRigChanged()
            }
            call.respond(result)
        }
    }

    post<ProjectVenueRunAgainstResource> { resource ->
        withCurrentProject(state, resource.id.toString(), "Only the current project's hold can be lifted") { project ->
            val request = call.receive<VenueRunAgainstRequest>()
            // Lifting the hold puts an out-of-date patch on the rig: from the public listener it is held
            // to arming's line (`REMOTE_EFFECTS_DISABLED` unless an admin allows it). Putting the hold
            // back never is.
            if (request.on) call.requireEffectsAccess(state)
            val status = withContext(Dispatchers.IO) { state.venueHold.setRunAgainst(project.id.value, request.on) }
            call.respond(
                HttpStatusCode.OK,
                VenueHoldDto(status.projectId, status.held, status.pinned, status.installed, status.runAgainst),
            )
        }
    }
}

@Resource("/{id}/venue/update")
data class ProjectVenueUpdateResource(val id: Int, val to: Int? = null)

@Resource("/{id}/venue/run-against")
data class ProjectVenueRunAgainstResource(val id: Int)

/** The hold as `venue.hold` carries it (`plugins/VenueSocket.kt`). */
@Serializable
data class VenueHoldDto(
    val projectId: Int? = null,
    val held: Boolean = false,
    val pinned: Int? = null,
    val installed: Int? = null,
    val runAgainst: Boolean = false,
)
