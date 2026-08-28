package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.ProgrammerEntryChangedOutMessage
import uk.me.cormack.lighting7.plugins.ProgrammerSetInMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * End-to-end HTTP + WebSocket round-trip: POST /patches → WS `programmer.set` → POST
 * programmer/record (STAGE_SNAPSHOT) → GET. Closes the gap named in `FU-TEST-HTTP-ROUNDTRIP`.
 *
 * The capture step used to be `POST /cues/{id}/snapshot-from-live`, which Session 3 replaced
 * with `programmer.record { source: STAGE_SNAPSHOT, mode: UPDATE_EXISTING }` — the same
 * capture, now one source among several rather than its own endpoint.
 *
 * The WS leg used to be a `cueEdit` session (beginEdit → setProperty → endEdit), writing into
 * the cue's Layer 4 directly. Sweep item D1 retired that family, so the leg is now a programmer
 * write — which is what the frontend has done since session 2b anyway. The shape of the test is
 * unchanged and so is what it covers: a value set over the socket has to be visible to a
 * subsequent REST capture, and survive to a REST read.
 */
class HttpRoundTripTest : RouteIntegrationTest() {

    @Test
    fun `patch then programmer set then stage-snapshot record then GET round-trips through HTTP + WS`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        // Universe 0 is pre-seeded as MOCK so DbFixtureLoader instantiates
        // MockDmxController (no UDP socket, no GlobalScope coroutines).
        val patchResp = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePatchRequest(
                    universe = 0,
                    fixtureTypeKey = "hex",
                    key = "hex-1",
                    name = "Hex 1",
                    startChannel = 1,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, patchResp.status, "patches body: ${patchResp.bodyAsText()}")

        val targetCueId = createEmptyCue(client, "target-cue")

        // The WS connection fans out a burst of initial-state messages on connect
        // (channelMapping, fxState, palette, etc. — see plugins/Sockets.kt). Filter
        // via [awaitOfType] rather than reading the first frame blindly.
        //
        // The snapshot happens while the socket is still open: the programmer entry is what
        // puts 200 on stage, and STAGE_SNAPSHOT reads the stage.
        client.webSocket("/api") {
            sendSerialized<InMessage>(
                ProgrammerSetInMessage(
                    targetType = "fixture",
                    targetKey = "hex-1",
                    propertyName = "dimmer",
                    value = "200",
                )
            )
            val changed = awaitOfType<ProgrammerEntryChangedOutMessage>()
            assertEquals("hex-1", changed.targetKey)
            assertEquals("dimmer", changed.propertyName)
            assertEquals("200", changed.value)

            val snapResp = client.post("/api/rest/programmer/record") {
                contentType(ContentType.Application.Json)
                setBody(
                    ProgrammerRecordRequest(
                        projectId = projectId.toString(),
                        mode = "UPDATE_EXISTING",
                        source = "STAGE_SNAPSHOT",
                        cueId = targetCueId,
                    )
                )
            }
            assertEquals(HttpStatusCode.OK, snapResp.status, "snapshot body: ${snapResp.bodyAsText()}")
        }

        val getResp = client.get("/api/rest/projects/$projectId/cues/$targetCueId")
        assertEquals(HttpStatusCode.OK, getResp.status)
        val details = getResp.body<CueDetails>()
        val dimmerRow = details.propertyAssignments.singleOrNull {
            it.targetKey == "hex-1" && it.propertyName == "dimmer"
        }
        assertNotNull(dimmerRow, "expected snapshot to contain hex-1.dimmer; got ${details.propertyAssignments}")
        assertEquals("200", dimmerRow.value, "snapshot should preserve the programmer write's dimmer value")
        assertEquals("fixture", dimmerRow.targetType)
    }

    private suspend fun createEmptyCue(client: HttpClient, name: String): Int {
        // Every cue belongs to a stack now; give each test cue its own stack.
        val stackResp = client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "$name stack"))
        }
        assertEquals(HttpStatusCode.Created, stackResp.status, "create stack for '$name' body: ${stackResp.bodyAsText()}")
        val stackId = stackResp.body<CueStackDetails>().id

        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId))
        }
        assertEquals(HttpStatusCode.Created, resp.status, "create cue '$name' body: ${resp.bodyAsText()}")
        return resp.body<CueDetails>().id
    }
}
