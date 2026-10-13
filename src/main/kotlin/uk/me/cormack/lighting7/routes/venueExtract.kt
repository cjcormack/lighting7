package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.VenueExtract
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind

/**
 * `POST /projects/{id}/venue/extract` `{name, include?, dryRun?}` — *Extract a venue* (venue plan
 * §3.5): move a show's room and house rig into a new venue, publish v1 and pin the show to it, in one
 * transaction (`venue/VenueExtract.kt`). **Admin only** (D3): it makes a venue. `include` names the
 * parts (`UNIVERSES | PATCHES | RIGGINGS | REGIONS | ELEMENTS | SEATING | GROUPS | VIEWPOINTS`),
 * absent for all of them; `dryRun` answers what the move would do and keeps nothing. A live show
 * reloads onto its pinned rig — the same fixtures at the same addresses. REST only, no socket
 * operation; the Projects page's *Extract a venue…* sheet reads its dry run as the preview (session 7).
 */
internal fun Route.routeApiRestVenueExtract(state: State) {
    post<ProjectVenueExtractResource> { resource ->
        call.requireAdmin()
        val request = call.receive<ExtractVenueRequest>()
        val include = request.include?.toSet() ?: ExtractKind.entries.toSet()
        val result = state.venueExtract.extract(
            showId = resource.id,
            name = request.name,
            include = include,
            dryRun = request.dryRun,
            publishedBy = call.authenticatedUserOrNull?.displayName,
        )
        if (!result.dryRun) {
            val current = transaction(state.database) { state.projectManager.currentProject.id.value }
            if (current == resource.id) state.projectManager.switchProject(resource.id, reload = true)
            state.show.fixtures.projectDetailsChanged(resource.id)
            result.venueId?.let { state.show.fixtures.projectDetailsChanged(it) }
        }
        call.respond(if (result.dryRun) HttpStatusCode.OK else HttpStatusCode.Created, result)
    }
}

@Serializable
data class ExtractVenueRequest(
    val name: String,
    /** The parts to move; null for all of them (venue plan §3.5's default set). */
    val include: List<ExtractKind>? = null,
    val dryRun: Boolean = false,
)

@Resource("/{id}/venue/extract")
data class ProjectVenueExtractResource(val id: Int)
