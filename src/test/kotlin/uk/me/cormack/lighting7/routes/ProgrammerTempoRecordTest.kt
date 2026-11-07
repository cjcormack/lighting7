package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import uk.me.cormack.lighting7.models.CueSpeedMasterDto
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.ProgrammerSetTempoInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerTempoStateOutMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Record, Include and Update for the programmer's tempos (fx-speed plan D10): a new cue takes the
 * held list as its *Tempo on GO*, a merge or a Replace replaces the held masters' rows and keeps the
 * rest, a Remove deletes them, `tempo: false` leaves them out, and no mask narrows them; Include holds
 * a cue's tempos and Update writes them back. A hold is a programmer step, so undo lets it go.
 */
class ProgrammerTempoRecordTest : RouteIntegrationTest() {

    private suspend fun master(client: HttpClient, name: String, bpm: Double): String {
        val resp = client.post("/api/rest/projects/$projectId/speed-masters") {
            contentType(ContentType.Application.Json)
            setBody(CreateSpeedMasterRequest(name = name, bpm = bpm))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<SpeedMasterDto>().uuid
    }

    private suspend fun HttpClient.record(request: ProgrammerRecordRequest): ProgrammerRecordResponse {
        val resp = post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertTrue(resp.status.isSuccess(), resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.setCueTempos(cueId: Int, vararg rows: Pair<String, Double>) {
        val resp = patch("/api/rest/projects/$projectId/cues/$cueId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("speedMasters") {
                    rows.forEach { (uuid, bpm) -> add(buildJsonObject { put("speedMasterUuid", uuid); put("bpm", bpm) }) }
                }
            })
        }
        assertTrue(resp.status.isSuccess(), resp.bodyAsText())
    }

    private suspend fun HttpClient.tempos(cueId: Int): Map<String, Double> =
        get("/api/rest/projects/$projectId/cues/$cueId").body<CueDetails>().speedMasters.associate { it.speedMasterUuid to it.bpm }

    private fun hold(uuid: String, bpm: Double) =
        assertEquals(uk.me.cormack.lighting7.state.ProgrammerTempo.Outcome.Applied, state.programmerTempo.set(UUID.fromString(uuid), bpm))

    private fun request(mode: String, cueId: Int? = null, stackId: Int? = null, tempo: Boolean = true, mask: List<String>? = null) =
        ProgrammerRecordRequest(projectId = projectId.toString(), mode = mode, cueId = cueId, cueStackId = stackId, tempo = tempo, mask = mask)

    @Test
    fun `CREATE takes the held tempos, and tempo false leaves them out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val chase = master(client, "Chase", 100.0)
        val moves = master(client, "Moves", 90.0)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        hold(chase, 132.0)
        hold(moves, 64.0)

        val recorded = client.record(request("CREATE", stackId = stackId))
        assertEquals(2, recorded.tempoWritten)
        assertEquals(
            listOf(CueSpeedMasterDto(chase, 132.0), CueSpeedMasterDto(moves, 64.0)),
            recorded.cue.speedMasters,
        )
        assertEquals(0, state.programmerTempo.flow.value.changedSinceInclude)

        val without = client.record(request("CREATE", stackId = stackId, tempo = false))
        assertEquals(0, without.tempoWritten)
        assertTrue(without.cue.speedMasters.isEmpty())
    }

    @Test
    fun `MERGE and Replace replace the held masters' rows and keep the rest, whatever the mask`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val chase = master(client, "Chase", 100.0)
        val moves = master(client, "Moves", 90.0)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q1 = ProgrammerRouteTestSupport.createCue(client, projectId, "Q1", stackId = stackId)
        client.setCueTempos(q1, chase to 100.0, moves to 90.0)

        hold(chase, 140.0)
        val merged = client.record(request("MERGE", cueId = q1, mask = listOf("POSITION")))
        assertEquals(1, merged.tempoWritten)
        assertEquals(mapOf(chase to 140.0, moves to 90.0), client.tempos(q1))

        hold(chase, 150.0)
        val replaced = client.record(request("UPDATE_EXISTING", cueId = q1, mask = listOf("INTENSITY")))
        assertEquals(1, replaced.tempoWritten)
        assertEquals(mapOf(chase to 150.0, moves to 90.0), client.tempos(q1))

        val again = client.record(request("MERGE", cueId = q1))
        assertEquals(0, again.tempoWritten, "a row already saying the held tempo is not counted")
    }

    @Test
    fun `REMOVE deletes the held masters' rows`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val chase = master(client, "Chase", 100.0)
        val moves = master(client, "Moves", 90.0)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q1 = ProgrammerRouteTestSupport.createCue(client, projectId, "Q1", stackId = stackId)
        client.setCueTempos(q1, chase to 100.0, moves to 90.0)

        hold(moves, 70.0)
        val removed = client.record(request("REMOVE", cueId = q1))
        assertEquals(1, removed.tempoRemoved)
        assertEquals(mapOf(chase to 100.0), client.tempos(q1))
    }

    @Test
    fun `Include holds the cue's tempos and Update writes the holds back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val chase = master(client, "Chase", 100.0)
        val moves = master(client, "Moves", 90.0)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q1 = ProgrammerRouteTestSupport.createCue(client, projectId, "Q1", stackId = stackId)
        client.setCueTempos(q1, chase to 128.0)

        val included = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", projectId.toString()); put("cueId", q1) })
        }
        assertEquals(HttpStatusCode.OK, included.status, included.bodyAsText())
        assertEquals(1, included.body<ProgrammerIncludeResponse>().tempoIncluded)
        assertEquals(mapOf(UUID.fromString(chase) to 128.0), state.programmerTempo.flow.value.holds)
        assertEquals(128.0, state.show.speedMasterBank.masterStates().single { it.uuid.toString() == chase }.bpm)
        assertEquals(0, state.programmerTempo.flow.value.changedSinceInclude)

        hold(chase, 136.0)
        hold(moves, 72.0)
        assertEquals(2, state.programmerTempo.flow.value.changedSinceInclude)

        val updated = client.post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", projectId.toString()) })
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        assertEquals(2, updated.body<ProgrammerUpdateResponse>().results.single().tempoWritten)
        assertEquals(mapOf(chase to 136.0, moves to 72.0), client.tempos(q1))
        assertEquals(0, state.programmerTempo.flow.value.changedSinceInclude)
    }

    @Test
    fun `Clear releases every hold and a hold is a programmer step undo lets go`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val chase = master(client, "Chase", 100.0)
        createWsClient().webSocket("/api") {
            sendSerialized<InMessage>(ProgrammerSetTempoInMessage(chase, 140.0))
            awaitOfType<ProgrammerTempoStateOutMessage> { s -> s.masters.any { it.speedMasterUuid == chase } }
        }
        assertEquals("Hold tempo", state.show.programmerHistory.frame().undo.first().label)
        val undo = client.post("/api/rest/projects/$projectId/history/undo") {
            contentType(ContentType.Application.Json)
            setBody(HistoryRequest(programmerOnly = true))
        }
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())
        assertTrue(state.programmerTempo.flow.value.holds.isEmpty())
        assertEquals(100.0, state.show.speedMasterBank.masterStates().single { it.uuid.toString() == chase }.bpm)

        hold(chase, 150.0)
        clearProgrammerCompletely(state)
        assertTrue(state.programmerTempo.flow.value.holds.isEmpty())
        assertEquals(100.0, state.show.speedMasterBank.masterStates().single { it.uuid.toString() == chase }.bpm)
    }
}
