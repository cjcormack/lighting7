package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiggingsRoutesTest : RouteIntegrationTest() {

    @Test
    fun `riggings round-trip through POST GET PUT DELETE`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post("/api/rest/project/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(
                name = "FOH-1",
                kind = "TRUSS",
                positionX = 0.0,
                positionY = -2.0,
                positionZ = 6.0,
                yawDeg = 5.0,
            ))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<RiggingDto>()
        assertEquals("FOH-1", created.name)
        assertEquals("TRUSS", created.kind)
        assertEquals(0.0, created.positionX)
        assertEquals(-2.0, created.positionY)
        assertEquals(6.0, created.positionZ)
        assertEquals(5.0, created.yawDeg)

        val list = client.get("/api/rest/project/$projectId/riggings").body<List<RiggingDto>>()
        assertEquals(1, list.size)
        assertEquals(created.id, list[0].id)

        val fetched = client.get("/api/rest/project/$projectId/riggings/${created.id}").body<RiggingDto>()
        assertEquals(created.uuid, fetched.uuid)

        val putResp = client.put("/api/rest/project/$projectId/riggings/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", JsonPrimitive("FOH Front"))
                put("yawDeg", JsonPrimitive(15.0))
                put("kind", JsonNull)
            })
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<RiggingDto>()
        assertEquals("FOH Front", updated.name)
        assertEquals(15.0, updated.yawDeg)
        assertNull(updated.kind)
        // Untouched fields survive.
        assertEquals(0.0, updated.positionX)
        assertEquals(-2.0, updated.positionY)
        assertEquals(6.0, updated.positionZ)

        val deleteResp = client.put("/api/rest/project/$projectId/riggings/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("positionX", JsonNull)
            })
        }
        assertEquals(HttpStatusCode.OK, deleteResp.status, "PUT with explicit null clears coord")
        assertNull(deleteResp.body<RiggingDto>().positionX)

        val del = client.delete("/api/rest/project/$projectId/riggings/${created.id}")
        assertEquals(HttpStatusCode.NoContent, del.status)
        val gone = client.get("/api/rest/project/$projectId/riggings/${created.id}")
        assertEquals(HttpStatusCode.NotFound, gone.status)
    }

    @Test
    fun `riggings POST rejects out-of-range pose values and duplicate name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        // First rigging — succeeds.
        val ok = client.post("/api/rest/project/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "Truss A"))
        }
        assertEquals(HttpStatusCode.Created, ok.status)

        // Duplicate name — Conflict.
        val dup = client.post("/api/rest/project/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "Truss A"))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        // Out-of-range yaw.
        val badYaw = client.post("/api/rest/project/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "Truss B", yawDeg = 900.0))
        }
        assertEquals(HttpStatusCode.BadRequest, badYaw.status)
        assertTrue(badYaw.bodyAsText().contains("yawDeg"))

        // Out-of-range positionZ.
        val badZ = client.post("/api/rest/project/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "Truss C", positionZ = 9999.0))
        }
        assertEquals(HttpStatusCode.BadRequest, badZ.status)
        assertTrue(badZ.bodyAsText().contains("positionZ"))

        // Blank name.
        val blank = client.post("/api/rest/project/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "   "))
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
    }

    @Test
    fun `deleting a rigging detaches dependent patches`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val rigging = client.post("/api/rest/project/$projectId/riggings") {
            contentType(ContentType.Application.Json)
            setBody(CreateRiggingRequest(name = "doomed"))
        }.body<RiggingDto>()

        val patch = client.post("/api/rest/project/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(
                universe = 0,
                fixtureTypeKey = "generic-dimmer",
                key = "linked",
                name = "Linked",
                startChannel = 1,
                riggingUuid = rigging.uuid,
            ))
        }.body<FixturePatchDto>()
        assertEquals(rigging.uuid, patch.riggingUuid)

        val del = client.delete("/api/rest/project/$projectId/riggings/${rigging.id}")
        assertEquals(HttpStatusCode.NoContent, del.status)

        val orphaned = client.get("/api/rest/project/$projectId/patches/${patch.id}").body<FixturePatchDto>()
        assertNull(orphaned.riggingUuid, "patch must be detached when its rigging is deleted")
    }

    @Test
    fun `patch POST rejects unknown rigging uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.post("/api/rest/project/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(
                universe = 0,
                fixtureTypeKey = "generic-dimmer",
                key = "ghost",
                name = "Ghost",
                startChannel = 1,
                riggingUuid = "00000000-0000-0000-0000-000000000000",
            ))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertTrue(resp.bodyAsText().contains("00000000-0000-0000-0000-000000000000"))
    }

}
