package uk.me.cormack.lighting7.plugins

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.BindingJson
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.MidiInputEvent
import uk.me.cormack.lighting7.midi.PartSource
import uk.me.cormack.lighting7.midi.RecordKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.WritesTo
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The served picture and identify mode over real sockets (surface-setup plan D14, D15). */
class SurfaceSocketTest : RouteIntegrationTest() {
    private val deviceTypeKey = "x-touch-compact-standard"

    private fun slot(controlId: String, binding: Binding, layer: String? = null, part: ControlPart = ControlPart.MAIN): JsonObject = buildJsonObject {
        put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
        put("controlId", JsonPrimitive(controlId))
        put("part", JsonPrimitive(part.name))
        layer?.let { put("layer", JsonPrimitive(it)) }
        put("binding", BindingJson.encodeToJsonElement(Binding.serializer(), binding))
    }

    private fun seedStack(name: String) = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject.findById(projectId)!!
            this.name = name; loop = false; type = CueStackType.STACK.name; sortOrder = 0
        }.uuid
    }

    @Test
    fun `the map names a bound record by its name, on the layer it is bound on`() = testApplication {
        mountTestApp(state)
        val stack = seedStack("Act 1")
        val rest = jsonClient()
        val client = createWsClient()

        client.webSocket("/api") {
            // No device is attached and nothing is bound, so no type is mapped yet.
            awaitOfType<SurfaceBankStateOutMessage>()

            rest.put("/api/rest/projects/$projectId/surface-bindings/slot") {
                contentType(ContentType.Application.Json)
                setBody(slot("btn-2", Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)), layer = "layer-a"))
            }
            val bound = awaitOfType<SurfaceMapStateOutMessage> {
                it.deviceTypeKey == deviceTypeKey && it.layers["layer-a"]?.get("btn-2:MAIN")?.from == PartSource.DIRECT
            }
            assertEquals(projectId, bound.projectId)
            val part = bound.layers.getValue("layer-a").getValue("btn-2:MAIN")
            assertEquals("GO · Act 1", part.label)
            assertEquals("Act 1", part.subjectLabel)
            assertEquals("go", part.entry)
            assertEquals(WritesTo.PLAYBACK, part.writesTo)
            assertEquals("Lit while Act 1 is running.", part.shows)
            assertEquals("layer-a", part.bank)
            assertEquals(PartSource.NONE, bound.layers.getValue("layer-b").getValue("btn-2:MAIN").from, "layer B is untouched")
        }
    }

    @Test
    fun `a control on a strip is drawn from its strip until it is bound on its own`() = testApplication {
        mountTestApp(state)
        val rest = jsonClient()
        val client = createWsClient()
        val wash = Subject.Group("front-wash")

        client.webSocket("/api") {
            awaitOfType<SurfaceBankStateOutMessage>()
            rest.put("/api/rest/projects/$projectId/surface-bindings/slot") {
                contentType(ContentType.Application.Json)
                setBody(slot("strip-1", Binding(Does.Strip, wash)))
            }
            val viaStrip = awaitOfType<SurfaceMapStateOutMessage> {
                it.deviceTypeKey == deviceTypeKey && it.layers["layer-a"]?.get("fader-1:MAIN")?.from == PartSource.STRIP
            }.layers.getValue("layer-a").getValue("fader-1:MAIN")
            assertEquals("strip-1", viaStrip.stripId)
            assertEquals(Does.Level("dimmer"), viaStrip.does)
            assertNull(viaStrip.bank)

            // *Make this control its own*: the derived binding at the control's own slot.
            rest.put("/api/rest/projects/$projectId/surface-bindings/slot") {
                contentType(ContentType.Application.Json)
                setBody(slot("fader-1", Binding(Does.Level("dimmer"), wash), layer = "layer-a"))
            }
            val own = awaitOfType<SurfaceMapStateOutMessage> {
                it.deviceTypeKey == deviceTypeKey && it.layers["layer-a"]?.get("fader-1:MAIN")?.from == PartSource.DIRECT
            }
            assertEquals(PartSource.STRIP, own.layers.getValue("layer-b").getValue("fader-1:MAIN").from, "the other layer still follows the strip")
        }
    }

    @Test
    fun `a capture reaches only the window that waited for it`() = testApplication {
        mountTestApp(state)
        val client = createWsClient()

        coroutineScope {
            val otherReady = CompletableDeferred<Unit>()
            val other = async {
                var heard: SurfaceIdentifyCapturedOutMessage? = null
                client.webSocket("/api") {
                    awaitOfType<SurfaceBankStateOutMessage>()
                    otherReady.complete(Unit)
                    heard = runCatching { withTimeout(1_500) { awaitOfType<SurfaceIdentifyCapturedOutMessage>() } }.getOrNull()
                }
                heard
            }

            client.webSocket("/api") {
                awaitOfType<SurfaceBankStateOutMessage>()
                withTimeout(20_000) { otherReady.await() }
                sendSerialized<InMessage>(SurfaceIdentifyBeginInMessage())
                val waiting = awaitOfType<SurfaceIdentifyStateOutMessage> { it.waiting }
                state.surfaceInputRouter.offerInputForTest(deviceTypeKey, MidiInputEvent.NoteOn(0, note = 16, velocity = 127u), "x-touch-1")
                val captured = awaitOfType<SurfaceIdentifyCapturedOutMessage>()
                assertEquals(waiting.sessionId, captured.sessionId)
                assertEquals("btn-1", captured.controlId)
                assertEquals("x-touch-1", captured.displayKey)
                delay(200)
            }

            assertNull(withTimeout(20_000) { other.await() }, "the other window heard nothing")
        }
    }
}
