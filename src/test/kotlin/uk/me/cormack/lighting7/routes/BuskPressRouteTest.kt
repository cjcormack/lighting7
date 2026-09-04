package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import org.junit.Test
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /busk/pads/{id}/press` — each kind, and the solo rules.
 *
 * The stack's own tests (`ProgrammerLayerStackTest`) prove `toggle`'s `releaseSiblings` narrows
 * and `release` drops; these prove the route *resolves* the siblings from the bank — per kind,
 * per pad, at press time — and that a cue pad presses the way a cue slot does.
 */
class BuskPressRouteTest : RouteIntegrationTest() {

    private fun pages() = "/api/rest/projects/$projectId/busk/pages"

    private suspend fun HttpClient.createTemplate(name: String, value: String = "#ff8800"): Int {
        val resp = post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", value))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<TemplateDto>().id
    }

    private suspend fun HttpClient.createLook(name: String, vararg fixtureKeys: String, deferredEffect: Boolean = false): Int {
        val resp = post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = fixtureKeys.map { LookRowDto("fixture", it, "dimmer", "200") },
                    effects = if (deferredEffect) {
                        listOf(
                            LookEffectDto(
                                targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                                effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                                beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                            ),
                        )
                    } else {
                        emptyList()
                    },
                ),
            )
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

    /** One page, one column, one bank holding [pads]; returns the pad ids in order. */
    private suspend fun HttpClient.bank(name: String, solo: Boolean, vararg pads: BuskLayoutPad): List<Int> {
        val page = post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }.body<BuskPageDto>()
        val resp = put("${pages()}/${page.id}/layout") {
            contentType(ContentType.Application.Json)
            val bank = BuskLayoutBank(name = name, solo = solo, pads = pads.toList())
            setBody(BuskLayoutRequest(listOf(BuskLayoutRow(listOf(BuskLayoutColumn(width = 12, banks = listOf(bank)))))))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body<BuskPageDto>().rows.single().columns.single().banks.single().pads.map { it.id }
    }

    private fun tpl(id: Int) = BuskLayoutPad(templateId = id)
    private fun look(id: Int) = BuskLayoutPad(lookId = id)
    private fun cue(id: Int) = BuskLayoutPad(cueId = id)

    private suspend fun HttpClient.pressRaw(padId: Int, vararg fixtureKeys: String): HttpResponse =
        post("/api/rest/projects/$projectId/busk/pads/$padId/press") {
            contentType(ContentType.Application.Json)
            setBody(BuskPressRequest(targets = fixtureKeys.map { CueTargetDto("fixture", it) }))
        }

    private suspend fun HttpClient.press(padId: Int, vararg fixtureKeys: String): BuskPressResponse {
        val resp = pressRaw(padId, *fixtureKeys)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.activeCueId(stackId: Int): Int? =
        get("/api/rest/projects/$projectId/cue-stacks/$stackId").body<CueStackDetails>().activeCueId

    /** `name@key+key` per live programmer layer, the `TemplateGroupRoutesTest` idiom. */
    private fun live(): Set<String> = state.show.programmerStore.layers
        .map { layer -> "${layer.source.name}@${layer.targets.map { it.key }.sorted().joinToString("+")}" }
        .toSet()

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        state.show.fixtures.patchListChanged()
    }

    // ─── Each kind ──────────────────────────────────────────────────────

    @Test
    fun `a template pad applies a family-masked layer and a second press removes it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val (pad) = client.bank("keys", solo = false, tpl(amber))

        val on = client.press(pad, "hex-1")
        assertEquals("TEMPLATE", on.kind)
        assertEquals("applied", on.action)
        assertEquals(0, on.released)
        assertEquals(setOf("amber@hex-1"), live())
        assertEquals("COLOUR", state.show.programmerStore.layers.single().propertyMask)

        val off = client.press(pad, "hex-1")
        assertEquals("removed", off.action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a generic template pad refuses an empty selection, a per-fixture one lands on its own heads`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val focus = client.post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "focus", rows = listOf(TemplateRowDto("fixture", "hex-1", "position", "deg:12,-8"))))
        }.body<TemplateDto>().id
        val (amberPad, focusPad) = client.bank("keys", solo = false, tpl(amber), tpl(focus))

        val refused = client.pressRaw(amberPad)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(CODE_TEMPLATE_NEEDS_SELECTION, refused.body<ErrorResponse>().code)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "no dead layer lighting the pad")

        assertEquals("applied", client.press(focusPad).action)
        assertEquals(setOf("focus@"), live(), "a per-fixture template's layer names no targets and lands on its own rows")
    }

    @Test
    fun `deleting a Look releases the layer a pad put on the stack`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1")
        val (pad) = client.bank("looks", solo = false, look(warm))
        client.press(pad, "hex-1")
        assertEquals(setOf("warm@hex-1"), live())

        // No cue layer names it, so the delete needs no `force` — and must not leave the layer.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/looks/$warm").status)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "the layer went with its record")
    }

    @Test
    fun `a Look pad applies on the selection and a second press removes it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1", "hex-2")
        val (pad) = client.bank("looks", solo = false, look(warm))

        val on = client.press(pad, "hex-2")
        assertEquals("LOOK", on.kind)
        assertEquals("applied", on.action)
        assertEquals(setOf("warm@hex-2"), live())
        assertNull(state.show.programmerStore.layers.single().propertyMask, "a Look layer is unmasked")

        assertEquals("removed", client.press(pad, "hex-2").action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a Look pad pressed with no selection uses the Look's own fixtures`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1", "hex-2")
        val (pad) = client.bank("looks", solo = false, look(warm))

        assertEquals("applied", client.press(pad).action)
        assertEquals(setOf("warm@hex-1+hex-2"), live())
        assertEquals("removed", client.press(pad).action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a Look pad with a deferred effect refuses an empty selection`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val pulse = client.createLook("pulse", "hex-1", deferredEffect = true)
        val (pad) = client.bank("looks", solo = false, look(pulse))

        val resp = client.pressRaw(pad)
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(CODE_LOOK_NEEDS_SELECTION, resp.body<ErrorResponse>().code)
        assertTrue(state.show.programmerStore.layers.isEmpty())

        assertEquals("applied", client.press(pad, "hex-1").action, "with a selection it presses")
    }

    @Test
    fun `a cue pad applies its cue and a second press stops it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (pad) = client.bank("cues", solo = false, cue(opening))

        val on = client.press(pad)
        assertEquals("CUE", on.kind)
        assertEquals("applied", on.action)
        assertEquals(opening, client.activeCueId(stackId))
        assertTrue(opening in state.show.fxEngine.cueLayer.activeCueIds(), "the rows-only cue is live on Layer 4")

        val off = client.press(pad)
        assertEquals("removed", off.action)
        assertNull(client.activeCueId(stackId), "the stack reads dark again")
        assertFalse(opening in state.show.fxEngine.cueLayer.activeCueIds())
    }

    // ─── Solo ───────────────────────────────────────────────────────────

    @Test
    fun `a solo press narrows a layer sibling on the pressed heads and leaves the rest`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue", "#0000ff")
        val (amberPad, bluePad) = client.bank("keys", solo = true, tpl(amber), tpl(blue))

        client.press(amberPad, "hex-1", "hex-2")
        val on = client.press(bluePad, "hex-1")
        assertEquals("applied", on.action)
        assertEquals(1, on.released, "amber was narrowed — one release")
        assertEquals(setOf("amber@hex-2", "blue@hex-1"), live())
    }

    @Test
    fun `a solo press stops a live cue sibling`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (amberPad, cuePad) = client.bank("keys", solo = true, tpl(amber), cue(opening))

        client.press(cuePad)
        assertEquals(opening, client.activeCueId(stackId))

        val on = client.press(amberPad, "hex-1")
        assertEquals("applied", on.action)
        assertEquals(1, on.released, "the live cue sibling was stopped")
        assertNull(client.activeCueId(stackId))
        assertEquals(setOf("amber@hex-1"), live())
    }

    @Test
    fun `a cue press in a solo bank turns its layer siblings off wholesale`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val warm = client.createLook("warm", "hex-1", "hex-2")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (amberPad, warmPad, cuePad) = client.bank("keys", solo = true, tpl(amber), look(warm), cue(opening))

        client.press(amberPad, "hex-1", "hex-2")
        // The Look press narrows amber to hex-2 rather than dropping it — the layer rule.
        assertEquals(1, client.press(warmPad, "hex-1").released)
        assertEquals(setOf("amber@hex-2", "warm@hex-1"), live())

        val on = client.press(cuePad)
        assertEquals("applied", on.action)
        assertEquals(2, on.released, "both layer siblings came off, whatever heads they held")
        assertTrue(state.show.programmerStore.layers.isEmpty())
        assertEquals(opening, client.activeCueId(stackId))

        // Off: nothing else moves.
        assertEquals(0, client.press(cuePad).released)
    }

    @Test
    fun `a stacking bank releases nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue", "#0000ff")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (amberPad, bluePad, cuePad) = client.bank("keys", solo = false, tpl(amber), tpl(blue), cue(opening))

        client.press(amberPad, "hex-1")
        assertEquals(0, client.press(bluePad, "hex-1").released)
        assertEquals(0, client.press(cuePad).released)
        assertEquals(setOf("amber@hex-1", "blue@hex-1"), live())
        assertEquals(opening, client.activeCueId(stackId))
    }

    @Test
    fun `an off press releases nothing, and a disjoint sibling is untouched`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue", "#0000ff")
        val (amberPad, bluePad) = client.bank("keys", solo = true, tpl(amber), tpl(blue))

        client.press(amberPad, "hex-1")
        assertEquals(0, client.press(bluePad, "hex-2").released, "two pads on two rigs are not a conflict")
        assertEquals(setOf("amber@hex-1", "blue@hex-2"), live())

        val off = client.press(bluePad, "hex-2")
        assertEquals("removed", off.action)
        assertEquals(0, off.released)
        assertEquals(setOf("amber@hex-1"), live(), "an off press touches no sibling")
    }

    @Test
    fun `a record on two pads lights on both and either pad turns it off`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val (one) = client.bank("page one", solo = true, tpl(amber))
        val (two) = client.bank("page two", solo = false, tpl(amber))

        assertEquals("applied", client.press(one, "hex-1").action)
        val applied = state.show.programmerLayerStack.appliedState()
        assertEquals(1, applied.size, "one record applied, however many pads show it")

        assertEquals("removed", client.press(two, "hex-1").action, "the other pad reads the same record as on")
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a record on two pads of one solo bank is not its own sibling`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val (first, second) = client.bank("keys", solo = true, tpl(amber), tpl(amber))

        client.press(first, "hex-1")
        val on = client.press(second, "hex-2")
        assertEquals("applied", on.action, "hex-2 was not covered, so the press extends")
        assertEquals(0, on.released)
        assertEquals(setOf("amber@hex-1", "amber@hex-2"), live())
    }

    @Test
    fun `an unknown pad is 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        assertEquals(HttpStatusCode.NotFound, client.pressRaw(999_999, "hex-1").status)
    }
}
