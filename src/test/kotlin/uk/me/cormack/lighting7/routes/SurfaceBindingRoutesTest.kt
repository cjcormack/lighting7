package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bind-time shape validation on the surface-binding routes, and the *Fader only…* expansion.
 * A strip id and a control id share one slot namespace but are not interchangeable, and the
 * check applies to POST **and** PATCH — a drag onto an already-bound control saves as a PATCH.
 */
class SurfaceBindingRoutesTest : RouteIntegrationTest() {

    private val deviceTypeKey = "x-touch-compact-standard"
    private val wash = CueTargetDto("group", "front-wash")

    private fun create(controlId: String, target: BindingTarget) = buildJsonObject {
        put("deviceTypeKey", JsonPrimitive(deviceTypeKey))
        put("controlId", JsonPrimitive(controlId))
        put("target", kotlinx.serialization.json.Json.parseToJsonElement(
            uk.me.cormack.lighting7.midi.BindingTargetJson.encodeToString(target),
        ))
    }

    @Test
    fun `a strip target on an ordinary control is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("fader-1", BindingTarget.Strip(wash)))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CODE_BINDING_STRIP_NEEDS_STRIP, response.body<ErrorResponse>().code)
    }

    @Test
    fun `a non-strip target on a strip slot is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("strip-1", BindingTarget.Blackout))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CODE_BINDING_CONTROL_NOT_STRIP, response.body<ErrorResponse>().code)
    }

    @Test
    fun `a strip target on a strip slot is accepted`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings") {
            contentType(ContentType.Application.Json)
            setBody(create("strip-1", BindingTarget.Strip(wash)))
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
            setBody(create("strip-1", BindingTarget.Strip(wash)))
        }.body<SurfaceBindingDto>()

        // Moving a strip binding onto an ordinary control: refused, as it is on POST.
        val moved = client.patch("/api/rest/projects/$projectId/surface-bindings/${strip.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("controlId", JsonPrimitive("fader-1")) })
        }
        assertEquals(HttpStatusCode.BadRequest, moved.status)
        assertEquals(CODE_BINDING_STRIP_NEEDS_STRIP, moved.body<ErrorResponse>().code)

        // And swapping the target out from under a strip slot is refused the other way.
        val retargeted = client.patch("/api/rest/projects/$projectId/surface-bindings/${strip.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("target", kotlinx.serialization.json.Json.parseToJsonElement(
                    uk.me.cormack.lighting7.midi.BindingTargetJson.encodeToString<BindingTarget>(
                        BindingTarget.Blackout,
                    ),
                ))
            })
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
            setBody(create("strip-1", BindingTarget.Strip(wash)))
        }.body<SurfaceBindingDto>()

        val expanded = client
            .post("/api/rest/projects/$projectId/surface-bindings/${strip.id}/expand")
            .body<List<SurfaceBindingDto>>()

        assertEquals(setOf("fader-1", "btn-25", "enc-1", "btn-1"), expanded.map { it.controlId }.toSet())
        assertEquals(
            BindingTarget.GroupProperty("front-wash", "dimmer"),
            expanded.single { it.controlId == "fader-1" }.target,
        )
        assertIs<BindingTarget.Flash>(expanded.single { it.controlId == "btn-1" }.target)
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
            setBody(create("btn-2", BindingTarget.Blackout))
        }.body<SurfaceBindingDto>()

        val response = client.post("/api/rest/projects/$projectId/surface-bindings/${plain.id}/expand")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(CODE_BINDING_CONTROL_NOT_STRIP, response.body<ErrorResponse>().code)
    }
}
