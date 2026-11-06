package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.authenticatedUser
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.cloud.AllowResult
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.CloudException
import uk.me.cormack.lighting7.cloud.CloudStateDto
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.state.State

/**
 * Install settings → Cloud (cloud-p0 plan D14): this desk's adoption by Lighting7 Cloud. Mounted
 * in `adminOnly`, machine-scoped. Every answer is the state `cloud.state` streams, which on the
 * public listener leaves out a pending adoption's code and links.
 *
 * - `GET /install/cloud`: the state.
 * - `POST /install/cloud/adoption`: start adopting, **only from the desk's own listener** (D5):
 *   403 [CloudErrorCodes.ADOPT_LOCAL_ONLY] on the public one whatever the role, as *Quit desk* is,
 *   and 409 [CloudErrorCodes.NEEDS_ACCOUNT] on a desk with no accounts, since the adoption links
 *   the admin who starts it.
 * - `DELETE /install/cloud/adoption`: cancel a pending adoption.
 * - `DELETE /install/cloud[?force=true]`: unadopt (D8), from either listener: it only reduces reach.
 * - `GET /install/cloud/remote`: what may come through the cloud (cloud-p1 plan D6), and the
 *   scripts, effects and commands switches every remote request answers to.
 * - `PUT /install/cloud/remote`: change it, **only from the desk's own listener**: 403
 *   [CloudErrorCodes.SETTINGS_LOCAL_ONLY] on the public one, since a compromised cloud can mint a
 *   linked admin's token, so nothing it can sign may widen what comes through it (D-17). 409
 *   [CloudErrorCodes.NOT_ADOPTED] for a cloud switch before adoption; the other three need none.
 * - `GET /install/cloud/sessions`: the open remote sessions and the latest ended ones.
 * - `DELETE /install/cloud/sessions/{sid}`: end an open one, from either listener: it only reduces reach.
 * - `POST /install/cloud/sessions/{sid}/allow` and `…/deny`: answer a session waiting for an admin
 *   (D13), **only from the desk's own listener**: 403 [CloudErrorCodes.APPROVAL_LOCAL_ONLY] on the
 *   public one. 404 [CloudErrorCodes.NO_SESSION] when it is not waiting, or not open for the end; 409
 *   [CloudErrorCodes.SESSION_LINK_CHANGED] for an *Allow* when the desk user the account links to has
 *   changed since the offer was shown, which leaves it waiting, shown afresh.
 */
internal fun Route.routeApiRestInstallCloud(state: State) {
    get<InstallCloudResource> {
        call.respond(state.cloud.current().forCaller(call.isRemote))
    }

    post<InstallCloudResource.Adoption> {
        if (call.isRemote) {
            return@post refuse(
                HttpStatusCode.Forbidden,
                CloudErrorCodes.ADOPT_LOCAL_ONLY,
                "A desk can only be adopted from the desk itself, never over remote access.",
            )
        }
        if (!state.authService.hasAnyUser) {
            return@post refuse(
                HttpStatusCode.Conflict,
                CloudErrorCodes.NEEDS_ACCOUNT,
                "Create a desk account before adopting this desk: the adoption links it to your cloud account.",
            )
        }
        val userId = call.authenticatedUser.userId
        cloudCall(state) { state.cloud.startAdoption(userId) }
    }

    delete<InstallCloudResource.Adoption> {
        cloudCall(state) { state.cloud.cancelAdoption() }
    }

    delete<InstallCloudResource> { resource ->
        cloudCall(state) { state.cloud.unadopt(resource.force) }
    }

    get<InstallCloudResource.Remote> {
        state.cloud.current()
        call.respond(remoteAccessOf(state))
    }

    put<InstallCloudResource.Remote> {
        if (call.isRemote) return@put refuseRemoteSettings()
        val request = call.receive<UpdateRemoteSwitchesRequest>()
        try {
            if (request.remoteAccess != null || request.mcpAccess != null || request.approveSessions != null) {
                state.cloud.setSwitches(
                    remoteAccess = request.remoteAccess,
                    mcpAccess = request.mcpAccess,
                    approveSessions = request.approveSessions,
                )
            }
            if (request.allowScripts != null || request.allowEffects != null || request.allowCommands != null) {
                state.remoteAccess.update(
                    allowScripts = request.allowScripts,
                    allowEffects = request.allowEffects,
                    allowCommands = request.allowCommands,
                    hasAnyUser = state.authService.hasAnyUser,
                )
            }
        } catch (e: CloudException) {
            return@put refuse(e.status, e.code, e.message ?: e.code)
        }
        call.respond(remoteAccessOf(state))
    }

    get<InstallCloudResource.Sessions> {
        call.respond(state.cloud.remoteSessions.listing())
    }

    delete<InstallCloudResource.Sessions.Session> { resource ->
        if (!state.cloud.endSession(resource.sid, call.actorName)) {
            return@delete refuse(HttpStatusCode.NotFound, CloudErrorCodes.NO_SESSION, "That remote session isn't open.")
        }
        call.respond(state.cloud.remoteSessions.listing())
    }

    post<InstallCloudResource.Sessions.Session.Allow> { resource ->
        if (call.isRemote) return@post refuseRemoteApproval()
        when (val result = state.cloud.allowSession(resource.parent.sid, call.actorName)) {
            AllowResult.Allowed -> call.respond(HttpStatusCode.NoContent)
            AllowResult.NotWaiting -> refuseNotWaiting()
            is AllowResult.LinkChanged -> refuse(
                HttpStatusCode.Conflict,
                CloudErrorCodes.SESSION_LINK_CHANGED,
                "That cloud account's link changed while it waited: it would now open " +
                    (result.linkedUser?.let { "as $it" } ?: "with no desk account linked") + ". Allow it again if that is right.",
            )
        }
    }

    post<InstallCloudResource.Sessions.Session.Deny> { resource ->
        if (call.isRemote) return@post refuseRemoteApproval()
        if (!state.cloud.denySession(resource.parent.sid, call.actorName)) return@post refuseNotWaiting()
        call.respond(HttpStatusCode.NoContent)
    }
}

