package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.plugins.ChannelMappingStateOutMessage
import uk.me.cormack.lighting7.plugins.CueRunStateChangedOutMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A session that didn't cause the change still hears about it. This is the whole point of the
 * run-state broadcast: an operator arming or firing a cue on the desk has to reach the prompt
 * book on a tablet, which previously computed "next" for itself and animated nothing.
 *
 * Goes over a real socket rather than asserting on the listener, because the failure mode that
 * actually bit was serialization — a message missing from the `OutMessage` polymorphic scope
 * encodes fine in a unit test and closes the socket in production.
 */
class CueRunStateBroadcastTest : RouteIntegrationTest() {

    private suspend fun createStack(client: HttpClient, name: String): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun createCue(client: HttpClient, name: String, stackId: Int): Int =
        client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId))
        }.body<CueDetails>().id

    @Test
    fun `a GO and an arm both reach a socket that did neither`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = createCue(client, "a1", stackId)
        val cue2 = createCue(client, "a2", stackId)
        val cue3 = createCue(client, "a3", stackId)

        client.webSocket("/api") {
            // Wait for a frame from the connect burst before touching anything: the server
            // registers its listener inside the socket handler, which has not necessarily run
            // by the time the client's `webSocket` block starts. Firing the GO first loses the
            // broadcast and the test hangs on a frame that was never queued.
            awaitOfType<ChannelMappingStateOutMessage>()

            // A GO fired over HTTP — the "desk" — observed from this socket, the "tablet".
            client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/activate") {
                contentType(ContentType.Application.Json)
                setBody(ActivateCueStackRequest(cueId = cue1))
            }

            val fired = awaitOfType<CueRunStateChangedOutMessage>()
            assertEquals(stackId, fired.stackId)
            assertEquals(cue1, fired.activeCueId)
            assertEquals(cue2, fired.nextCueId, "the positional next is on deck")
            assertFalse(fired.nextIsArmed)
            assertTrue(fired.transition, "a GO is a transition — the client animates the fade")

            // Somebody arms a different cue.
            client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/standby") {
                contentType(ContentType.Application.Json)
                setBody(SetStandbyRequest(cueId = cue3))
            }

            val armed = awaitOfType<CueRunStateChangedOutMessage>()
            assertEquals(cue1, armed.activeCueId, "arming doesn't move the live cue")
            assertEquals(cue3, armed.nextCueId)
            assertTrue(armed.nextIsArmed)
            assertFalse(armed.transition, "arming must not restart anyone's fade")
        }
    }

    @Test
    fun `a paused auto-advance timer reports autoAdvance false`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = "a1",
                    cueStackId = stackId,
                    autoAdvance = true,
                    autoAdvanceDelayMs = 60_000,
                )
            )
        }.body<CueDetails>().id
        createCue(client, "a2", stackId)

        state.show.cueStackManager.activateCueInStack(state, stackId, cue1)
        assertTrue(
            state.show.cueStackManager.runState.runStateFor(state, stackId).autoAdvance,
            "a live timer means the stack will roll forward",
        )

        // What a cue-edit Live session and the surface's Pause binding both do.
        state.show.cueStackManager.pauseAutoAdvance(state, stackId)

        val paused = state.show.cueStackManager.runState.runStateFor(state, stackId)
        assertFalse(
            paused.autoAdvance,
            "the client draws the countdown but no longer drives it: a paused timer must not " +
                "leave a bar counting down to a step that never comes",
        )
        assertEquals(60_000, paused.autoAdvanceDelayMs, "the cue's delay is still reported")
    }

    @Test
    fun `connecting mid-show reports the run state without claiming a transition`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = createCue(client, "a1", stackId)
        val cue2 = createCue(client, "a2", stackId)

        client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = cue1))
        }

        // Now open a socket, as a phone joining a show already in progress.
        client.webSocket("/api") {
            val snapshot = awaitOfType<CueRunStateChangedOutMessage>()
            assertEquals(cue1, snapshot.activeCueId)
            assertEquals(cue2, snapshot.nextCueId)
            assertFalse(
                snapshot.transition,
                "the connect snapshot is state, not an event — treating it as a GO replays a dead fade",
            )
        }
    }
}
