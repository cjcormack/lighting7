package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** *In production* on the project routes (show-view-fixes plan D15–D17). */
class ProjectInProductionRoutesTest : RouteIntegrationTest() {

    @Test
    fun `a new show starts not in production unless the request says so`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val plain = client.post("/api/rest/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Tech week"}""")
        }.body<ProjectDetailDto>()
        assertFalse(plain.inProduction)

        val asked = client.post("/api/rest/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Opening night","inProduction":true}""")
        }.body<ProjectDetailDto>()
        assertTrue(asked.inProduction)

        val list = client.get("/api/rest/projects").body<List<ProjectListDto>>()
        assertFalse(list.single { it.id == plain.id }.inProduction)
        assertTrue(list.single { it.id == asked.id }.inProduction)
    }

    @Test
    fun `an existing show reads as in production and an update switches it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        assertTrue(client.get("/api/rest/projects/$projectId").body<ProjectDetailDto>().inProduction)

        val off = client.put("/api/rest/projects/$projectId") {
            contentType(ContentType.Application.Json)
            setBody("""{"inProduction":false}""")
        }
        assertEquals(HttpStatusCode.OK, off.status)
        assertFalse(off.body<ProjectDetailDto>().inProduction)
        transaction(state.database) { assertFalse(DaoProject[projectId].inProduction) }

        val rename = client.put("/api/rest/projects/$projectId") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Renamed"}""")
        }.body<ProjectDetailDto>()
        assertFalse(rename.inProduction, "an update that does not name it leaves it")
    }

    @Test
    fun `a venue refuses it on create and on update`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val create = client.post("/api/rest/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Barn","kind":"VENUE","inProduction":true}""")
        }
        assertEquals(HttpStatusCode.BadRequest, create.status)
        assertTrue(CODE_IN_PRODUCTION_NOT_A_SHOW in create.bodyAsText())

        val venue = client.post("/api/rest/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Barn","kind":"VENUE"}""")
        }.body<ProjectDetailDto>()
        val update = client.put("/api/rest/projects/${venue.id}") {
            contentType(ContentType.Application.Json)
            setBody("""{"inProduction":false}""")
        }
        assertEquals(HttpStatusCode.BadRequest, update.status)
        assertTrue(CODE_IN_PRODUCTION_NOT_A_SHOW in update.bodyAsText())
        transaction(state.database) { assertTrue(DaoProject[venue.id].inProduction) }
    }
}
