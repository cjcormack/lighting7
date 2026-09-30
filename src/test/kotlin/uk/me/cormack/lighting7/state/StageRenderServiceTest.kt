package uk.me.cormack.lighting7.state

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.state.StageRenderService.Claim
import uk.me.cormack.lighting7.state.StageRenderService.Outcome
import uk.me.cormack.lighting7.state.StageRenderService.RenderSocket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `render_view`'s job queue (stage-view plan session 4) without a socket: which window is asked,
 * and that an answer is taken once, from the socket it was sent to, and not after the timeout.
 * `McpRenderViewTest` drives the same over a real socket and the upload route.
 */
class StageRenderServiceTest {

    private val registry = WindowRegistry()
    private val service = StageRenderService(registry)
    private val png = byteArrayOf(1, 2, 3)

    private fun window(id: String, name: String = id, view: String = "/busk") =
        registry.announce(id, "tab-$id", name, view, fullscreen = false, follows = true, user = "Op")

    private val queues = mutableMapOf<String, kotlinx.coroutines.channels.ReceiveChannel<StageRenderService.Request>>()

    private fun attach(id: String, session: String = "session-$id", loopback: Boolean = false) {
        queues[id] = service.attach(id, RenderSocket(session, loopback))
    }

    /** The next request sent down [id]'s own queue. */
    private suspend fun next(id: String = "a") = queues.getValue(id).receive()

    @Test
    fun `no attached window is no window, whatever is announced`() = runBlocking<Unit> {
        window("a")
        assertEquals(Outcome.NoWindow, service.render(15, "plan", 1280, 720, "output"))
    }

    @Test
    fun `an attached socket that never announced is not a window`() = runBlocking<Unit> {
        attach("a")
        assertEquals(Outcome.NoWindow, service.render(15, "plan", 1280, 720, "output"))
    }

    @Test
    fun `the desk machine's own windows come first, then the order the registry holds`() {
        window("tablet")
        window("screen-1")
        window("screen-2")
        attach("tablet", loopback = false)
        attach("screen-1", loopback = true)
        attach("screen-2", loopback = true)
        assertEquals(listOf("screen-1", "screen-2", "tablet"), service.eligibleWindows(15).map { it.id })
    }

    @Test
    fun `a window on another project's view is passed over, and one naming no project is not`() {
        window("other", view = "/projects/12/stage")
        window("this", view = "/projects/15/busk")
        window("none", view = "/install")
        listOf("other", "this", "none").forEach { attach(it) }
        assertEquals(listOf("this", "none"), service.eligibleWindows(15).map { it.id })
        assertEquals(12, StageRenderService.viewProjectId("/projects/12"))
        assertNull(StageRenderService.viewProjectId("/projectsX/12"))
    }

    @Test
    fun `the request goes to the chosen socket only, and its upload answers the render once`() = runBlocking<Unit> {
        window("a")
        window("b")
        attach("a", loopback = true)
        attach("b")
        val render = async { service.render(15, "front", 800, 450, "nextGo") }
        val sent = next("a")
        assertEquals("a", sent.socketId)
        assertTrue(queues.getValue("b").tryReceive().isFailure, "the other socket is sent nothing")
        assertEquals(listOf("front", "nextGo"), listOf(sent.viewpoint, sent.source))
        assertEquals(800 to 450, sent.width to sent.height)

        assertEquals(Claim.OURS, service.claim(sent.requestId, sent.token, "session-a"))
        assertEquals(Claim.OURS, service.deliver(sent.requestId, sent.token, "session-a", png))
        val outcome = assertIs<Outcome.Rendered>(render.await())
        assertEquals("a", outcome.window.id)

        // Spent: a second answer, or even a question about it, finds nothing.
        assertEquals(Claim.UNKNOWN, service.claim(sent.requestId, sent.token, "session-a"))
        assertEquals(Claim.UNKNOWN, service.deliver(sent.requestId, sent.token, "session-a", png))
    }

