package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import uk.me.cormack.lighting7.state.State

/**
 * Boot-status endpoint. Returns the current [uk.me.cormack.lighting7.state.BootStatus] so a
 * client can render a loading bar during the server-first warm-up window and know when the show
 * is ready. Deliberately exempt from the readiness gate installed in [configureRouting] — it must
 * answer even before the show exists.
 */
internal fun Route.routeApiRestStatus(state: State) {
    get<BootStatusResource> {
        call.respond(state.bootProgress.current)
    }
}

@Resource("/status")
data object BootStatusResource
