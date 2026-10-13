package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.telemetry.BrowserTunnel
import uk.me.cormack.lighting7.telemetry.TunnelRefusal

/**
 * `POST /api/sentry`, the browser SDK's `tunnel` (Sentry plan D9). Open to every caller on both
 * listeners, signed in or not, and mounted outside the warm-up gate, so the login screen and the
 * boot overlay report too; [BrowserTunnel.accept] decides what is forwarded. Not traced.
 * See `docs/observability-engineering.md` §"The browser".
 */
internal fun Route.routeApiSentryTunnel(state: State) {
    post("/sentry") {
        val declared = call.request.contentLength()
        val refusal = if (declared != null && declared > BrowserTunnel.MAX_ENVELOPE_BYTES) {
            TunnelRefusal.TOO_LARGE
        } else {
            // Bounded read: never buffer more than the cap and one sentinel byte.
            val bytes = call.receiveChannel().readBuffer(BrowserTunnel.MAX_ENVELOPE_BYTES + 1L).readByteArray()
            state.browserTunnel.accept(bytes)
        }
        if (refusal == null) {
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.fromValue(refusal.status), ErrorResponse(refusal.message, refusal.code))
        }
    }
}
