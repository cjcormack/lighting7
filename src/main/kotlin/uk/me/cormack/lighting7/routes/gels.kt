package uk.me.cormack.lighting7.routes

import io.ktor.server.response.*
import io.ktor.server.routing.*
import uk.me.cormack.lighting7.fixture.media.GelLibrary

/**
 * `GET /gels` — the gel library (fixture optics plan D7): every gel a patch's `gelCode` picker and a
 * unit's fitted media offer, as the desk ships it. Beside `/lanterns`, for its reason: the library
 * is the desk's, not a show's.
 */
internal fun Route.routeApiRestGels() {
    get("/gels") {
        call.respond(GelLibrary.all)
    }
}
