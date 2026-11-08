package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.AuthenticationException
import uk.me.cormack.lighting7.auth.Caller
import uk.me.cormack.lighting7.auth.authenticatedUser
import uk.me.cormack.lighting7.auth.resolveCaller
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.CloudException
import uk.me.cormack.lighting7.cloud.CloudInviteDto
import uk.me.cormack.lighting7.cloud.CloudLinkDto
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.models.CloudUnlinkReason
import uk.me.cormack.lighting7.state.State

/**
 * The caller's own cloud link (cloud-p1 plan D12):
 *
 * - `GET /cloud/link`: the caller's link and live invite, each null when there is none. Any role.
 * - `DELETE /cloud/link`: unlink the caller's own account; its remote sessions end, this one too
 *   when it is one. 204, or 404 `CLOUD_NO_LINK`.
 * - `POST /cloud/link/prove` `{username, password}`: *prove once*. Only an unlinked remote session
 *   may, and the gate lets one reach it (`auth/AuthGate.kt`); the password is checked as the sign-in
 *   form checks it, under the public listener's per-username lockout. The session's account is then
 *   linked to that user and the session carries on as them: answers `auth/status`'s shape.
 */
internal fun Route.routeApiRestCloudLink(state: State) {
    get<CloudLinkResource> {
        val user = call.authenticatedUser
        val standing = state.cloud.links.forUser(user.userId)
        call.respond(OwnCloudLinkDto(standing.link, standing.invite))
    }

    delete<CloudLinkResource> {
        val user = call.authenticatedUser
        val record = state.authService.findUser(user.userId)
        if (!state.cloud.links.unlink(user.userId, CloudUnlinkReason.BY_USER, by = record)) {
            return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Your account isn't linked to a cloud account.", CloudErrorCodes.NO_LINK))
        }
        call.respond(HttpStatusCode.NoContent)
    }

    post<CloudLinkResource.Prove> {
        val session = when (val caller = call.resolveCaller(state)) {
            is Caller.Unlinked -> caller.bearer.session
            is Caller.SignedIn -> return@post refuse(
                HttpStatusCode.Conflict,
                if (caller.bearer != null) CloudErrorCodes.LINK_EXISTS else CloudErrorCodes.NOT_UNLINKED,
                "This is not an unlinked remote session.",
            )
            else -> return@post refuse(HttpStatusCode.Forbidden, ErrorCode.REMOTE_SESSION_UNKNOWN, "This is not a remote session.")
        }
        val request = call.receive<ProveCloudLinkRequest>()
        if (state.authService.remoteSignInLockedOut(request.username)) {
            return@post refuse(HttpStatusCode.TooManyRequests, "SIGN_IN_LOCKED", "Too many failed sign-ins. Try again later.")
        }
        val user = try {
            state.authService.verifyCredentials(request.username, request.password)
        } catch (e: AuthenticationException) {
            state.authService.recordRemoteSignInFailure(request.username)
            throw e
        }
        state.authService.clearRemoteSignInFailures(request.username)
        try {
            state.cloud.links.prove(session, user)
        } catch (e: CloudException) {
            return@post refuse(e.status, e.code, e.message ?: e.code)
        }
        call.respond(AuthStatusDto(setupRequired = false, authenticated = true, user = user.toAuthDto()))
    }
}

private suspend fun RoutingContext.refuse(status: HttpStatusCode, code: String, message: String) {
    call.respond(status, ErrorResponse(message, code))
}

@Resource("/cloud/link")
data object CloudLinkResource {
    @Resource("prove")
    data class Prove(val parent: CloudLinkResource = CloudLinkResource)
}

@Serializable
data class ProveCloudLinkRequest(val username: String, val password: String)

/** The caller's own link and live invite. */
@Serializable
data class OwnCloudLinkDto(val link: CloudLinkDto? = null, val invite: CloudInviteDto? = null)
