package uk.me.cormack.lighting7.routes

import io.ktor.server.response.*
import io.ktor.server.routing.*
import uk.me.cormack.lighting7.fixture.lantern.LanternLibrary

/**
 * `GET /lanterns` — the lantern library (stage-view plan session 7): every conventional a generic
 * dimmer can be hung with, as the desk ships it. A vocabulary enumeration like `/fixture-types`,
 * so it sits beside it rather than under a project: the library is the desk's, not a show's (D8).
 */
internal fun Route.routeApiRestLanterns() {
    get("/lanterns") {
        call.respond(LanternLibrary.all)
    }
}
