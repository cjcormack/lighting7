package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.update.ApplyUpdateRequest
import uk.me.cormack.lighting7.update.UpdateService
import uk.me.cormack.lighting7.update.UpdateSettingsRequest

/**
 * In-app updates for the Windows distribution.
 *
 * **Auth is per-handler, not by prefix.** `/api/rest/update` is deliberately *not* added to
 * `ADMIN_ONLY_PREFIXES` in `auth/AuthGate.kt`: that gate is method-blind, and `GET /status`
 * should be readable by an operator — knowing what version the desk is on, and that it is about
 * to restart, is not administration. Only the actions call [requireAdmin], which is the same
 * split `PUT /install` already uses.
 *
 * **Long-running operations never fail through a status code.** `POST /download` and
 * `POST /apply` answer 202 with the current status, and anything that goes wrong afterwards
 * surfaces in `UpdateStatusDto.error` plus a socket frame — the started/done/failed shape
 * `routes/cloudSync.kt` established. Only *synchronous* conflicts get a 409. A GitHub rate limit
 * is a 200 with `error.code = RATE_LIMITED`: the endpoint succeeded, the check didn't.
 */
internal fun Route.routeApiRestUpdate(state: State) {
    // Resolved per request, not once at route-registration time. `State.updateService` is lazy so
    // that the many tests which mount the app never construct it — and binding it here would
    // force it during module() setup, defeating that and running the boot-time apply
    // reconciliation plus a staged-directory sweep in every route test.
    fun service() = state.updateService

    get<UpdateStatusResource> {
        call.respond(service().status())
    }

    post<UpdateCheckResource> {
        call.requireAdmin()
        call.respond(service().check(manual = true))
    }

    post<UpdateDownloadResource> {
        call.requireAdmin()
        when (val result = service().startDownload()) {
            is UpdateService.StartResult.Started ->
                call.respond(HttpStatusCode.Accepted, service().status())
            is UpdateService.StartResult.Conflict ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.message, result.code))
        }
    }

    post<UpdateDownloadCancelResource> {
        call.requireAdmin()
        service().cancelDownload()
        call.respond(service().status())
    }

    post<UpdateApplyResource> {
        call.requireAdmin()
        val request = call.receive<ApplyUpdateRequest>()
        when (val result = service().requestApply(request.confirmVersion)) {
            is UpdateService.StartResult.Started ->
                // 202, not 200: by the time the client reads this the launcher may already be
                // stopping the backend. The response is an acknowledgement, not a completion.
                call.respond(HttpStatusCode.Accepted, service().status())
            is UpdateService.StartResult.Conflict ->
                call.respond(HttpStatusCode.Conflict, ErrorResponse(result.message, result.code))
        }
    }

    put<UpdateSettingsResource> {
        call.requireAdmin()
        val request = call.receive<UpdateSettingsRequest>()
        state.setUpdateAutoCheckEnabled(request.autoCheckEnabled)
        call.respond(service().status())
    }
}

@Resource("/update/status")
data object UpdateStatusResource

@Resource("/update/check")
data object UpdateCheckResource

@Resource("/update/download")
data object UpdateDownloadResource

@Resource("/update/download/cancel")
data object UpdateDownloadCancelResource

@Resource("/update/apply")
data object UpdateApplyResource

@Resource("/update/settings")
data object UpdateSettingsResource
