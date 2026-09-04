package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskColumn
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.plugins.BuskLayoutChangedOutMessage
import uk.me.cormack.lighting7.plugins.ChannelMappingStateOutMessage
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The busk layout at route level: page CRUD and reorder, the whole-page layout write and its three
 * refusals, the nested read with its embedded record summaries, and the hand-rolled cascades — a
 * page delete, and a template, Look, cue or cue stack delete taking its pads with it.
 *
 * The layout write's tests are the ones worth reading first: they pin the D10 contract that a
 * refused document touches no row, that the response carries the ids the write minted, and that a
 * pad absent from the document is gone.
 */
class BuskLayoutRoutesTest : RouteIntegrationTest() {

    private fun pages() = "/api/rest/projects/$projectId/busk/pages"

    private suspend fun HttpClient.createPage(name: String): BuskPageDto {
        val resp = post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.putLayout(pageId: Int, request: BuskLayoutRequest): HttpResponse =
        put("${pages()}/$pageId/layout") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.writeLayout(pageId: Int, request: BuskLayoutRequest): BuskPageDto {
        val resp = putLayout(pageId, request)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.page(pageId: Int): BuskPageDto = get("${pages()}/$pageId").body()

    private suspend fun HttpClient.createTemplate(name: String): Int {
        val resp = post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", "#ff8800"))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<TemplateDto>().id
    }

    private suspend fun HttpClient.createLook(name: String): Int {
        val resp = post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name, rows = listOf(LookRowDto("fixture", "hex-1", "dimmer", "200"))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<LookDetails>().id
    }

    private suspend fun HttpClient.createStack(name: String): Int =
        post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun HttpClient.createCue(name: String, stackId: Int): Int {
        val resp = post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = name, cueStackId = stackId,
                    propertyAssignments = listOf(CuePropertyAssignmentDto(TargetRef.Fixture.TYPE, "hex-1", "dimmer", "128")),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private fun bank(name: String, vararg pads: BuskLayoutPad, solo: Boolean = false, flow: String = "WRAP", bankId: Int? = null) =
        BuskLayoutBank(bankId = bankId, name = name, solo = solo, flow = flow, pads = pads.toList())

    private fun column(width: Int, vararg banks: BuskLayoutBank, columnId: Int? = null) =
        BuskLayoutColumn(columnId = columnId, width = width, banks = banks.toList())

    private fun row(vararg columns: BuskLayoutColumn) = BuskLayoutRow(columns.toList())

    private fun layout(vararg rows: BuskLayoutRow) = BuskLayoutRequest(rows.toList())

    private fun tpl(id: Int, padId: Int? = null) = BuskLayoutPad(padId = padId, templateId = id)
    private fun look(id: Int, padId: Int? = null) = BuskLayoutPad(padId = padId, lookId = id)
    private fun cue(id: Int, padId: Int? = null) = BuskLayoutPad(padId = padId, cueId = id)

    private fun padCount(): Long = transaction(state.database) { DaoBuskPad.all().count() }
    private fun bankCount(): Long = transaction(state.database) { DaoBuskBank.all().count() }
    private fun columnCount(): Long = transaction(state.database) { DaoBuskColumn.all().count() }

    private val BuskPageDto.pads: List<BuskPadDto> get() = rows.flatMap { it.columns }.flatMap { it.banks }.flatMap { it.pads }

    // ─── Pages ──────────────────────────────────────────────────────────

    @Test
    fun `creating a page appends, and a duplicate or blank name is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val first = client.createPage("Act 1")
        val second = client.createPage("Act 2")
        assertEquals(0, first.sortOrder)
        assertEquals(1, second.sortOrder)
        assertEquals(listOf("Act 1", "Act 2"), client.get(pages()).body<List<BuskPageDto>>().map { it.name })

        val duplicate = client.post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(" Act 1 "))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals(CODE_BUSK_PAGE_NAME_TAKEN, duplicate.body<ErrorResponse>().code)

        val blank = client.post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest("   "))
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
    }

    @Test
    fun `renaming a page, and deleting one cascades its pads, banks and columns by hand`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val page = client.createPage("Act 1")
        val other = client.createPage("Act 2")

        val renamed = client.put("${pages()}/${page.id}") {
            contentType(ContentType.Application.Json)
            setBody(RenameBuskPageRequest("Opening"))
        }
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        assertEquals("Opening", renamed.body<BuskPageDto>().name)

        val clash = client.put("${pages()}/${page.id}") {
            contentType(ContentType.Application.Json)
            setBody(RenameBuskPageRequest("Act 2"))
        }
        assertEquals(HttpStatusCode.Conflict, clash.status)

        client.writeLayout(page.id, layout(row(column(6, bank("keys", tpl(amber))), column(6, bank("more", tpl(amber))))))
        assertEquals(2, padCount())

        assertEquals(HttpStatusCode.NoContent, client.delete("${pages()}/${page.id}").status)
        assertEquals(0, padCount())
        assertEquals(0, bankCount())
        assertEquals(0, columnCount())
        val remaining = client.get(pages()).body<List<BuskPageDto>>()
        assertEquals(listOf(other.id), remaining.map { it.id })
        assertEquals(0, remaining.single().sortOrder, "the survivor closes the gap")
        assertEquals(HttpStatusCode.NotFound, client.delete("${pages()}/${page.id}").status)
    }

    @Test
    fun `reorder must name every page exactly once`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val a = client.createPage("A")
        val b = client.createPage("B")
        val c = client.createPage("C")

        val partial = client.post("${pages()}/reorder") {
            contentType(ContentType.Application.Json)
            setBody(ReorderBuskPagesRequest(listOf(c.id, a.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, partial.status)
        assertEquals(CODE_BUSK_REORDER_INCOMPLETE, partial.body<ErrorResponse>().code)

        val twice = client.post("${pages()}/reorder") {
            contentType(ContentType.Application.Json)
            setBody(ReorderBuskPagesRequest(listOf(c.id, a.id, a.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, twice.status)

        val whole = client.post("${pages()}/reorder") {
            contentType(ContentType.Application.Json)
            setBody(ReorderBuskPagesRequest(listOf(c.id, a.id, b.id)))
        }
        assertEquals(HttpStatusCode.OK, whole.status, whole.bodyAsText())
        val listed = client.get(pages()).body<List<BuskPageDto>>()
        assertEquals(listOf("C", "A", "B"), listed.map { it.name })
        assertEquals(listOf(0, 1, 2), listed.map { it.sortOrder })
    }

    // ─── The layout write ───────────────────────────────────────────────

    @Test
    fun `layout PUT refuses a malformed shape before touching a row`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val warm = client.createLook("warm")
        val page = client.createPage("Act 1")

        suspend fun refused(request: BuskLayoutRequest, because: String) {
            val resp = client.putLayout(page.id, request)
            assertEquals(HttpStatusCode.BadRequest, resp.status, because)
            assertEquals(CODE_BUSK_LAYOUT_INVALID, resp.body<ErrorResponse>().code, because)
        }

        refused(layout(row(column(5, bank("keys", tpl(amber))))), "width 5 is not a share")
        refused(layout(row(column(6, bank("keys", tpl(amber), flow = "GRID")))), "flow GRID is not one of the two")
        refused(layout(row(column(6, bank("  ", tpl(amber))))), "a blank bank name")
        refused(layout(row(column(6, bank("keys", BuskLayoutPad(templateId = amber, lookId = warm))))), "a pad naming two records")
        refused(layout(row(column(6, bank("keys", BuskLayoutPad())))), "a pad naming none")
        refused(layout(row()), "an empty row")
        refused(layout(row(column(6))), "an empty column")

        assertEquals(0, padCount())
        assertEquals(0, columnCount())
        assertTrue(client.page(page.id).rows.isEmpty(), "nothing landed")
    }

    @Test
    fun `layout PUT refuses ids not on this page and ids named twice`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val page = client.createPage("Act 1")
        val other = client.createPage("Act 2")
        val written = client.writeLayout(page.id, layout(row(column(12, bank("keys", tpl(amber))))))
        val foreign = client.writeLayout(other.id, layout(row(column(12, bank("keys", tpl(amber))))))
        val padId = written.pads.single().id
        val foreignPadId = foreign.pads.single().id

        suspend fun refused(request: BuskLayoutRequest, because: String) {
            val resp = client.putLayout(page.id, request)
            assertEquals(HttpStatusCode.BadRequest, resp.status, because)
            assertEquals(CODE_BUSK_LAYOUT_IDENTITY, resp.body<ErrorResponse>().code, because)
        }

        refused(layout(row(column(12, bank("keys", tpl(amber, padId)), columnId = 999_999))), "an unknown column id")
        refused(layout(row(column(12, bank("keys", tpl(amber, foreignPadId))))), "another page's pad")
        refused(layout(row(column(12, bank("keys", tpl(amber, padId), tpl(amber, padId))))), "a pad named twice")

        assertEquals(written, client.page(page.id), "the page is exactly as it was")
    }

    @Test
    fun `layout PUT refuses a record not in this project`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val page = client.createPage("Act 1")

        val resp = client.putLayout(page.id, layout(row(column(12, bank("keys", tpl(999_999))))))
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(CODE_BUSK_LAYOUT_REF, resp.body<ErrorResponse>().code)
        assertEquals(0, columnCount())
    }

    @Test
    fun `layout PUT writes densely from zero and returns the page with minted ids`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue")
        val warm = client.createLook("warm")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val page = client.createPage("Act 1")

        val written = client.writeLayout(
            page.id,
            layout(
                row(column(8, bank("keys", tpl(amber), look(warm), cue(opening), solo = true)), column(4, bank("moves", tpl(blue), flow = "COLUMN"), bank("cues"))),
                row(column(12, bank("fx", tpl(amber)))),
            ),
        )
        assertEquals(2, written.rows.size)
        assertEquals(listOf(8, 4), written.rows[0].columns.map { it.width })
        val keys = written.rows[0].columns[0].banks.single()
        assertEquals("keys", keys.name)
        assertTrue(keys.solo)
        assertEquals(listOf("TEMPLATE", "LOOK", "CUE"), keys.pads.map { it.kind })
        assertEquals(listOf("moves", "cues"), written.rows[0].columns[1].banks.map { it.name })
        assertEquals("COLUMN", written.rows[0].columns[1].banks[0].flow)
        assertTrue(written.rows[0].columns[1].banks[1].pads.isEmpty(), "an empty bank is legal")
        assertEquals(written, client.page(page.id), "the response is the page as stored")

        transaction(state.database) {
            val columns = DaoBuskColumn.all().sortedWith(compareBy({ it.row }, { it.sortOrder }))
            assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), columns.map { it.row to it.sortOrder })
            assertEquals(listOf(0, 1, 2), DaoBuskPad.all().filter { it.bank.name == "keys" }.map { it.sortOrder }.sorted())
        }
    }

    @Test
    fun `pads absent from the document are deleted and pads without an id are created`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue")
        val page = client.createPage("Act 1")

        val first = client.writeLayout(page.id, layout(row(column(12, bank("keys", tpl(amber), tpl(blue))))))
        val column = first.rows[0].columns[0]
        val bank = column.banks[0]
        val (amberPad, bluePad) = bank.pads

        // Drop blue, keep amber by id, add blue back as a *new* pad after it.
        val second = client.writeLayout(
            page.id,
            layout(row(column(12, bank("keys", tpl(amber, amberPad.id), tpl(blue), bankId = bank.id), columnId = column.id))),
        )
        val pads = second.pads
        assertEquals(2, pads.size)
        assertEquals(amberPad.id, pads[0].id, "the named pad keeps its identity")
        assertEquals(amberPad.uuid, pads[0].uuid)
        assertNotEquals(bluePad.id, pads[1].id, "the unnamed pad was recreated")
        assertEquals(column.id, second.rows[0].columns[0].id)
        assertEquals(bank.id, second.rows[0].columns[0].banks[0].id)
        assertEquals(2, padCount())

        // A whole-page write that names nothing empties the page.
        val emptied = client.writeLayout(page.id, layout())
        assertTrue(emptied.rows.isEmpty())
        assertEquals(0, padCount())
        assertEquals(0, bankCount())
        assertEquals(0, columnCount())
    }

    @Test
    fun `a bank moves between columns and a pad between banks keeping their ids`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue")
        val page = client.createPage("Act 1")

        val first = client.writeLayout(page.id, layout(row(column(6, bank("keys", tpl(amber), tpl(blue))), column(6, bank("moves")))))
        val (left, right) = first.rows[0].columns
        val keys = left.banks[0]
        val moves = right.banks[0]
        val (amberPad, bluePad) = keys.pads

        // The blue pad goes to "moves"; "moves" stacks under "keys" in the left column; the right
        // column is gone, so the left one takes the full width.
        val second = client.writeLayout(
            page.id,
            layout(
                row(
                    column(
                        12,
                        bank("keys", tpl(amber, amberPad.id), bankId = keys.id),
                        bank("moves", tpl(blue, bluePad.id), bankId = moves.id),
                        columnId = left.id,
                    ),
                ),
            ),
        )
        val only = second.rows.single().columns.single()
        assertEquals(left.id, only.id)
        assertEquals(12, only.width)
        assertEquals(listOf(keys.id, moves.id), only.banks.map { it.id })
        assertEquals(listOf(amberPad.id), only.banks[0].pads.map { it.id })
        assertEquals(listOf(bluePad.id), only.banks[1].pads.map { it.id })
        assertEquals(1, columnCount())
        assertEquals(2, bankCount())
    }

    // ─── The read ───────────────────────────────────────────────────────

    @Test
    fun `GET nests every page and embeds each record's summary on its pad`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val warm = client.createLook("warm")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val page = client.createPage("Act 1")
        client.writeLayout(page.id, layout(row(column(12, bank("keys", tpl(amber), look(warm), cue(opening))))))

        val listed = client.get(pages()).body<List<BuskPageDto>>().single()
        val (templatePad, lookPad, cuePad) = listed.pads
        assertEquals("amber", templatePad.template?.name)
        assertEquals("COLOUR", templatePad.template?.family)
        assertNull(templatePad.look)
        assertEquals("warm", lookPad.look?.name)
        assertEquals(1, lookPad.look?.rowCount)
        val cue = assertNotNull(cuePad.cue)
        assertEquals("opening", cue.name)
        assertEquals(stackId, cue.cueStackId)
        assertEquals("Main", cue.cueStackName)
        assertNotNull(cue.cueNumber, "a created cue carries its auto number")
    }

    @Test
    fun `a record may sit on two pads on two pages`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val one = client.createPage("One")
        val two = client.createPage("Two")
        client.writeLayout(one.id, layout(row(column(12, bank("keys", tpl(amber), tpl(amber))))))
        client.writeLayout(two.id, layout(row(column(12, bank("keys", tpl(amber))))))

        val listed = client.get(pages()).body<List<BuskPageDto>>()
        assertEquals(listOf(2, 1), listed.map { it.pads.size })
        assertTrue(listed.flatMap { it.pads }.all { it.template?.id == amber })
    }

    // ─── Cascades ───────────────────────────────────────────────────────

    @Test
    fun `deleting a template, a Look or a cue deletes its pads and nothing else`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue")
        val warm = client.createLook("warm")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val page = client.createPage("Act 1")
        client.writeLayout(page.id, layout(row(column(12, bank("keys", tpl(amber), look(warm), cue(opening), tpl(blue), tpl(amber))))))
        assertEquals(5, padCount())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/templates/$amber").status)
        assertEquals(listOf("LOOK", "CUE", "TEMPLATE"), client.page(page.id).pads.map { it.kind })

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/looks/$warm").status)
        assertEquals(listOf("CUE", "TEMPLATE"), client.page(page.id).pads.map { it.kind })

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/cues/$opening").status)
        assertEquals(listOf(blue), client.page(page.id).pads.map { it.template?.id })
        assertEquals(1, padCount())
        assertEquals(1, bankCount(), "the bank survives its pads")
    }

