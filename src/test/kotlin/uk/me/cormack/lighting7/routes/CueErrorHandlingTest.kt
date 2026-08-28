package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Test
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cue name collisions used to escape the handler as an Exposed unique-constraint exception and
 * come back as a bodyless 500, which the UI could only render as "something went wrong" — or, as
 * it turned out, as nothing at all. Two things changed: names are no longer unique, and anything
 * that *does* still violate a constraint gets a 409 with a readable body from `StatusPages`.
 */
class CueErrorHandlingTest : RouteIntegrationTest() {

    private suspend fun io.ktor.client.HttpClient.newStack(name: String): Int {
        val resp = post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueStackDetails>().id
    }

    @Test
    fun `two stacks in a project may hold cues with the same name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val actOne = client.newStack("Act 1")
        val actTwo = client.newStack("Act 2")

        val first = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Blackout", cueStackId = actOne))
        }
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())

        val second = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Blackout", cueStackId = actTwo))
        }
        assertEquals(HttpStatusCode.Created, second.status, second.bodyAsText())
        assertNotEquals(first.body<CueDetails>().id, second.body<CueDetails>().id)
    }

    @Test
    fun `a duplicate name within one stack is also allowed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val stack = client.newStack("Act 1")

        repeat(2) {
            val resp = client.post("/api/rest/projects/$projectId/cues") {
                contentType(ContentType.Application.Json)
                setBody(NewCue(name = "Blackout", cueStackId = stack))
            }
            assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        }
    }

    @Test
    fun `renaming a cue onto an existing name succeeds`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val stack = client.newStack("Act 1")

        client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Pre-show", cueStackId = stack))
        }
        val other = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Cradle Glows", cueStackId = stack))
        }.body<CueDetails>()

        val resp = client.patch("/api/rest/projects/$projectId/cues/${other.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", JsonPrimitive("Pre-show")) })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals("Pre-show", resp.body<CueDetails>().name)
    }

    @Test
    fun `a duplicate cue number in one stack returns 409 with a readable body`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val stack = client.newStack("Act 1")

        val first = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "one", cueStackId = stack, cueNumber = "1"))
        }
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())

        // cue_number *is* still unique per stack, so this genuinely violates a constraint —
        // exactly the case that used to escape as a bodyless 500.
        val clash = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "two", cueStackId = stack, cueNumber = "1"))
        }
        assertEquals(HttpStatusCode.Conflict, clash.status, clash.bodyAsText())

        val body = clash.body<ErrorResponse>()
        assertTrue(body.error.isNotBlank(), "409 must carry a message, not an empty body")
        assertTrue(
            body.error.contains("cue number", ignoreCase = true),
            "message should name the offending field, got: ${body.error}",
        )
    }

    @Test
    fun `an unrecognised effect enum is rejected on create and on patch`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val stack = client.newStack("Act 1")

        // Both must answer 400 — a stored "ADD" reads back as itself, renders in the UI as the
        // layer's blend, and plays as OVERRIDE, permanently. PATCH is the one worth pinning
        // separately: its children are decoded off a raw body, so the check sits on a different
        // line of the handler than POST's.
        val created = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = "Bad Blend", cueStackId = stack,
                    layers = listOf(CueLayerDto(lookId = 1, blendMode = "ADD")),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, created.status, created.bodyAsText())
        assertTrue(
            created.body<ErrorResponse>().error.contains("Unknown blendMode 'ADD'"),
            created.body<ErrorResponse>().error,
        )

        val cue = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Fine", cueStackId = stack))
        }.body<CueDetails>()

        val patched = client.patch("/api/rest/projects/$projectId/cues/${cue.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("layers", Json.encodeToJsonElement(listOf(CueLayerDto(lookId = 1, blendMode = "ADD"))))
                }
            )
        }
        assertEquals(HttpStatusCode.BadRequest, patched.status, patched.bodyAsText())
        assertTrue(
            patched.body<ErrorResponse>().error.contains("Unknown blendMode 'ADD'"),
            patched.body<ErrorResponse>().error,
        )
    }

    @Test
    fun `a malformed request body returns 400 with an error body`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
        assertTrue(
            resp.body<ErrorResponse>().error.isNotBlank(),
            "400 must carry a message, not an empty body",
        )
    }
}
