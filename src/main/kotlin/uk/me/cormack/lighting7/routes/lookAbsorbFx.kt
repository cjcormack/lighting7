package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("lookAbsorbFx")

/**
 * Move running programmer-band effects **into** a Look.
 *
 * This is what `+ Effect` does when the programmer's grid is focused on a layer. The effect is
 * authored exactly as it always was — the busking flow, the picker and live editor (the one picker's
 * effects arm), whichever surface the operator reached for, all of which put it in the programmer band — and then this moves it to
 * where the focused scope says it belongs. Session 2a changes where an effect *lands*, not how it
 * is configured, and this route is that seam.
 *
 * **Not folded into `record-look`.** That writes rows *and* effects as one recording, and applying
 * it here would rewrite the Look's values as a side effect of adding a chase to it. Adding an
 * effect to an existing look must touch only its effects.
 *
 * MERGE semantics deliberately: the Look keeps the effects it has and gains these. Replacing them
 * would make "add one effect" silently delete the others, and the operator asked for an addition.
 *
 * The instances are removed from the band once the write commits — the layer applying this Look
 * starts running them, and two copies of one chase beat against each other. Order matters: remove
 * after the commit, or a failed write stops an effect the operator still has.
 */
@Resource("/{projectId}/looks/{lookId}/absorb-fx")
internal class LookAbsorbFxResource(val projectId: String, val lookId: Int)

@Serializable
internal data class LookAbsorbFxRequest(
    /** `FxInstance.id`s, which must be loose programmer-band effects. */
    val fxIds: List<Long> = emptyList(),
)

@Serializable
internal data class LookAbsorbFxResponse(
    val look: LookDetails,
    val absorbed: Int,
)

internal fun Route.routeApiRestLookAbsorbFx(state: State) {
    post<LookAbsorbFxResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<LookAbsorbFxRequest>()
            // Resolved before the transaction: this reads the engine, not the DB. Filtered to the
            // programmer band inside, so a stale client cannot name a cue's effect and tear it out
            // of a running cue.
            val effects = programmerBandFxById(state, request.fxIds)

            // The uuid is taken inside the transaction: `republishForLookEdit` addresses a Look by
            // its portable identity, and re-parsing it out of the DTO would be a round trip through
            // a string for something already in hand.
            val written = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction null
                writeLookFx(look, effects, RecordMode.MERGE)
                look.uuid to look.toDetailsDto(state)
            }
            if (written == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Look not found"))
                return@withCurrentProject
            }
            val (uuid, details) = written

            for (effect in effects) state.show.fxEngine.removeFx(effect.id)
            // The same republish a contents edit takes, so every cue layering this Look picks the
            // effect up without being re-fired — the point of the feature.
            republishForLookEdit(state, uuid)
            logger.info("absorb-fx '{}': {} moved in", details.name, effects.size)
            call.respond(LookAbsorbFxResponse(look = details, absorbed = effects.size))
        }
    }
}
