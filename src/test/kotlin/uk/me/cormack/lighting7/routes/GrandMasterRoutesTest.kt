package uk.me.cormack.lighting7.routes

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjectScalerState
import uk.me.cormack.lighting7.models.DaoProjectScalerStates
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.ProjectScalerStateSnapshot
import uk.me.cormack.lighting7.models.loadProjectScalerState
import uk.me.cormack.lighting7.models.saveProjectScalerState
import uk.me.cormack.lighting7.plugins.GrandMasterSetInMessage
import uk.me.cormack.lighting7.plugins.GrandMasterStateOutMessage
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The grand master's REST and WS doors, its persistence and its old on/off rows (venue-visit S5). */
class GrandMasterRoutesTest : RouteIntegrationTest() {

    private val level get() = state.show.globalScalerState.grandMasterLevel.value

    @Test
    fun `REST reads and sets the live project's level, and refuses one outside 0 to 100`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val path = "/api/rest/projects/$projectId/grand-master"

        assertEquals(GrandMasterDto(100.0), TestJson.decodeFromString(client.get(path).bodyAsText()))

        val set = client.put(path) { contentType(ContentType.Application.Json); setBody("""{"level":40}""") }
        assertEquals(HttpStatusCode.OK, set.status)
        assertEquals(GrandMasterDto(40.0), TestJson.decodeFromString(set.bodyAsText()))
        assertEquals(40.0, level)

        val over = client.put(path) { contentType(ContentType.Application.Json); setBody("""{"level":140}""") }
        assertEquals(HttpStatusCode.BadRequest, over.status)
        assertTrue(over.bodyAsText().contains(CODE_GRAND_MASTER_INVALID), over.bodyAsText())
        assertEquals(40.0, level)

        val other = transaction(state.database) {
            DaoProject.new { name = "Other"; description = ""; isCurrent = false }.id.value
        }
        val notLive = client.put("/api/rest/projects/$other/grand-master") {
            contentType(ContentType.Application.Json); setBody("""{"level":10}""")
        }
        assertEquals(HttpStatusCode.Conflict, notLive.status)
        assertEquals(40.0, level)
    }

    @Test
    fun `WS sets the level and streams it`() = testApplication {
        mountTestApp(state)
        createWsClient().webSocket("/api") {
            assertEquals(100.0, awaitOfType<GrandMasterStateOutMessage>().level)
            sendSerialized<InMessage>(GrandMasterSetInMessage(25.0))
            val moved = awaitOfType<GrandMasterStateOutMessage> { it.level == 25.0 }
            assertEquals(projectId, moved.projectId)
        }
        assertEquals(25.0, level)
    }

    @Test
    fun `the level persists on this machine, and the on-off flag still reads as zero`() {
        state.show.globalScalerState.setGrandMasterLevel(35.0)
        state.flushScalerWrites()
        val row = transaction(state.database) {
            DaoProjectScalerState.find { DaoProjectScalerStates.project eq projectId }.single().let {
                it.grandMaster to it.grandMasterLevel
            }
        }
        assertEquals(true to 35.0, row)
        assertEquals(35.0, loadProjectScalerState(state.database, projectId).grandMasterLevel)

        state.show.globalScalerState.setGrandMasterLevel(0.0)
        state.flushScalerWrites()
        assertEquals(false, transaction(state.database) {
            DaoProjectScalerState.find { DaoProjectScalerStates.project eq projectId }.single().grandMaster
        })

        // A row written before the level existed: the flag off, the level column at its default.
        saveProjectScalerState(state.database, projectId, ProjectScalerStateSnapshot(grandMasterLevel = 100.0))
        transaction(state.database) {
            DaoProjectScalerState.find { DaoProjectScalerStates.project eq projectId }.single().grandMaster = false
        }
        assertEquals(0.0, loadProjectScalerState(state.database, projectId).grandMasterLevel)
    }

    @Test
    fun `an operator may move the grand master on a venue`() {
        assertNull(projectWriteRefusal(ProjectKind.VENUE, isAdmin = false, rest = listOf("grand-master")))
    }
}
