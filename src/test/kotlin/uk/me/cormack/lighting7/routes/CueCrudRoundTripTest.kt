package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-HTTP cue CRUD + stack membership. Proves the harness works for non-WS routes. */
class CueCrudRoundTripTest : RouteIntegrationTest() {

    @Test
    fun `create cue, PATCH name and notes, list, GET, DELETE`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val stackResp = client.post("/api/rest/project/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "stack-a"))
        }
        assertEquals(HttpStatusCode.Created, stackResp.status, stackResp.bodyAsText())
        val stackId = stackResp.body<CueStackDetails>().id

        val createResp = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "cue-1", cueStackId = stackId))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val cueId = createResp.body<CueDetails>().id

        // Partial update: change notes only, leave name unchanged.
        val patchResp = client.patch("/api/rest/project/$projectId/cues/$cueId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("notes", JsonPrimitive("backstage change"))
            })
        }
        assertEquals(HttpStatusCode.OK, patchResp.status, patchResp.bodyAsText())
        val patched = patchResp.body<CueDetails>()
        assertEquals("cue-1", patched.name, "PATCH without 'name' must leave name unchanged")

        val list = client.get("/api/rest/project/$projectId/cues").body<List<CueDetails>>()
        assertTrue(list.any { it.id == cueId }, "listed cues should include the created one")

        val getResp = client.get("/api/rest/project/$projectId/cues/$cueId")
        assertEquals(HttpStatusCode.OK, getResp.status)
        val details = getResp.body<CueDetails>()
        assertEquals("cue-1", details.name)
        assertEquals(stackId, details.cueStackId)

        val deleteResp = client.delete("/api/rest/project/$projectId/cues/$cueId")
        assertEquals(HttpStatusCode.OK, deleteResp.status)
        val getAfterResp = client.get("/api/rest/project/$projectId/cues/$cueId")
        assertEquals(HttpStatusCode.NotFound, getAfterResp.status)
        assertNull(
            client.get("/api/rest/project/$projectId/cues").body<List<CueDetails>>()
                .firstOrNull { it.id == cueId },
            "deleted cue should not appear in list",
        )
    }

    @Test
    fun `creating a cue without a stack is rejected`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.post("/api/rest/project/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "loose", cueStackId = null))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
    }
}
