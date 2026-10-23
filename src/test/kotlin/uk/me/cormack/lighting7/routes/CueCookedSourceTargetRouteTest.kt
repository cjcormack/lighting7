package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.inStack
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `GET /cues/{id}/cooked` names each composed row's **source target** — the own row's or the layer
 * row's own target, before group expansion (show-cue-editing plan §3.8, session 5). The cue editor's
 * Values grid draws a group's own row on the group's row from it, which `targetKey` cannot say: by
 * the cook a group row has become one row per member.
 */
class CueCookedSourceTargetRouteTest : RouteIntegrationTest() {

    @Test
    fun `each cooked row carries the own or layer row's own target`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 21)
        LocateTestSupport.seedHex(state, projectId, "hex-3", 41)
        LocateTestSupport.seedGroup(state, projectId, "Front", "hex-1", "hex-2")

        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val look = DaoLook.new { this.project = project; name = "Scrim" }
            DaoLookRow.new {
                this.look = look
                targetType = "group"; targetKey = "Front"
                attribute = "colour"; value = "#ff8800"; sortOrder = 0
            }
            DaoLookRow.new {
                this.look = look
                targetType = "fixture"; targetKey = "hex-3"
                attribute = "colour"; value = "#0000ff"; sortOrder = 1
            }
            val stack = DaoCueStack.new {
                this.project = project
                name = "Act 1"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new { this.project = project; name = "Q1" }.inStack(stack, sortOrder = 0)
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "group"; targetKey = "Front"
                attribute = "dimmer"; value = "120"; sortOrder = 0
            }
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                attribute = "dimmer"; value = "200"; sortOrder = 1
            }
            DaoCueLayer.new {
                this.cue = cue
                this.look = look
                sortOrder = 0
                targets = emptyList()
            }
            cue.id.value
        }

        val resp = client.get("/api/rest/projects/$projectId/cues/$cueId/cooked")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val rows = resp.body<CueCookedResponse>().rows
        fun row(key: String, property: String) =
            assertNotNull(rows.singleOrNull { it.targetKey == key && it.propertyName == property }, "$key.$property in $rows")

        // The fixture's own row beats the group's on hex-1, and names the fixture.
        row("hex-1", "dimmer").let {
            assertEquals("200", it.value)
            assertNull(it.layerId)
            assertEquals("fixture", it.sourceTargetType)
            assertEquals("hex-1", it.sourceTargetKey)
        }
        // hex-2 takes the group's own row: the head is the member, the source is the group.
        row("hex-2", "dimmer").let {
            assertEquals("120", it.value)
            assertNull(it.layerId)
            assertEquals("group", it.targetType)
            assertEquals("group", it.sourceTargetType)
            assertEquals("Front", it.sourceTargetKey)
        }
        // A layer row keeps its own target too — the Look's group row on a member, its fixture row
        // on hex-3.
        row("hex-1", "rgbColour").let {
            assertNotNull(it.layerId)
            assertEquals("group", it.sourceTargetType)
            assertEquals("Front", it.sourceTargetKey)
        }
        row("hex-3", "rgbColour").let {
            assertNotNull(it.layerId)
            assertEquals("fixture", it.sourceTargetType)
            assertEquals("hex-3", it.sourceTargetKey)
        }
    }
}
