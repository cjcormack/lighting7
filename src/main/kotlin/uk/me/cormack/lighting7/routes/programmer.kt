package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.State

/**
 * REST escape hatch for the programmer: `POST /api/rest/programmer/clear-all` releases
 * every programmer entry (all owners, property entries and the channel sideband) back to
 * the layers below. This exists so an operator can always recover from stuck manual values
 * even before the programmer UI (Session 2) ships — the WS `programmer.clearAll` op is the
 * same operation.
 */
internal fun Route.routeApiRestProgrammer(state: State) {
    route("/programmer") {
        post<ProgrammerClearAllResource> {
            val request = try {
                call.receive<ProgrammerClearAllRequest>()
            } catch (_: Exception) {
                ProgrammerClearAllRequest()
            }
            val cleared = clearProgrammerCompletely(state, request.fadeMs ?: 0)
            call.respond(ProgrammerClearAllResponse(cleared))
        }
    }
}

/**
 * The full programmer clear: reset the toggle bookkeeping that tracks programmer entries
 * (locate targets, preset toggles and previews) *first*, then sweep and republish the store
 * in one pass. Order matters — the store sweep releases every owner's entries, so running
 * the subsystems' own release paths afterwards would double-release, and leaving their
 * bookkeeping would desync the toggles.
 *
 * Returns the number of entries removed.
 */
internal fun clearProgrammerCompletely(state: State, fadeMs: Long = 0): Int {
    state.show.locateManager.reset()
    resetPresetProgrammerBookkeeping()
    return state.show.fxEngine.clearProgrammerAll(fadeMs)
}

@Resource("/clear-all")
private class ProgrammerClearAllResource

@Serializable
internal data class ProgrammerClearAllRequest(val fadeMs: Long? = null)

@Serializable
internal data class ProgrammerClearAllResponse(val cleared: Int)
