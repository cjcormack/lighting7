package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.plugins.CueEditAssignmentChangedOutMessage
import uk.me.cormack.lighting7.plugins.CueEditBeginEditInMessage
import uk.me.cormack.lighting7.plugins.CueEditEndEditInMessage
import uk.me.cormack.lighting7.plugins.CueEditSessionEndedOutMessage
import uk.me.cormack.lighting7.plugins.CueEditSessionStartedOutMessage
import uk.me.cormack.lighting7.plugins.CueEditSetPropertyInMessage
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.OutMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * End-to-end HTTP + WebSocket round-trip: POST /patches → WS cueEdit (beginEdit →
 * setProperty → endEdit) → POST snapshot-from-live → GET. Closes the gap named in
 * `FU-TEST-HTTP-ROUNDTRIP`.
 */
class HttpRoundTripTest : RouteIntegrationTest() {

    @Test
    fun `patch then cueEdit then snapshot-from-live then GET round-trips through HTTP + WS`() = testApplication {
        mountTestApp(state)
        val client = createClient {
            install(ContentNegotiation) { json(TestJson) }
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(TestJson)
            }
        }

        // Universe 0 is pre-seeded as MOCK so DbFixtureLoader instantiates
        // MockDmxController (no UDP socket, no GlobalScope coroutines).
        val patchResp = client.post("/api/rest/project/$projectId/patches") {
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

        val sourceCueId = createEmptyCue(client, "source-cue")
        val targetCueId = createEmptyCue(client, "target-cue")

        // The WS connection fans out a burst of initial-state messages on connect
        // (channelMapping, fxState, palette, etc. — see plugins/Sockets.kt). Filter
        // via [awaitOfType] rather than reading the first frame blindly.
        //
        // Snapshot happens inside the open Live session: after setProperty, Layer 3
        // reflects the edit, but endEdit would tear it down (removeEffectsForCue →
        // removeCueAssignments), so the snapshot has to come before endEdit.
        client.webSocket("/api") {
            sendSerialized<InMessage>(CueEditBeginEditInMessage(sourceCueId, "LIVE"))
            val started = awaitOfType<CueEditSessionStartedOutMessage>()
            assertEquals(sourceCueId, started.cueId)

            sendSerialized<InMessage>(
                CueEditSetPropertyInMessage(
                    cueId = sourceCueId,
                    targetType = "fixture",
                    targetKey = "hex-1",
                    propertyName = "dimmer",
                    value = "200",
                )
            )
            val changed = awaitOfType<CueEditAssignmentChangedOutMessage>()
            assertEquals(sourceCueId, changed.cueId)
            assertEquals("hex-1", changed.targetKey)
            assertEquals("dimmer", changed.propertyName)
            assertEquals("200", changed.value)

            val snapResp = client.post(
                "/api/rest/project/$projectId/cues/$targetCueId/snapshot-from-live"
            )
            assertEquals(HttpStatusCode.OK, snapResp.status, "snapshot body: ${snapResp.bodyAsText()}")

            sendSerialized<InMessage>(CueEditEndEditInMessage(sourceCueId))
            val ended = awaitOfType<CueEditSessionEndedOutMessage>()
            assertEquals(sourceCueId, ended.cueId)
        }

        val getResp = client.get("/api/rest/project/$projectId/cues/$targetCueId")
        assertEquals(HttpStatusCode.OK, getResp.status)
        val details = getResp.body<CueDetails>()
        val dimmerRow = details.propertyAssignments.singleOrNull {
            it.targetKey == "hex-1" && it.propertyName == "dimmer"
        }
        assertNotNull(dimmerRow, "expected snapshot to contain hex-1.dimmer; got ${details.propertyAssignments}")
        assertEquals("200", dimmerRow.value, "snapshot should preserve the Live edit's dimmer value")
        assertEquals("fixture", dimmerRow.targetType)

        val sourceResp = client.get("/api/rest/project/$projectId/cues/$sourceCueId")
        val sourceDetails = sourceResp.body<CueDetails>()
        val sourceDimmer = sourceDetails.propertyAssignments.singleOrNull {
            it.targetKey == "hex-1" && it.propertyName == "dimmer"
        }
        assertNotNull(sourceDimmer, "cueEdit.setProperty should persist to source cue")
        assertEquals("200", sourceDimmer.value)
    }

    private suspend fun createEmptyCue(client: HttpClient, name: String): Int {
        val resp = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name))
        }
        assertEquals(HttpStatusCode.Created, resp.status, "create cue '$name' body: ${resp.bodyAsText()}")
        return resp.body<CueDetails>().id
    }

    /**
     * Read inbound frames until one deserializes as [T], discarding intervening
     * messages (initial channelMapping, fxState, palette, beatSync, …).
     */
    private suspend inline fun <reified T : OutMessage> DefaultClientWebSocketSession.awaitOfType(): T {
        repeat(100) {
            val msg = receiveDeserialized<OutMessage>()
            if (msg is T) return msg
        }
        error("Never saw ${T::class.simpleName} after 100 frames")
    }
}
