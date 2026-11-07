package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals

/**
 * [SocketScope.subscribe] has subscribed by the time it returns, so a replay-0 broadcast emitted
 * after a socket's setup (the `windows.*` commands, `effects.fired`, a session revocation) reaches
 * that socket.
 */
class SocketScopeSubscribeTest : RouteIntegrationTest() {

    /** Runs nothing until [runAll], so a dispatched start cannot win the race by luck. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun runAll() {
            while (true) (synchronized(queue) { queue.removeFirstOrNull() } ?: return).run()
        }
    }

    @Test
    fun `a replay-0 flow emitted to as soon as subscribe returns reaches the collector`() = testApplication {
        mountTestApp(state)
        routing {
            webSocket("/probe") {
                val held = HeldDispatcher()
                val session = this
                val heldSession = object : DefaultWebSocketServerSession by session {
                    override val coroutineContext: CoroutineContext = session.coroutineContext + held
                }
                val scope = SocketScope(heldSession, state)
                val broadcast = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
                val received = CompletableDeferred<Int>()

                scope.subscribe(broadcast) { received.complete(it) }
                val subscribers = broadcast.subscriptionCount.value
                broadcast.tryEmit(1)
                held.runAll()
                val got = if (received.isCompleted) received.await().toString() else "nothing"

                scope.cancelAll()
                held.runAll()
                send(Frame.Text("subscribers $subscribers, received $got"))
            }
        }

        createWsClient().webSocket("/probe") {
            assertEquals("subscribers 1, received 1", (incoming.receive() as Frame.Text).readText())
        }
    }
}
