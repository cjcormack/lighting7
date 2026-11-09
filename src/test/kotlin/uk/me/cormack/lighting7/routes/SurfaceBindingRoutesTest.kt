package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.BindingJson
import uk.me.cormack.lighting7.midi.CatalogueEntry
import uk.me.cormack.lighting7.midi.ControlKind
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.How
import uk.me.cormack.lighting7.midi.RecordKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.SubjectKind
import uk.me.cormack.lighting7.midi.SurfaceCatalogue
import uk.me.cormack.lighting7.midi.WritesTo
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bind-time shape validation on the surface-binding routes, the slot write the editors use, and the
 * function catalogue. A strip id and a control id share one slot namespace but are not
 * interchangeable, and the check applies to every write.
 */
class SurfaceBindingRoutesTest : RouteIntegrationTest() {

    private val deviceTypeKey = "x-touch-compact-standard"
    private val wash = Subject.Group("front-wash")

    private fun bindingJson(binding: Binding): JsonObject =
        BindingJson.encodeToJsonElement(Binding.serializer(), binding) as JsonObject

    private fun create(controlId: String, binding: Binding) = buildJsonObject {
        put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
        put("controlId", JsonPrimitive(controlId))
        put("binding", bindingJson(binding))
    }

    private fun seedStack(): Pair<Int, java.util.UUID> = transaction(state.database) {
        val stack = DaoCueStack.new {
            project = DaoProject.findById(projectId)!!
            name = "Song"; loop = false; type = CueStackType.STACK.name; sortOrder = 0
        }
        stack.id.value to stack.uuid
    }

