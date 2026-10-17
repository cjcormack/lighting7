package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /cues`, the Cues table's list (cue model plan session 5, §3.7): every cue once, by name, each
 * with every entry naming it and what else uses it — its busk pads, cue slots and `FireCue` bindings.
 */
class CueListRouteTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private fun stack(name: String, sort: Int): Int = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject[projectId]
            this.name = name; loop = false
            type = CueStackType.STACK.name; sortOrder = sort
        }.id.value
    }

    private fun cue(name: String): Int = transaction(state.database) {
        DaoCue.new { project = DaoProject[projectId]; this.name = name }.id.value
    }

    @Test
    fun `the list carries each cue's entries and what else uses it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val act1 = stack("Act 1", 0)
        val act2 = stack("Act 2", 1)
        val storm = cue("Storm")
        val loose = cue("Loose wash")
        for ((stackId, number) in listOf(act1 to "7", act2 to "14")) {
            assertEquals(
                HttpStatusCode.OK,
                client.post("$base/cue-stacks/$stackId/add-cue") {
                    contentType(ContentType.Application.Json)
                    setBody(AddCueToStackRequest(cueId = storm, cueNumber = number))
                }.status,
            )
        }
        transaction(state.database) {
            val project = DaoProject[projectId]
            val page = DaoBuskPage.new { this.project = project; name = "Page 1"; sortOrder = 0 }
            val column = DaoBuskColumn.new { this.page = page; row = 0; sortOrder = 0; width = 12 }
            val bank = DaoBuskBank.new { this.column = column; sortOrder = 0; name = "Bank"; solo = false; flow = BuskFlow.WRAP.name }
            DaoBuskPad.new { this.bank = bank; sortOrder = 0; cue = DaoCue[loose] }
            DaoCueSlot.new { this.project = project; this.page = 0; slotIndex = 2; cue = DaoCue[loose] }
            DaoControlSurfaceBinding.new {
                this.project = project
                deviceTypeKey = "xtouch-mini"; controlId = "button-7"
                targetType = "fireCue"
                targetPayload = """{"type":"fireCue","cueId":$storm,"cueUuid":"${DaoCue[storm].uuid}"}"""
                sortOrder = 0
            }
        }

        val response = client.get("$base/cues")
        assertEquals(HttpStatusCode.OK, response.status)
        val cues = response.body<List<CueDetails>>()
        assertEquals(listOf("Loose wash", "Storm"), cues.map { it.name })

        val shared = cues.single { it.id == storm }
        assertEquals(listOf("Act 1" to "7", "Act 2" to "14"), shared.entries.map { it.stackName to it.cueNumber })
        // No context entry for a cue in two places.
        assertNull(shared.cueStackId)
        assertNull(shared.cueNumber)
        val sharedUses = shared.usedBy!!
        assertTrue(sharedUses.pads.isEmpty())
        assertTrue(sharedUses.slots.isEmpty())
        assertEquals(listOf("xtouch-mini · button-7"), sharedUses.bindings.map { it.label })

        val looseCue = cues.single { it.id == loose }
        assertTrue(looseCue.entries.isEmpty())
        val looseUses = looseCue.usedBy!!
        assertEquals(listOf("Page 1"), looseUses.pads.map { it.pageName })
        assertEquals(listOf("Page 1, slot 3"), looseUses.slots.map { it.label })
        assertTrue(looseUses.bindings.isEmpty())

        // One cue's read leaves *Used by* to the list.
        assertNull(client.get("$base/cues/$loose").body<CueDetails>().usedBy)
    }
}
