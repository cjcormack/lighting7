package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlin.test.assertTrue

/**
 * The preview-compose endpoint: what a cue *would* look like, composed by the same resolver the
 * apply path uses, with nothing published. Backs the Next GO stage view — see
 * `routes/cuePreview.kt` for the three deliberate limits.
 *
 * Hex dimmer sits at `startChannel + 0` (see [LocateTestSupport]).
 */
class CuePreviewRouteTest : RouteIntegrationTest() {

    private val hex1Dimmer = 1
    private val hex2Dimmer = 21

    private suspend fun createStack(client: HttpClient, name: String): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun createCue(
        client: HttpClient,
        name: String,
        stackId: Int,
        fixtureKey: String,
        dimmer: Int,
    ): Int {
        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = name,
                    cueStackId = stackId,
                    propertyAssignments = listOf(
                        CuePropertyAssignmentDto(
                            targetType = TargetRef.Fixture.TYPE,
                            targetKey = fixtureKey,
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

    private suspend fun activate(client: HttpClient, stackId: Int, cueId: Int) =
        client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = cueId))
        }

    private suspend fun preview(client: HttpClient, stackId: Int, cueId: Int? = null) =
        client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/preview") {
            contentType(ContentType.Application.Json)
            setBody(PreviewCueRequest(cueId = cueId))
        }

    private fun List<PreviewChannel>.at(channel: Int): Int? =
        firstOrNull { it.channel == channel }?.value?.toInt()

    @Test
    fun `preview composes the next cue over the other stacks, and changes nothing live`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", hex1Dimmer)
        LocateTestSupport.seedHex(state, projectId, "hex-2", hex2Dimmer)

        val stackA = createStack(client, "Act 1")
        val a1 = createCue(client, "a1", stackA, "hex-1", 100)
        val a2 = createCue(client, "a2", stackA, "hex-1", 200)
        val stackB = createStack(client, "Act 2")
        val b1 = createCue(client, "b1", stackB, "hex-2", 50)

        activate(client, stackA, a1)
        activate(client, stackB, b1)

        val before = state.show.fxEngine.layerResolver.currentCueLayerState

        // No cueId: the stack's effective next, which is a2.
        val response = preview(client, stackA).body<PreviewCueResponse>()

        assertEquals(a2, response.cueId)
        assertEquals(200, response.channels.at(hex1Dimmer), "the incoming cue replaces its own stack's look")
        assertEquals(50, response.channels.at(hex2Dimmer), "another stack's live cue is retained")
        assertTrue(response.skipped.isEmpty(), "nothing should have been skipped: ${response.skipped}")

        assertEquals(
            before,
            state.show.fxEngine.layerResolver.currentCueLayerState,
            "a preview must not disturb the live cue layer",
        )
    }

    @Test
    fun `preview agrees with the GO that follows it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", hex1Dimmer)
        LocateTestSupport.seedHex(state, projectId, "hex-2", hex2Dimmer)

        val stackA = createStack(client, "Act 1")
        val a1 = createCue(client, "a1", stackA, "hex-1", 100)
        createCue(client, "a2", stackA, "hex-1", 200)
        val stackB = createStack(client, "Act 2")
        val b1 = createCue(client, "b1", stackB, "hex-2", 50)

        activate(client, stackA, a1)
        activate(client, stackB, b1)

        val previewed = preview(client, stackA).body<PreviewCueResponse>()

        client.post("/api/rest/projects/$projectId/cue-stacks/$stackA/advance") {
            contentType(ContentType.Application.Json)
            setBody(AdvanceCueStackRequest(direction = "FORWARD"))
        }

        // Every channel the preview asserted has to match the composed layer the GO published —
        // the whole point of running the real resolver rather than a browser-side merge.
        val live = state.show.fxEngine.layerResolver.currentCueLayerState
        val liveByChannel = live.entries.flatMap { (key, value) ->
            uk.me.cormack.lighting7.fx.PropertyChannelWriter
                .resolve(state.show.fixtures.untypedFixture(key.targetKey), key.propertyName, value)
                .map { it.channel to it.value.toInt() }
        }.toMap()

        for (channel in previewed.channels) {
            assertEquals(
                channel.value.toInt(),
                liveByChannel[channel.channel],
                "channel ${channel.channel} disagrees between preview and the GO",
            )
        }
    }

    @Test
    fun `previewing a named cue from another stack is rejected`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", hex1Dimmer)

        val stackA = createStack(client, "Act 1")
        createCue(client, "a1", stackA, "hex-1", 100)
        val stackB = createStack(client, "Act 2")
        val b1 = createCue(client, "b1", stackB, "hex-1", 50)

        assertEquals(HttpStatusCode.BadRequest, preview(client, stackA, b1).status)
    }

    @Test
    fun `an empty stack has nothing to preview`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackId = createStack(client, "Act 1")

        assertEquals(HttpStatusCode.BadRequest, preview(client, stackId).status)
    }
}
