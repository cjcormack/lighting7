package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `render_view`'s other half (stage-view plan session 4, D4): the backend cannot draw WebGL, so it
 * asks a **signed-in desk window** to render a viewpoint offscreen and upload the frame.
 *
 * **A job, not a command.** The five `windows.*` commands are rebroadcast to every socket and
 * acted on by the one they name; a render is addressed to **one** socket and answered **once**. So
 * the request goes down that socket's own queue ([attach]) — no other socket is ever sent it — and
 * carries a one-shot request id and a secret [Request.token].
 * The answer is a REST upload (`routes/stageRenders.kt`), accepted only with that id, that token and
 * the socket's own session; a second answer, or one after the timeout, finds nothing to answer.
 *
 * **Which window.** Only a socket that [attach]ed — on the desk's own listener (never the public
 * one, decided by port) and signed in — and that has announced itself to the [WindowRegistry], on a view that does
 * not name another project. Of those, a window on the desk machine itself (its socket's peer is
 * loopback) first — the desk's own GPU, not an iPad's — then the registry's order, oldest announce
 * first. Deterministic, and it names the operator's first screen on an ordinary night.
 *
 * **One render at a time**, desk-wide ([lock]): a render holds a WebGL context on someone's screen
 * for a moment, and two at once on an iPad is the memory the stage-view plan's session 0 fixed.
 *
 * Nothing here is persisted, nothing is project-scoped state (a job carries its project id), and a
 * job dies with its socket ([detach]) — so, like the registry, `SyncCoverageTest` gains no row.
 */
class StageRenderService(private val windows: WindowRegistry) {

    /** One render, as sent to [socketId] in `stageRender.request`. */
    data class Request(
        val socketId: String,
        val requestId: String,
        /** Sent only to [socketId]: what binds the upload to that socket. */
        val token: String,
        val projectId: Int,
        /** In the Stage view's vocabulary: a camera, a saved view's uuid, or `seat:<uuid>:<id>`. */
        val viewpoint: String,
        val width: Int,
        val height: Int,
        val source: String,
        /** How long the window has; it gives up a little sooner and says what it was waiting for. */
        val timeoutMs: Long,
    )

    /** What the desk knows of a socket that may be asked to render. */
    data class RenderSocket(
        /** The socket's session: an upload must come from the same one. */
        val sessionTokenHash: String,
        /** The socket's peer is this machine — a desk screen rather than a tablet on the LAN. */
        val loopback: Boolean,
    )

    sealed interface Outcome {
        class Rendered(val png: ByteArray, val window: WindowRegistry.Window) : Outcome
        data object NoWindow : Outcome
        /** Another render held the desk for as long as a window has to answer one. */
        data class Busy(val waited: Duration) : Outcome
        data class TimedOut(val window: WindowRegistry.Window, val after: Duration) : Outcome
        data class WindowClosed(val window: WindowRegistry.Window) : Outcome
        data class Failed(val window: WindowRegistry.Window, val reason: String) : Outcome
    }

    /** Whether an answer is this job's to give, before its body is read. */
    enum class Claim {
        /** A live job, and this caller holds its token and its session. */
        OURS,
        /** No such job: never issued, already answered, or timed out. */
        UNKNOWN,
        /** A live job, but the token or the session is not the one it was sent to. */
        NOT_YOURS,
    }

    private class Pending(
        val window: WindowRegistry.Window,
        val socketId: String,
        val token: String,
        val sessionTokenHash: String,
        val answer: CompletableDeferred<Outcome> = CompletableDeferred(),
    )

    /** An attached socket: what the desk knows of it, and the queue its requests go down. */
    private class Attached(val socket: RenderSocket, val requests: Channel<Request>)

    private val sockets = ConcurrentHashMap<String, Attached>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val lock = Mutex()
    private val random = SecureRandom()

    /** How long a window has to answer. A `var` so a test need not wait out the real one. */
    @Volatile
    internal var answerTimeout: Duration = DEFAULT_ANSWER_TIMEOUT

    /**
     * [socketId] may be asked to render; the answer is the queue its requests arrive on, which the
     * socket drains for as long as it lives. Only a signed-in socket on the desk's own listener (never
     * the public one) is ever attached.
     *
     * **A queue per socket, attached synchronously**, on the connection's own coroutine at setup and
     * detached in its `finally`: a request sent before the socket's collector is running waits in the
     * queue rather than being lost, and an attach can never land after the detach that ends it.
     */
    fun attach(socketId: String, socket: RenderSocket): ReceiveChannel<Request> {
        // One render at a time, so a job or two is all a queue ever holds.
        val requests = Channel<Request>(capacity = 4)
        sockets.put(socketId, Attached(socket, requests))?.requests?.close()
        return requests
    }

    /** [socketId] has gone: nothing more is asked of it, and a job it held ends now, not at the timeout. */
    fun detach(socketId: String) {
        sockets.remove(socketId)?.requests?.close()
        for (job in pending.values) {
            if (job.socketId == socketId) job.answer.complete(Outcome.WindowClosed(job.window))
        }
    }

