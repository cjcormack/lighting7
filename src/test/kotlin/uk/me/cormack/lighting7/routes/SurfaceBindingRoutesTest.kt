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
import uk.me.cormack.lighting7.midi.CODE_BINDING_WRONG_CONTROL_KIND
import uk.me.cormack.lighting7.midi.CODE_SURFACE_JOB_INVALID
import uk.me.cormack.lighting7.midi.EncoderBankSelection
import uk.me.cormack.lighting7.midi.JobItem
import uk.me.cormack.lighting7.midi.JobItemKind
import uk.me.cormack.lighting7.midi.JobKind
import uk.me.cormack.lighting7.midi.JobSignal
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun `a strip id is no slot any more`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("strip-1", Binding(Does.Level("dimmer"), wash)))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("Unknown controlId 'strip-1'" in response.body<ErrorResponse>().error, response.body<ErrorResponse>().error)
    }

    @Test
    fun `PATCH is validated against the row it would become`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val fader = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("fader-1", Binding(Does.Level("dimmer"), wash)))
        }.body<SurfaceBindingDto>()

        // Moving it onto a button: refused, as it is on POST.
        val moved = client.patch("/api/rest/projects/$projectId/surface-bindings/${fader.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("controlId", JsonPrimitive("btn-2")) })
        }
        assertEquals(HttpStatusCode.BadRequest, moved.status)
        assertEquals(CODE_BINDING_WRONG_CONTROL_KIND, moved.body<ErrorResponse>().code)

        // An unknown control is refused, uncoded.
        val unknown = client.patch("/api/rest/projects/$projectId/surface-bindings/${fader.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("controlId", JsonPrimitive("no-such-control")) })
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }

    // ─── Jobs ───────────────────────────────────────────────────────────────

    @Test
    fun `a region's job is put, read, listed and taken off, per layer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val url = "/api/rest/projects/$projectId/surface-jobs/x-touch-compact-standard/strips"

        val put = client.put(url) {
            contentType(ContentType.Application.Json)
            setBody(PutSurfaceJobRequest("groupStrips", listOf(JobItem.Group("front-wash"), null, JobItem.Group("back-wash"))))
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val job = put.body<SurfaceJobDto>()
        assertEquals(listOf(JobItem.Group("front-wash"), null, JobItem.Group("back-wash")), job.items)
        assertNull(job.layer)

        client.put("$url?layer=layer-a") {
            contentType(ContentType.Application.Json)
            setBody(PutSurfaceJobRequest("headStrips"))
        }
        assertEquals(job.id, client.get(url).body<SurfaceJobDto>().id)
        assertEquals("headStrips", client.get("$url?layer=layer-a").body<SurfaceJobDto>().kind)
        assertEquals(2, client.get("/api/rest/projects/$projectId/surface-jobs").body<List<SurfaceJobDto>>().size)

        // A control the job reaches resolves through it.
        assertEquals(
            Binding(Does.Level("dimmer"), Subject.Group("back-wash")),
            state.controlSurfaceBindingService.resolve(projectId, "x-touch-compact-standard", "fader-3", null, EncoderBankSelection("dimmer"))?.binding,
        )

        assertEquals(HttpStatusCode.NoContent, client.delete(url).status)
        assertEquals(HttpStatusCode.NotFound, client.delete(url).status)
        assertEquals(HttpStatusCode.NotFound, client.get(url).status)
        assertEquals(listOf("layer-a"), client.get("/api/rest/projects/$projectId/surface-jobs").body<List<SurfaceJobDto>>().map { it.layer })
    }

    @Test
    fun `a job that does not fit is refused by name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val response = client.put("/api/rest/projects/$projectId/surface-jobs/x-touch-compact-standard/row-2") {
            contentType(ContentType.Application.Json)
            setBody(PutSurfaceJobRequest("groupStrips", listOf(JobItem.Group("front-wash"))))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals(CODE_SURFACE_JOB_INVALID, body.code)
        assertEquals("Groups as channel strips does not fit Buttons 9–16", body.error)
        assertTrue(state.controlSurfaceBindingService.jobs(projectId).isEmpty())
    }

    @Test
    fun `the job kinds are served whole`() = testApplication {
        mountTestApp(state)
        val kinds = jsonClient().get("/api/rest/surface-job-kinds").body<List<JobKindDto>>()
        assertEquals(JobKind.entries.map { it.id }, kinds.map { it.id })
        assertEquals(JobItemKind.GROUP, kinds.single { it.id == "groupStrips" }.item)
        assertEquals(1, kinds.single { it.id == "transport" }.maxItems)
        assertEquals(JobSignal.SELECTION, kinds.single { it.id == "headStrips" }.follows)
        assertEquals(listOf("encoderPage"), kinds.single { it.id == "encoderPages" }.entries)
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
    fun `a slot preview names the place and the binding a write there replaces`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        client.put(slotUrl) { contentType(ContentType.Application.Json); setBody(slot("btn-2", Binding(Does.Blackout, how = How.ON))) }

        val both = client.get("$slotUrl/preview?deviceTypeKey=$deviceTypeKey&controlId=btn-2").body<uk.me.cormack.lighting7.midi.SurfaceSlotPreview>()
        assertEquals("Button 2", both.place)
        assertEquals(1, both.replaces.size)
        assertTrue(both.replaces.single().startsWith("Replaces "), both.replaces.single())

        val onA = client.get("$slotUrl/preview?deviceTypeKey=$deviceTypeKey&controlId=btn-2&layer=layer-a").body<uk.me.cormack.lighting7.midi.SurfaceSlotPreview>()
        assertEquals("Button 2 · A", onA.place)
        assertTrue(onA.replaces.single().startsWith("Beats "), onA.replaces.single())

        val empty = client.get("$slotUrl/preview?deviceTypeKey=$deviceTypeKey&controlId=btn-7&part=MAIN").body<uk.me.cormack.lighting7.midi.SurfaceSlotPreview>()
        assertEquals(emptyList(), empty.replaces)

        assertEquals(HttpStatusCode.BadRequest, client.get("$slotUrl/preview?deviceTypeKey=$deviceTypeKey&controlId=btn-2&layer=layer-z").status)
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