    @Test
    fun `an answer with the wrong token or from another session is not this job's`() = runBlocking<Unit> {
        window("a")
        attach("a")
        val render = async { service.render(15, "plan", 320, 180, "output") }
        val sent = next()

        assertEquals(Claim.UNKNOWN, service.deliver("not-a-request", sent.token, "session-a", png))
        assertEquals(Claim.NOT_YOURS, service.deliver(sent.requestId, "guessed", "session-a", png))
        assertEquals(Claim.NOT_YOURS, service.deliver(sent.requestId, null, "session-a", png))
        assertEquals(Claim.NOT_YOURS, service.deliver(sent.requestId, sent.token, "session-b", png), "another socket's session")
        assertEquals(Claim.NOT_YOURS, service.fail(sent.requestId, sent.token, null, "no session"))

        // None of those spent it: the right window still answers.
        assertEquals(Claim.OURS, service.fail(sent.requestId, sent.token, "session-a", "WebGL is off"))
        assertEquals("WebGL is off", assertIs<Outcome.Failed>(render.await()).reason)
    }

    @Test
    fun `a window that does not answer in time times out, and its late answer is refused`() = runBlocking<Unit> {
        service.answerTimeout = 50.milliseconds
        window("a")
        attach("a")
        val outcome = service.render(15, "plan", 320, 180, "output")
        val sent = next()
        assertEquals(50L, sent.timeoutMs)
        assertEquals(50.milliseconds, assertIs<Outcome.TimedOut>(outcome).after)
        assertEquals(Claim.UNKNOWN, service.deliver(sent.requestId, sent.token, "session-a", png))
    }

    @Test
    fun `a window that closes mid-render ends it at once rather than at the timeout`() = runBlocking<Unit> {
        window("a")
        attach("a")
        val render = async { service.render(15, "plan", 320, 180, "output") }
        val sent = next()
        service.detach("a")
        assertEquals("a", assertIs<Outcome.WindowClosed>(render.await()).window.id)
        assertEquals(Claim.UNKNOWN, service.deliver(sent.requestId, sent.token, "session-a", png))
        assertEquals(Outcome.NoWindow, service.render(15, "plan", 320, 180, "output"), "and it is never asked again")
    }

    @Test
    fun `a window's reason is kept to a sane length`() = runBlocking<Unit> {
        window("a")
        attach("a")
        val render = async { service.render(15, "plan", 320, 180, "output") }
        val sent = next()
        service.fail(sent.requestId, sent.token, "session-a", "x".repeat(10_000))
        assertEquals(StageRenderService.MAX_REASON_LENGTH, assertIs<Outcome.Failed>(render.await()).reason.length)
    }

    @Test
    fun `a request sent before its socket's collector runs waits in that socket's queue`() = runBlocking<Unit> {
        window("a")
        attach("a")
        val render = async { service.render(15, "plan", 320, 180, "output") }
        // Nothing has been reading the queue; the request is there all the same.
        val sent = next()
        service.deliver(sent.requestId, sent.token, "session-a", png)
        assertIs<Outcome.Rendered>(render.await())
    }

    @Test
    fun `a call queued behind a render that outlasts the timeout is told the desk is busy`() = runBlocking<Unit> {
        service.answerTimeout = 100.milliseconds
        window("a")
        attach("a")
        val first = async { service.render(15, "plan", 320, 180, "output") }
        next()
        val second = async { service.render(15, "front", 320, 180, "output") }
        assertIs<Outcome.TimedOut>(first.await())
        // The second waited its whole allowance behind the first, and was not left queued for good.
        val outcome = second.await()
        assertTrue(outcome is Outcome.Busy || outcome is Outcome.TimedOut, outcome.toString())
    }

    @Test
    fun `a busy desk answers busy without asking the window`() = runBlocking<Unit> {
        service.answerTimeout = 50.milliseconds
        window("a")
        attach("a")
        val first = async { service.render(15, "plan", 320, 180, "output") }
        next()
        // The first holds the desk for its 50 ms; a second with a 20 ms allowance cannot get in.
        service.answerTimeout = 20.milliseconds
        assertEquals(Outcome.Busy(20.milliseconds), service.render(15, "front", 320, 180, "output"))
        assertTrue(queues.getValue("a").tryReceive().isFailure, "the window was never sent the second")
        first.await()
    }

    @Test
    fun `a detached socket's queue is closed`() {
        window("a")
        attach("a")
        service.detach("a")
        assertTrue(queues.getValue("a").tryReceive().isClosed)
    }
}
