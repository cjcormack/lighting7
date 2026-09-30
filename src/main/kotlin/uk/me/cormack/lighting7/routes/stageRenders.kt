package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.state.StageRenderService
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.StageRenderService.Claim

/** The header an answer carries its request's secret in — never the URL, which is logged. */
internal const val RENDER_TOKEN_HEADER = "X-Render-Token"

internal const val CODE_RENDER_REQUEST_UNKNOWN = "RENDER_REQUEST_UNKNOWN"
internal const val CODE_RENDER_REQUEST_NOT_YOURS = "RENDER_REQUEST_NOT_YOURS"
internal const val CODE_RENDER_TOO_LARGE = "RENDER_TOO_LARGE"
internal const val CODE_RENDER_NOT_PNG = "RENDER_NOT_PNG"

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * A window's answer to `stageRender.request` (stage-view plan session 4): the frame, or why there
 * is none. **A REST upload, not a socket frame**, for three reasons:
 *
 * - **The payload.** A PNG is hundreds of KB, up to [StageRenderService.MAX_RENDER_BYTES]; every
 *   socket frame is a small JSON message, and this one would be base64 — a third larger — through
 *   the one sequential message loop the operator's writes also use.
 * - **The cap.** A bounded read of a raw body is the prompt book's pattern; the socket has no
 *   per-message size at all (`maxFrameSize` is unbounded).
 * - **The socket gains no inbound message.** Nothing an operator's client could send over the
 *   socket changes, so `FU-AUTH-WS-PER-MESSAGE` is not fired.
 *
 * An answer is accepted only while its job is live, with the request's secret (sent to one socket
 * only) and from that socket's session ([StageRenderService.claim]); a second answer, or one after
 * the timeout, is 404. **Never on the public listener** — a render is never asked of a remote
 * socket, so a signed-in remote answer is 404 before anything is looked up (an unsigned one is the
 * auth gate's 401, before this route runs). The frame is capped and must be a
 * PNG; a frame refused for either ends the job with that reason, since its window will not send
 * another.
 */
internal fun Route.routeApiRestStageRenders(state: State) {
    post<StageRenderResource> { resource ->
        if (call.isRemote) return@post call.respond(HttpStatusCode.NotFound, unknownRequest())
        val service = state.stageRender
        val token = call.request.headers[RENDER_TOKEN_HEADER]
        val session = call.authenticatedUserOrNull?.sessionTokenHash
        when (service.claim(resource.requestId, token, session)) {
            Claim.UNKNOWN -> return@post call.respond(HttpStatusCode.NotFound, unknownRequest())
            Claim.NOT_YOURS -> return@post call.respond(HttpStatusCode.Forbidden, notYours())
            Claim.OURS -> Unit
        }
        // Bounded read: never buffer more than the cap and one sentinel byte.
        val cap = StageRenderService.MAX_RENDER_BYTES
        val bytes = call.receiveChannel().readBuffer(cap + 1L).readByteArray()
        if (bytes.size > cap) {
            service.fail(resource.requestId, token, session, "the frame was over the ${cap / (1024 * 1024)} MB limit — ask for a smaller size")
            return@post call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse("A render may be at most ${cap / (1024 * 1024)} MB", CODE_RENDER_TOO_LARGE),
            )
        }
        if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            service.fail(resource.requestId, token, session, "the window uploaded something that was not a PNG")
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Not a PNG", CODE_RENDER_NOT_PNG))
        }
        when (service.deliver(resource.requestId, token, session, bytes)) {
            Claim.OURS -> call.respond(HttpStatusCode.NoContent)
            // Answered or timed out while the body was on its way.
            Claim.UNKNOWN -> call.respond(HttpStatusCode.NotFound, unknownRequest())
            Claim.NOT_YOURS -> call.respond(HttpStatusCode.Forbidden, notYours())
        }
    }

    post<StageRenderFailureResource> { resource ->
        if (call.isRemote) return@post call.respond(HttpStatusCode.NotFound, unknownRequest())
        val service = state.stageRender
        val token = call.request.headers[RENDER_TOKEN_HEADER]
        val session = call.authenticatedUserOrNull?.sessionTokenHash
        // Checked before the body is parsed, so a stranger learns nothing from a malformed one.
        when (service.claim(resource.parent.requestId, token, session)) {
            Claim.UNKNOWN -> return@post call.respond(HttpStatusCode.NotFound, unknownRequest())
            Claim.NOT_YOURS -> return@post call.respond(HttpStatusCode.Forbidden, notYours())
            Claim.OURS -> Unit
        }
        // A body that will not parse still ends the render — its window will not send another, and a
        // job left pending would answer the model a bare timeout instead of the failure it was.
        val body = runCatching { call.receive<StageRenderFailureRequest>() }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            service.fail(resource.parent.requestId, token, session, "the window's failure report could not be read")
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("A failure is {reason}", "RENDER_FAILURE_MALFORMED"))
        }
        val reason = body.reason.trim().ifEmpty { "the window gave no reason" }
        when (service.fail(resource.parent.requestId, token, session, reason)) {
            Claim.OURS -> call.respond(HttpStatusCode.NoContent)
            Claim.UNKNOWN -> call.respond(HttpStatusCode.NotFound, unknownRequest())
            Claim.NOT_YOURS -> call.respond(HttpStatusCode.Forbidden, notYours())
        }
    }
}

private fun unknownRequest() =
    ErrorResponse("No render is waiting on that request: it was answered, it timed out, or it never existed", CODE_RENDER_REQUEST_UNKNOWN)

private fun notYours() =
    ErrorResponse("That render was asked of another window", CODE_RENDER_REQUEST_NOT_YOURS)

@Resource("/stage-renders/{requestId}")
data class StageRenderResource(val requestId: String)

@Resource("/failure")
data class StageRenderFailureResource(val parent: StageRenderResource)

/** Why a window could not render: its words, shown to the model as the render's failure. */
@Serializable
data class StageRenderFailureRequest(val reason: String)
