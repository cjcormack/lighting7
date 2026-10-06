package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.state.StageRenderService

// ─── Outbound ───────────────────────────────────────────────────────────

/**
 * `render_view`'s request to one window (stage-view plan session 4). Outbound only: the answer is a
 * REST upload (`routes/stageRenders.kt`), so the socket gains no inbound message — nothing an
 * operator's client could send that it could not before (`FU-AUTH-WS-PER-MESSAGE` stays unfired).
 */
@Serializable
sealed class StageRenderOutMessage : OutMessage()

/**
 * Render [viewpoint] offscreen at [width] × [height] from [source], with [workLights] lifting the
 * dark or not (stage-view menu plan D9 — the request's, never the window's own), and upload the PNG to
 * `POST /api/rest/stage-renders/{requestId}` with [token] — or say why not at `…/failure`. Sent to
 * **one** socket, never rebroadcast: [token] is the secret that binds the upload to it.
 * [viewpoint] is already the Stage view's own vocabulary (a camera, a saved view's uuid, or
 * `seat:<uuid>:<id>`), resolved and checked by the desk; [timeoutMs] is how long the desk waits.
 */
@Serializable
@SerialName("stageRender.request")
data class StageRenderRequestOutMessage(
    val requestId: String,
    val token: String,
    val projectId: Int,
    val viewpoint: String,
    val width: Int,
    val height: Int,
    val source: String,
    val workLights: Boolean,
    val timeoutMs: Long,
) : StageRenderOutMessage()

internal fun StageRenderService.Request.toOutMessage() =
    StageRenderRequestOutMessage(requestId, token, projectId, viewpoint, width, height, source, workLights, timeoutMs)

// ─── Subscriptions ──────────────────────────────────────────────────────

/**
 * Make this socket one a render may be asked of — only if it is signed in and on the desk's own
 * listener. A socket on the public listener (`remote`, decided by port) and a bootstrap-open one (no
 * session for an upload to match) are never attached, so they are never chosen and never sent a
 * request. "The desk's own listener" is a port, not a peer address: a signed-in browser that reaches
 * that port is eligible wherever it is.
 *
 * Attached **synchronously**, here on the connection's own coroutine, and detached in the same
 * connection's `finally`, so the two can never land out of order; requests queue on the socket's own
 * channel until its collector drains them, so none is lost if one is sent the moment the window
 * announces. Registered past the warm-up gate, beside the show band: a request goes only to an
 * announced window, and an announce is handled only past that gate.
 */
fun setupStageRenderSubscriptions(scope: SocketScope) {
    val session = scope.sessionTokenHash ?: return
    if (scope.remote) return
    val requests = scope.state.stageRender.attach(scope.id, StageRenderService.RenderSocket(session, scope.loopbackPeer))
    scope.subscribe(requests.receiveAsFlow()) { scope.send(it.toOutMessage()) }
}
