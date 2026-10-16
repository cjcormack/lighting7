package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the switch confirmation says about the project being made current (navigation plan §4.3):
 * how many channels it parks, and whether *Make rig safe*'s blackout still holds on it.
 */
class ProjectListSwitchFactsTest : RouteIntegrationTest() {

    @Test
    fun `the list counts each project's parks and says which is blacked out by make rig safe`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val other = client.post("/api/rest/projects") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Second show"}""")
        }.body<ProjectDetailDto>().id

        state.show.parkManager.park(0, 5, 128u)
        state.show.parkManager.park(0, 6, 0u)
        state.scalerHolderFor(projectId).setSafeBlackout(true)

        val list = client.get("/api/rest/projects").body<List<ProjectListDto>>()
        val current = list.single { it.id == projectId }
        val second = list.single { it.id == other }
        assertEquals(2, current.parks)
        assertTrue(current.safeBlackout)
        assertEquals(0, second.parks)
        assertFalse(second.safeBlackout)
    }
}
