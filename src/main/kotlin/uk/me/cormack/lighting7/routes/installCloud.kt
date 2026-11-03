package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import uk.me.cormack.lighting7.auth.authenticatedUser
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
}

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
}