    /** The windows that could render [projectId], best first — see the class header for the rule. */
    fun eligibleWindows(projectId: Int): List<WindowRegistry.Window> =
        windows.windows.value
            .filter { sockets.containsKey(it.id) }
            .filter { viewProjectId(it.view).let { id -> id == null || id == projectId } }
            .sortedBy { if (sockets[it.id]?.socket?.loopback == true) 0 else 1 }

    /**
     * Ask the best window to render, and wait for its answer or [answerTimeout]. Waits behind a
     * render already in flight for no longer than that same timeout, then answers [Outcome.Busy] —
     * so every call ends within twice the timeout, however many are queued.
     */
    suspend fun render(
        projectId: Int,
        viewpoint: String,
        width: Int,
        height: Int,
        source: String,
    ): Outcome {
        val wait = answerTimeout
        // `Mutex.lock` is cancellable, so a wait that times out holds nothing.
        withTimeoutOrNull(wait) { lock.lock() } ?: return Outcome.Busy(wait)
        try {
            return renderLocked(projectId, viewpoint, width, height, source)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun renderLocked(
        projectId: Int,
        viewpoint: String,
        width: Int,
        height: Int,
        source: String,
    ): Outcome = run {
        val window = eligibleWindows(projectId).firstOrNull() ?: return@run Outcome.NoWindow
        val attached = sockets[window.id] ?: return@run Outcome.NoWindow
        val requestId = UUID.randomUUID().toString()
        val job = Pending(window, window.id, newToken(), attached.socket.sessionTokenHash)
        pending[requestId] = job
        try {
            // A socket that left between the choice and the registration was never failed by
            // [detach], which looked before the job was there.
            if (!sockets.containsKey(window.id)) return@run Outcome.WindowClosed(window)
            val timeout = answerTimeout
            val sent = attached.requests.trySend(
                Request(window.id, requestId, job.token, projectId, viewpoint, width, height, source, timeout.inWholeMilliseconds),
            )
            // Closed: the socket went after it was chosen.
            if (sent.isFailure) return@run Outcome.WindowClosed(window)
            withTimeoutOrNull(timeout) { job.answer.await() } ?: Outcome.TimedOut(window, timeout)
        } finally {
            pending.remove(requestId)
        }
    }

    /** Whether the caller may answer [requestId]: checked before an upload's body is read. */
    fun claim(requestId: String, token: String?, sessionTokenHash: String?): Claim {
        val job = pending[requestId] ?: return Claim.UNKNOWN
        if (job.answer.isCompleted) return Claim.UNKNOWN
        return if (holds(job, token, sessionTokenHash)) Claim.OURS else Claim.NOT_YOURS
    }

    /** Answer [requestId] with a frame. [Claim.OURS] only if this call was the one that answered it. */
    fun deliver(requestId: String, token: String?, sessionTokenHash: String?, png: ByteArray): Claim =
        answer(requestId, token, sessionTokenHash) { Outcome.Rendered(png, it.window) }

    /** Answer [requestId] with the window's reason for not rendering it. */
    fun fail(requestId: String, token: String?, sessionTokenHash: String?, reason: String): Claim =
        answer(requestId, token, sessionTokenHash) { Outcome.Failed(it.window, reason.take(MAX_REASON_LENGTH)) }

    private fun answer(
        requestId: String,
        token: String?,
        sessionTokenHash: String?,
        outcome: (Pending) -> Outcome,
    ): Claim {
        val job = pending[requestId] ?: return Claim.UNKNOWN
        if (!holds(job, token, sessionTokenHash)) return Claim.NOT_YOURS
        // `complete` is the once: a second answer, or one racing the timeout, finds it done.
        return if (job.answer.complete(outcome(job))) Claim.OURS else Claim.UNKNOWN
    }

    private fun holds(job: Pending, token: String?, sessionTokenHash: String?): Boolean =
        token != null &&
            sessionTokenHash == job.sessionTokenHash &&
            MessageDigest.isEqual(token.toByteArray(), job.token.toByteArray())

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /**
         * A render loads the scene, compiles its shaders and draws a few frames; on a desk GPU that is
         * a second or two, on a software renderer much longer. Under what an MCP client waits.
         */
        val DEFAULT_ANSWER_TIMEOUT: Duration = 30.seconds

        /** The frame a window may upload. A stage render at the default size is a few hundred KB. */
        const val MAX_RENDER_BYTES: Int = 4 * 1024 * 1024

        const val MAX_REASON_LENGTH: Int = 500

        private val PROJECT_VIEW = Regex("^/projects/(\\d+)(?:[/?#]|$)")

        /** The project a window's view names (`/projects/15/stage`), or null for one that names none. */
        fun viewProjectId(view: String): Int? = PROJECT_VIEW.find(view)?.groupValues?.get(1)?.toIntOrNull()
    }
}
