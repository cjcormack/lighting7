package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueRunState
import uk.me.cormack.lighting7.plugins.ChannelMappingStateOutMessage
import uk.me.cormack.lighting7.plugins.CueRunStateChangedOutMessage
import uk.me.cormack.lighting7.plugins.CueRunStateGate
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import uk.me.cormack.lighting7.testsupport.entryIdOf

/**
 * A session that didn't cause the change still hears about it. This is the whole point of the
 * run-state broadcast: an operator arming or firing a cue on the desk has to reach the prompt
 * book on a tablet, which previously computed "next" for itself and animated nothing.
 *
 * The GO and arm go over a real socket rather than asserting on the listener, because the failure
 * mode that actually bit was serialization — a message missing from the `OutMessage` polymorphic
 * scope encodes fine in a unit test and closes the socket in production. The stack-list writes
 * count frames at the listener: they send the same message, and a socket cannot say "exactly one".
 */
class CueRunStateBroadcastTest : RouteIntegrationTest() {

    private suspend fun createStack(client: HttpClient, name: String): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun createCue(client: HttpClient, name: String, stackId: Int?, sortOrder: Int? = null): Int =
        client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, sortOrder = sortOrder))
        }.body<CueDetails>().id

    /** The run-state frames [stackId] sends while [block] runs. */
    private suspend fun framesFor(stackId: Int, block: suspend () -> Unit): List<CueRunState> {
        val heard = mutableListOf<CueRunState>()
        val listener = object : FixturesChangeListener {
            override fun cueRunStateChanged(runState: CueRunState) {
                if (runState.stackId == stackId) heard += runState
            }
        }
        state.show.fixtures.registerListener(listener)
        try {
            block()
        } finally {
            state.show.fixtures.unregisterListener(listener)
        }
        return heard
    }

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
            val go = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/activate") {
                contentType(ContentType.Application.Json)
                setBody(ActivateCueStackRequest(entryId = state.entryIdOf(cue1)))
            }
            assertEquals(HttpStatusCode.OK, go.status)

            // Keyed on content, not position: the connect snapshot is read after the listener is
            // registered, so it may describe this GO too, and may legitimately arrive first.
            val fired = awaitOfType<CueRunStateChangedOutMessage> { it.transition }
            assertEquals(stackId, fired.stackId)
            assertEquals(cue1, fired.activeCueId)
            assertEquals(cue2, fired.nextCueId, "the positional next is on deck")
            assertFalse(fired.nextIsArmed)
            assertTrue(fired.transition, "a GO is a transition — the client animates the fade")

            // Somebody arms a different cue.
            val arm = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/standby") {
                contentType(ContentType.Application.Json)
                setBody(SetStandbyRequest(entryId = state.entryIdOf(cue3)))
            }
            assertEquals(HttpStatusCode.OK, arm.status)

            val armed = awaitOfType<CueRunStateChangedOutMessage> { it.nextIsArmed }
            assertEquals(cue1, armed.activeCueId, "arming doesn't move the live cue")
            assertEquals(cue3, armed.nextCueId)
            assertTrue(armed.nextIsArmed)
            assertFalse(armed.transition, "arming must not restart anyone's fade")
        }
    }

    @Test
    fun `a connect snapshot read after a GO is not sent beside the GO's own frame`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = createStack(client, "Act 1")
        val cue1 = createCue(client, "a1", stackId)
        createCue(client, "a2", stackId)

        // The socket's listener hears the GO, and its snapshot — read after the listener was
        // registered — describes the same GO.
        val heard = mutableListOf<CueRunState>()
        val listener = object : FixturesChangeListener {
            override fun cueRunStateChanged(runState: CueRunState) { heard += runState }
        }
        state.show.fixtures.registerListener(listener)
        try {
            state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cue1))
        } finally {
            state.show.fixtures.unregisterListener(listener)
        }
        val go = heard.single()
        val snapshot = state.show.cueStackManager.runState.runStateFor(state, stackId)
        assertEquals(go.seq, snapshot.seq, "a read after a publish ties it")

        val sent = mutableListOf<CueRunStateChangedOutMessage>()
        val gate = CueRunStateGate { sent += it as CueRunStateChangedOutMessage }
        runBlocking {
            gate.change(go)
            gate.snapshot(snapshot)
        }
        assertEquals(listOf(true), sent.map { it.transition }, "only the GO reaches the client")
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

        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cue1))
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
            setBody(ActivateCueStackRequest(entryId = state.entryIdOf(cue1)))
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

    @Test
    fun `a cue added after the live one is the stack's next, in one frame`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = createStack(client, "Act 1")
        val cues = (1..5).map { createCue(client, "q$it", stackId) }
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cues.last()))
        val added = createCue(client, "q5.5", stackId = null)

        val frames = framesFor(stackId) {
            val response = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/add-cue") {
                contentType(ContentType.Application.Json)
                setBody(AddCueToStackRequest(cueId = added, sortOrder = 5))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        val frame = frames.single()
        assertEquals(state.entryIdOf(cues.last()), frame.activeEntryId, "an insert moves nothing on stage")
        assertEquals(state.entryIdOf(added), frame.nextEntryId)
        assertFalse(frame.transition)
    }

    @Test
    fun `a reorder that moves the next sends the new next, in one frame`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = createStack(client, "Act 1")
        val entries = (1..5).map { state.entryIdOf(createCue(client, "q$it", stackId)) }
        state.show.cueStackManager.activateEntry(state, stackId, entries[1])

        // Q5 straight after the live Q2, ahead of Q3.
        val frames = framesFor(stackId) {
            val response = client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/reorder") {
                contentType(ContentType.Application.Json)
                setBody(ReorderCuesRequest(listOf(entries[0], entries[1], entries[4], entries[2], entries[3])))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        val frame = frames.single()
        assertEquals(entries[1], frame.activeEntryId)
        assertEquals(entries[4], frame.nextEntryId)
    }

    @Test
    fun `a cue created, duplicated or recorded after the live one is the stack's next`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = createStack(client, "Act 1")
        val first = createCue(client, "q1", stackId)
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(first))

        // Each places a new last entry after the live last one, which a GO then makes live for the next.
        val placings: List<Pair<String, suspend () -> Int>> = listOf(
            "POST /cues" to { createCue(client, "q2", stackId, sortOrder = 1) },
            "duplicate" to {
                client.post("/api/rest/projects/$projectId/cues/$first/duplicate") {
                    contentType(ContentType.Application.Json)
                    setBody(DuplicateCueRequest(stackId = stackId))
                }.body<DuplicateCueResponse>().cueId
            },
            "Record CREATE" to {
                client.post("/api/rest/programmer/record") {
                    contentType(ContentType.Application.Json)
                    setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId))
                }.body<ProgrammerRecordResponse>().cue.id
            },
        )
        for ((handler, place) in placings) {
            var placed = 0
            val frames = framesFor(stackId) { placed = place() }
            assertEquals(listOf(state.entryIdOf(placed)), frames.map { it.nextEntryId }, handler)
            state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(placed))
        }
    }

    @Test
    fun `turning loop off on the last entry clears the stack's next`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        val stackId = client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1", loop = true))
        }.body<CueStackDetails>().id
        val first = createCue(client, "q1", stackId)
        val last = createCue(client, "q2", stackId)
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(last))
        assertEquals(state.entryIdOf(first), state.show.cueStackManager.runState.runStateFor(state, stackId).nextEntryId)

        val frames = framesFor(stackId) {
            val response = client.put("/api/rest/projects/$projectId/cue-stacks/$stackId") {
                contentType(ContentType.Application.Json)
                setBody(NewCueStack(name = "Act 1", loop = false))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals(listOf<Int?>(null), frames.map { it.nextEntryId })
    }
}
