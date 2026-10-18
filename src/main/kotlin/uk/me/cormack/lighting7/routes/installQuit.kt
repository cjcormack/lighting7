package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.state.State

/**
 * *Quit desk* (venue-visit plan S6, D9, D13): `POST /install/quit` `{force?}` stops the desk process
 * cleanly, a moment after answering 202.
 *
 * - **Admin only, and never remote**: 403 [CODE_QUIT_LOCAL_ONLY] on the public listener, whatever
 *   the role — a tunnel must not be able to switch the desk off.
 * - **It warns when the rig is not marked safe**: 409 [CODE_RIG_NOT_SAFE] unless *Ready to switch
 *   off* holds, the rig is made safe and only cooling (quitting cuts no fixture's mains), or the
 *   caller sends `force`, which the confirm sends once the operator has read why.
 *
 * Making the rig safe and quitting are separate (D9): this never lamps off or blacks out.
 */
internal fun Route.routeApiRestInstallQuit(state: State) {
    post<InstallQuitResource> {
        call.requireAdmin()
        if (call.isRemote) {
            return@post call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("The desk can only be quit from the desk itself, never over remote access", CODE_QUIT_LOCAL_ONLY),
            )
        }
        val request = call.receive<QuitDeskRequest>()
        val ready = state.rigSafety.snapshot.value.let { it.ready || it.coolingUntilMs != null }
        if (!ready && !request.force) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("The rig is not marked safe to switch off — make it safe first, or quit anyway", CODE_RIG_NOT_SAFE),
            )
        }
        call.respond(HttpStatusCode.Accepted, QuitDeskResponse(rigWasSafe = ready))
        state.quitDesk()
    }
}

internal const val CODE_QUIT_LOCAL_ONLY = "QUIT_LOCAL_ONLY"
internal const val CODE_RIG_NOT_SAFE = "RIG_NOT_SAFE"

@Serializable
data class QuitDeskRequest(val force: Boolean = false)

@Serializable
data class QuitDeskResponse(val rigWasSafe: Boolean)

@Resource("/install/quit")
data object InstallQuitResource
