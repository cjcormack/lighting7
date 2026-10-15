package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.GRAND_MASTER_FULL
import uk.me.cormack.lighting7.state.State

/**
 * The grand master over REST (venue-visit plan S5, D5): the live project's level, 0–100 %.
 *
 * - `GET /projects/{id}/grand-master` — answers `{level}`.
 * - `PUT /projects/{id}/grand-master` `{level}` — sets it; 400 `GRAND_MASTER_INVALID` outside
 *   0–100. Answers the level as set.
 *
 * The current project's only, like the desk's arm: the level scales what the live show sends. Both
 * roles, and a runtime gesture on a venue (`projectWriteGuard.kt`). The level persists per project
 * on this machine (`project_scaler_states`) and never syncs. `grandMaster.state` streams it and
 * `grandMaster.set` sets it at frame rate (`plugins/GrandMasterSocket.kt`).
 */
internal fun Route.routeApiRestProjectGrandMaster(state: State) {
    get<GrandMasterResource> { resource ->
        withCurrentProject(state, resource.projectId, "Only the current project's grand master can be read") { _ ->
            call.respond(GrandMasterDto(state.show.globalScalerState.grandMasterLevel.value))
        }
    }

    put<GrandMasterResource> { resource ->
        withCurrentProject(state, resource.projectId, "Only the current project's grand master can be set") { _ ->
            val request = call.receive<GrandMasterDto>()
            if (!request.level.isFinite() || request.level !in 0.0..GRAND_MASTER_FULL) {
                return@withCurrentProject call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("level must be a number from 0 to 100", CODE_GRAND_MASTER_INVALID),
                )
            }
            val scaler = state.show.globalScalerState
            scaler.setGrandMasterLevel(request.level)
            call.respond(GrandMasterDto(scaler.grandMasterLevel.value))
        }
    }
}

internal const val CODE_GRAND_MASTER_INVALID = "GRAND_MASTER_INVALID"

/** The grand master, 0–100 %. */
@Serializable
data class GrandMasterDto(val level: Double)

@Resource("/{projectId}/grand-master")
internal data class GrandMasterResource(val projectId: String)
