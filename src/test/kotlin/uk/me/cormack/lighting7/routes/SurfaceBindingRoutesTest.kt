package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import uk.me.cormack.lighting7.midi.ControlPart
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.RecordKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bind-time shape validation on the surface-binding routes, and the *Fader only…* expansion.
 * A strip id and a control id share one slot namespace but are not interchangeable, and the
 * check applies to POST **and** PATCH — a drag onto an already-bound control saves as a PATCH.
 * A write carries a `binding` or, from the old screens, a `target` in the old union's words.
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

    private fun createOld(controlId: String, target: JsonObject) = buildJsonObject {
        put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
        put("controlId", JsonPrimitive(controlId))
        put("target", target)
    }

    private fun oldTarget(type: String, vararg fields: Pair<String, Any>) = buildJsonObject {
        put("type", JsonPrimitive(type))
        for ((key, value) in fields) {
            put(
                key,
                when (value) {
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                },
            )
        }
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
        assertEquals("strip", response.body<SurfaceBindingDto>().targetType)
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

    @Test
    fun `expand replaces a strip with the bindings it was deriving`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val strip = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("strip-1", Binding(Does.Strip, wash)))
        }.body<SurfaceBindingDto>()

        val expanded = client
            .post("/api/rest/projects/$projectId/surface-bindings/${strip.id}/expand")
            .body<List<SurfaceBindingDto>>()

        assertEquals(setOf("fader-1", "btn-25", "enc-1", "btn-1"), expanded.map { it.controlId }.toSet())
        val fader = expanded.single { it.controlId == "fader-1" }
        assertEquals(Binding(Does.Level("dimmer"), wash), fader.binding)
        assertEquals("groupAttribute", fader.targetType)
        val flash = expanded.single { it.controlId == "btn-1" }
        assertEquals(Binding(Does.Flash("dimmer"), wash), flash.binding)
        assertEquals("flash", flash.targetType)
        assertTrue(expanded.all { it.part == ControlPart.MAIN }, "a strip derives main parts only")
        // The strip row is gone; only the four singles remain.
        val remaining = state.controlSurfaceBindingService.list(projectId)
        assertEquals(4, remaining.size)
        assertTrue(remaining.none { it.controlId == "strip-1" })
    }

    @Test
    fun `expand refuses a binding that is not a strip`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val plain = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("btn-2", Binding(Does.Blackout)))
        }.body<SurfaceBindingDto>()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings/${plain.id}/expand")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CODE_BINDING_CONTROL_NOT_STRIP, response.body<ErrorResponse>().code)
    }

    @Test
    fun `a POST with a binding answers the binding, its part and the old union's target`() = testApplication {
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
        assertEquals("groupAttribute", dto.targetType)
        assertEquals(oldTarget("groupAttribute", "groupName" to "front-wash", "attribute" to "dimmer"), dto.target)
    }

    @Test
    fun `a POST with the old union's target translates it, an int-only cueStackGo to the stack's uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (stackId, stackUuid) = seedStack()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(createOld("btn-2", oldTarget("cueStackGo", "stackId" to stackId)))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val dto = response.body<SurfaceBindingDto>()
        assertEquals(Binding(Does.Go, Subject.Record(RecordKind.STACK, stackUuid)), dto.binding)
        assertEquals(ControlPart.MAIN, dto.part)
        assertEquals("cueStackGo", dto.targetType)
        assertEquals(
            oldTarget("cueStackGo", "stackId" to stackId, "stackUuid" to stackUuid.toString()),
            dto.target,
        )
        assertEquals(dto.binding, state.controlSurfaceBindingService.get(projectId, dto.id)?.binding)
    }

    @Test
    fun `a POST with both a binding and a target is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
                put("controlId", JsonPrimitive("btn-2"))
                put("binding", bindingJson(Binding(Does.Blackout)))
                put("target", oldTarget("blackout"))
            })
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(state.controlSurfaceBindingService.list(projectId).isEmpty())
    }

    @Test
    fun `an old union button target posted on an encoder lands on its push`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(createOld("enc-1", oldTarget("blackout")))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val dto = response.body<SurfaceBindingDto>()
        assertEquals(ControlPart.PUSH, dto.part)
        assertEquals(Binding(Does.Blackout), dto.binding)
    }
}