    @Test
    fun `a strip binding on an ordinary control is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("fader-1", Binding(Does.Strip, wash)))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CODE_BINDING_STRIP_NEEDS_STRIP, response.body<ErrorResponse>().code)
    }

    @Test
    fun `a non-strip binding on a strip slot is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("strip-1", Binding(Does.Blackout)))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CODE_BINDING_CONTROL_NOT_STRIP, response.body<ErrorResponse>().code)
    }

    @Test
    fun `a strip binding on a strip slot is accepted`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("strip-1", Binding(Does.Strip, wash)))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(Does.Strip, response.body<SurfaceBindingDto>().binding.does)
    }

    @Test
    fun `PATCH is validated against the row it would become`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val strip = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("strip-1", Binding(Does.Strip, wash)))
        }.body<SurfaceBindingDto>()

        // Moving a strip binding onto an ordinary control: refused, as it is on POST.
        val moved = client.patch("/api/rest/projects/$projectId/surface-bindings/${strip.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("controlId", JsonPrimitive("fader-1")) })
        }
        assertEquals(HttpStatusCode.BadRequest, moved.status)
        assertEquals(CODE_BINDING_STRIP_NEEDS_STRIP, moved.body<ErrorResponse>().code)

        // And swapping the binding out from under a strip slot is refused the other way.
        val retargeted = client.patch("/api/rest/projects/$projectId/surface-bindings/${strip.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("binding", bindingJson(Binding(Does.Blackout))) })
        }
        assertEquals(HttpStatusCode.BadRequest, retargeted.status)
        assertEquals(CODE_BINDING_CONTROL_NOT_STRIP, retargeted.body<ErrorResponse>().code)

        // An unknown control is still refused, uncoded, as before.
        val unknown = client.patch("/api/rest/projects/$projectId/surface-bindings/${strip.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("controlId", JsonPrimitive("no-such-control")) })
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }

    private fun slot(controlId: String, binding: Binding, part: ControlPart = ControlPart.MAIN, layer: String? = null, takeover: String? = null) =
        buildJsonObject {
            put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
            put("controlId", JsonPrimitive(controlId))
            put("part", JsonPrimitive(part.name))
            layer?.let { put("layer", JsonPrimitive(it)) }
            put("binding", bindingJson(binding))
            takeover?.let { put("takeoverPolicy", JsonPrimitive(it)) }
        }

    private fun slotRef(controlId: String, part: ControlPart = ControlPart.MAIN, layer: String? = null) = buildJsonObject {
        put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
        put("controlId", JsonPrimitive(controlId))
        put("part", JsonPrimitive(part.name))
        layer?.let { put("layer", JsonPrimitive(it)) }
    }

    private val slotUrl get() = "/api/rest/projects/$projectId/surface-bindings/slot"

    @Test
    fun `a slot write binds an empty part, and a second write replaces it in place`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val first = client.put(slotUrl) {
            contentType(ContentType.Application.Json)
            setBody(slot("enc-1", Binding(Does.Go, Subject.Record(RecordKind.STACK, seedStack().second)), ControlPart.PUSH, "layer-a"))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val written = first.body<SurfaceBindingDto>()
        assertEquals(ControlPart.PUSH, written.part)
        assertEquals("layer-a", written.bank)

        val second = client.put(slotUrl) {
            contentType(ContentType.Application.Json)
            setBody(slot("enc-1", Binding(Does.Blackout, how = How.ON), ControlPart.PUSH, "layer-a"))
        }.body<SurfaceBindingDto>()
        assertEquals(written.id, second.id, "the slot's own row is rewritten, not a second one added")
        assertEquals(Binding(Does.Blackout, how = How.ON), second.binding)
        assertEquals(1, state.controlSurfaceBindingService.list(projectId).size)
    }

    @Test
    fun `a slot write on one layer leaves the other layers' rows alone`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.put(slotUrl) { contentType(ContentType.Application.Json); setBody(slot("btn-2", Binding(Does.Blackout))) }
        client.put(slotUrl) { contentType(ContentType.Application.Json); setBody(slot("btn-2", Binding(Does.Drop), layer = "layer-b")) }

        val rows = state.controlSurfaceBindingService.list(projectId)
        assertEquals(setOf(null, "layer-b"), rows.map { it.bank }.toSet())
    }

    @Test
    fun `a slot write sets the takeover it is given`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val written = client.put(slotUrl) {
            contentType(ContentType.Application.Json)
            setBody(slot("fader-1", Binding(Does.Level("dimmer"), wash), takeover = "IMMEDIATE"))
        }.body<SurfaceBindingDto>()
        assertEquals("IMMEDIATE", written.takeoverPolicy)
        val cleared = client.put(slotUrl) {
            contentType(ContentType.Application.Json)
            setBody(slot("fader-1", Binding(Does.Level("dimmer"), wash)))
        }.body<SurfaceBindingDto>()
        assertEquals(null, cleared.takeoverPolicy, "a slot write states the whole slot")
    }

    @Test
    fun `a slot write is refused by the function's spec`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val goOnFader = client.put(slotUrl) {
            contentType(ContentType.Application.Json)
            setBody(slot("fader-1", Binding(Does.Go, Subject.Record(RecordKind.STACK, seedStack().second))))
        }
        assertEquals(HttpStatusCode.BadRequest, goOnFader.status)
        assertEquals("BINDING_WRONG_CONTROL_KIND", goOnFader.body<ErrorResponse>().code)

        val fanOnOne = client.put(slotUrl) {
            contentType(ContentType.Application.Json)
            setBody(slot("enc-2", Binding(Does.Fan(uk.me.cormack.lighting7.midi.FanAxis.PAN), Subject.Fixture("hex-1"))))
        }
        assertEquals(HttpStatusCode.BadRequest, fanOnOne.status)
        assertEquals("BINDING_WRONG_SUBJECT", fanOnOne.body<ErrorResponse>().code)

        val stripOnControl = client.put(slotUrl) {
            contentType(ContentType.Application.Json)
            setBody(slot("fader-1", Binding(Does.Strip, wash)))
        }
        assertEquals(CODE_BINDING_STRIP_NEEDS_STRIP, stripOnControl.body<ErrorResponse>().code)
        assertTrue(state.controlSurfaceBindingService.list(projectId).isEmpty())
    }

    @Test
    fun `a slot delete clears the part, and an empty part answers 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.put(slotUrl) { contentType(ContentType.Application.Json); setBody(slot("btn-2", Binding(Does.Blackout))) }

        val gone = client.delete(slotUrl) { contentType(ContentType.Application.Json); setBody(slotRef("btn-2")) }
        assertEquals(HttpStatusCode.NoContent, gone.status)
        assertTrue(state.controlSurfaceBindingService.list(projectId).isEmpty())
        val again = client.delete(slotUrl) { contentType(ContentType.Application.Json); setBody(slotRef("btn-2")) }
        assertEquals(HttpStatusCode.NotFound, again.status)
    }

    @Test
    fun `a POST answers the binding and its part`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val binding = Binding(Does.Level("dimmer"), wash)

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("fader-1", binding))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val dto = response.body<SurfaceBindingDto>()
        assertEquals(binding, dto.binding)
        assertEquals(ControlPart.MAIN, dto.part)
    }

    @Test
    fun `the old union's target is no longer a write`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
                put("controlId", JsonPrimitive("btn-2"))
                put("target", buildJsonObject { put("type", JsonPrimitive("blackout")) })
            })
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(state.controlSurfaceBindingService.list(projectId).isEmpty())
    }

    @Test
    fun `the function catalogue lists every entry once, from the specs`() = testApplication {
        mountTestApp(state)
        val entries = jsonClient().get("/api/rest/surface-functions").body<List<CatalogueEntry>>()
        assertEquals(SurfaceCatalogue.entries.map { it.id }, entries.map { it.id })
        val go = entries.single { it.id == "go" }
        assertEquals(listOf(SubjectKind.STACK), go.subjects)
        assertEquals(listOf(ControlKind.BUTTON), go.inputs)
        assertEquals(WritesTo.PLAYBACK, go.writesTo)
    }
}
