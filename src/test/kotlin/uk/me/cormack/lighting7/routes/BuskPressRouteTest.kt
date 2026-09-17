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
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.state.SelectionSource
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
 * `POST /busk/pads/{id}/press` — each kind, the solo rules, and **the surface's own door onto the
 * same press**.
 *
 * The stack's own tests (`ProgrammerLayerStackTest`) prove `toggle`'s `releaseSiblings` narrows
 * and `release` drops; these prove the route *resolves* the siblings from the bank — per kind,
 * per pad, at press time — and that a cue pad presses the way a cue slot does.
 *
 * The last section drives `DefaultSurfaceActions` against the same fixtures. That is the whole
 * point of `BuskPressService` existing: a `PressPad` binding and a screen press are one press, and
 * two test classes could each be green while the two doors did different things.
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

    private suspend fun HttpClient.createLook(
        name: String,
        vararg fixtureKeys: String,
        deferredEffect: Boolean = false,
        /** Also a colour row per head, so the Look spans INTENSITY + COLOUR. */
        withColour: Boolean = false,
    ): Int {
        val resp = post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = fixtureKeys.flatMap { key ->
                        listOfNotNull(
                            LookRowDto("fixture", key, "dimmer", "200"),
                            if (withColour) LookRowDto("fixture", key, "rgbColour", "#ff0000") else null,
                        )
                    },
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

    private suspend fun HttpClient.pressRaw(
        padId: Int,
        vararg fixtureKeys: String,
        families: List<String>? = null,
    ): HttpResponse =
        post("/api/rest/projects/$projectId/busk/pads/$padId/press") {
            contentType(ContentType.Application.Json)
            setBody(BuskPressRequest(targets = fixtureKeys.map { CueTargetDto("fixture", it) }, families = families))
        }

    private suspend fun HttpClient.press(
        padId: Int,
        vararg fixtureKeys: String,
        families: List<String>? = null,
    ): BuskPressResponse {
        val resp = pressRaw(padId, *fixtureKeys, families = families)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.activeCueId(stackId: Int): Int? =
        get("/api/rest/projects/$projectId/cue-stacks/$stackId").body<CueStackDetails>().activeCueId

    /** `name@key+key` per live programmer layer — the idiom the template-group tests left behind. */
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

    // ─── The selection's attribute mask (multi-screen plan D5) ──────────

    @Test
    fun `a template pad outside the selection's mask is refused by name, inside it lands as before`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val (pad) = client.bank("keys", solo = false, tpl(amber))

        val refused = client.pressRaw(pad, "hex-1", families = listOf("INTENSITY"))
        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        val error = refused.body<ErrorResponse>()
        assertEquals(CODE_TEMPLATE_OUTSIDE_MASK, error.code)
        assertTrue("Colour" in error.error && "Intensity" in error.error, "names both families: ${error.error}")
        assertTrue(state.show.programmerStore.layers.isEmpty(), "nothing lands, so nothing lights the pad")

        // Inside the mask the template's own family is the mask: the intersection is itself.
        val on = client.press(pad, "hex-1", families = listOf("COLOUR", "INTENSITY"))
        assertEquals("applied", on.action)
        assertTrue(on.skippedFamilies.isEmpty(), "a template skips nothing — it is one family")
        assertEquals("COLOUR", state.show.programmerStore.layers.single().propertyMask)
    }

    @Test
    fun `a Look pad under a mask lands masked to what they share and reports what it skipped`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1", withColour = true)
        val (pad) = client.bank("looks", solo = false, look(warm))

        val on = client.press(pad, "hex-1", families = listOf("COLOUR", "POSITION"))
        assertEquals("applied", on.action)
        assertEquals(listOf("INTENSITY"), on.skippedFamilies, "the dimmer rows are outside the mask")
        assertEquals(setOf("warm@hex-1"), live())
        assertEquals(
            "COLOUR", state.show.programmerStore.layers.single().propertyMask,
            "mask ∩ look.families — POSITION is in the mask but not in the Look, so it is not on the layer",
        )

        // Pressed again with no mask: coverage says on, so it comes off — the second press is
        // still off, and an off press skips nothing.
        val off = client.press(pad, "hex-1")
        assertEquals("removed", off.action)
        assertTrue(off.skippedFamilies.isEmpty())
        assertTrue(state.show.programmerStore.layers.isEmpty())

        // Unmasked, the layer is unmasked — today's press.
        assertEquals("applied", client.press(pad, "hex-1").action)
        assertNull(state.show.programmerStore.layers.single().propertyMask)
    }

    @Test
    fun `a Look pad with nothing inside the mask is refused, and a cue pad ignores it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1")
        val stackId = client.createStack("Main")
        val cueId = client.createCue("Q1", stackId)
        val (lookPad, cuePad) = client.bank("mixed", solo = false, look(warm), cue(cueId))

        val refused = client.pressRaw(lookPad, "hex-1", families = listOf("COLOUR"))
        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        assertEquals(CODE_LOOK_OUTSIDE_MASK, refused.body<ErrorResponse>().code)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "a layer asserting nothing would light the pad for nothing")

        val cue = client.press(cuePad, "hex-1", families = listOf("COLOUR"))
        assertEquals("CUE", cue.kind)
        assertEquals("applied", cue.action, "a cue has no targets to be masked on")
        assertEquals(cueId, client.activeCueId(stackId))
    }

    @Test
    fun `an off press comes off under any mask — the mask is about what a press puts on`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val warm = client.createLook("warm", "hex-1")
        val (amberPad, warmPad) = client.bank("keys", solo = false, tpl(amber), look(warm))

        // Both lit unmasked, then a marquee elsewhere masks the selection to Position: the operator
        // presses each lit pad to turn it off, and neither may read dead.
        client.press(amberPad, "hex-1")
        client.press(warmPad, "hex-1")
        assertEquals(setOf("amber@hex-1", "warm@hex-1"), live())

        val amberOff = client.press(amberPad, "hex-1", families = listOf("POSITION"))
        assertEquals("removed", amberOff.action, "a lit Colour pad releases under a Position mask")
        val warmOff = client.press(warmPad, "hex-1", families = listOf("POSITION"))
        assertEquals("removed", warmOff.action, "a lit Intensity Look releases under a Position mask")
        assertTrue(warmOff.skippedFamilies.isEmpty(), "an off press skips nothing")
        assertTrue(state.show.programmerStore.layers.isEmpty())

        // And now that nothing is lit, the same presses are on presses and the mask refuses them.
        assertEquals(CODE_TEMPLATE_OUTSIDE_MASK, client.pressRaw(amberPad, "hex-1", families = listOf("POSITION")).body<ErrorResponse>().code)
        assertEquals(CODE_LOOK_OUTSIDE_MASK, client.pressRaw(warmPad, "hex-1", families = listOf("POSITION")).body<ErrorResponse>().code)
    }

    @Test
    fun `a per-fixture template pressed with no targets releases under an excluding mask — the twin arm`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val focus = client.post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "focus", rows = listOf(TemplateRowDto("fixture", "hex-1", "position", "deg:12,-8"))))
        }.body<TemplateDto>().id
        val (pad) = client.bank("keys", solo = false, tpl(focus))

        assertEquals("applied", client.press(pad).action)
        assertEquals(setOf("focus@"), live(), "a per-fixture template's layer names no targets")
        // No heads to compare coverage on: `toggle` finds its twin by identical (empty) targets,
        // and so must the arm decided before the mask test.
        assertEquals("removed", client.press(pad, families = listOf("COLOUR")).action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
        assertEquals(CODE_TEMPLATE_OUTSIDE_MASK, client.pressRaw(pad, families = listOf("COLOUR")).body<ErrorResponse>().code)
    }

    @Test
    fun `pressWouldRelease agrees with toggle on both arms`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val uuid = java.util.UUID.fromString(client.templateUuid(amber))
        val source = uk.me.cormack.lighting7.models.LayerSource.template(amber, uuid, "amber")
        val stack = state.show.programmerLayerStack
        val hex1 = listOf(CueTargetDto("fixture", "hex-1"))
        val both = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2"))

        // The guard: for every press, the helper's answer before the press is the arm `toggle`
        // then takes. A change to toggle's comparison fails here rather than drifting in pressArm.kt.
        fun check(targets: List<CueTargetDto>) {
            val predicted = pressWouldRelease(state, uuid, targets)
            val outcome = stack.toggle(source = source, targets = targets, propertyMask = "COLOUR")
            assertEquals(predicted, outcome.action == "removed", "targets=$targets")
        }
        check(hex1)          // on
        check(both)          // covered only partly → on (widens)
        check(both)          // fully covered → off, both heads
        check(hex1)          // on again
        check(emptyList())   // no twin with empty targets → on
        check(emptyList())   // twin → off
        check(hex1)          // hex-1 still covered by the first layer → off
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a family name outside the vocabulary is refused rather than widening the mask`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val (pad) = client.bank("keys", solo = false, tpl(amber))

        val resp = client.pressRaw(pad, "hex-1", families = listOf("GOBO"))
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `an unknown pad is 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        assertEquals(HttpStatusCode.NotFound, client.pressRaw(999_999, "hex-1").status)
    }

    // ─── The same press, from a button (midi-surface plan D6) ───────────

    private fun actions() = DefaultSurfaceActions(state)

    private suspend fun HttpClient.lookUuid(id: Int): String =
        get("/api/rest/projects/$projectId/looks/$id").body<LookDetails>().uuid

    private suspend fun HttpClient.templateUuid(id: Int): String =
        get("/api/rest/projects/$projectId/templates/$id").body<TemplateDto>().uuid

    private suspend fun HttpClient.padUuid(padId: Int): String =
        get(pages()).body<List<BuskPageDto>>()
            .flatMap { it.rows }.flatMap { it.columns }.flatMap { it.banks }.flatMap { it.pads }
            .single { it.id == padId }.uuid

    @Test
    fun `applyLook presses onto the Look's own fixtures, not the selection`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1")
        // A selection naming a *different* head, to prove the button ignores it: an `ApplyLook`
        // does the same thing every press, which is what makes it safe on a button.
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")))

        actions().applyLook(client.lookUuid(warm))
        assertEquals(setOf("warm@hex-1"), live())

        actions().applyLook(client.lookUuid(warm))
        assertTrue(state.show.programmerStore.layers.isEmpty(), "a second press takes it off")
    }

    @Test
    fun `applyLook on a deferred-effect Look is dropped`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val pulse = client.createLook("pulse", "hex-1", deferredEffect = true)
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")))

        // Refused at bind time, so this is the state a Look *edited afterwards* falls into — and
        // the selection must not be quietly substituted for the targets it no longer has.
        actions().applyLook(client.lookUuid(pulse))
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `pressTemplate lands on the selection and is dropped when a generic one has none`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val uuid = client.templateUuid(amber)

        actions().pressTemplate(uuid)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "generic, nothing selected")

        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")))
        actions().pressTemplate(uuid)
        assertEquals(setOf("amber@hex-2"), live())
        assertEquals("COLOUR", state.show.programmerStore.layers.single().propertyMask, "mask derived, not sent")
    }

    @Test
    fun `pressTemplate honours the desk's mask, and a Sel property fader ignores it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val uuid = client.templateUuid(amber)

        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")), setOf(PropertyMaskGroup.INTENSITY))
        actions().pressTemplate(uuid)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "a Colour template under an Intensity mask is dropped")

        // The fader names its own attribute, so the mask does not touch it (§3.3).
        actions().writeSelectionProperty("rgbColour", 127u, null)
        assertTrue(state.show.programmerStore.size > 0, "the selection write landed under a mask it is outside")

        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")), setOf(PropertyMaskGroup.COLOUR))
        actions().pressTemplate(uuid)
        assertEquals(setOf("amber@hex-2"), live())
        assertEquals("COLOUR", state.show.programmerStore.layers.single().propertyMask)
    }

    @Test
    fun `a surface press releases a lit record under an excluding mask`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val warm = client.createLook("warm", "hex-1")
        val (warmPad) = client.bank("keys", solo = false, look(warm))
        val uuid = client.templateUuid(amber)
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")))

        actions().pressTemplate(uuid)
        actions().pressPad(client.padUuid(warmPad))
        assertEquals(setOf("amber@hex-1", "warm@hex-1"), live())

        // A marquee on a screen masks the desk to Position; the buttons must still turn them off.
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")), setOf(PropertyMaskGroup.POSITION))
        actions().pressTemplate(uuid)
        actions().pressPad(client.padUuid(warmPad))
        assertTrue(state.show.programmerStore.layers.isEmpty(), "both released under a mask that excludes them")

        // Off, the same presses are on presses and are dropped by name.
        actions().pressTemplate(uuid)
        actions().pressPad(client.padUuid(warmPad))
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `pressPad passes the desk's mask through the same press`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1", withColour = true)
        val amber = client.createTemplate("amber")
        val (lookPad, amberPad) = client.bank("keys", solo = false, look(warm), tpl(amber))
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")), setOf(PropertyMaskGroup.INTENSITY))

        actions().pressPad(client.padUuid(amberPad))
        assertTrue(state.show.programmerStore.layers.isEmpty(), "refused by name, logged — a button has no 400")

        actions().pressPad(client.padUuid(lookPad))
        assertEquals(setOf("warm@hex-1"), live())
        assertEquals("INTENSITY", state.show.programmerStore.layers.single().propertyMask, "mask ∩ look.families")
    }

    @Test
    fun `a surface select toggle keeps the desk's mask and a replace clears it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val colour = setOf(PropertyMaskGroup.COLOUR)
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")), colour)

        actions().selectTarget(CueTargetDto("fixture", "hex-2"), BindingTarget.SelectMode.TOGGLE)
        assertEquals(colour, state.deskSelection.state.value.families, "a select button adds a head under the standing mask")
        assertEquals(SelectionSource.SURFACE, state.deskSelection.state.value.source)

        actions().selectTarget(CueTargetDto("fixture", "hex-2"), BindingTarget.SelectMode.REPLACE)
        assertEquals(listOf(CueTargetDto("fixture", "hex-2")), state.deskSelection.state.value.targets)
        assertNull(state.deskSelection.state.value.families, "a replace has no column axis to speak with")

        actions().clearSelection()
        assertNull(state.deskSelection.state.value.source)
    }

    @Test
    fun `pressPad runs the pad's own plan, solo siblings included`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createTemplate("amber")
        val blue = client.createTemplate("blue", "#0044ff")
        val (amberPad, bluePad) = client.bank("keys", solo = true, tpl(amber), tpl(blue))
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")))

        actions().pressPad(client.padUuid(amberPad))
        assertEquals(setOf("amber@hex-1"), live())

        actions().pressPad(client.padUuid(bluePad))
        assertEquals(setOf("blue@hex-1"), live(), "the solo bank released its sibling from a hardware press too")
    }

    @Test
    fun `the busk page targets move the desk's showing page`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val first = client.post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest("act one"))
        }.body<BuskPageDto>()
        val second = client.post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest("act two"))
        }.body<BuskPageDto>()

        actions().buskPageSet(second.uuid)
        assertEquals(second.id, state.buskPageState.pageId.value)

        actions().buskPageStep(1)
        assertEquals(first.id, state.buskPageState.pageId.value, "next wraps")

        // A uuid that resolves to nothing leaves the page where it is rather than clearing it.
        actions().buskPageSet("00000000-0000-4000-8000-000000000000")
        assertEquals(first.id, state.buskPageState.pageId.value)
    }

    // ─── Cells (busk-further plan D11) ─────────────────────────────────

    private fun cell(i: Int) = "bar-1.pixel-$i"

    private fun seedBar() {
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 1)
    }

    /** A Look holding one colour row per named cell of `bar-1`. */
    private suspend fun HttpClient.createCellLook(name: String, vararg cells: Int): Int {
        val resp = post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = cells.map { LookRowDto("fixture", "bar-1", "rgbColour", "#ff0000", elementKey = cell(it)) },
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<LookDetails>().id
    }

    @Test
    fun `a cell press under a whole-bar Look layer reads lit and releases nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        seedBar()
        val client = jsonClient()
        val cells = client.createCellLook("cells", 0, 1, 2, 3, 6)
        val other = client.createLook("other", "hex-1")
        val (pad, otherPad) = client.bank("solo", solo = true, look(cells), look(other))
        assertEquals("applied", client.press(otherPad, "hex-1").action)

        assertEquals("applied", client.press(pad, "bar-1").action)
        assertEquals(setOf("cells@bar-1", "other@hex-1"), live())

        val off = client.press(pad, cell(0), cell(1), cell(2), cell(3))
        assertEquals("removed", off.action, "the whole bar covers four of its cells")
        assertEquals(0, off.released, "an off press releases nothing")
        assertEquals(
            setOf("cells@" + (4 until 12).map { cell(it) }.sorted().joinToString("+"), "other@hex-1"),
            live(),
            "the layer is narrowed to the cells the press did not name; the sibling is untouched",
        )
    }

    @Test
    fun `a whole-bar press over a cell layer adds`() = testApplication {
        mountTestApp(state)
        seedRig()
        seedBar()
        val client = jsonClient()
        val cells = client.createCellLook("cells", 0, 1, 2, 3, 6)
        val (pad) = client.bank("keys", solo = false, look(cells))

        assertEquals("applied", client.press(pad, cell(0), cell(1), cell(2), cell(3)).action)
        assertEquals(setOf("cells@" + (0 until 4).map { cell(it) }.joinToString("+")), live())

        val on = client.press(pad, "bar-1")
        assertEquals("applied", on.action, "four cells do not cover the whole bar")
        assertEquals(0, on.released)
        assertEquals(setOf("cells@bar-1"), live(), "the bar subsumes its cells: one layer per head")
    }
}
