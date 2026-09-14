package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
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
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `TemplatePressLog` — the one place a template press is recorded, through all four of its doors.
 *
 * The programmer's row of recent chips is ordered by `lastPressedAt`, so what this class is really
 * pinning is the *definition of a press*: an application, from any surface, and never a release or
 * a refusal. Each of the four doors could get that wrong on its own — which is why they share one
 * recorder and why each is driven here rather than trusting the shared function alone.
 */
class TemplatePressLogTest : RouteIntegrationTest() {

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        state.show.fixtures.patchListChanged()
    }

    private suspend fun HttpClient.createTemplate(name: String, value: String = "#ff8800"): Int {
        val resp = post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = name,
                    rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", value)),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<TemplateDto>().id
    }

    private suspend fun HttpClient.template(id: Int): TemplateDto =
        get("/api/rest/projects/$projectId/templates/$id").body()

    private suspend fun HttpClient.stamp(id: Int): String? = template(id).lastPressedAt

    private suspend fun HttpClient.apply(id: Int, vararg fixtureKeys: String): ApplyTemplateResponse {
        val resp = post("/api/rest/projects/$projectId/templates/$id/apply") {
            contentType(ContentType.Application.Json)
            setBody(ApplyTemplateRequest(targets = fixtureKeys.map { TemplateTargetDto("fixture", it) }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.toggle(id: Int, vararg fixtureKeys: String): ToggleTemplateResponse {
        val resp = post("/api/rest/projects/$projectId/templates/$id/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = fixtureKeys.map { TemplateTargetDto("fixture", it) }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    /** Every `templatePressed` frame this show fired, in order. */
    private fun recordPresses(): MutableList<Pair<Int, String>> {
        val announced = mutableListOf<Pair<Int, String>>()
        state.show.fixtures.registerListener(object : FixturesChangeListener {
            override fun templatePressed(templateId: Int, lastPressedAt: String) {
                announced += templateId to lastPressedAt
            }
        })
        return announced
    }

    // ─── Door 1: the chip's click ───────────────────────────────────────

    @Test
    fun `a click stamps the template and announces the stamp it wrote`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        assertNull(client.stamp(amber), "a template nobody has pressed has no stamp")
        val announced = recordPresses()

        assertEquals(1, client.apply(amber, "hex-1").written)

        val stamp = assertNotNull(client.stamp(amber), "the click stamped it")
        assertEquals(
            listOf(amber to stamp), announced,
            "one frame, carrying the very instant the row now holds — not each client's own clock",
        )
    }

    @Test
    fun `a click that reaches no head does not stamp`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val announced = recordPresses()

        // No targets at all: the route answers 200 with nothing written, which is the one refusal
        // the click door cannot express as a status code.
        assertEquals(0, client.apply(amber).written)

        assertNull(client.stamp(amber), "a press that did nothing is not something you reached for")
        assertTrue(announced.isEmpty())
    }

    // ─── Door 2: ⌥click / hold ──────────────────────────────────────────

    @Test
    fun `a toggle stamps on the way on and leaves the stamp alone on the way off`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val announced = recordPresses()

        assertEquals("applied", client.toggle(amber, "hex-1").action)
        val onStamp = assertNotNull(client.stamp(amber))
        assertEquals(listOf(amber to onStamp), announced)

        assertEquals("removed", client.toggle(amber, "hex-1").action)
        assertEquals(onStamp, client.stamp(amber), "a release is not a press")
        assertEquals(1, announced.size, "and announces nothing")
    }

    // ─── Door 3: a busk pad ─────────────────────────────────────────────

    @Test
    fun `a busk pad stamps on the arm that puts the template on`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val page = client.post("/api/rest/projects/$projectId/busk/pages") {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest("busk"))
        }.body<BuskPageDto>()
        val layout = client.put("/api/rest/projects/$projectId/busk/pages/${page.id}/layout") {
            contentType(ContentType.Application.Json)
            setBody(
                BuskLayoutRequest(
                    listOf(
                        BuskLayoutRow(
                            listOf(
                                BuskLayoutColumn(
                                    width = 12,
                                    banks = listOf(
                                        BuskLayoutBank(
                                            name = "keys",
                                            solo = false,
                                            pads = listOf(BuskLayoutPad(templateId = amber)),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, layout.status, layout.bodyAsText())
        val pad = layout.body<BuskPageDto>().rows.single().columns.single().banks.single().pads.single().id
        val announced = recordPresses()

        suspend fun press() = client.post("/api/rest/projects/$projectId/busk/pads/$pad/press") {
            contentType(ContentType.Application.Json)
            setBody(BuskPressRequest(targets = listOf(CueTargetDto("fixture", "hex-1"))))
        }.body<BuskPressResponse>()

        assertEquals("applied", press().action)
        val onStamp = assertNotNull(client.stamp(amber))
        assertEquals(listOf(amber to onStamp), announced)

        assertEquals("removed", press().action)
        assertEquals(onStamp, client.stamp(amber), "the pad's off press is a release, not a press")
        assertEquals(1, announced.size)
    }

    // ─── Door 4: a MIDI button ──────────────────────────────────────────

    @Test
    fun `pressTemplate from a surface stamps, and its two refusals do not`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val uuid = client.template(amber).uuid
        val actions = DefaultSurfaceActions(state)
        val announced = recordPresses()

        // Refusal one: a generic template with nothing selected never reaches the stack.
        actions.pressTemplate(uuid)
        assertNull(client.stamp(amber))
        // Refusal two: no such template.
        actions.pressTemplate("00000000-0000-0000-0000-000000000000")
        assertTrue(announced.isEmpty())

        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")))
        actions.pressTemplate(uuid)
        val onStamp = assertNotNull(client.stamp(amber), "pressing from the desk's own hardware counts")
        assertEquals(listOf(amber to onStamp), announced)

        // And the hardware's own second press is a release.
        actions.pressTemplate(uuid)
        assertEquals(onStamp, client.stamp(amber))
        assertEquals(1, announced.size)
    }

    // ─── The history goes with the template ─────────────────────────────

    @Test
    fun `a deleted template takes its stamp with it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        client.apply(amber, "hex-1")
        assertNotNull(client.stamp(amber))

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/rest/projects/$projectId/templates/$amber").status,
        )

        // No second table to sweep: the stamp is a column on the row that just went, which is the
        // whole reason recents needed no storage of its own.
        val remaining: List<TemplateDto> = client.get("/api/rest/projects/$projectId/templates").body()
        assertTrue(remaining.none { it.id == amber })
    }
}
