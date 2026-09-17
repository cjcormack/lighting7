package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `record-look` over a selection of **cells** writes element rows (`targetKey` = the parent,
 * `elementKey` = the cell), and toggling that Look drives exactly those cells — the Look door of
 * the element arm (busk-further plan, session 1).
 */
class LookRecordElementTest : RouteIntegrationTest() {

    private fun cell(i: Int) = "bar-1.pixel-$i"
    private fun cellTarget(i: Int) = CueTargetDto("fixture", cell(i))

    private fun seedBar() {
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 1)
    }

    private fun setProgrammer(key: String, property: String, value: String) {
        ProgrammerHandler.set(state, TargetRef.Fixture(key), property, value, 0)
    }

    private fun clearProgrammer(key: String, property: String) {
        ProgrammerHandler.clearEntry(state, TargetRef.Fixture(key), property, 0)
    }

    private fun layerColour(key: String): Color? {
        val slot = state.show.programmerStore.get(key, "rgbColour") ?: return null
        assertEquals(ProgrammerOwner.LAYERS, slot.owner)
        return (slot.value.resolved as CueAssignmentResolver.PropertyValue.Colour).value.color
    }

    private suspend fun HttpClient.recordLook(request: ProgrammerRecordLookRequest): ProgrammerRecordLookResponse {
        val resp = post("/api/rest/programmer/record-look") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.toggle(lookId: Int, vararg targets: CueTargetDto): ToggleLookResponse {
        val resp = post("/api/rest/projects/$projectId/looks/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = targets.toList()))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    @Test
    fun `record-look from two cells writes element rows`() = testApplication {
        mountTestApp(state)
        seedBar()
        val client = jsonClient()
        setProgrammer(cell(2), "rgbColour", "#ff0000")
        setProgrammer(cell(5), "rgbColour", "#00ff00")
        setProgrammer(cell(7), "rgbColour", "#0000ff")

        val response = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), mode = "CREATE", name = "Two Cells",
                targets = listOf(cellTarget(2), cellTarget(5)),
            ),
        )

        assertEquals(2, response.rowsWritten)
        val rows = response.look.rows.associate { it.elementKey to it }
        assertEquals(setOf(cell(2), cell(5)), rows.keys, "the unselected cell stayed out")
        for (row in rows.values) {
            assertEquals(TargetRef.Fixture.TYPE, row.targetType)
            assertEquals("bar-1", row.targetKey, "an element row names its parent and carries the cell")
        }
        assertEquals("#ff0000", rows.getValue(cell(2)).value)
        assertEquals("#00ff00", rows.getValue(cell(5)).value)
    }

    @Test
    fun `record-look over the whole bar records its cells' entries`() = testApplication {
        mountTestApp(state)
        seedBar()
        val client = jsonClient()
        setProgrammer(cell(2), "rgbColour", "#ff0000")

        val response = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), mode = "CREATE", name = "Whole Bar",
                targets = listOf(CueTargetDto("fixture", "bar-1")),
            ),
        )

        assertEquals(1, response.rowsWritten, "the parent's scope covers its cells")
        assertEquals(cell(2), response.look.rows.single().elementKey)
    }

    @Test
    fun `toggling a Look recorded from cells drives those cells and nothing else`() = testApplication {
        mountTestApp(state)
        seedBar()
        val client = jsonClient()
        setProgrammer(cell(2), "rgbColour", "#ff0000")
        setProgrammer(cell(5), "rgbColour", "#00ff00")
        val lookId = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), mode = "CREATE", name = "Two Cells",
                targets = listOf(cellTarget(2), cellTarget(5)),
            ),
        ).look.id
        clearProgrammer(cell(2), "rgbColour")
        clearProgrammer(cell(5), "rgbColour")
        assertNull(state.show.programmerStore.get(cell(2), "rgbColour"))

        assertEquals("applied", client.toggle(lookId, cellTarget(2), cellTarget(5)).action)
        assertEquals(Color.RED, layerColour(cell(2)))
        assertEquals(Color.GREEN, layerColour(cell(5)))
        assertNull(state.show.programmerStore.get(cell(3), "rgbColour"), "a cell the Look does not name")
        assertNull(state.show.programmerStore.get("bar-1", "rgbColour"), "the parent itself holds nothing")

        assertEquals("removed", client.toggle(lookId, cellTarget(2), cellTarget(5)).action)
        assertNull(state.show.programmerStore.get(cell(2), "rgbColour"))
        assertNull(state.show.programmerStore.get(cell(5), "rgbColour"))
    }

    @Test
    fun `a cell press under a Look on the whole bar comes off, narrowed to the other cells`() = testApplication {
        mountTestApp(state)
        seedBar()
        val client = jsonClient()
        setProgrammer(cell(2), "rgbColour", "#ff0000")
        setProgrammer(cell(5), "rgbColour", "#00ff00")
        val lookId = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), mode = "CREATE", name = "Two Cells",
                targets = listOf(cellTarget(2), cellTarget(5)),
            ),
        ).look.id
        clearProgrammer(cell(2), "rgbColour")
        clearProgrammer(cell(5), "rgbColour")

        assertEquals("applied", client.toggle(lookId, CueTargetDto("fixture", "bar-1")).action)
        assertEquals(Color.RED, layerColour(cell(2)))
        assertEquals(Color.GREEN, layerColour(cell(5)))

        // Pressing cell 2 under the whole-bar layer reads as lit: off arm, and the layer keeps the rest.
        assertEquals("removed", client.toggle(lookId, cellTarget(2)).action)
        assertNull(state.show.programmerStore.get(cell(2), "rgbColour"))
        assertEquals(Color.GREEN, layerColour(cell(5)), "cell 5 is still under the narrowed layer")
    }

    @Test
    fun `a recorded cell Look derives its family from the cell and takes a masked press`() = testApplication {
        mountTestApp(state)
        seedBar()
        val client = jsonClient()
        setProgrammer(cell(2), "rgbColour", "#ff0000")
        val look = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), mode = "CREATE", name = "Cell Colour",
                targets = listOf(cellTarget(2)),
            ),
        ).look
        // The 48-channel bar's parent declares no properties of its own; the family is the cell's.
        assertEquals(listOf("COLOUR"), look.families)
        clearProgrammer(cell(2), "rgbColour")

        val resp = client.post("/api/rest/projects/$projectId/looks/${look.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = listOf(cellTarget(2)), families = listOf("COLOUR")))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals("applied", resp.body<ToggleLookResponse>().action)
        assertEquals(Color.RED, layerColour(cell(2)))
    }

    @Test
    fun `Update writes a nudged cell back into the included Look as its element row`() = testApplication {
        mountTestApp(state)
        seedBar()
        val client = jsonClient()
        setProgrammer(cell(2), "rgbColour", "#ff0000")
        val lookId = client.recordLook(
            ProgrammerRecordLookRequest(
                projectId = projectId.toString(), mode = "CREATE", name = "Cell Colour",
                targets = listOf(cellTarget(2)),
            ),
        ).look.id
        clearProgrammer(cell(2), "rgbColour")

        val include = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), lookId = lookId))
        }
        assertEquals(HttpStatusCode.OK, include.status, include.bodyAsText())
        setProgrammer(cell(2), "rgbColour", "#00ff00")

        val update = client.post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
        }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())

        val rows = client.get("/api/rest/projects/$projectId/looks/$lookId").body<LookDetails>().rows
        val row = rows.single()
        assertEquals("bar-1", row.targetKey)
        assertEquals(cell(2), row.elementKey, "still one element row, not a second whole-fixture one")
        assertEquals("#00ff00", row.value)
    }
}
