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
import org.junit.Test
import uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Route-level preset CRUD with property-assignment children round-tripping. */
class FxPresetRoundTripTest : RouteIntegrationTest() {

    @Test
    fun `create preset, list, GET, PUT, DELETE round-trip property assignments`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post("/api/rest/project/$projectId/fx-presets") {
            contentType(ContentType.Application.Json)
            setBody(
                NewFxPreset(
                    name = "dim-50",
                    description = "dimmer at 50%",
                    fixtureType = "hex",
                    effects = emptyList(),
                    propertyAssignments = listOf(
                        FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "128"),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<FxPresetDetails>()
        assertEquals("dim-50", created.name)
        assertEquals("hex", created.fixtureType)
        assertEquals(1, created.propertyAssignments.size)
        assertEquals("128", created.propertyAssignments.single().value)
        val presetId = created.id

        val list = client.get("/api/rest/project/$projectId/fx-presets").body<List<FxPresetDetails>>()
        assertTrue(list.any { it.id == presetId }, "preset list should include created preset")

        val fetched = client.get("/api/rest/project/$projectId/fx-presets/$presetId").body<FxPresetDetails>()
        assertEquals("dimmer", fetched.propertyAssignments.single().propertyName)

        val putResp = client.put("/api/rest/project/$projectId/fx-presets/$presetId") {
            contentType(ContentType.Application.Json)
            setBody(
                NewFxPreset(
                    name = "dim-75",
                    description = null,
                    fixtureType = "hex",
                    effects = emptyList(),
                    propertyAssignments = listOf(
                        FxPresetPropertyAssignmentDto(propertyName = "dimmer", value = "192"),
                        FxPresetPropertyAssignmentDto(propertyName = "uv", value = "64"),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<FxPresetDetails>()
        assertEquals("dim-75", updated.name)
        assertEquals(2, updated.propertyAssignments.size)
        val byProp = updated.propertyAssignments.associateBy { it.propertyName }
        assertEquals("192", byProp.getValue("dimmer").value)
        assertEquals("64", byProp.getValue("uv").value)

        assertEquals(HttpStatusCode.OK, client.delete("/api/rest/project/$projectId/fx-presets/$presetId").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/rest/project/$projectId/fx-presets/$presetId").status,
        )
    }
}