/** D13: an approval is an admin's at the desk itself, never anything the cloud can sign for. */
private suspend fun RoutingContext.refuseRemoteApproval() = refuse(
    HttpStatusCode.Forbidden,
    CloudErrorCodes.APPROVAL_LOCAL_ONLY,
    "A remote session can only be allowed or denied at the desk itself.",
)

private suspend fun RoutingContext.refuseNotWaiting() =
    refuse(HttpStatusCode.NotFound, CloudErrorCodes.NO_SESSION, "That remote session is no longer waiting.")

private fun remoteAccessOf(state: State): RemoteAccessDto {
    val switches = state.cloud.switches
    return RemoteAccessDto(
        remoteAccess = switches.remoteAccess,
        mcpAccess = switches.mcpAccess,
        approveSessions = switches.approveSessions,
        allowScripts = state.remoteAccess.allowRemoteScripts,
        allowEffects = state.remoteAccess.allowRemoteEffects,
        allowCommands = state.remoteAccess.allowRemoteCommands,
    )
}

/**
 * The Cloud tab's *Remote access* section: the three switches for what comes through the cloud
 * (D6, D13), and the three every remote request answers to, through the cloud or ngrok alike.
 */
@Serializable
data class RemoteAccessDto(
    val remoteAccess: Boolean,
    val mcpAccess: Boolean,
    val approveSessions: Boolean,
    val allowScripts: Boolean,
    val allowEffects: Boolean,
    val allowCommands: Boolean,
)

/** 403 for a setting that limits what comes through the cloud, asked from the public listener (cloud-p1 plan D6). */
internal suspend fun RoutingContext.refuseRemoteSettings() {
    call.respond(
        HttpStatusCode.Forbidden,
        ErrorResponse(
            "This setting limits what can reach the desk remotely, so it can only be changed on the desk itself.",
            CloudErrorCodes.SETTINGS_LOCAL_ONLY,
        ),
    )
}

/** Every field optional: absent leaves it as it is. */
@Serializable
data class UpdateRemoteSwitchesRequest(
    val remoteAccess: Boolean? = null,
    val mcpAccess: Boolean? = null,
    val approveSessions: Boolean? = null,
    val allowScripts: Boolean? = null,
    val allowEffects: Boolean? = null,
    val allowCommands: Boolean? = null,
)

private suspend fun RoutingContext.cloudCall(state: State, action: suspend () -> Unit) {
    try {
        action()
    } catch (e: CloudException) {
        return refuse(e.status, e.code, e.message ?: e.code)
    }
    call.respond(state.cloud.current().forCaller(call.isRemote))
}

private fun CloudStateDto.forCaller(remote: Boolean): CloudStateDto = if (remote) forRemote() else this

private suspend fun RoutingContext.refuse(status: HttpStatusCode, code: String, message: String) {
    call.respond(status, ErrorResponse(message, code))
}

@Resource("/install/cloud")
data class InstallCloudResource(val force: Boolean = false) {
    @Resource("adoption")
    data class Adoption(val parent: InstallCloudResource = InstallCloudResource())

    @Resource("remote")
    data class Remote(val parent: InstallCloudResource = InstallCloudResource())

    @Resource("sessions")
    data class Sessions(val parent: InstallCloudResource = InstallCloudResource()) {
        @Resource("{sid}")
        data class Session(val parent: Sessions = Sessions(), val sid: String) {
            @Resource("allow")
            data class Allow(val parent: Session)

            @Resource("deny")
            data class Deny(val parent: Session)
        }
    }
}

/** Who answered, for the log: the signed-in admin, or the desk itself while it has no accounts. */
private val io.ktor.server.application.ApplicationCall.actorName: String
    get() = authenticatedUserOrNull?.username ?: "the desk"
