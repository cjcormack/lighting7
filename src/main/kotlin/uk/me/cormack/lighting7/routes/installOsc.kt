package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.osc.OscSettingsRequest
import uk.me.cormack.lighting7.state.State

/**
 * This machine's OSC listener and allowlist (OSC plan D5): `GET` and `PUT /install/osc`, admin only
 * and refused on the public listener, both ways — which LAN machines may drive the show is no remote
 * caller's business. The `PUT` is the whole document and answers it as stored. The live status and
 * the log stream as `osc.state` to every window on the desk's own listener (`plugins/MachineSocket.kt`).
 * See `docs/osc-engineering.md`.
 */
internal fun Route.routeApiRestInstallOsc(state: State) {
    get<InstallOscResource> {
        call.requireAdmin()
        if (call.isRemote) return@get refuseRemoteOsc()
        call.respond(state.osc.settings())
    }

    put<InstallOscResource> {
        call.requireAdmin()
        if (call.isRemote) return@put refuseRemoteOsc()
        val request = call.receive<OscSettingsRequest>()
        val problems = state.osc.problems(request)
        if (problems.isNotEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                OscSettingsRefusal(problems.joinToString("; "), OSC_SETTINGS_INVALID, problems),
            )
            return@put
        }
        call.respond(state.osc.update(request))
    }
}

private suspend fun RoutingContext.refuseRemoteOsc() {
    call.respond(
        HttpStatusCode.Forbidden,
        ErrorResponse("OSC is set up on the desk itself, never remotely.", OSC_SETTINGS_LOCAL_ONLY),
    )
}

@Resource("/install/osc")
data object InstallOscResource

@Serializable
internal data class OscSettingsRefusal(val error: String, val code: String, val problems: List<String>)

internal const val OSC_SETTINGS_LOCAL_ONLY = "OSC_SETTINGS_LOCAL_ONLY"
internal const val OSC_SETTINGS_INVALID = "OSC_SETTINGS_INVALID"
