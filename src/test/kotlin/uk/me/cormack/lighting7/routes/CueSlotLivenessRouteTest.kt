package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backend half of `FS-BUG-CUESLOT-LIVENESS`: the slot pads' fire/stop toggle is only safe if
 * a mis-read (or raced) tap cannot move the playhead, and if a **rows-only** cue — property
 * assignments and effect-free layers, no `FxInstance` anywhere — genuinely starts and stops
 * through the pad's routes. Rows-only is the case the old effect-stream liveness could never see,
 * so nothing pinned it.
 */
class CueSlotLivenessRouteTest : RouteIntegrationTest() {

    private suspend fun createStack(client: HttpClient, name: String): Int =
        client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    /** A rows-only cue: one dimmer assignment, no layers, no effects. */
    private suspend fun createRowsOnlyCue(
        client: HttpClient,
        name: String,
        stackId: Int,
        dimmer: Int,
    ): Int {
        val resp = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = name,
                    cueStackId = stackId,
                    propertyAssignments = listOf(
                        CuePropertyAssignmentDto(
                            targetType = TargetRef.Fixture.TYPE,
                            targetKey = "hex-1",
                            propertyName = "dimmer",
                            value = dimmer.toString(),
                        ),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private suspend fun activate(client: HttpClient, stackId: Int, cueId: Int? = null): CueStackActivateResponse =
        client.post("/api/rest/project/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = cueId))
        }.body()

    private suspend fun stack(client: HttpClient, stackId: Int): CueStackDetails =
        client.get("/api/rest/project/$projectId/cue-stacks/$stackId").body()

    @Test
    fun `bare activate on an already-active stack does not rewind the playhead`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val stackId = createStack(client, "Act 1")
        val cue1 = createRowsOnlyCue(client, "a1", stackId, 10)
        val cue2 = createRowsOnlyCue(client, "a2", stackId, 20)

        assertEquals(cue1, activate(client, stackId).cueId, "bare activate on a dark stack fires the first cue")
        activate(client, stackId, cueId = cue2)
        assertEquals(cue2, stack(client, stackId).activeCueId)

        // The raced/mis-read tap: a second bare activate must answer with what is already on
        // stage, not throw the playhead back to cue 1.
        assertEquals(cue2, activate(client, stackId).cueId)
        assertEquals(cue2, stack(client, stackId).activeCueId)
    }

    @Test
    fun `explicit-cue activate on an active stack is still a deliberate re-fire`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val stackId = createStack(client, "Act 1")
        val cue1 = createRowsOnlyCue(client, "a1", stackId, 10)
        val cue2 = createRowsOnlyCue(client, "a2", stackId, 20)

        activate(client, stackId, cueId = cue2)
        assertEquals(cue1, activate(client, stackId, cueId = cue1).cueId)
        assertEquals(cue1, stack(client, stackId).activeCueId)
    }

    @Test
    fun `a rows-only cue fired from a pad starts and stops`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        val stackId = createStack(client, "Act 1")
        val cueId = createRowsOnlyCue(client, "warm wash", stackId, 128)

        // The pad's fire: apply routes through the stack manager, so the stack is genuinely
        // active and the cue's rows are live on Layer 4 — with no effect ever existing.
        val applyResp = client.post("/api/rest/project/$projectId/cues/$cueId/apply")
        assertEquals(HttpStatusCode.OK, applyResp.status, applyResp.bodyAsText())
        assertEquals(cueId, stack(client, stackId).activeCueId)
        assertTrue(
            cueId in state.show.fxEngine.activeCueAssignmentIds(),
            "the rows-only cue's assignments are live",
        )

        // The pad's stop: takes the stack-deactivate branch and clears Layer 4.
        val stopResp = client.post("/api/rest/project/$projectId/cues/$cueId/stop")
        assertEquals(HttpStatusCode.OK, stopResp.status, stopResp.bodyAsText())
        assertNull(stack(client, stackId).activeCueId, "the playhead reads dark again")
        assertFalse(
            cueId in state.show.fxEngine.activeCueAssignmentIds(),
            "the rows-only cue's assignments are gone",
        )
    }
}
