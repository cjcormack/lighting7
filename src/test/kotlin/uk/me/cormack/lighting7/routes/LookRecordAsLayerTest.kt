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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LayerSourceKind
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `asLayer` on `POST /programmer/record-look`: the new Look goes on the programmer's stack as a
 * layer in the same call, the values and effects it took leave Local, and the stage stays still.
 */
class LookRecordAsLayerTest : RouteIntegrationTest() {

    private val store get() = state.show.programmerStore
    private val fx get() = state.show.fxEngine

    @Test
    fun `asLayer adds one layer and releases exactly the keys the Look took`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val other = client.createLook("Other", LookRowDto("fixture", "hex-1", "dimmer", "10"))
        client.includeLook(other)
        setProgrammer("hex-1", "dimmer", "200")
        setProgrammer("hex-1", "rgbColour", "#ff8000")
        setProgrammer("hex-2", "dimmer", "120")
        val before = channels()

        val response = client.recordAsLayer(
            name = "Warm", mask = listOf("INTENSITY"), targets = listOf(CueTargetDto("fixture", "hex-1")),
        )

        val layer = store.layers.single()
        assertEquals(response.layerId, layer.layerId)
        assertEquals(LayerSourceKind.LOOK, layer.source.kind)
        assertEquals(response.look.id, layer.source.id)
        assertNull(layer.attributeMask, "the Look holds only what was recorded, so the layer needs no mask")
        assertEquals(1, response.keysReleased)
        assertFalse(webOwns("hex-1", "dimmer"), "the recorded level left Local")
        assertTrue(webOwns("hex-1", "rgbColour"), "a family outside the mask stays Local")
        assertTrue(webOwns("hex-2", "dimmer"), "a head outside the selection stays Local")
        assertEquals(before, channels(), "and nothing on stage moved")
        assertEquals(other, store.lastIncludedTarget?.lookId, "the include target is where it was")
    }

    @Test
    fun `a group row releases each member it names`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "wash", "hex-1", "hex-2")
        ProgrammerHandler.set(state, TargetRef.Group("wash"), "dimmer", "180", 0)
        val before = channels()

        val response = client.recordAsLayer(name = "Wash")

        assertEquals(listOf(TargetRef.Group.TYPE), response.look.rows.map { it.targetType }, "collapsed to one group row")
        assertEquals(2, response.keysReleased)
        assertFalse(webOwns("hex-1", "dimmer"))
        assertFalse(webOwns("hex-2", "dimmer"))
        assertEquals(before, channels())
    }

    @Test
    fun `asLayer with any mode but CREATE is refused and changes nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val look = client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "10"))
        setProgrammer("hex-1", "dimmer", "200")

        for (mode in listOf("MERGE", "UPDATE_EXISTING", "REMOVE")) {
            val response = client.recordLook(
                ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = mode, lookId = look, asLayer = true),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status, mode)
            assertTrue(response.bodyAsText().contains("asLayer"), "the refusal names the problem")
        }
        assertTrue(store.layers.isEmpty())
        assertTrue(webOwns("hex-1", "dimmer"))
    }

    @Test
    fun `a write that fails leaves the programmer as it was`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        client.createLook("Warm", LookRowDto("fixture", "hex-1", "dimmer", "10"))
        setProgrammer("hex-1", "dimmer", "200")
        val effect = client.pressFx()

        val response = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), name = "Warm", fxIds = listOf(effect.id), asLayer = true,
            ),
        )

        assertEquals(HttpStatusCode.BadRequest, response.status, "the name is taken")
        assertTrue(store.layers.isEmpty())
        assertTrue(webOwns("hex-1", "dimmer"))
        assertTrue(assertNotNull(fx.getFx(effect.id)).isLocalFx, "the FX is still the operator's")
    }

    @Test
    fun `a ticked FX becomes the layer's, the same instance, and is not doubled`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        setProgrammer("hex-1", "rgbColour", "#ff8000")
        val effect = client.pressFx()

        val response = client.recordAsLayer(name = "Pulse", fxIds = listOf(effect.id))

        assertEquals(1, response.fxWritten)
        val band = fx.getActiveFx().filter { FxEngine.isProgrammerFxPriority(it.priority) && !it.leaving }
        assertEquals(listOf(effect.id), band.map { it.id }, "one instance, the one that was running, so its phase is kept")
        val adopted = band.single()
        assertEquals(response.layerId, adopted.programmerLayerId)
        assertEquals(response.look.id, adopted.source?.id)
        assertFalse(adopted.isLocalFx)
    }

    @Test
    fun `the held scenery the Look took passes to its layer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val moon = UUID.fromString(client.moon())
        assertTrue(state.programmerScenery.set(moon, buildJsonObject { put("trimM", 3.0) }, null).isEmpty())

        client.recordAsLayer(name = "Moon in")

        assertNull(state.programmerScenery.flow.value.elements[moon], "the programmer no longer holds it")
        state.sceneryService.awaitIdle()
        val entry = state.sceneryService.frame.value.entries.single { it.elementUuid == moon }
        assertEquals(3.0, entry.state.trimM, "the moon stays in")
        assertEquals("programmerLook", entry.source.kind, "held by the layer now")
    }

    @Test
    fun `an included Look keeps its rows for the keys the layer took`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val included = client.createLook("Other", LookRowDto("fixture", "hex-1", "dimmer", "10"))
        client.includeLook(included)
        setProgrammer("hex-1", "dimmer", "200")

        client.recordAsLayer(name = "Warm")
        client.update()

        assertEquals("10", client.lookRowValue(included, "hex-1", "dimmer"), "a Look cannot take the layer, so its row stays")
    }

    @Test
    fun `an included cue's own row for a key the layer took goes on Update, under a masked Include too`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "Q5",
            rows = listOf(
                CueAttributeAssignmentDto("fixture", "hex-1", "dimmer", "50"),
                CueAttributeAssignmentDto("fixture", "hex-1", "rgbColour", "#ff0000"),
            ),
        )
        client.includeCue(cueId, mask = listOf("COLOUR"))
        setProgrammer("hex-1", "dimmer", "200")

        val recorded = client.recordAsLayer(name = "Warm", mask = listOf("INTENSITY"))
        assertEquals(cueId, store.lastIncludedTarget?.cueId, "the cue is still what Update writes")
        client.update()

        val cue = client.cue(cueId)
        assertEquals(listOf("rgbColour"), cue.attributeAssignments.map { it.attribute }, "the layer holds the level now")
        assertEquals(listOf(recorded.look.id), cue.layers.mapNotNull { it.lookId })
    }

    @Test
    fun `a cue FX the layer took over leaves the cue when Update writes the layer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "Q5",
            rows = listOf(CueAttributeAssignmentDto("fixture", "hex-1", "rgbColour", "#ff0000")),
            adHoc = listOf(CueAdHocFxSpec(targetType = "fixture", targetKey = "hex-1", fxType = "Wave", attribute = "dimmer")),
        )
        client.includeCue(cueId)
        val included = fx.getActiveFx().single { it.programmerOrigin?.cueId == cueId }

        val recorded = client.recordAsLayer(name = "Pulse", fxIds = listOf(included.id))
        assertEquals(recorded.layerId, assertNotNull(fx.getFx(included.id)).programmerLayerId)
        client.update()

        val cue = client.cue(cueId)
        assertTrue(cue.adHocFx.isEmpty(), "the layer plays it, so the cue's own copy is retired")
        assertEquals(listOf(recorded.look.id), cue.layers.mapNotNull { it.lookId })
    }

    @Test
    fun `an FX taken over from above a Local FX it shares a key with is named`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val under = client.pressFx("Wave")
        val over = client.pressFx("Flicker", stack = true)

        val response = client.recordAsLayer(name = "Flicker", fxIds = listOf(over.id))

        assertEquals(1, response.warnings.size, response.warnings.toString())
        assertTrue(fx.getFx(under.id)!!.isLocalFx)
    }

    @Test
    fun `two identical ticked FX both keep running though the layer runs one`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val first = client.pressFx("Wave", stack = true, blendMode = "ADDITIVE")
        val second = client.pressFx("Wave", stack = true, blendMode = "ADDITIVE")

        val response = client.recordAsLayer(name = "Twins", fxIds = listOf(first.id, second.id))

        val running = listOf(first.id, second.id).map { assertNotNull(fx.getFx(it), "still running") }
        assertEquals(1, running.count { it.programmerLayerId == response.layerId }, "the layer runs one")
        assertEquals(1, running.count { it.isLocalFx }, "the other stays the operator's")
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun controller() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    /** Both hexes' channels as sent. */
    private fun channels() = (1..24).map { controller().getEffectiveValue(it) }

    /** Whether a `WEB` slot holds [attribute] on [fixtureKey]. */
    private fun webOwns(fixtureKey: String, attribute: String) =
        store.slotsFor(fixtureKey, attribute).any { it.owner == ProgrammerOwner.WEB }

    private suspend fun HttpClient.recordAsLayer(
        name: String,
        mask: List<String>? = null,
        targets: List<CueTargetDto>? = null,
        fxIds: List<Long> = emptyList(),
    ): ProgrammerRecordLookResponse {
        val response = recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), name = name, mask = mask, targets = targets,
                fxIds = fxIds, asLayer = true,
            ),
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.recordLook(request: ProgrammerRecordLookRequest) =
        post("/api/rest/programmer/record-look") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.includeLook(lookId: Int) =
        post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), lookId = lookId))
        }

    private suspend fun HttpClient.includeCue(cueId: Int, mask: List<String>? = null) =
        post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId, mask = mask))
        }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

    private suspend fun HttpClient.update() =
        post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
        }

    private suspend fun HttpClient.cue(cueId: Int): CueDetails =
        get("/api/rest/projects/$projectId/cues/$cueId").body()

    private suspend fun HttpClient.lookRowValue(lookId: Int, targetKey: String, attribute: String): String? =
        get("/api/rest/projects/$projectId/looks/$lookId").body<LookDetails>()
            .rows.firstOrNull { it.targetKey == targetKey && it.attribute == attribute }?.value

    private suspend fun HttpClient.createLook(name: String, vararg rows: LookRowDto): Int =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name, rows = rows.toList()))
        }.body<LookDetails>().id

    /** A Local FX on hex-1's dimmer. */
    private suspend fun HttpClient.pressFx(fxType: String = "Wave", stack: Boolean = false, blendMode: String? = null) =
        post("/api/rest/projects/$projectId/programmer/fx") {
            contentType(ContentType.Application.Json)
            setBody(
                ProgrammerFxRequest(
                    fxType = fxType, attribute = "dimmer", targets = listOf(CueTargetDto("fixture", "hex-1")),
                    stack = stack, blendMode = blendMode, amount = blendMode?.let { 1.0 },
                ),
            )
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertNotNull(fx.getFx(response.body<ProgrammerFxResponse>().fxId))
        }

    /** A flown moon, out at 7 m. Answers its uuid. */
    private suspend fun HttpClient.moon(): String =
        post("/api/rest/projects/$projectId/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", "Moon"); put("kind", "OBJECT"); put("layer", "SET")
                put("positionZ", 3.0); put("widthM", 1.0); put("depthM", 0.05); put("heightM", 1.0)
                putJsonObject("params") {
                    put("shape", "DISC"); put("flies", true)
                    putJsonObject("states") { put("trimM", 7.0) }
                }
            })
        }.body<StageElementDto>().uuid

    private fun setProgrammer(fixtureKey: String, attribute: String, value: String) {
        ProgrammerHandler.set(state, TargetRef.Fixture(fixtureKey), attribute, value, 0)
    }

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)
}
