package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import uk.me.cormack.lighting7.auth.remoteSessionOrNull
import uk.me.cormack.lighting7.cloud.RemoteSessionEnd
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.state.State

/**
 * A remote session's own routes (cloud-p1 plan D5), reached through the sidecar with the session's
 * bearer token and open to an unlinked session too (D4):
 *
 * - `POST /cloud/session/token`: the sidecar hands over a refreshed token as this request's bearer.
 *   The gate has already judged it (same session, account and org); it must also expire later than
 *   any before it, or the session ends. 204.
 * - `DELETE /cloud/session`: end this session. 204.
 *
 * Anything but a remote session is 403 `REMOTE_SESSION_UNKNOWN`.
 */
internal fun Route.routeApiRestCloudSession(state: State) {
    post<CloudSessionResource.Token> {
        val check = call.remoteSessionOrNull(state) ?: return@post notARemoteSession()
        state.cloud.remoteSessions.refresh(check)?.let { refused ->
            return@post call.respond(refused.status, ErrorResponse(refused.message, refused.code))
        }
        call.respond(HttpStatusCode.NoContent)
    }

    delete<CloudSessionResource> {
        val session = call.remoteSessionOrNull(state)?.sessionOrNull ?: return@delete notARemoteSession()
        state.cloud.remoteSessions.end(session.sid, RemoteSessionEnd.ENDED)
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.notARemoteSession() {
    call.respond(HttpStatusCode.Forbidden, ErrorResponse("This is not a remote session.", ErrorCode.REMOTE_SESSION_UNKNOWN))
}

@Resource("/cloud/session")
data object CloudSessionResource {
    @Resource("token")
    data class Token(val parent: CloudSessionResource = CloudSessionResource)
}
