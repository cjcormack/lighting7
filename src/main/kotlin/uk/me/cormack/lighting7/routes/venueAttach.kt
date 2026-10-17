package uk.me.cormack.lighting7.routes

import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.VenueAttach
import uk.me.cormack.lighting7.venue.VenueAttachPairing
import uk.me.cormack.lighting7.venue.VenueAttachRequest
import uk.me.cormack.lighting7.venue.VenueException

/**
 * *Attach to venue…* (venue-attach plan §4.1, D4):
 *
 * - `GET /projects/{id}/venue/attach?venue={venueId}&to={n}` — the **review**: what attaching the show
 *   to that venue at `n` (the installed version when absent) would do — what it inherits, overrides,
 *   keeps, hides, releases and brings in, what it renames, the choices it offers, the address overlaps
 *   it introduces (P8), its blockers and every key left pointing at nothing. Nothing is written: it is
 *   the attach rolled back (`venue/VenueAttach.kt`).
 * - `POST /projects/{id}/venue/attach` `{venueId, to?, decisions, dryRun?}` — the **attach**, in one
 *   transaction. 409 `NOT_A_STANDALONE_SHOW` for a venue or a show already on one, `VENUE_NOTHING_PUBLISHED`
 *   before the venue's v1, `ATTACH_UNDECIDED` while an overlap is unanswered, `ATTACH_BLOCKED` on a
 *   blocker; 404 `VENUE_VERSION_UNKNOWN`; 400 `ATTACH_INVALID` for an answer that is not one. A live show
 *   reloads, and one attached behind on output runs against its pin rather than going dark (§4.4).
 *
 * **Both roles**, as Detach and Update: an attach changes only the show — its own rows, hides,
 * overrides, releases and pin. A venue (`VENUE_ADMIN_ONLY` for an operator's write) and a version
 * (`VENUE_VERSION_READ_ONLY`) are refused before this runs (`projectWriteGuard.kt`). REST only.
 */
private val logger = LoggerFactory.getLogger("VenueAttachRoutes")

internal fun Route.routeApiRestVenueAttach(state: State) {
    get<ProjectVenueAttachResource> { resource ->
        withProject(state, resource.id.toString()) { project ->
            val venueId = resource.venue
                ?: throw VenueException.badRequest(VenueAttachPairing.CODE_ATTACH_INVALID, "venue is required: the venue to attach to")
            call.respond(state.venueAttach.review(project.id.value, venueId, resource.to))
        }
    }

    post<ProjectVenueAttachResource> { resource ->
        withProject(state, resource.id.toString()) { project ->
            val request = call.receive<VenueAttachRequest>()
            val result = state.venueAttach.attach(project.id.value, request.venueId, request.to ?: resource.to, request.decisions, request.dryRun)
            if (!result.review.dryRun) afterAttach(state, result)
            call.respond(result.review)
        }
    }
}

/**
 * The live desk's side of an attach that was kept: a current show reloads onto its venue — run against
 * its pin first when the attach left it behind on output (§4.4) — and every list the attach changed is
 * announced, as Detach's are. Committed by now, so a reload that fails is logged, not answered as a
 * failed attach.
 */
internal suspend fun afterAttach(state: State, result: VenueAttach.Result) {
    val review = result.review
    val projectId = review.projectId
    val current = transaction(state.database) { state.projectManager.currentProject.id.value }
    if (current == projectId) {
        if (review.runAgainst) state.venueHold.runAgainstOnLoad(projectId, review.to, review.installed)
        runCatching { state.projectManager.switchProject(projectId, reload = true) }
            .onFailure { logger.error("Project {} was attached but did not reload: {}", projectId, it.message, it) }
    }
    val fixtures = state.show.fixtures
    fixtures.projectDetailsChanged(projectId)
    fixtures.projectDetailsChanged(review.venueId)
    fixtures.patchListChanged()
    fixtures.stageElementListChanged()
    fixtures.stageViewpointListChanged()
    fixtures.buskRigChanged()
    // A paired or kept scene element's uuid changes, and every owner's scenery names elements by uuid.
    sceneryOwnersChanged(state)
    val touched = result.touched
    if (touched.looks.isNotEmpty()) fixtures.lookListChanged()
    if (touched.templates.isNotEmpty()) fixtures.templateListChanged()
    if (touched.cues.isNotEmpty()) fixtures.cuesRecomposed(touched.cues.toList())
    if (touched.bindings > 0) state.controlSurfaceBindingService.invalidate(projectId)
}

@Resource("/{id}/venue/attach")
data class ProjectVenueAttachResource(val id: Int, val venue: Int? = null, val to: Int? = null)
