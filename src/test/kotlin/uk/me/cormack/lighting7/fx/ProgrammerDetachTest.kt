package uk.me.cormack.lighting7.fx

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.routes.StageElementDto
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.ProgrammerDetachLayerInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerLayerStateOutMessage
import uk.me.cormack.lighting7.routes.ApplyLookRequest
import uk.me.cormack.lighting7.routes.ApplyLookResponse
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.ErrorResponse
import uk.me.cormack.lighting7.routes.LookDetails
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Detach* and *Apply as values* (apply-model plan §3.5): a layer turned into Local leaves the stage
 * where it was, and a Look applied as values leaves what a press followed by Detach would.
 */
class ProgrammerDetachTest : RouteIntegrationTest() {

    private val stack get() = state.show.programmerLayerStack
    private val store get() = state.show.programmerStore
    private val engine get() = state.show.fxEngine

    private fun controller() =
        state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    /** hex-1's twelve channels as sent. */
    private fun channels() = (1..12).map { controller().getEffectiveValue(it) }

    /** hex-1's channels as sent, by number, less [channel]. */
    private fun channelsExcept(channel: Int) =
        (1..12).filter { it != channel }.associateWith { controller().getEffectiveValue(it) }

    private suspend fun HttpClient.createLook(
        name: String,
        withEffect: Boolean,
        rows: List<LookRowDto> = listOf(
            LookRowDto("fixture", "hex-1", "dimmer", "200"),
            LookRowDto("fixture", "hex-1", "rgbColour", "#ff8000"),
        ),
    ): LookDetails =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = rows,
                    effects = if (!withEffect) emptyList() else listOf(
                        LookEffectDto(
                            targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                            effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                            beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                        ),
                    ),
                ),
            )
        }.body()

    /** A flown moon, out (its base) at 7 m. Answers its uuid. */
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

    private suspend fun HttpClient.putLookScenery(lookId: Int, elementUuid: String, trimM: Double) {
        val resp = put("/api/rest/projects/$projectId/looks/$lookId/scenery") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("scenery") {
                    add(buildJsonObject { put("elementUuid", elementUuid); putJsonObject("state") { put("trimM", trimM) } })
                }
            })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private fun press(look: LookDetails) = stack.toggle(
        LayerSource.look(look.id, UUID.fromString(look.uuid), look.name),
        listOf(CueTargetDto("fixture", "hex-1")),
    )

    @Test
    fun `detach leaves every channel where it was and the values in Local`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = jsonClient().createLook("Amber", withEffect = false)
        press(look)
        val before = channels()

        val outcome = assertNotNull(stack.detach(store.layers.single().layerId))

        assertTrue(store.layers.isEmpty(), "the layer is gone")
        assertEquals(before, channels(), "and nothing on stage moved")
        assertEquals(2, outcome.written)
        for (property in listOf("dimmer", "rgbColour")) {
            val slot = assertNotNull(store.get("hex-1", property), property)
            assertEquals(ProgrammerOwner.WEB, slot.owner, property)
            assertTrue(slot.touched, "Record takes it as the operator's own")
        }
    }

    @Test
    fun `detach keeps the layer's effects running as Local effects`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = jsonClient().createLook("Pulse", withEffect = true)
        press(look)
        val effect = engine.getActiveEffects().single { it.programmerLayerEffectKey != null }
        // The Pulse keeps moving the dimmer on master 1's live clock, so a tick between the two
        // reads changes it whatever Detach does; that channel is the effect's, asserted below.
        val pulsed = state.show.fixtures.fixture<HexFixture>("hex-1").dimmer.channelNo
        val before = channelsExcept(pulsed)

        val outcome = assertNotNull(stack.detach(store.layers.single().layerId))

        assertEquals(before, channelsExcept(pulsed), "every channel the Pulse does not drive stays where it was")
        assertEquals(1, outcome.effects)
        val after = assertNotNull(engine.getEffect(effect.id), "the same instance, so its phase is kept")
        assertNull(after.programmerLayerEffectKey)
        assertNull(after.source)
        assertTrue(after.isLocalEffect)
    }

    @Test
    fun `detach removes a spent N-shot rather than keeping it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = jsonClient().createLook("Pulse", withEffect = true)
        press(look)
        val effect = engine.getActiveEffects().single { it.programmerLayerEffectKey != null }
        effect.spent = true

        assertEquals(0, assertNotNull(stack.detach(store.layers.single().layerId)).effects)
        assertNull(engine.getEffect(effect.id))
    }

    @Test
    fun `detach leaves a value the operator set over the layer alone`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = jsonClient().createLook("Amber", withEffect = false)
        press(look)
        val hex = state.show.fixtures.untypedGroupableFixture("hex-1")
        engine.programmer.writeProperty(
            ProgrammerOwner.WEB, hex, "dimmer", CueAssignmentResolver.PropertyValue.Slider(40u),
        )

        stack.detach(store.layers.single().layerId)

        val dimmer = store.get("hex-1", "dimmer")!!.value.resolved as CueAssignmentResolver.PropertyValue.Slider
        assertEquals(40u.toUByte(), dimmer.value)
    }

    @Test
    fun `detach under a higher layer that blends over it leaves the stage still`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        press(client.createLook("Amber", withEffect = false))
        press(client.createLook("Blue", withEffect = false, rows = listOf(LookRowDto("fixture", "hex-1", "rgbColour", "#0000ff"))))
        val (amber, blue) = store.layers
        stack.patch(blue.layerId, amount = 0.5)
        val before = channels()

        stack.detach(amber.layerId)

        assertEquals(before, channels(), "the half-blue colour stays where it was, not half blue over nothing")
        assertEquals(ProgrammerOwner.WEB, store.get("hex-1", "rgbColour")?.owner)
    }

    @Test
    fun `detach under a flash keeps the layer's value beneath it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        press(jsonClient().createLook("Amber", withEffect = false))
        val hex = state.show.fixtures.untypedGroupableFixture("hex-1")
        engine.programmer.writeProperty(ProgrammerOwner.FLASH, hex, "dimmer", CueAssignmentResolver.PropertyValue.Slider(255u))
        val before = channels()

        stack.detach(store.layers.single().layerId)

        assertEquals(before, channels(), "the flash is still on top")
        engine.programmer.clearProperty(ProgrammerOwner.FLASH, hex, "dimmer")
        val dimmer = store.get("hex-1", "dimmer")!!
        assertEquals(ProgrammerOwner.WEB, dimmer.owner)
        assertEquals(200u.toUByte(), (dimmer.value.resolved as CueAssignmentResolver.PropertyValue.Slider).value, "the layer's level, kept")
    }

    @Test
    fun `detach hands a Look's scenery to the programmer`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val moon = client.moon()
        val look = client.createLook("Moon in", withEffect = false)
        client.putLookScenery(look.id, moon, trimM = 3.0)
        press(look)

        stack.detach(store.layers.single().layerId)

        val held = state.programmerScenery.flow.value.elements[UUID.fromString(moon)]
        assertEquals(3.0, held?.state?.trimM, "the moon stays in, now the programmer's")
    }

    @Test
    fun `a Look applied as values hands its scenery to the programmer`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val moon = client.moon()
        val look = client.createLook("Moon in", withEffect = false)
        client.putLookScenery(look.id, moon, trimM = 3.0)

        client.post("/api/rest/projects/$projectId/looks/${look.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1"))))
        }

        assertEquals(3.0, state.programmerScenery.flow.value.elements[UUID.fromString(moon)]?.state?.trimM)
    }

    @Test
    fun `the detachLayer frame detaches and answers the layer state`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        press(jsonClient().createLook("Amber", withEffect = false))
        val layerId = store.layers.single().layerId

        createWsClient().webSocket("/api") {
            sendSerialized<InMessage>(ProgrammerDetachLayerInMessage(layerId))
            awaitOfType<ProgrammerLayerStateOutMessage> { it.layers.isEmpty() }
        }
        assertEquals(ProgrammerOwner.WEB, store.get("hex-1", "dimmer")?.owner)
    }

    @Test
    fun `a Look applied as values starts its effects above an existing layer's`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        press(client.createLook("Pulse", withEffect = true))
        val layerEffect = engine.getActiveEffects().single { it.programmerLayerEffectKey != null }
        val look = client.createLook("Pulse again", withEffect = true)

        val body = client.post("/api/rest/projects/$projectId/looks/${look.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1"))))
        }.body<ApplyLookResponse>()

        val local = assertNotNull(engine.getEffect(body.effectIds.single()))
        assertTrue(local.priority > layerEffect.priority, "on top, as a press and a Detach would leave it")
    }

    @Test
    fun `a Look apply with a bad target is a 400, not a 500`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val look = client.createLook("Amber", withEffect = false)

        val resp = client.post("/api/rest/projects/$projectId/looks/${look.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyLookRequest(targets = listOf(CueTargetDto("bogus", "x"))))
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
    }

    @Test
    fun `detach of a layer that is not there changes nothing`() = testApplication {
        mountTestApp(state)
        assertNull(stack.detach(12345))
    }

    @Test
    fun `a Look applied as values is a press and a Detach in one`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val look = client.createLook("Pulse", withEffect = true)

        val resp = client.post("/api/rest/projects/$projectId/looks/${look.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1"))))
        }

        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = resp.body<ApplyLookResponse>()
        assertEquals(2, body.written)
        assertTrue(store.layers.isEmpty(), "no layer ever goes on")
        assertEquals(ProgrammerOwner.WEB, store.get("hex-1", "dimmer")?.owner)
        val effect = assertNotNull(engine.getEffect(body.effectIds.single()))
        assertTrue(effect.isLocalEffect)
    }

    @Test
    fun `a Look applied as values honours the selection's mask`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val look = client.createLook("Amber", withEffect = false)

        val masked = client.post("/api/rest/projects/$projectId/looks/${look.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1")), families = listOf("COLOUR")))
        }.body<ApplyLookResponse>()
        assertEquals(1, masked.written)
        assertEquals(listOf("INTENSITY"), masked.skippedFamilies)
        assertNull(store.get("hex-1", "dimmer"))

        val outside = client.post("/api/rest/projects/$projectId/looks/${look.id}/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyLookRequest(targets = listOf(CueTargetDto("fixture", "hex-1")), families = listOf("POSITION")))
        }
        assertEquals(HttpStatusCode.BadRequest, outside.status)
        assertEquals("LOOK_OUTSIDE_MASK", outside.body<ErrorResponse>().code)
    }
}
