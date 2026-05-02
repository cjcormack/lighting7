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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageRegionsRoutesTest : RouteIntegrationTest() {

    @Test
    fun `stage regions round-trip through POST GET PUT DELETE`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post("/api/rest/project/$projectId/stageRegions") {
            contentType(ContentType.Application.Json)
            setBody(CreateStageRegionRequest(
                name = "main",
                centerX = 0.0,
                centerY = 0.0,
                centerZ = 0.0,
                widthM = 12.0,
                depthM = 8.0,
                heightM = 0.0,
            ))
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<StageRegionDto>()
        assertEquals("main", created.name)
        assertEquals(12.0, created.widthM)

        val list = client.get("/api/rest/project/$projectId/stageRegions").body<List<StageRegionDto>>()
        assertEquals(1, list.size)

        val putResp = client.put("/api/rest/project/$projectId/stageRegions/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("widthM", JsonPrimitive(14.0))
                put("yawDeg", JsonPrimitive(10.0))
            })
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<StageRegionDto>()
        assertEquals(14.0, updated.widthM)
        assertEquals(8.0, updated.depthM, "untouched depth survives")
        assertEquals(10.0, updated.yawDeg)

        val clearResp = client.put("/api/rest/project/$projectId/stageRegions/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("yawDeg", JsonNull) })
        }
        assertNull(clearResp.body<StageRegionDto>().yawDeg)

        val del = client.delete("/api/rest/project/$projectId/stageRegions/${created.id}")
        assertEquals(HttpStatusCode.NoContent, del.status)
        val gone = client.get("/api/rest/project/$projectId/stageRegions/${created.id}")
        assertEquals(HttpStatusCode.NotFound, gone.status)
    }

    @Test
    fun `stage region POST validates extents and rejects duplicates`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val first = client.post("/api/rest/project/$projectId/stageRegions") {
            contentType(ContentType.Application.Json)
            setBody(CreateStageRegionRequest(name = "main"))
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val dup = client.post("/api/rest/project/$projectId/stageRegions") {
            contentType(ContentType.Application.Json)
            setBody(CreateStageRegionRequest(name = "main"))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        val bad = client.post("/api/rest/project/$projectId/stageRegions") {
            contentType(ContentType.Application.Json)
            setBody(CreateStageRegionRequest(name = "huge", widthM = 9999.0))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.bodyAsText().contains("widthM"))

        val negative = client.post("/api/rest/project/$projectId/stageRegions") {
            contentType(ContentType.Application.Json)
            setBody(CreateStageRegionRequest(name = "neg", depthM = -1.0))
        }
        assertEquals(HttpStatusCode.BadRequest, negative.status)
        assertTrue(negative.bodyAsText().contains("depthM"))
    }
}
