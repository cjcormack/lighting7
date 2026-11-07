package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.osc.DbOscLookup
import uk.me.cormack.lighting7.osc.OscAddresses
import uk.me.cormack.lighting7.state.State

/**
 * `GET /projects/{id}/osc/addresses` (OSC plan session 2): every OSC address the project answers to,
 * made from the address table and answered by its resolver (P2), so the listing says exactly what a
 * message would do. Any project, both roles: it is what *Copy OSC address* and the cheat sheet read.
 * Never on the public listener (D5): OSC is the show network's, and 403 `OSC_LOCAL_ONLY` there.
 */
internal fun Route.routeApiRestProjectOsc(state: State) {
    get<ProjectOscAddressesResource> { resource ->
        if (call.isRemote) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("OSC addresses are read on the desk itself, never remotely.", OSC_LOCAL_ONLY))
            return@get
        }
        withProject(state, resource.projectId) { project ->
            call.respond(transaction(state.database) { OscAddresses.list(DbOscLookup(project.id.value)) })
        }
    }
}

internal const val OSC_LOCAL_ONLY = "OSC_LOCAL_ONLY"

@Resource("/{projectId}/osc/addresses")
data class ProjectOscAddressesResource(val projectId: String)