    @Test
    fun `deleting a cue stack deletes the pads of its cues`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val stackId = client.createStack("Main")
        val keep = client.createStack("Keep")
        val opening = client.createCue("opening", stackId)
        val closing = client.createCue("closing", stackId)
        val kept = client.createCue("kept", keep)
        val page = client.createPage("Act 1")
        client.writeLayout(page.id, layout(row(column(12, bank("cues", cue(opening), cue(kept), cue(closing))))))

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/cue-stacks/$stackId").status)
        assertEquals(listOf(kept), client.page(page.id).pads.map { it.cue?.id })
    }

    @Test
    fun `writes are refused for a project that is not current`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val otherId = transaction(state.database) { DaoProject.new { name = "other"; isCurrent = false }.id.value }

        val resp = client.post("/api/rest/projects/$otherId/busk/pages") {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest("Act 1"))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/projects/$otherId/busk/pages").status, "reads are never gated")
    }

    @Test
    fun `every layout write and a record delete broadcast busk layoutChanged for the pages touched`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = createWsClient()
        val amber = client.createTemplate("amber")

        client.webSocket("/api") {
            // Drain the connect burst first: the broadcast subscription is wired as part of it,
            // and a frame fired before that is fired to nobody.
            awaitOfType<ChannelMappingStateOutMessage>()
            val page = client.createPage("Act 1")
            assertEquals(listOf(page.id), awaitOfType<BuskLayoutChangedOutMessage>().pageIds)

            client.writeLayout(page.id, layout(row(column(12, bank("keys", tpl(amber))))))
            assertEquals(listOf(page.id), awaitOfType<BuskLayoutChangedOutMessage>().pageIds)

            // The delete is on the template's route, not the busk's; the page still hears about it.
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/templates/$amber").status)
            assertEquals(listOf(page.id), awaitOfType<BuskLayoutChangedOutMessage>().pageIds)

            // A page delete names the survivors too: their positions moved, and the deleted id
            // alone would send a client to a 404.
            val second = client.createPage("Act 2")
            awaitOfType<BuskLayoutChangedOutMessage>()
            assertEquals(HttpStatusCode.NoContent, client.delete("${pages()}/${page.id}").status)
            assertEquals(listOf(page.id, second.id), awaitOfType<BuskLayoutChangedOutMessage>().pageIds)
        }
    }
}
